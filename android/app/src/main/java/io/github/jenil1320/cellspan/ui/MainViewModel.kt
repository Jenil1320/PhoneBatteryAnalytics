package io.github.jenil1320.cellspan.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jenil1320.cellspan.analytics.Analysis
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
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
                    history = store.recent()
                    usage = store.dailyUsage(7)
                    socLog = store.recentSoc(snapshot.timestampMs - SOC_WINDOW_MS)
                }
            } catch (e: Exception) {
                // history is best-effort; never let it crash the app
            }
            _state.value = UiState(false, snapshot, analysis, history, usage, socLog, vitals)
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
}
