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
import io.github.jenil1320.cellspan.data.BatteryReader
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.DailyVital
import io.github.jenil1320.cellspan.data.HistoryStore
import io.github.jenil1320.cellspan.data.HistoryPoint
import io.github.jenil1320.cellspan.data.SocPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val loading: Boolean = true,
    val snapshot: BatterySnapshot? = null,
    val analysis: Analysis? = null,
    val history: List<HistoryPoint> = emptyList(),
    val dailyUsage: List<Pair<String, Double>> = emptyList(),
    val socLog: List<SocPoint> = emptyList(),
    val dailyVitals: List<DailyVital> = emptyList(),
    val sohForecast: SohForecaster.Forecast? = null,
)

private const val TREND_DAYS = 31

private const val SOC_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = BatteryReader.read(getApplication())
            val now = System.currentTimeMillis()
            var analysis = BatteryAnalytics.analyze(snapshot, now)
            var history: List<HistoryPoint> = emptyList()
            var usage: List<Pair<String, Double>> = emptyList()
            var socLog: List<SocPoint> = emptyList()
            var vitals: List<DailyVital> = emptyList()
            try {
                HistoryStore(getApplication()).use { store ->
                    vitals = store.dailyVitals(TREND_DAYS)
                    // Prefer session-measured capacity for a stable SoH; feed the
                    // degradation model the daily-average temperature.
                    analysis = BatteryAnalytics.analyze(
                        snapshot, now, store.sessionCapacityMah(), store.sessionCount(),
                        avgTemp(vitals))
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
                }
            } catch (e: Exception) {
                // history is best-effort; never let it crash the app
            }
            val forecast = runCatching { buildForecast(history, analysis, now) }.getOrNull()
            _state.value = UiState(false, snapshot, analysis, history, usage, socLog, vitals, forecast)
        }
    }

    /** Light live refresh (every few seconds while the app is open): updates the
     *  live vitals and the SoC log without re-inserting a health sample. */
    fun tick() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = BatteryReader.read(getApplication())
            val now = System.currentTimeMillis()
            var analysis = BatteryAnalytics.analyze(snapshot, now)
            var socLog = _state.value.socLog
            var usage = _state.value.dailyUsage
            var vitals = _state.value.dailyVitals
            try {
                HistoryStore(getApplication()).use { store ->
                    vitals = store.dailyVitals(TREND_DAYS)
                    analysis = BatteryAnalytics.analyze(
                        snapshot, now, store.sessionCapacityMah(), store.sessionCount(),
                        avgTemp(vitals))
                    accrueDischarge(store, snapshot)
                    store.appendSoc(snapshot.timestampMs, snapshot.levelPct, snapshot.temperatureC,
                        mv(snapshot.voltageV), ma(snapshot.currentUa))
                    store.recordVitals(dayKey(snapshot.timestampMs), snapshot.temperatureC,
                        snapshot.voltageV, ma(snapshot.currentUa), snapshot.cycleCount)
                    analysis = smoothEstimate(store, analysis)
                    socLog = store.recentSoc(snapshot.timestampMs - SOC_WINDOW_MS)
                    usage = store.dailyUsage(7)
                }
            } catch (e: Exception) { /* ignore */ }
            _state.value = _state.value.copy(snapshot = snapshot, analysis = analysis,
                socLog = socLog, dailyUsage = usage, dailyVitals = vitals)
        }
    }

    /** Accrue any level drop since the last accrual, using one shared baseline. */
    private fun accrueDischarge(store: HistoryStore, snapshot: BatterySnapshot) {
        val app = getApplication<Application>()
        val prev = AccrualCursor.get(app)
        if (prev != null && snapshot.levelPct < prev) {
            store.addDischarge(dayKey(snapshot.timestampMs), (prev - snapshot.levelPct).toDouble())
        }
        AccrualCursor.set(app, snapshot.levelPct)
    }

    private fun dayKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun mv(v: Float?): Int? = v?.let { (it * 1000).toInt() }
    private fun ma(ua: Long?): Int? = ua?.let { (it / 1000).toInt() }

    private fun avgTemp(v: List<DailyVital>): Double? =
        v.mapNotNull { it.tempAvg?.toDouble() }.average().takeIf { !it.isNaN() }

    /**
     * Stabilise the displayed capacity/SoH when it's the noisy instantaneous estimate,
     * using a median of recent stored readings so the headline number stops bouncing
     * between refreshes. The raw sample is stored first, so the capacity scatter stays
     * honest; only the displayed headline is smoothed.
     */
    private fun smoothEstimate(store: HistoryStore, analysis: Analysis): Analysis {
        if (analysis.capacitySource != CapacitySource.ESTIMATE) return analysis
        val cur = analysis.currentCapacityMah ?: return analysis
        val design = analysis.designCapacityMah ?: return analysis
        if (design <= 0) return analysis
        val smoothed = store.medianCapacityMah(cur)
        val soh = (smoothed.toDouble() / design * 100.0).coerceIn(0.0, 100.0)
        return analysis.copy(
            currentCapacityMah = smoothed,
            sohPct = soh,
            condition = BatteryAnalytics.conditionFor(soh, analysis.cycleCount),
        )
    }

    /** Fit the on-device SoH trend from history, cross-checked against the physics model. */
    private fun buildForecast(history: List<HistoryPoint>, analysis: Analysis, nowMs: Long): SohForecaster.Forecast? {
        val design = analysis.designCapacityMah ?: return null
        val cyclesNow = analysis.cycleCount?.toDouble() ?: return null
        val samples = history.mapNotNull { hp ->
            val soh = hp.sohPct
            val cyc = hp.cycleCount
            if (soh != null && cyc != null) SohForecaster.Sample(soh, cyc.toDouble(), hp.timestampMs) else null
        }
        return SohForecaster.forecast(samples, cyclesNow, nowMs,
            physicsLossFrac = { c -> DegradationModel.cyclicLossFrac(c, design) })
    }
}
