package io.github.jenil1320.cellspan.data

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics

/**
 * Periodic background job that records one battery sample. Scheduled by
 * [io.github.jenil1320.cellspan.CellspanApp]; needs no permissions and no input.
 */
class SampleWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result = try {
        val snapshot = BatteryReader.read(applicationContext)
        val analysis = BatteryAnalytics.analyze(snapshot, System.currentTimeMillis())
        HistoryStore(applicationContext).use { it.insert(snapshot, analysis) }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
