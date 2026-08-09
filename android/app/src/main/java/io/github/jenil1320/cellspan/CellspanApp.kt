package io.github.jenil1320.cellspan

import android.app.Application
import io.github.jenil1320.cellspan.data.ChargeAlarm
import io.github.jenil1320.cellspan.data.LowBatteryAlarm
import io.github.jenil1320.cellspan.data.SampleScheduler

class CellspanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Notification channels for the two battery life saver reminders.
        ChargeAlarm.ensureChannel(this)
        LowBatteryAlarm.ensureChannel(this)
        // Re-arm the low-battery loop: a force-stop drops the plug/unplug broadcast that
        // normally starts it, and the user would never know the reminder had gone quiet.
        LowBatteryAlarm.resume(this)
        // Hourly background sample, honouring the user's switch in Settings.
        SampleScheduler.apply(this)
    }
}
