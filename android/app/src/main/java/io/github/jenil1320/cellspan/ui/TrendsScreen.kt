package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.util.Date
import java.util.TimeZone
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.data.DailyVital
import io.github.jenil1320.cellspan.data.SocPoint
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import java.text.SimpleDateFormat
import java.util.Locale

enum class TrendRange(val label: String, val days: Int) {
    DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 31), YEAR("Year", 365)
}

private fun dayLabel(key: String): String = try {
    SimpleDateFormat("MMM d", Locale.getDefault())
        .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!)
} catch (e: Exception) { key.takeLast(5) }

private fun dayEpoch(key: String): Long = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!.time
} catch (e: Exception) { 0L }

private const val NO_DAILY_HISTORY = "Not enough daily history yet — keep Cellspan around for a few days."

/** Shown when a custom range is set but no day inside it was ever recorded. */
private const val NO_DATA_IN_RANGE = "No readings in the dates you picked. Cellspan only has days on which the phone was running it."

/**
 * A live series and the instant its x-axis starts from. The origin travels with the
 * points so the axis labels can never be worked out from a different reading than
 * the one that actually drew the first point.
 */
private data class LiveSeries(val points: List<Pair<Float, Float>>, val t0: Long?) {
    /** Label for x = 0; empty when the series has no points. */
    val startLabel: String get() = Fmt.time(t0)
    /** Turns an x value (hours since [t0]) back into a clock time. */
    val xValueLabel: ((Float) -> String)? get() =
        t0?.let { origin -> { x: Float -> Fmt.time(origin + (x * 3_600_000f).toLong()) } }
}

/** Intra-day points (last 24h) for a live vital, normalised to hours-since-first. */
private fun dayPoints(soc: List<SocPoint>, nowTs: Long, sel: (SocPoint) -> Float?): LiveSeries {
    val since = nowTs - 24 * 3_600_000L
    val pts = soc.filter { it.timestampMs >= since && sel(it) != null }
    if (pts.isEmpty()) return LiveSeries(emptyList(), null)
    val t0 = pts.first().timestampMs
    return LiveSeries(pts.map { ((it.timestampMs - t0) / 3_600_000f) to sel(it)!! }, t0)
}

/**
 * One point per calendar day for the week/month trend, with x = real days since the
 * first day in the window (NOT the list index) so gaps between recorded days render
 * proportionally to elapsed time instead of being compressed.
 */
private fun vitalPoints(days: List<DailyVital>, sel: (DailyVital) -> Float?): List<Pair<Float, Float>> {
    val withX = days.mapNotNull { d -> sel(d)?.let { dayEpoch(d.day) to it } }.filter { it.first > 0L }
    if (withX.isEmpty()) return emptyList()
    val t0 = withX.first().first
    return withX.map { ((it.first - t0) / 86_400_000f) to it.second }
}

