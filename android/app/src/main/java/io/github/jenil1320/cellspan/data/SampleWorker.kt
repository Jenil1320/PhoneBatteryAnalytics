package io.github.jenil1320.cellspan.data

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic background job that records one battery sample and accrues daily
 * usage. Scheduled by [io.github.jenil1320.cellspan.CellspanApp]; needs no
 * permissions and no input.
 */
class SampleWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result = try {
        val snapshot = BatteryReader.read(applicationContext)
        val analysis = BatteryAnalytics.analyze(snapshot, System.currentTimeMillis())
        HistoryStore(applicationContext).use { store ->
            val prev = store.lastLevel()
            if (prev != null && snapshot.levelPct < prev) {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(snapshot.timestampMs))
                store.addDischarge(day, (prev - snapshot.levelPct).toDouble())
            }
            store.insert(snapshot, analysis)
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
