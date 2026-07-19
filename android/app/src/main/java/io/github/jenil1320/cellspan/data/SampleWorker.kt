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
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(snapshot.timestampMs))
            // Shared accrual baseline (same one tick()/refresh() use) → counted once.
            val prev = AccrualCursor.get(applicationContext)
            if (prev != null && snapshot.levelPct < prev) {
                store.addDischarge(day, (prev - snapshot.levelPct).toDouble())
            }
            AccrualCursor.set(applicationContext, snapshot.levelPct)
            store.ensureDay(day)
            store.insert(snapshot, analysis)
            // Fold this reading into the day's aggregates so week/month trends
            // build even when the app is never opened.
            store.recordVitals(day, snapshot.temperatureC, snapshot.voltageV,
                snapshot.currentUa?.let { (it / 1000).toInt() }, snapshot.cycleCount)
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