@Composable
fun TrendsScreen(socLog: List<SocPoint>, dailyVitals: List<DailyVital>, dailyUsage: List<Pair<String, Double>>) {
    var range by rememberSaveable { mutableStateOf(TrendRange.DAY) }
    // Saveable: a rotation must not silently drop the range the user picked.
    var custom by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }  // ISO day keys
    var showPicker by rememberSaveable { mutableStateOf(false) }
    // "Live" = the intra-day view from the 24h log; otherwise we aggregate daily rows.
    val live = range == TrendRange.DAY && custom == null

    if (showPicker) {
        DateRangePickerSheet(
            onDismiss = { showPicker = false },
            onApply = { s, e -> custom = s to e; showPicker = false },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Trends", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(16.dp))
        // Selecting a preset clears any custom range; while custom is active no preset is highlighted.
        RangeToggle(range, active = custom == null) { range = it; custom = null }

        // Custom date range, anywhere inside the daily history Cellspan holds.
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // The vertical padding sits inside the clickable, so it grows the touch target to ~48dp.
            Text(custom?.let { "Custom: ${dayLabel(it.first)} – ${dayLabel(it.second)}" } ?: "Pick a custom range",
                color = if (custom != null) Cell.Accent else Cell.TextMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { showPicker = true }.padding(vertical = 15.dp))
            if (custom != null) Text("Clear", color = Cell.TextMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { custom = null }.padding(vertical = 15.dp))
        }

        val nowTs = socLog.lastOrNull()?.timestampMs ?: System.currentTimeMillis()
        val since24 = nowTs - 24 * 3_600_000L
        // Aggregated window: a custom range, else the last N CALENDAR days (not the last
        // N recorded rows), so missing days don't quietly widen the range beyond its label.
        val window = when {
            custom != null -> dailyVitals.filter { it.day >= custom!!.first && it.day <= custom!!.second }
            range == TrendRange.DAY -> emptyList()
            else -> {
                val lastEpoch = dailyVitals.lastOrNull()?.day?.let { dayEpoch(it) } ?: 0L
                val cutoff = lastEpoch - (range.days - 1).toLong() * 86_400_000L
                dailyVitals.filter { dayEpoch(it.day) >= cutoff }
            }
        }
        // The first day that actually has a value for a given metric, not just the first
        // day in the window: vitalPoints drops days whose selector is null, so a boundary
        // day missing that one metric made the axis name a day the chart does not plot.
        fun startLabel(sel: (DailyVital) -> Any?): String =
            window.firstOrNull { sel(it) != null }?.day?.let { dayLabel(it) } ?: ""
        val xStartDays = window.firstOrNull()?.day?.let { dayLabel(it) } ?: ""
        // A custom range that came back empty is a different fact from "no history yet",
        // and saying the wrong one told users with months of data that it had been lost.
        val emptyDaily = if (custom != null) NO_DATA_IN_RANGE else NO_DAILY_HISTORY
        // "today" only when the newest plotted day really IS today. Hard-coding it meant a
        // phone that had recorded nothing for a few days still labelled the right-hand edge
        // "today", so the reader took a stale temperature for the current one.
        val lastDay = window.lastOrNull()?.day
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date())
        val xEndDays = when {
            custom != null -> lastDay?.let { dayLabel(it) } ?: ""
            lastDay == null || lastDay == todayKey -> "today"
            else -> dayLabel(lastDay)
        }
        // Mid x-axis label (shared): the middle of the visible window.
        val dayTsList = socLog.filter { it.timestampMs >= since24 }.map { it.timestampMs }
        val dayMid = if (dayTsList.size >= 2) Fmt.time((dayTsList.min() + dayTsList.max()) / 2) else ""
        val aggMid = window.getOrNull(window.size / 2)?.day?.let { dayLabel(it) } ?: ""

        // ── Temperature ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        if (live) {
            val temp = dayPoints(socLog, nowTs, { it.temperatureC })
            TrendCard("Temperature · live", temp.points, { String.format(Locale.US, "%.0f°C", it) }, Cell.Cool,
                temp.startLabel, "now", xMid = dayMid,
                xValueLabel = temp.xValueLabel,
                emptyMsg = "Battery temperature over the last 24 hours appears here as readings accumulate.") {
                MinNowMaxRow(temp.points.map { it.second }) { String.format(Locale.US, "%.1f°C", it) }
            }
        } else {
            val tp = vitalPoints(window, { it.tempAvg })
            TrendCard("Temperature · daily avg", tp, { String.format(Locale.US, "%.0f°C", it) }, Cell.Cool,
                startLabel { it.tempAvg }, xEndDays, xMid = aggMid,
                emptyMsg = emptyDaily) {
                val mins = window.mapNotNull { it.tempMin }
                val maxs = window.mapNotNull { it.tempMax }
                if (mins.isNotEmpty() && maxs.isNotEmpty()) {
                    LegendRow(
                        "low" to String.format(Locale.US, "%.1f°C", mins.min()),
                        "avg" to String.format(Locale.US, "%.1f°C", tp.map { it.second }.average()),
                        "high" to String.format(Locale.US, "%.1f°C", maxs.max()),
                    )
                }
            }
        }

        // ── Voltage ─────────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        if (live) {
            val volt = dayPoints(socLog, nowTs, { it.voltageV })
            TrendCard("Voltage · live", volt.points, { String.format(Locale.US, "%.2f V", it) }, Cell.Accent,
                volt.startLabel, "now", xMid = dayMid,
                xValueLabel = volt.xValueLabel,
                emptyMsg = "Terminal voltage over the last 24 hours — updates while the app is open.") {
                MinNowMaxRow(volt.points.map { it.second }) { String.format(Locale.US, "%.2f V", it) }
            }
        } else {
            val vp = vitalPoints(window, { it.voltAvg })
            TrendCard("Voltage · daily avg", vp, { String.format(Locale.US, "%.2f V", it) }, Cell.Accent,
                startLabel { it.voltAvg }, xEndDays, xMid = aggMid,
                emptyMsg = emptyDaily)
        }

        // ── Current ─────────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        if (live) {
            val curr = dayPoints(socLog, nowTs, { it.currentMa?.toFloat() })
            TrendCard("Current · live", curr.points, { "${it.toInt()} mA" }, Cell.AccentBright,
                curr.startLabel, "now", xMid = dayMid, threshold = 0f, thresholdLabel = "0 mA", thresholdColor = Cell.TextMuted,
                xValueLabel = curr.xValueLabel,
                emptyMsg = "Charge/discharge current over the last 24 hours — updates while the app is open.") {
                MinNowMaxRow(curr.points.map { it.second }) { "${it.toInt()} mA" }
            }
        } else {
            val cp = vitalPoints(window, { it.currAvg?.toFloat() })
            TrendCard("Current · daily avg", cp, { "${it.toInt()} mA" }, Cell.AccentBright,
                startLabel { it.currAvg }, xEndDays, xMid = aggMid, threshold = 0f, thresholdLabel = "0 mA", thresholdColor = Cell.TextMuted,
                emptyMsg = emptyDaily)
        }

        // ── Charge cycles ───────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        run {
            // Cycle count is a daily metric: on the Day view show the recent daily history
            // rather than a flat within-day line, so the chart is always meaningful.
            val cs = if (live) dailyVitals.takeLast(21) else window
            val cyc = vitalPoints(cs, { it.cycles?.toFloat() })
            TrendCard("Charge cycles · daily", cyc, { "${it.toInt()} cyc" }, Cell.AccentDeep,
                cs.firstOrNull()?.day?.let { dayLabel(it) } ?: "",
                if (live) "today" else xEndDays,
                xMid = cs.getOrNull(cs.size / 2)?.day?.let { dayLabel(it) } ?: "",
                threshold = BatteryAnalytics.REPLACE_CYCLES.toFloat(), thresholdLabel = "800",
                emptyMsg = "Cycle history builds one point per day. If your phone reports cycle count, it appears here.")
        }

        // ── Where the charge sits (habit) ───────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Where your charge sits")
                Spacer(Modifier.height(12.dp))
                val dist = BatteryAnalytics.levelDistribution(socLog.map { it.levelPct })
                val total = dist.sum().coerceAtLeast(1)
                val peak = (dist.maxOrNull() ?: 1).coerceAtLeast(1)
                if (dist.sum() > 0) {
                    dist.forEachIndexed { i, v ->
                        HBar("${i * 10}–${i * 10 + 10}%", v.toFloat() / peak, "${(v * 100 / total)}%",
                            barColor = if (i in 2..7) Cell.Accent else Cell.Warn)
                    }
                } else {
                    Text("Collecting…", color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
                }
            }
        }

        // ── Daily usage ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Daily usage")
                Spacer(Modifier.height(4.dp))
                if (dailyUsage.isNotEmpty()) {
                    val avg = dailyUsage.map { it.second }.average()
                    Text(String.format(Locale.US, "~%.2f equivalent cycles per day", BatteryAnalytics.equivalentCycles(avg)),
                        color = Cell.TextMuted, fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    UsageBars(dailyUsage.map { Fmt.dayShort(it.first) to (it.second / 100.0).toFloat() })
                } else {
                    Text("How much battery you use each day appears here as you go — one bar per day.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun RangeToggle(current: TrendRange, active: Boolean = true, onSelect: (TrendRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cell.BgElevated)
            .border(1.dp, Cell.Border, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TrendRange.entries.forEach { r ->
            val isActive = active && r == current
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (isActive) Cell.AccentSoft else Color.Transparent)
                    .clickable { onSelect(r) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(r.label, color = if (isActive) Cell.Accent else Cell.TextMuted,
                    fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

/** Material date-range picker; returns the chosen start/end as ISO "yyyy-MM-dd" day keys. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerSheet(onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    val state = rememberDateRangePickerState()
    // The picker returns UTC-midnight millis; format in UTC so the day key matches.
    val utc = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                onClick = {
                    val s = state.selectedStartDateMillis
                    val e = state.selectedEndDateMillis
                    if (s != null && e != null) onApply(utc.format(Date(s)), utc.format(Date(e)))
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.height(500.dp))
    }
}

@Composable
private fun TrendCard(
    title: String,
    points: List<Pair<Float, Float>>,
    yFormat: (Float) -> String,
    lineColor: Color,
    xStart: String,
    xEnd: String,
    xMid: String = "",
    threshold: Float? = null,
    thresholdLabel: String? = null,
    thresholdColor: Color = Cell.Bad,
    xValueLabel: ((Float) -> String)? = null,
    emptyMsg: String,
    extra: (@Composable () -> Unit)? = null,
) {
    SurfaceCard {
        Column {
            SectionLabel(title)
            Spacer(Modifier.height(12.dp))
            if (points.size >= 2) {
                TimeSeriesChart(points, yFormat = yFormat, lineColor = lineColor, xStart = xStart, xEnd = xEnd,
                    xMid = xMid, threshold = threshold, thresholdLabel = thresholdLabel,
                    thresholdColor = thresholdColor, xValueLabel = xValueLabel)
                extra?.invoke()
            } else {
                Text(emptyMsg, color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun MinNowMaxRow(ys: List<Float>, fmt: (Float) -> String) {
    if (ys.isEmpty()) return
    LegendRow("min" to fmt(ys.min()), "now" to fmt(ys.last()), "max" to fmt(ys.max()))
}

@Composable
private fun LegendRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(k.uppercase(), color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.sp)
                Text(v, color = Cell.TextSecondary, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
