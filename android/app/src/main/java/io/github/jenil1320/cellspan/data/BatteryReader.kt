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

        // Normalise BOTH the sign and the unit of CURRENT_NOW — OEMs get each of them
        // wrong, independently. See [normaliseCurrentUa].
        val currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Long.MIN_VALUE && it != 0L }
            ?.let { normaliseCurrentUa(it, statusStr, unitsAreMicro(context, kotlin.math.abs(it))) }
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

    /**
     * Turn a raw `BATTERY_PROPERTY_CURRENT_NOW` into signed microamps.
     *
     * The Android documentation says microamps, positive into the battery. Real devices
     * disagree on both halves, and each one has to be fixed separately.
     *
     * **Sign.** AOSP means positive while charging; several manufacturers, Samsung most
     * visibly, invert it. Rather than keep a device blacklist, the sign is taken from the
     * charge status, which every device reports correctly. Charging is forced positive and
     * discharging negative, so "positive means charging" holds everywhere.
     *
     * **Unit.** A well-known driver bug reports milliamps through the microamp property.
     * Xiaomi and several other ROMs have shipped it. Unfixed, every derived figure is off
     * by a thousand: a 3000 mA fast charge is displayed as "3 mA", instantaneous power
     * reads 0.01 W instead of 12 W, [BatteryAnalytics.chargeRate] calls a 30 W charger
     * "Trickle", and the fuel-gauge runtime estimate returns a figure a thousand times too
     * long.
     *
     * **The unit is settled per device, not per reading.** Judging each reading on its own
     * magnitude is unsafe, because small values are genuinely reachable on a correct
     * device: a phone held at a root charge cap, or in the last minutes of the
     * constant-voltage taper, really does report a few milliamps — 3000 µA. Scaling that
     * would claim a 3 A, 12 W charge on a phone doing almost nothing, label it fast
     * charging, and collapse the runtime estimate from days to minutes.
     *
     * The reliable discriminator runs the other way: a reading too large to be milliamps.
     * No phone battery carries 20 A, so a magnitude at or above [MICRO_PROOF_UA] can only
     * be microamps, and a device with the milliamp bug can never produce one. The first
     * such reading settles the question for good, and the answer is remembered so a cold
     * start on a resting phone does not have to guess again.
     *
     * Until a device has proved itself, small readings are still scaled — that is the case
     * the correction exists for — but only while the phone is actively charging or
     * discharging. A near-zero current on a Full or Not-charging phone is the expected
     * reading, not a symptom.
     */
    fun normaliseCurrentUa(raw: Long, status: String, unitsKnownMicro: Boolean = false): Long {
        val mag = kotlin.math.abs(raw)
        val moving = status == "Charging" || status == "Discharging"
        val looksLikeMa = !unitsKnownMicro && moving &&
            mag in 1 until MA_SUSPICION_CEILING && mag * 1000 <= MAX_PLAUSIBLE_UA
        val scaled = if (looksLikeMa) mag * 1000 else mag
        return when (status) {
            "Charging" -> scaled
            "Discharging" -> -scaled
            // Full / Not charging / Unknown: near zero either way, so keep the reported
            // direction rather than inventing one.
            else -> if (raw < 0) -scaled else scaled
        }
    }

    /** A magnitude at or above this can only be microamps, so it settles the device's unit. */
    const val MICRO_PROOF_UA = 20_000L

    /** Readings below this (5 mA expressed in µA) are suspected of being milliamps. */
    private const val MA_SUSPICION_CEILING = 5_000L

    /** 15 A. Above this no phone cell is charging or discharging, so the scale-up is wrong. */
    private const val MAX_PLAUSIBLE_UA = 15_000_000L

    /**
     * Cached once the device has proved it reports microamps. Held in preferences as well
     * as memory, because the proof may not repeat for a long while — a phone parked at a
     * charge cap reports small values indefinitely.
     */
    @Volatile private var unitsMicro: Boolean? = null

    private fun unitsAreMicro(context: Context, rawMagnitude: Long): Boolean {
        val prefs = context.getSharedPreferences("cellspan_prefs", Context.MODE_PRIVATE)
        var known = unitsMicro ?: prefs.getBoolean("current_units_micro", false).also { unitsMicro = it }
        // A ceiling as well as a floor. Proof needs a value that is too large to be
        // milliamps but still physically possible — a stuck sentinel such as 2147483647,
        // or a transient spike far beyond what any cell can carry, is garbage, and letting
        // it "prove" the unit would latch the wrong answer into preferences for the life
        // of the install with no path that ever clears it.
        if (!known && rawMagnitude in MICRO_PROOF_UA..MAX_PLAUSIBLE_UA) {
            known = true
            unitsMicro = true
            prefs.edit().putBoolean("current_units_micro", true).apply()
        }
        return known
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
