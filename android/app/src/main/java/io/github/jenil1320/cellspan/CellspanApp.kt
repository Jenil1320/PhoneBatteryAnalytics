package io.github.jenil1320.cellspan

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.jenil1320.cellspan.data.ChargeAlarm
import io.github.jenil1320.cellspan.data.ChargeControl
import io.github.jenil1320.cellspan.data.LowBatteryAlarm
import io.github.jenil1320.cellspan.data.SampleScheduler

class CellspanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Notification channels for the two battery life saver reminders.
        ChargeAlarm.ensureChannel(this)
        LowBatteryAlarm.ensureChannel(this)
        // Re-arm both loops: a force-stop drops the plug/unplug broadcast that normally
        // starts them, and the user would never know the limit had gone quiet.
        LowBatteryAlarm.resume(this)
        ChargeAlarm.resume(this)
        // Root-cap safety net: if a force-stop left the kernel cap engaged with no loop
        // supervising it, put the defaults back so the phone charges normally.
        if (ChargeControl.engagedTarget(this) > 0) {
            Thread {
                val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                ChargeControl.releaseIfStale(this, plugged)
            }.start()
        }
        // Hourly background sample, honouring the user's switch in Settings.
        SampleScheduler.apply(this)
    }
}
