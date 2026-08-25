package io.github.jenil1320.cellspan.data

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.ThermalAdvisor
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
        checkTemperature(snapshot)
        maybeStartGuard()
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

    /**
     * The background half of the heat warning.
     *
     * Heat used to keep the guard service running permanently, which put a card in the
     * notification shade around the clock for a warning that fires a few times a year.
     * That was a bad trade. The check now rides on the hourly sample this worker already
     * takes, which costs nothing extra.
     *
     * The gap that leaves is narrower than it looks. The dangerous case — hot *while
     * charging* — is exactly when the guard is running anyway, so it is caught within a
     * second. What hourly sampling can be late for is a phone cooking on battery in a car
     * mount, and there the app is usually open or the user can already feel it.
     */
    private fun checkTemperature(snapshot: BatterySnapshot) {
        val limit = AppPrefs.tempAlertC(applicationContext) ?: return
        val tempC = snapshot.temperatureC ?: return
        val prev = AppPrefs.guardState(applicationContext)
        val cfg = GuardPolicy.Config(
            lowTarget = AppPrefs.lowBatteryAlert(applicationContext),
            highTarget = AppPrefs.chargeAlarmTarget(applicationContext),
            strictness = AppPrefs.strictness(applicationContext),
            tempAlertC = limit,
        )
        val reading = GuardPolicy.Reading(snapshot.levelPct, snapshot.pluggedIn, tempC)
        // evaluateHeat, NOT evaluate: the full evaluation would also decide the charge-band
        // reminder and rewrite its state, so this background check could mark a reminder as
        // already given without ever showing one. Only the temperature fields are shared,
        // which is what keeps the two paths from both warning about one hot episode.
        val d = GuardPolicy.evaluateHeat(cfg, reading, prev, System.currentTimeMillis())
        AppPrefs.setGuardState(applicationContext, d.state)
        if (!d.tempAlert) return
        val advice = ThermalAdvisor.advise(tempC, snapshot.pluggedIn) ?: return
        BatteryGuardNotifications.ensureChannels(applicationContext)
        BatteryGuardNotifications.postHeat(
            applicationContext, tempC, advice.headline, advice.actions, advice.why,
        )
    }

    /**
     * Start the guard if the level has drifted into range since the last check.
     *
     * On battery the level falls over hours, so nothing is watching until a threshold gets
     * close — and something has to notice when it does. This hourly check is that
     * something. It is well inside the fifteen-point margin the guard starts watching at,
     * so even the slowest hourly wake-up still arrives long before the crossing.
     */
    private fun maybeStartGuard() {
        runCatching { BatteryGuard.ensureRunning(applicationContext) }
    }
}
