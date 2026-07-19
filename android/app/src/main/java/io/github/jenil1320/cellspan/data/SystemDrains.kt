package io.github.jenil1320.cellspan.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class DrainCost { HIGH, MEDIUM, LOW }

/** A power-relevant device feature and whether it's currently on. */
data class DrainItem(val name: String, val on: Boolean, val detail: String, val cost: DrainCost)

/**
 * Reads the current on/off state of power-relevant features using public APIs.
 * These are states, not measured drain — a normal app can't attribute mAh to a
 * feature — but they tell the user what's currently costing them.
 */
object SystemDrains {
    fun read(context: Context): List<DrainItem> {
        val out = ArrayList<DrainItem>()
        val app = context.applicationContext

        val brightness = try {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        } catch (e: Exception) { -1 }
        if (brightness >= 0) out.add(DrainItem("Screen brightness", true, "$brightness%",
            when { brightness > 70 -> DrainCost.HIGH; brightness > 40 -> DrainCost.MEDIUM; else -> DrainCost.LOW }))

        runCatching { (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled }
            .getOrNull()?.let { out.add(DrainItem("Wi-Fi", it, if (it) "on" else "off", DrainCost.MEDIUM)) }

        runCatching { (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled }
            .getOrNull()?.let { out.add(DrainItem("Bluetooth", it, if (it) "on" else "off", DrainCost.LOW)) }

        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled }
                .getOrNull()?.let { out.add(DrainItem("Location", it, if (it) "on" else "off", DrainCost.HIGH)) }
        }

        runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }
            .getOrNull()?.let { out.add(DrainItem("Battery saver", it, if (it) "on — limiting drain" else "off", DrainCost.LOW)) }

        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1 }
            .getOrNull()?.let { out.add(DrainItem("Airplane mode", it, if (it) "on" else "off", DrainCost.LOW)) }

        return out
    }
}
