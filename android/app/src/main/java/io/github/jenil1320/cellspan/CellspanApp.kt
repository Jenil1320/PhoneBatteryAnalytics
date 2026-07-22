package io.github.jenil1320.cellspan

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.jenil1320.cellspan.data.ChargeAlarm
import io.github.jenil1320.cellspan.data.SampleWorker
import java.util.concurrent.TimeUnit

class CellspanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Notification channel for the charge alarm.
        ChargeAlarm.ensureChannel(this)
        // Record a battery sample about once an hour so the charts fill in even when
        // the app isn't open. UPDATE (not KEEP) so the new cadence replaces the old
        // 6-hour schedule on existing installs. Android may still batch this under Doze.
        val request = PeriodicWorkRequestBuilder<SampleWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cellspan-sample",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
