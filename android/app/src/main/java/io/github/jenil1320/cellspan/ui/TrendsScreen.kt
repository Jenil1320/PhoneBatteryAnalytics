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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

enum class TrendRange(val label: String, val days: Int) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 31) }

private fun dayLabel(key: String): String = try {
    SimpleDateFormat("MMM d", Locale.getDefault())
        .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!)
} catch (e: Exception) { key.takeLast(5) }

private fun dayEpoch(key: String): Long = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!.time
} catch (e: Exception) { 0L }

/** Intra-day points (last 24h) for a live vital, normalised to hours-since-first. */
private fun dayPoints(soc: List<SocPoint>, nowTs: Long, sel: (SocPoint) -> Float?): List<Pair<Float, Float>> {
    val since = nowTs - 24 * 3_600_000L
    val pts = soc.filter { it.timestampMs >= since && sel(it) != null }
    if (pts.isEmpty()) return emptyList()
    val t0 = pts.first().timestampMs
    return pts.map { ((it.timestampMs - t0) / 3_600_000f) to sel(it)!! }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Trends", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Temperature, voltage, current and charge cycles over time. Day is live; Week and Month aggregate one point per day.",
            color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(16.dp))
        RangeToggle(range) { range = it }

        val nowTs = socLog.lastOrNull()?.timestampMs ?: System.currentTimeMillis()
        val since24 = nowTs - 24 * 3_600_000L
        // Week/Month = the last N CALENDAR days (not the last N recorded rows), so a
        // stretch with missing days doesn't quietly widen the range beyond its label.
        val window = if (range == TrendRange.DAY) emptyList() else {
            val lastEpoch = dailyVitals.lastOrNull()?.day?.let { dayEpoch(it) } ?: 0L
            val cutoff = lastEpoch - (range.days - 1).toLong() * 86_400_000L
            dailyVitals.filter { dayEpoch(it.day) >= cutoff }
        }
        val xStartDays = window.firstOrNull()?.day?.let { dayLabel(it) } ?: ""

        // ── Temperature ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        if (range == TrendRange.DAY) {
            val tp = dayPoints(socLog, nowTs, { it.temperatureC })
            TrendCard("Temperature · live", tp, { String.format(Locale.US, "%.1f°", it) }, Cell.Cool,
                Fmt.time(socLog.firstOrNull { it.timestampMs >= since24 && it.temperatureC != null }?.timestampMs), "now",
                emptyMsg = "Battery temperature over the last 24 hours appears here as readings accumulate.") {
                MinNowMaxRow(tp.map { it.second }) { String.format(Locale.US, "%.1f°C", it) }
            }
        } else {
            val tp = vitalPoints(window, { it.tempAvg })
            TrendCard("Temperature · daily avg", tp, { String.format(Locale.US, "%.1f°", it) }, Cell.Cool,
                xStartDays, "today",
                emptyMsg = "Not enough daily history yet — keep Cellspan around for a few days.") {
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
        if (range == TrendRange.DAY) {
            val vp = dayPoints(socLog, nowTs, { it.voltageV })
            TrendCard("Voltage · live", vp, { String.format(Locale.US, "%.2fV", it) }, Cell.Accent,
                Fmt.time(socLog.firstOrNull { it.timestampMs >= since24 && it.voltageV != null }?.timestampMs), "now",
                emptyMsg = "Terminal voltage over the last 24 hours — updates while the app is open.") {
                MinNowMaxRow(vp.map { it.second }) { String.format(Locale.US, "%.2f V", it) }
            }
        } else {
            val vp = vitalPoints(window, { it.voltAvg })
            TrendCard("Voltage · daily avg", vp, { String.format(Locale.US, "%.2fV", it) }, Cell.Accent,
                xStartDays, "today",
                emptyMsg = "Not enough daily history yet — keep Cellspan around for a few days.")
        }

        // ── Current ─────────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        if (range == TrendRange.DAY) {
            val cp = dayPoints(socLog, nowTs, { it.currentMa?.toFloat() })
            TrendCard("Current · live", cp, { "${it.toInt()}" }, Cell.AccentBright,
                Fmt.time(socLog.firstOrNull { it.timestampMs >= since24 && it.currentMa != null }?.timestampMs), "now",
                threshold = 0f, thresholdLabel = "0 mA",
                emptyMsg = "Charge/discharge current over the last 24 hours — updates while the app is open.") {
                MinNowMaxRow(cp.map { it.second }) { "${it.toInt()} mA" }
                Spacer(Modifier.height(4.dp))
                Text("Positive = charging, negative = draw.", color = Cell.TextFaint, fontSize = 11.sp)
            }
        } else {
            val cp = vitalPoints(window, { it.currAvg?.toFloat() })
            TrendCard("Current · daily avg", cp, { "${it.toInt()}" }, Cell.AccentBright,
                xStartDays, "today", threshold = 0f, thresholdLabel = "0 mA",
                emptyMsg = "Not enough daily history yet — keep Cellspan around for a few days.") {
                Text("Positive = net charging that day, negative = net draw.", color = Cell.TextFaint, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ── Charge cycles ───────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        if (range == TrendRange.DAY) {
            SurfaceCard {
                Column {
                    SectionLabel("Charge cycles")
                    Spacer(Modifier.height(10.dp))
                    Text("Cycle count changes too slowly to chart within a day. Switch to Week or Month to see it climb.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        } else {
            val cyc = vitalPoints(window, { it.cycles?.toFloat() })
            TrendCard("Charge cycles · daily", cyc, { it.toInt().toString() }, Cell.AccentDeep,
                xStartDays, "today",
                threshold = BatteryAnalytics.REPLACE_CYCLES.toFloat(), thresholdLabel = "800",
                emptyMsg = "Cycle history builds one point per day. If your phone reports cycle count, it appears here.")
        }

        // ── Where the charge sits (habit) ───────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Where your charge sits")
                Spacer(Modifier.height(4.dp))
                Text("Share of recent time at each charge level. The 20–80% middle is gentlest on the cell.",
                    color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
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
private fun RangeToggle(current: TrendRange, onSelect: (TrendRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cell.BgElevated)
            .border(1.dp, Cell.Border, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TrendRange.entries.forEach { r ->
            val active = r == current
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (active) Cell.AccentSoft else Color.Transparent)
                    .clickable { onSelect(r) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(r.label, color = if (active) Cell.Accent else Cell.TextMuted,
                    fontSize = 13.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
            }
        }
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
    threshold: Float? = null,
    thresholdLabel: String? = null,
    emptyMsg: String,
    extra: (@Composable () -> Unit)? = null,
) {
    SurfaceCard {
        Column {
            SectionLabel(title)
            Spacer(Modifier.height(12.dp))
            if (points.size >= 2) {
                TimeSeriesChart(points, yFormat = yFormat, lineColor = lineColor, xStart = xStart, xEnd = xEnd,
                    threshold = threshold, thresholdLabel = thresholdLabel)
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
