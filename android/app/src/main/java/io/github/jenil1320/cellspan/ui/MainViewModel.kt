package io.github.jenil1320.cellspan.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jenil1320.cellspan.analytics.Analysis
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.CapacitySource
import io.github.jenil1320.cellspan.analytics.DegradationModel
import io.github.jenil1320.cellspan.analytics.SohForecaster
import io.github.jenil1320.cellspan.data.AccrualCursor
import io.github.jenil1320.cellspan.data.AppPrefs
import io.github.jenil1320.cellspan.data.BatteryGuard
import io.github.jenil1320.cellspan.data.BatteryReader
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.DailyVital
import io.github.jenil1320.cellspan.data.HistoryStore
import io.github.jenil1320.cellspan.data.HistoryPoint
import io.github.jenil1320.cellspan.data.Session
import io.github.jenil1320.cellspan.data.SocPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val snapshot: BatterySnapshot? = null,
    val analysis: Analysis? = null,
    val history: List<HistoryPoint> = emptyList(),
    val dailyUsage: List<Pair<String, Double>> = emptyList(),
    val socLog: List<SocPoint> = emptyList(),
    val dailyVitals: List<DailyVital> = emptyList(),
    val sohForecast: SohForecaster.Forecast? = null,
    val sessions: List<Session> = emptyList(),
    /** Screen-on vs screen-off drain over the last week, or null while unknown. */
    val screenSplit: HistoryStore.ScreenSplit? = null,
    /** True when the battery guard is running, which is what records the split. */
    val guardRunning: Boolean = false,
)

/**
 * How many days of aggregated daily vitals to load.
 *
 * Was 31, which quietly capped the Trends screen at a month while the database kept those
 * rows forever. Picking last March in the custom-range picker therefore returned nothing
 * and the screen said "not enough daily history yet" — telling a user with eight months of
 * data that their history did not exist. These are one small pre-aggregated row per day,
 * so a bit over a year of them costs almost nothing to read.
 */
private const val TREND_DAYS = 400

private const val SOC_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

