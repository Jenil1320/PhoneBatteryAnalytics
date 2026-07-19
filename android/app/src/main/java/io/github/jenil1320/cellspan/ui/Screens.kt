package io.github.jenil1320.cellspan.ui

import android.os.Build
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.analytics.Analysis
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.CapacitySource
import io.github.jenil1320.cellspan.analytics.Condition
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.HistoryPoint
import io.github.jenil1320.cellspan.data.SocPoint
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── helpers ──────────────────────────────────────────────────────────────────
object Fmt {
    fun age(days: Double?): String {
        if (days == null) return "—"
        val y = (days / 365.25).toInt()
        val m = ((days - y * 365.25) / 30.437).toInt()
        return when {
            y > 0 && m > 0 -> "$y yr $m mo"
            y > 0 -> "$y yr"
            else -> "$m mo"
        }
    }
    fun date(ms: Long?) = if (ms == null) "—" else SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(ms))
    fun isoDate(ms: Long?) = if (ms == null) "—" else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    fun time(ms: Long?) = if (ms == null) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    fun dayShort(key: String): String = try {
        SimpleDateFormat("EEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!)
    } catch (e: Exception) { key.takeLast(2) }
}

fun conditionColor(c: Condition) = when (c) {
    Condition.GOOD -> Cell.Good; Condition.FAIR -> Cell.Warn
    Condition.REPLACE -> Cell.Bad; Condition.UNKNOWN -> Cell.TextMuted
}
private fun tempColor(b: BatteryAnalytics.TempBand) = when (b) {
    BatteryAnalytics.TempBand.COOL -> Cell.Cool; BatteryAnalytics.TempBand.NORMAL -> Cell.Good
    BatteryAnalytics.TempBand.WARM -> Cell.Warn; BatteryAnalytics.TempBand.HOT -> Cell.Bad
    BatteryAnalytics.TempBand.UNKNOWN -> Cell.TextMuted
}
private fun orbColors(level: Int): Pair<Color, Color> = when {
    level <= 15 -> Cell.Bad to Color(0xFF6E2F29)
    level <= 30 -> Cell.Warn to Color(0xFF7A5C28)
    else -> Cell.AccentLight to Cell.AccentDeep
}

private fun chartPoints(history: List<HistoryPoint>, selector: (HistoryPoint) -> Double?): List<Pair<Float, Float>> {
    val raw = history.mapNotNull { hp -> selector(hp)?.let { hp.timestampMs to it } }
    if (raw.isEmpty()) return emptyList()
    val t0 = raw.first().first
    return raw.map { (ts, v) -> ((ts - t0) / 86_400_000.0).toFloat() to v.toFloat() }
}
private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun socToday(soc: List<SocPoint>): List<Pair<Float, Float>> {
    val m = startOfToday()
    val today = soc.filter { it.timestampMs >= m }
    return today.map { ((it.timestampMs - m) / 3_600_000f) to it.levelPct.toFloat() }
}
private fun tempPoints(soc: List<SocPoint>): List<Pair<Float, Float>> {
    val pts = soc.filter { it.temperatureC != null }
    if (pts.isEmpty()) return emptyList()
    val t0 = pts.first().timestampMs
    return pts.map { ((it.timestampMs - t0) / 3_600_000f) to it.temperatureC!! }
}
private fun voltPoints(soc: List<SocPoint>): List<Pair<Float, Float>> {
    val pts = soc.filter { it.voltageV != null }
    if (pts.isEmpty()) return emptyList()
    val t0 = pts.first().timestampMs
    return pts.map { ((it.timestampMs - t0) / 3_600_000f) to it.voltageV!! }
}
private fun currPoints(soc: List<SocPoint>): List<Pair<Float, Float>> {
    val pts = soc.filter { it.currentMa != null }
    if (pts.isEmpty()) return emptyList()
    val t0 = pts.first().timestampMs
    return pts.map { ((it.timestampMs - t0) / 3_600_000f) to it.currentMa!!.toFloat() }
}

