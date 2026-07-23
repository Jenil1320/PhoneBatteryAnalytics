package io.github.jenil1320.cellspan.data

import android.annotation.SuppressLint
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
        val designFromProfile = readPowerProfileCapacityMah(context)

        // Best-effort fuel-gauge enrichment (usually blocked by SELinux on stock).
        // Some drivers expose these nodes but return 0/-1, or report mAh instead of
        // the expected µAh. Anything outside a plausible µAh range is treated as
        // absent, so the PowerProfile fallback runs instead of reporting 0% health.
        val chargeFull = readLongNode("charge_full")?.takeIf { it in 1_000_000L..20_000_000L }
        val chargeFullDesign = readLongNode("charge_full_design")?.takeIf { it in 1_000_000L..20_000_000L }
        val mfg = readLongNode("manufacturing_date")?.takeIf { it > 0 }

        return BatterySnapshot(
            timestampMs = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            levelPct = level.coerceIn(0, 100),
            status = statusStr,
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
            sysfsAvailable = chargeFull != null && chargeFullDesign != null,
        )
    }

    /**
     * Design capacity (mAh) from the framework's PowerProfile — the value every
     * ROM ships in `frameworks/base` as `battery.capacity`. Reached by reflection
     * because the class is `com.android.internal`, but it is widely readable and,
     * unlike the fuel-gauge nodes, needs no SELinux exception. Best-effort: any
     * failure (blocked hidden API, missing method) simply returns null and the
     * caller falls back to the published-capacity table.
     */
    @SuppressLint("PrivateApi")
    private fun readPowerProfileCapacityMah(context: Context): Int? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val instance = cls.getConstructor(Context::class.java).newInstance(context)
        val mAh = try {
            cls.getMethod("getBatteryCapacity").invoke(instance) as? Double
        } catch (e: Throwable) {
            cls.getMethod("getAveragePower", String::class.java)
                .invoke(instance, "battery.capacity") as? Double
        }
        mAh?.takeIf { it > 1000.0 && it < 20000.0 }?.toInt()
    } catch (e: Throwable) {
        null
    }

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