/** How far back to look when measuring the user's real depth of discharge. */
private const val DOD_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Serialises the two database loads, with different behaviour for each caller.
     *
     * refresh() and tick() both write the same reading into `soc_log` and `daily_vitals`,
     * and on launch they fire within milliseconds of each other — the constructor calls
     * refresh(), the foreground loop immediately calls tick() — so without this every app
     * open recorded that moment twice and double-counted it in the day's mean temperature,
     * voltage and current.
     *
     * The two differ in what they should do when they lose the race:
     *  - tick() is a periodic sample. A second one while a load is already running adds
     *    nothing, so it gives up (`tryLock`) rather than queueing behind a slow one.
     *  - refresh() always follows a write the user just made — a new design capacity, a
     *    battery start date, an imported history — so it must not be dropped. It waits.
     *    Dropping it left the import toast saying "3400 records" while every chart still
     *    showed the old data until the next 60-second tick.
     *
     * One primitive rather than a mutex plus a flag: a separate flag has a leak path,
     * because a coroutine cancelled between setting it and entering its try block would
     * leave it set forever and freeze every later refresh.
     */
    private val loadMutex = kotlinx.coroutines.sync.Mutex()

    init {
        refresh()
    }

    /**
     * Refresh just the live figures, driven by an ACTION_BATTERY_CHANGED broadcast so the
     * orb and vitals move the moment the phone reports a change rather than waiting for
     * the next poll. Deliberately does NO database work: that broadcast can fire every few
     * seconds while charging, and the 60-second tick already owns persistence.
     */
    fun liveUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = BatteryReader.read(getApplication())
            _state.update { it.copy(snapshot = snapshot) }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            // Waits rather than skips: a refresh always follows a write the user just made.
            loadMutex.withLock {
            val snapshot = BatteryReader.read(getApplication())
            val now = System.currentTimeMillis()
            // Read once, outside the try: a database hiccup must not silently drop the
            // user's own design capacity and battery start date from the fallback.
            val designOverride = AppPrefs.designOverrideMah(getApplication())
            val startMs = AppPrefs.batteryStartMs(getApplication())
            var analysis = BatteryAnalytics.analyze(snapshot, now,
                designOverrideMah = designOverride, batteryStartMs = startMs)
            var history: List<HistoryPoint> = emptyList()
            var usage: List<Pair<String, Double>> = emptyList()
            var socLog: List<SocPoint> = emptyList()
            var vitals: List<DailyVital> = emptyList()
            var sessions: List<Session> = emptyList()
            var split: HistoryStore.ScreenSplit? = null
            try {
                HistoryStore(getApplication()).use { store ->
                    vitals = store.dailyVitals(TREND_DAYS)
                    analysis = fullAnalysis(store, snapshot, now, vitals, designOverride, startMs)
                    val last = store.lastSampleMs()
                    if (last == null || snapshot.timestampMs - last > 10 * 60 * 1000) {
                        store.insert(snapshot, analysis)
                    }
                    store.ensureDay(dayKey(snapshot.timestampMs))
                    accrueDischarge(store, snapshot)
                    store.appendSoc(snapshot.timestampMs, snapshot.levelPct, snapshot.temperatureC,
                        mv(snapshot.voltageV), ma(snapshot.currentUa))
                    store.recordVitals(dayKey(snapshot.timestampMs), snapshot.temperatureC,
                        snapshot.voltageV, ma(snapshot.currentUa), snapshot.cycleCount)
                    analysis = smoothEstimate(store, analysis)  // steady headline (after inserting raw)
                    history = store.recent()
                    usage = store.dailyUsage(7)
                    socLog = store.recentSoc(snapshot.timestampMs - SOC_WINDOW_MS)
                    sessions = store.recentSessions(20)
                    split = store.screenSplit(7)
                }
            } catch (e: Exception) {
                // history is best-effort; never let it crash the app
                android.util.Log.w("Cellspan", "history store failed", e)
            }
            val forecast = runCatching { buildForecast(history, analysis, now) }.getOrNull()
            _state.update {
                it.copy(snapshot = snapshot, analysis = analysis, history = history,
                    dailyUsage = usage, socLog = socLog, dailyVitals = vitals,
                    sohForecast = forecast, sessions = sessions,
                    screenSplit = split ?: it.screenSplit,
                    guardRunning = BatteryGuard.isRunning(getApplication()))
            }
            }
        }
    }

    /** Light live refresh (every few seconds while the app is open): updates the
     *  live vitals and the SoC log without re-inserting a health sample. */
    fun tick() {
        viewModelScope.launch(Dispatchers.IO) {
            // Skips, unlike refresh(): if a load is already running, this periodic sample
            // has nothing to add, and queueing it would only pile ticks up behind a slow one.
            if (!loadMutex.tryLock()) return@launch
            try {
            val snapshot = BatteryReader.read(getApplication())
            val now = System.currentTimeMillis()
            val designOverride = AppPrefs.designOverrideMah(getApplication())
            val startMs = AppPrefs.batteryStartMs(getApplication())
            var analysis = BatteryAnalytics.analyze(snapshot, now,
                designOverrideMah = designOverride, batteryStartMs = startMs)
            // Null until the store answers, so a failed read keeps whatever the
            // screen already shows instead of blanking it.
            var socLog: List<SocPoint>? = null
            var usage: List<Pair<String, Double>>? = null
            var vitals: List<DailyVital>? = null
            // The tick is the only refresh a warm resume gets, so it re-reads
            // history and sessions too.
            var history = _state.value.history
            var sessions = _state.value.sessions
            var split: HistoryStore.ScreenSplit? = null
            try {
                HistoryStore(getApplication()).use { store ->
                    val v = store.dailyVitals(TREND_DAYS)
                    vitals = v
                    analysis = fullAnalysis(store, snapshot, now, v, designOverride, startMs)
                    accrueDischarge(store, snapshot)
                    store.appendSoc(snapshot.timestampMs, snapshot.levelPct, snapshot.temperatureC,
                        mv(snapshot.voltageV), ma(snapshot.currentUa))
                    store.recordVitals(dayKey(snapshot.timestampMs), snapshot.temperatureC,
                        snapshot.voltageV, ma(snapshot.currentUa), snapshot.cycleCount)
                    analysis = smoothEstimate(store, analysis)
                    socLog = store.recentSoc(snapshot.timestampMs - SOC_WINDOW_MS)
                    usage = store.dailyUsage(7)
                    history = store.recent()
                    sessions = store.recentSessions(20)
                    split = store.screenSplit(7)
                }
            } catch (e: Exception) {
                android.util.Log.w("Cellspan", "history store failed", e)
            }
            val forecast = runCatching { buildForecast(history, analysis, now) }.getOrNull()
            _state.update {
                it.copy(snapshot = snapshot, analysis = analysis, history = history,
                    dailyUsage = usage ?: it.dailyUsage, socLog = socLog ?: it.socLog,
                    dailyVitals = vitals ?: it.dailyVitals,
                    sohForecast = forecast, sessions = sessions,
                    screenSplit = split ?: it.screenSplit,
                    guardRunning = BatteryGuard.isRunning(getApplication()))
            }
            } finally {
                loadMutex.unlock()
            }
        }
    }

    /** Accrue any level drop since the last accrual, using one shared baseline.
     *  Serves both refresh() and tick(); the cursor is advanced under a lock so
     *  the background worker cannot count the same drop as well, and the database
     *  write happens inside that lock so a failure rolls the cursor back instead of
     *  discarding the drop. */
    private fun accrueDischarge(store: HistoryStore, snapshot: BatterySnapshot) {
        AccrualCursor.accrue(getApplication<Application>(), snapshot.levelPct) { drop ->
            runCatching { store.addDischarge(dayKey(snapshot.timestampMs), drop) }.isSuccess
        }
    }

    /**
     * The calendar day a timestamp belongs to, in the device's own timezone.
     *
     * Must stay identical to the rule SQLite uses in [HistoryStore.recent]
     * (`'unixepoch','localtime'`). When the two disagreed — one bucketing in local time,
     * the other elsewhere — the samples trend and the daily usage bars silently landed on
     * different days after travel or a daylight-saving change, and the same reading could
     * be counted against two dates.
     */
    private fun dayKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(Date(ms))

    private fun mv(v: Float?): Int? = v?.let { (it * 1000).toInt() }
    private fun ma(ua: Long?): Int? = ua?.let { (it / 1000).toInt() }

    private fun avgTemp(v: List<DailyVital>): Double? =
        v.mapNotNull { it.tempAvg?.toDouble() }.average().takeIf { !it.isNaN() }

    /**
     * The one analysis every caller uses: session-measured capacity for a stable SoH,
     * the daily-average temperature for the degradation model, and the user's own
     * settings. Kept in one place because the copy in refresh() and the copy in tick()
     * drifted apart and produced two different SoH figures on one screen.
     */
    private fun fullAnalysis(store: HistoryStore, snapshot: BatterySnapshot, now: Long,
                             vitals: List<DailyVital>, designOverride: Int?, startMs: Long?): Analysis =
        BatteryAnalytics.analyze(
            snapshot, now, store.sessionCapacityMah(), store.sessionCount(),
            avgTemp(vitals),
            // Real depth of discharge from recorded sessions. Without it the
            // model falls back to a representative constant and, historically,
            // to an EV-scale 0.7 that over-predicted wear badly.
            avgDoD = store.avgDischargeDoD(now - DOD_WINDOW_MS),
            designOverrideMah = designOverride,
            batteryStartMs = startMs,
            // The observed scatter between sessions is the health figure's real error bar,
            // so it is measured rather than assumed.
            sessionSpreadPct = store.sessionSpreadPct())

    /**
     * Stabilise the displayed capacity/SoH when it's the noisy instantaneous estimate,
     * using a median of recent stored readings so the headline number stops bouncing
     * between refreshes. The raw sample is stored first, so the capacity scatter stays
     * honest; only the displayed headline is smoothed.
     */
    private fun smoothEstimate(store: HistoryStore, analysis: Analysis): Analysis {
        if (analysis.capacitySource != CapacitySource.ESTIMATE &&
            analysis.capacitySource != CapacitySource.NONE) return analysis
        val design = analysis.designCapacityMah ?: return analysis
        if (design <= 0) return analysis
        val smoothed = if (analysis.capacitySource == CapacitySource.NONE) {
            // Below ~15% there is no usable live reading at all, so lean entirely on
            // what is already stored rather than leaving the Health screen blank.
            store.storedMedianCapacityMah() ?: return analysis
        } else {
            store.medianCapacityMah(analysis.currentCapacityMah ?: return analysis)
        }
        // Same implausibility guard the analyser uses: reject the reading outright
        // rather than clamping a nonsense capacity into a believable-looking SoH.
        val ratio = smoothed.toDouble() / design * 100.0
        if (ratio !in 0.0..130.0) return analysis
        val soh = ratio.coerceAtMost(100.0)
        return analysis.copy(
            currentCapacityMah = smoothed,
            sohPct = soh,
            // Still an estimate, whichever path got us here — the UI must say so.
            capacitySource = CapacitySource.ESTIMATE,
            condition = BatteryAnalytics.conditionFor(soh, analysis.cycleCount),
        )
    }

    /** Fit the on-device SoH trend from history, cross-checked against the physics model. */
    private fun buildForecast(history: List<HistoryPoint>, analysis: Analysis, nowMs: Long): SohForecaster.Forecast? {
        // No design-capacity requirement any more. The cyclic term is normalised to the
        // model's reference cell, so it does not use the pack's capacity — and demanding
        // one anyway blanked the entire forecast on exactly the phones the normalisation
        // was written for: a ROM that hides its PowerProfile and a model absent from the
        // capacity table. Those phones have a perfectly good cycle count, which is all the
        // projection needs.
        val design = analysis.designCapacityMah
        val cyclesNow = analysis.cycleCount?.toDouble() ?: return null
        val samples = history.mapNotNull { hp ->
            val soh = hp.sohPct
            val cyc = hp.cycleCount
            // Points backed by a real measurement carry full weight; points that were only
            // the noisy instantaneous estimate are down-weighted, so a long run of jittery
            // early readings cannot dominate the projected replacement date. The forecaster
            // has always accepted a quality figure — nothing was ever passing one.
            if (soh != null && cyc != null)
                SohForecaster.Sample(soh, cyc.toDouble(), hp.timestampMs, quality = hp.quality)
            else null
        }
        // The physics baseline must match the "Measured vs modelled" card exactly: same
        // depth of discharge, and the same calendar loss added on top. Cyclic-only here
        // let the two cards judge the same cell against different baselines, so one
        // screen could call the battery both better and worse than typical.
        val dod = analysis.modeledDoD

        // Calendar loss must keep accruing along the projection, not sit frozen at today's
        // value. Holding it constant made the model claim a stored phone ages not at all,
        // and pushed every long-horizon date later than the physics actually supports.
        //
        // The age itself now comes FROM the forecaster, as the second argument. Working it
        // out here was the bug: this method only has the lifetime average cycles-per-day,
        // while the forecaster dates everything with the recent rate it measured from the
        // sample window. Two different rates on one axis made the bisection cross the 80%
        // line at a date the model never supported — years early for anyone whose recent
        // use is heavier than their lifetime average.
        val ageNow = analysis.ageDays
        val tempC = analysis.modeledTempC
        val calFracNow = (analysis.modeledCalendarLossPct ?: 0.0) / 100.0

        return SohForecaster.forecast(
            samples, cyclesNow, nowMs,
            physicsLossFrac = { c, ageDays ->
                val cal = if (ageNow != null) DegradationModel.calendarLossFrac(ageDays, tempC)
                else calFracNow
                cal + DegradationModel.cyclicLossFrac(c, design, dod)
            },
            ageDaysNow = ageNow,
        )
    }
}