@Composable
private fun MinNowMax(pts: List<Pair<Float, Float>>, fmt: (Float) -> String) {
    if (pts.isEmpty()) return
    val ys = pts.map { it.second }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Legend("min", fmt(ys.min())); Legend("now", fmt(pts.last().second)); Legend("max", fmt(ys.max()))
    }
}
@Composable
private fun Legend(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(k.uppercase(), color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(v, color = Cell.TextSecondary, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Now ──────────────────────────────────────────────────────────────────────
@Composable
fun NowScreen(snapshot: BatterySnapshot?, analysis: Analysis?, dailyUsage: List<Pair<String, Double>>, socLog: List<SocPoint>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("Right now")
            Text(snapshot?.model ?: "Battery", color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
        }

        val level = snapshot?.levelPct ?: 0
        val (top, bottom) = orbColors(level)
        val status = snapshot?.status ?: "—"
        val rate = BatteryAnalytics.chargeRate(snapshot?.currentUa, status)
        val statusLine = when {
            status == "Charging" && rate != "—" -> "Charging · $rate"
            status == "Full" -> "Full"; status == "Charging" -> "Charging"
            else -> "On battery"
        }
        Appear {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                WaterOrb(fraction = level / 100f, diameter = 210.dp, top = top, bottom = bottom) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("$level", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 60.sp, fontWeight = FontWeight.SemiBold)
                            Text("%", color = Cell.TextStatus, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                        Text(statusLine.uppercase(), color = Cell.TextStatus, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }
            }
        }

        analysis?.let {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ConditionPill(it.condition.label, conditionColor(it.condition))
            }
        }

        val band = BatteryAnalytics.tempBand(snapshot?.temperatureC)
        val power = BatteryAnalytics.powerWatts(snapshot?.voltageV, snapshot?.currentUa)
        Spacer(Modifier.height(20.dp))
        Appear(80) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Temperature", snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—", band.label, Modifier.weight(1f), accent = tempColor(band))
                    VitalTile("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—", "", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Current", snapshot?.currentUa?.let { "${kotlin.math.abs(it / 1000)} mA" } ?: "—", if (status == "Charging") "charging" else "draw", Modifier.weight(1f))
                    VitalTile("Power", power?.let { String.format(Locale.US, "%.1f W", it) } ?: "—", "", Modifier.weight(1f))
                }
            }
        }

        val todayPts = socToday(socLog)
        if (todayPts.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Appear(140) {
                SurfaceCard {
                    Column {
                        SectionLabel("Charge today")
                        Spacer(Modifier.height(10.dp))
                        TimeSeriesChart(todayPts, height = 108.dp, yFormat = { "${it.toInt()}%" },
                            xStart = Fmt.time(socLog.firstOrNull { it.timestampMs >= startOfToday() }?.timestampMs), xEnd = "now")
                    }
                }
            }
        }

        // Derive from the live snapshot time so it rolls over correctly at midnight.
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(snapshot?.timestampMs ?: System.currentTimeMillis()))
        val usedToday = dailyUsage.firstOrNull { it.first == todayKey }?.second ?: 0.0
        Spacer(Modifier.height(12.dp))
        Appear(200) {
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        SectionLabel("Used today")
                        Text("${usedToday.toInt()}% discharged", color = Cell.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(String.format(Locale.US, "%.2f", BatteryAnalytics.equivalentCycles(usedToday)) + " cyc",
                        color = Cell.Accent, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Health ───────────────────────────────────────────────────────────────────
@Composable
fun HealthScreen(snapshot: BatterySnapshot?, analysis: Analysis?, history: List<HistoryPoint>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("State of Health")
            Text(buildString {
                append(snapshot?.model ?: "Battery"); analysis?.ageDays?.let { append(" · ${Fmt.age(it)}") }
            }, color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
        }
        val sohInt = analysis?.sohPct?.toInt() ?: 0
        var target by remember { mutableIntStateOf(0) }
        LaunchedEffect(sohInt) { target = sohInt }
        val shown by animateIntAsState(target, tween(900), label = "sohCount")
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
            Text(if (analysis?.sohPct != null) "$shown" else "—", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 104.sp, fontWeight = FontWeight.SemiBold)
            Text("%", color = Cell.TextMuted, fontFamily = Sans, fontSize = 32.sp, modifier = Modifier.padding(top = 14.dp))
        }
        analysis?.let { ConditionPill(it.condition.label, conditionColor(it.condition)) }
        val holds = analysis?.sohPct?.toInt()
        Text(
            when {
                holds == null -> "Charge your phone past 20% a few times and Cellspan will measure your battery's real health."
                analysis.capacitySource == CapacitySource.SESSION ->
                    "Measured across ${analysis.sessionCount} charge session${if (analysis.sessionCount == 1) "" else "s"} — your battery holds about $holds% of its original capacity."
                analysis.capacitySource == CapacitySource.GAUGE ->
                    "Your battery holds $holds% of the charge it did new."
                else ->
                    "Estimated at ~$holds% from a single reading — charge past 20% a few times and Cellspan will measure it precisely."
            },
            color = Cell.TextSecondary, fontSize = 14.5.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 16.dp),
        )
        val pts = chartPoints(history) { it.sohPct }
        if (pts.isNotEmpty()) {
            Spacer(Modifier.height(24.dp)); SectionLabel("Capacity trend"); Spacer(Modifier.height(10.dp))
            TimeSeriesChart(pts, yFormat = { "${it.toInt()}%" }, xStart = Fmt.isoDate(history.firstOrNull()?.timestampMs), xEnd = "now",
                threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%")
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(analysis?.currentCapacityMah?.let { "$it" } ?: "—", "mAh now")
            StatTile(analysis?.designCapacityMah?.let { "$it" } ?: "—", "mAh new")
            StatTile(analysis?.cycleCount?.let { "$it" } ?: "—", "cycles", color = if ((analysis?.cycleCount ?: 0) >= 800) Cell.Bad else Cell.TextPrimary)
        }
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("What this means")
                Text("State of Health is how much charge the battery still holds versus new. Above 85% is healthy; 80–85% is aging; below 80% or 800 cycles is the usual point to consider a replacement.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        analysis?.etaSohMs?.let { eta ->
            Spacer(Modifier.height(14.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Looking ahead", color = Cell.Accent)
                    Text("At its lifetime-average rate, this battery reaches 80% around ${Fmt.date(eta)}.",
                        color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Analytics ────────────────────────────────────────────────────────────────
@Composable
fun AnalyticsScreen(analysis: Analysis?, history: List<HistoryPoint>, dailyUsage: List<Pair<String, Double>>, socLog: List<SocPoint>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Analytics", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        // Live SoC today.
        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Charge level today  ·  live")
                Spacer(Modifier.height(12.dp))
                val today = socToday(socLog)
                if (today.size >= 2) {
                    TimeSeriesChart(today, yFormat = { "${it.toInt()}%" },
                        xStart = Fmt.time(socLog.firstOrNull { it.timestampMs >= startOfToday() }?.timestampMs), xEnd = "now")
                } else {
                    Text("Your charge level through the day is tracked live while the app is open — the line grows as time passes.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        // Voltage (live).
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("Voltage  ·  live")
                Spacer(Modifier.height(12.dp))
                val vp = voltPoints(socLog)
                if (vp.size >= 2) {
                    TimeSeriesChart(vp, yFormat = { String.format(Locale.US, "%.2fV", it) }, lineColor = Cell.Accent,
                        xStart = Fmt.time(socLog.firstOrNull { it.voltageV != null }?.timestampMs), xEnd = "now")
                    MinNowMax(vp) { String.format(Locale.US, "%.2f V", it) }
                } else {
                    Text("Terminal voltage over time — updates while the app is open.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        // Current (live).
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("Current  ·  live")
                Spacer(Modifier.height(12.dp))
                val cp = currPoints(socLog)
                if (cp.size >= 2) {
                    TimeSeriesChart(cp, yFormat = { "${it.toInt()}" }, lineColor = Cell.AccentBright,
                        threshold = 0f, thresholdLabel = "0 mA",
                        xStart = Fmt.time(socLog.firstOrNull { it.currentMa != null }?.timestampMs), xEnd = "now")
                    MinNowMax(cp) { "${it.toInt()} mA" }
                    Spacer(Modifier.height(4.dp))
                    Text("Positive = charging, negative = draw.", color = Cell.TextFaint, fontSize = 11.sp)
                } else {
                    Text("Charge/discharge current over time — updates while the app is open.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        // Temperature history.
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("Temperature")
                Spacer(Modifier.height(12.dp))
                val tp = tempPoints(socLog)
                if (tp.size >= 2) {
                    TimeSeriesChart(tp, yFormat = { String.format(Locale.US, "%.1f°", it) }, lineColor = Cell.Cool,
                        xStart = Fmt.time(socLog.firstOrNull { it.temperatureC != null }?.timestampMs), xEnd = "now")
                    MinNowMax(tp) { String.format(Locale.US, "%.1f°C", it) }
                } else {
                    Text("Battery temperature over time appears here as readings accumulate.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        // Charge-level distribution.
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("Where your charge sits")
                Spacer(Modifier.height(4.dp))
                Text("Share of time spent at each charge level. The 20–80% middle is gentlest on the cell.",
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

        // SoH over time.
        val sohPts = chartPoints(history) { it.sohPct }
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("State of Health over time")
                Spacer(Modifier.height(12.dp))
                if (sohPts.isNotEmpty()) {
                    TimeSeriesChart(sohPts, yFormat = { "${it.toInt()}%" }, xStart = Fmt.isoDate(history.firstOrNull()?.timestampMs), xEnd = "now",
                        threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%")
                    Spacer(Modifier.height(6.dp))
                    Text("${sohPts.size} sample(s)", color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp)
                } else {
                    Text("Health trend builds as Cellspan samples your battery over the coming days.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        val cyclePts = chartPoints(history) { it.cycleCount?.toDouble() }
        if (cyclePts.size >= 2) {
            Spacer(Modifier.height(20.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Charge cycles over time")
                    Spacer(Modifier.height(12.dp))
                    TimeSeriesChart(cyclePts, yFormat = { it.toInt().toString() }, xStart = Fmt.isoDate(history.firstOrNull()?.timestampMs), xEnd = "now",
                        lineColor = Cell.AccentDeep, threshold = BatteryAnalytics.REPLACE_CYCLES.toFloat(), thresholdLabel = "800")
                }
            }
        }

        // Daily usage.
        Spacer(Modifier.height(20.dp))
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

        analysis?.ageDays?.let {
            Spacer(Modifier.height(20.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Since manufacture")
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Battery age", Fmt.age(it))
                    analysis.avgCyclesPerMonth?.let { c -> KeyValueRow("Avg cycles / month", String.format(Locale.US, "%.1f", c)) }
                    analysis.avgFadePerYear?.let { f -> KeyValueRow("Avg fade / year", String.format(Locale.US, "%.1f pts", f)) }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Diagnose ─────────────────────────────────────────────────────────────────
@Composable
fun DiagnoseScreen(snapshot: BatterySnapshot?, analysis: Analysis?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Diagnose", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Device")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Manufacturer", snapshot?.manufacturer?.ifBlank { "—" } ?: "—")
                KeyValueRow("Model", snapshot?.model?.ifBlank { "—" } ?: "—")
                KeyValueRow("Codename", snapshot?.device?.ifBlank { "—" } ?: "—")
                KeyValueRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Battery")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Chemistry", snapshot?.technology ?: "—")
                KeyValueRow("Design capacity", analysis?.designCapacityMah?.let { "$it mAh" + if (analysis.designIsFromDatabase) " (typical)" else "" } ?: "—")
                KeyValueRow("Full charge (est.)", analysis?.currentCapacityMah?.let { "$it mAh" } ?: "—")
                KeyValueRow("Charge remaining", snapshot?.chargeCounterUah?.let { "${it / 1000} mAh" } ?: "—")
                KeyValueRow("State of Health", analysis?.sohPct?.let { "${it.toInt()}%" + if (analysis.sohIsEstimated) " (est.)" else "" } ?: "—",
                    valueColor = analysis?.let { conditionColor(it.condition) } ?: Cell.TextPrimary)
                KeyValueRow("Cycle count", analysis?.cycleCount?.let { "$it" } ?: "not reported")
                if (snapshot?.manufacturingEpoch != null) KeyValueRow("Manufactured", Fmt.isoDate(snapshot.manufacturingEpoch * 1000))
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Live")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Charge level", "${snapshot?.levelPct ?: "—"}%")
                KeyValueRow("Status", snapshot?.status ?: "—")
                KeyValueRow("Charging speed", BatteryAnalytics.chargeRate(snapshot?.currentUa, snapshot?.status ?: ""))
                KeyValueRow("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—")
                KeyValueRow("Current", snapshot?.currentUa?.let { "${kotlin.math.abs(it / 1000)} mA" } ?: "—")
                KeyValueRow("Power", BatteryAnalytics.powerWatts(snapshot?.voltageV, snapshot?.currentUa)?.let { String.format(Locale.US, "%.1f W", it) } ?: "—")
                val band = BatteryAnalytics.tempBand(snapshot?.temperatureC)
                KeyValueRow("Temperature", (snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—") + "  ${band.label}",
                    valueColor = tempColor(band))
                KeyValueRow("Health flag", snapshot?.health ?: "—")
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Care tips", color = Cell.Accent)
                Spacer(Modifier.height(8.dp))
                Tip("Keep the charge roughly between 20% and 80% for everyday use.")
                Tip("Avoid leaving it hot while charging — heat is the biggest cause of wear.")
                Tip("Occasional full charges are fine; constant 100% at high heat is not.")
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Data source")
                Spacer(Modifier.height(8.dp))
                val full = snapshot?.sysfsAvailable == true
                Text(
                    if (full) "Reading exact values directly from the fuel gauge. State of Health is measured, not estimated."
                    else "State of Health is estimated: full charge ≈ remaining charge ÷ level, divided by the model's design capacity. Android doesn't expose the fuel gauge's exact capacity to apps.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                )
                if (!full) {
                    Spacer(Modifier.height(10.dp))
                    Text("Exact capacity, manufacture date and the charge/temperature history unlock automatically with root or an ADB helper (Shizuku).",
                        color = Cell.TextFaint, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun Tip(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(Cell.Accent))
        Text(text, color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
