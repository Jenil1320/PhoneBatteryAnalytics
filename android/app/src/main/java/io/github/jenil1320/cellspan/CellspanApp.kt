package io.github.jenil1320.cellspan

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.jenil1320.cellspan.data.SampleWorker
import java.util.concurrent.TimeUnit

class CellspanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Record a battery sample a few times a day so trends build over time.
        val request = PeriodicWorkRequestBuilder<SampleWorker>(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cellspan-sample",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
