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

    fun read(context: Context): BatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = run {
            val l = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (l >= 0 && scale > 0) l * 100 / scale
            else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        val statusStr = statusToString(sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
        val healthStr = healthToString(sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1)
        val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.ifBlank { null }
        val tempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val voltageV = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }?.let { it / 1000f }

        val currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Long.MIN_VALUE && it != 0L }
        val chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Long.MIN_VALUE && it > 0 }

        val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 }
        } else null

        // Best-effort fuel-gauge enrichment (usually blocked by SELinux on stock).
        val chargeFull = readLongNode("charge_full")
        val chargeFullDesign = readLongNode("charge_full_design")
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
            chargeFullUah = chargeFull,
            chargeFullDesignUah = chargeFullDesign,
            manufacturingEpoch = mfg,
            sysfsAvailable = chargeFull != null && chargeFullDesign != null,
        )
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
