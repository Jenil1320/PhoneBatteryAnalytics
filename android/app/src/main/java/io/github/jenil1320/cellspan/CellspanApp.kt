package io.github.jenil1320.cellspan

import android.app.Application
import io.github.jenil1320.cellspan.data.ChargeAlarm
import io.github.jenil1320.cellspan.data.SampleScheduler

class CellspanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Notification channel for the charge alarm.
        ChargeAlarm.ensureChannel(this)
        // Hourly background sample, honouring the user's switch in Settings.
        SampleScheduler.apply(this)
    }
}
