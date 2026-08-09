package io.github.jenil1320.cellspan.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.io.File

/**
 * Reads a [BatterySnapshot] from the device using only the public Android APIs
 * (no permissions, no user input), plus a best-effort attempt to read the
 * protected fuel-gauge nodes — which normally fails on stock, unrooted Android
 * and simply leaves those fields null.
 */
object BatteryReader {

    private const val PSY = "/sys/class/power_supply/battery"

    // Both of these describe the cell rather than its state, so they are read once and
    // remembered. Each value is published before its flag, so another thread never sees
    // "already read" with nothing behind it.
    @Volatile private var designUah: Long? = null
    @Volatile private var designRead = false
    @Volatile private var mfgEpoch: Long? = null

    fun read(context: Context): BatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = run {
            val l = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (l >= 0 && scale > 0) l * 100 / scale
            else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 } ?: 0
        }

        val statusStr = statusToString(sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
        // Non-zero means some charger (AC, USB, wireless, dock) is attached. Kept separate
        // from the status string, which lags a plug/unplug by a moment.
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val healthStr = healthToString(sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1)
        val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.ifBlank { null }
        val tempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val voltageV = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }?.let { it / 1000f }

        // Normalise the current's sign from the charge status. OEMs disagree on the
        // sign of CURRENT_NOW (AOSP: + into the battery; several, e.g. Samsung, invert
        // it), so we force it: charging → positive, discharging → negative. This keeps
        // the "positive = charging" convention true on every manufacturer. Magnitude is
        // untouched, and abs()-based consumers are unaffected.
        val currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Long.MIN_VALUE && it != 0L }
            ?.let { raw ->
                val mag = kotlin.math.abs(raw)
                when (statusStr) {
                    "Charging" -> mag
                    "Discharging" -> -mag
                    else -> raw   // Full / Not charging / Unknown: near zero, keep as-is
                }
            }
        val chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Long.MIN_VALUE && it > 0 }

        val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 }
        } else null

        // Design capacity from the ROM's PowerProfile — the AccuBattery trick.
        // Works on nearly all devices regardless of manufacturer, so State of
        // Health is available even for phones not in DeviceCapacityTable.
        val designFromProfile = PowerProfileReader.batteryCapacityMah(context)

        // Best-effort fuel-gauge enrichment (usually blocked by SELinux on stock).
        // Some drivers expose these nodes but return 0/-1, or report mAh instead of
        // the expected µAh. Anything outside a plausible µAh range is treated as
        // absent, so the PowerProfile fallback runs instead of reporting 0% health.
        // charge_full is re-read every time because it genuinely moves as the cell ages.
        val chargeFull = readLongNode("charge_full")?.takeIf { it in 1_000_000L..20_000_000L }
        val chargeFullDesign = chargeFullDesignUah()
        val mfg = manufacturingEpoch(bm)
        // The manufacturer's own health figure, when the device publishes one.
        val soh = readStateOfHealth(bm)

        return BatterySnapshot(
            timestampMs = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            levelPct = level.coerceIn(0, 100),
            status = statusStr,
            pluggedIn = plugged,
            health = healthStr,
            technology = tech,
            temperatureC = tempC,
            voltageV = voltageV,
            currentUa = currentUa,
            chargeCounterUah = chargeCounter,
            cycleCount = cycleCount,
            designCapacityFromProfileMah = designFromProfile,
            chargeFullUah = chargeFull,
            chargeFullDesignUah = chargeFullDesign,
            manufacturingEpoch = mfg,
            stateOfHealthPct = soh,
        )
    }

    /** Design charge is a constant of the cell, so the node is read at most once. */
    private fun chargeFullDesignUah(): Long? {
        if (designRead) return designUah
        val v = readLongNode("charge_full_design")?.takeIf { it in 1_000_000L..20_000_000L }
        designUah = v
        designRead = true
        return v
    }

    /**
     * Manufacture date, best source first: the platform property, then the raw node.
     * A found date is remembered, but a null is deliberately not cached — the date starts
     * working the moment BATTERY_STATS is granted over ADB, and that must be picked up
     * without a restart.
     */
    private fun manufacturingEpoch(bm: BatteryManager): Long? {
        mfgEpoch?.let { return it }
        val v = readManufacturingDate(bm) ?: readLongNode("manufacturing_date")?.takeIf { it > 0 }
        if (v != null) mfgEpoch = v
        return v
    }

    /**
     * The battery's manufacture date straight from the platform, in epoch SECONDS, or
     * null when this device won't give it to us.
     *
     * `BATTERY_PROPERTY_MANUFACTURING_DATE` (7) exists from Android 14, but it is a
     * hidden @SystemApi guarded by BATTERY_STATS, and system_server enforces that on its
     * side — so on a normal phone this returns null and the user has to supply a date
     * instead. BATTERY_STATS is signature|privileged|**development**, so it CAN be
     * granted without root, once, over ADB:
     *
     *   adb shell pm grant io.github.jenil1320.cellspan android.permission.BATTERY_STATS
     *
     * after which the date is picked up automatically and survives reboots.
     *
     * Two traps this guards against: the denial arrives as a SecurityException across the
     * binder rather than a sentinel value, so it must be caught or it crashes the caller;
     * and AOSP's own docs disagree with their sample over seconds vs milliseconds, so the
     * magnitude is sniffed rather than trusted.
     */
    private fun readManufacturingDate(bm: BatteryManager): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val raw = try {
            bm.getLongProperty(BATTERY_PROPERTY_MANUFACTURING_DATE)
        } catch (e: Throwable) {
            return null      // SecurityException on any unprivileged device
        }
        if (raw == Long.MIN_VALUE || raw <= 0L) return null
        // Seconds or milliseconds? Anything past ~5138 in seconds is really millis.
        val seconds = if (raw > 100_000_000_000L) raw / 1000 else raw
        // Sanity: a lithium cell made between 2005 and now-ish.
        val nowSec = System.currentTimeMillis() / 1000
        return seconds.takeIf { it in 1_100_000_000L..nowSec }
    }

    /**
     * The platform's own State of Health, 0–100, or null when this device won't report it.
     *
     * Unlike the manufacturing-date property, `BATTERY_PROPERTY_STATE_OF_HEALTH` (10) is
     * gated on a feature flag rather than a permission — where the flag is on, the check
     * short-circuits before BatteryManager consults BATTERY_STATS, so an ordinary app can
     * read it. Plenty of devices simply don't implement it, hence the guard: this is an
     * opportunistic upgrade over our own estimate, never something the app depends on.
     */
    private fun readStateOfHealth(bm: BatteryManager): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val raw = try {
            bm.getIntProperty(BATTERY_PROPERTY_STATE_OF_HEALTH)
        } catch (e: Throwable) {
            return null
        }
        // Int.MIN_VALUE is the "unsupported" sentinel and 0 means unimplemented rather
        // than a dead cell — but the more common lie is a flat 100. Android does not
        // require a device to populate this, and several (the Pixel 7 among them) return
        // a hard-coded 100 while their own health_status reads "not computed". We cannot
        // tell that stub apart from a genuinely new battery, and it costs nothing to
        // ignore it: a truly new cell measures ~100 by our own method anyway. So only a
        // value that actually reports wear is believed.
        return raw.takeIf { it in 40..99 }
    }

    /** Hidden BatteryManager property ids; the constants are @hide, the values are not. */
    private const val BATTERY_PROPERTY_MANUFACTURING_DATE = 7
    private const val BATTERY_PROPERTY_STATE_OF_HEALTH = 10

    private fun readLongNode(name: String): Long? = try {
        val f = File("$PSY/$name")
        if (f.canRead()) f.readText().trim().toLongOrNull() else null
    } catch (e: Exception) {
        null
    }

    private fun statusToString(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun healthToString(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
}
