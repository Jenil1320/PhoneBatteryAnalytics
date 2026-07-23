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
        val analysis = BatteryAnalytics.analyze(
            snapshot, System.currentTimeMillis(),
            batteryStartMs = AppPrefs.batteryStartMs(applicationContext),
        )
        HistoryStore(applicationContext).use { store ->
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(snapshot.timestampMs))
            // Shared accrual baseline (same one tick()/refresh() use), read and
            // advanced under one lock so a drop is counted once, never twice.
            val drop = AccrualCursor.accrue(applicationContext, snapshot.levelPct)
            if (drop > 0) store.addDischarge(day, drop)
            store.ensureDay(day)
            store.insert(snapshot, analysis)
            // Fold this reading into the day's aggregates so week/month trends
            // build even when the app is never opened.
            store.recordVitals(day, snapshot.temperatureC, snapshot.voltageV,
                snapshot.currentUa?.let { (it / 1000).toInt() }, snapshot.cycleCount)
            // Also drop a live point so the intra-day temperature/voltage/current
            // charts fill in between app opens (previously only refreshed while open).
            store.appendSoc(snapshot.timestampMs, snapshot.levelPct, snapshot.temperatureC,
                snapshot.voltageV?.let { (it * 1000).toInt() },
                snapshot.currentUa?.let { (it / 1000).toInt() })
        }
        Result.success()
    } catch (e: Exception) {
        // Success, not retry: on a periodic request a retry replaces the next
        // scheduled run and backs off to hours, so a lasting fault would kill the
        // hourly rhythm. The next hourly run is the retry — losing one sample is
        // cheaper than losing the cadence.
        Result.success()
    }
}
