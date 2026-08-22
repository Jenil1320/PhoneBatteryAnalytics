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
        HistoryStore(applicationContext).use { store ->
            // Analysed inside the store, so the hourly rows use the same session
            // capacity and design override the screen does. Hourly rows outnumber
            // foreground ones, so a different pipeline here would make the capacity
            // chart contradict the headline.
            val analysis = BatteryAnalytics.analyze(
                snapshot, System.currentTimeMillis(),
                sessionMeasuredMah = store.sessionCapacityMah(),
                designOverrideMah = AppPrefs.designOverrideMah(applicationContext),
                batteryStartMs = AppPrefs.batteryStartMs(applicationContext),
            )
            // Local time, matching the rule the trend queries use in SQL. Without the
            // explicit zone this quietly followed whatever default the JVM had, so a
            // reading could land on a different day than the chart that plots it.
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = java.util.TimeZone.getDefault() }
                .format(Date(snapshot.timestampMs))
            // Shared accrual baseline (same one tick()/refresh() use), read and
            // advanced under one lock so a drop is counted once, never twice — and the
            // write happens inside that lock, so a failure returns the drop to the cursor.
            AccrualCursor.accrue(applicationContext, snapshot.levelPct) { drop ->
                runCatching { store.addDischarge(day, drop) }.isSuccess
            }
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
