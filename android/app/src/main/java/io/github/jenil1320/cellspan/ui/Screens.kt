package io.github.jenil1320.cellspan.ui

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
import io.github.jenil1320.cellspan.analytics.Condition
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.HistoryPoint
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import java.text.SimpleDateFormat
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
    fun dayShort(key: String): String = try {
        SimpleDateFormat("EEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!)
    } catch (e: Exception) { key.takeLast(2) }
}

fun conditionColor(c: Condition) = when (c) {
    Condition.GOOD -> Cell.Good
    Condition.FAIR -> Cell.Warn
    Condition.REPLACE -> Cell.Bad
    Condition.UNKNOWN -> Cell.TextMuted
}

private fun tempColor(band: BatteryAnalytics.TempBand) = when (band) {
    BatteryAnalytics.TempBand.COOL -> Cell.Cool
    BatteryAnalytics.TempBand.NORMAL -> Cell.Good
    BatteryAnalytics.TempBand.WARM -> Cell.Warn
    BatteryAnalytics.TempBand.HOT -> Cell.Bad
    BatteryAnalytics.TempBand.UNKNOWN -> Cell.TextMuted
}

private fun orbColors(level: Int): Pair<Color, Color> = when {
    level <= 15 -> Cell.Bad.copy(alpha = 0.9f) to Color(0xFF7A3A34)
    level <= 30 -> Cell.Warn to Color(0xFF8A6A2E)
    else -> Cell.AccentLight to Cell.AccentDeep
}

private fun chartPoints(history: List<HistoryPoint>, selector: (HistoryPoint) -> Double?): List<Pair<Float, Float>> {
    val raw = history.mapNotNull { hp -> selector(hp)?.let { hp.timestampMs to it } }
    if (raw.isEmpty()) return emptyList()
    val t0 = raw.first().first
    return raw.map { (ts, v) -> ((ts - t0) / 86_400_000.0).toFloat() to v.toFloat() }
}

// ── Now ──────────────────────────────────────────────────────────────────────
@Composable
fun NowScreen(snapshot: BatterySnapshot?, analysis: Analysis?, dailyUsage: List<Pair<String, Double>>) {
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
            status == "Full" -> "Full"
            status == "Charging" -> "Charging"
            else -> "On battery"
        }

        Appear {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                WaterOrb(fraction = level / 100f, diameter = 208.dp, top = top, bottom = bottom) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("$level", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 58.sp, fontWeight = FontWeight.SemiBold)
                            Text("%", color = Cell.TextStatus, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        Text(statusLine.uppercase(), color = Cell.TextStatus, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }
            }
        }

        val band = BatteryAnalytics.tempBand(snapshot?.temperatureC)
        val power = BatteryAnalytics.powerWatts(snapshot?.voltageV, snapshot?.currentUa)
        Spacer(Modifier.height(26.dp))
        Appear(80) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Temperature", snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—",
                        band.label, Modifier.weight(1f), accent = tempColor(band))
                    VitalTile("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—",
                        "", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Current", snapshot?.currentUa?.let { "${kotlin.math.abs(it / 1000)} mA" } ?: "—",
                        if (status == "Charging") "charging" else "draw", Modifier.weight(1f))
                    VitalTile("Power", power?.let { String.format(Locale.US, "%.1f W", it) } ?: "—",
                        "", Modifier.weight(1f))
                }
            }
        }

        // Battery used today.
        val todayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        val usedToday = dailyUsage.firstOrNull { it.first == todayKey }?.second ?: 0.0
        Spacer(Modifier.height(12.dp))
        Appear(160) {
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        SectionLabel("Used today")
                        Text("${usedToday.toInt()}% discharged", color = Cell.TextPrimary, fontSize = 16.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
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
                append(snapshot?.model ?: "Battery")
                analysis?.ageDays?.let { append(" · ${Fmt.age(it)}") }
            }, color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
        }

        // Animated count-up of the health number.
        val sohInt = analysis?.sohPct?.toInt() ?: 0
        var target by remember { mutableIntStateOf(0) }
        LaunchedEffect(sohInt) { target = sohInt }
        val shown by animateIntAsState(target, tween(900), label = "sohCount")
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top) {
            Text(if (analysis?.sohPct != null) "$shown" else "—", color = Cell.TextPrimary,
                fontFamily = Sans, fontSize = 104.sp, fontWeight = FontWeight.SemiBold)
            Text("%", color = Cell.TextMuted, fontFamily = Sans, fontSize = 32.sp, modifier = Modifier.padding(top = 14.dp))
        }
        analysis?.let {
            ConditionPill(it.condition.label, conditionColor(it.condition))
        }

        val holds = analysis?.sohPct?.toInt()
        Text(
            when {
                holds != null && analysis.sohIsEstimated -> "Your battery holds about $holds% of the charge it did new — estimated from its design capacity."
                holds != null -> "Your battery holds $holds% of the charge it did new. That's normal for its age."
                else -> "Exact State of Health needs fuel-gauge access this device doesn't grant to apps. Showing what's available below."
            },
            color = Cell.TextSecondary, fontSize = 14.5.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 16.dp),
        )

        val pts = chartPoints(history) { it.sohPct }
        if (pts.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Capacity trend")
            Spacer(Modifier.height(10.dp))
            TimeSeriesChart(pts, yFormat = { "${it.toInt()}%" },
                xStart = Fmt.isoDate(history.firstOrNull()?.timestampMs), xEnd = "now",
                threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%")
        }

        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(analysis?.currentCapacityMah?.let { "$it" } ?: "—", "mAh now")
            StatTile(analysis?.designCapacityMah?.let { "$it" } ?: "—", "mAh new")
            StatTile(analysis?.cycleCount?.let { "$it" } ?: "—", "cycles",
                color = if ((analysis?.cycleCount ?: 0) >= 800) Cell.Bad else Cell.TextPrimary)
        }

        Spacer(Modifier.height(22.dp))
        SurfaceCard {
            Column {
                SectionLabel("What this means")
                Text(
                    "State of Health is how much charge the battery still holds versus new. " +
                        "Above 85% is healthy; 80–85% is aging; below 80% or 800 cycles is the usual point to consider a replacement.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp),
                )
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

// ── Trends ───────────────────────────────────────────────────────────────────
@Composable
fun TrendsScreen(analysis: Analysis?, history: List<HistoryPoint>, dailyUsage: List<Pair<String, Double>>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Trends", color = Cell.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        analysis?.ageDays?.let {
            Spacer(Modifier.height(16.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Since manufacture")
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Battery age", Fmt.age(it))
                    analysis.avgCyclesPerMonth?.let { c -> KeyValueRow("Avg cycles / month", String.format(Locale.US, "%.1f", c)) }
                    analysis.avgFadePerYear?.let { f -> KeyValueRow("Avg fade / year", String.format(Locale.US, "%.1f pts", f)) }
                    analysis.etaCyclesMs?.let { KeyValueRow("Reaches 800 cycles", Fmt.date(it)) }
                }
            }
        }

        val sohPts = chartPoints(history) { it.sohPct }
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("State of Health over time")
                Spacer(Modifier.height(12.dp))
                if (sohPts.isNotEmpty()) {
                    TimeSeriesChart(sohPts, yFormat = { "${it.toInt()}%" },
                        xStart = Fmt.isoDate(history.firstOrNull()?.timestampMs), xEnd = "now",
                        threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%")
                    Spacer(Modifier.height(6.dp))
                    Text("${sohPts.size} sample(s)", color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp)
                } else {
                    Text("Trends build as Cellspan samples your battery over the coming days. Check back after a week.",
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
                    TimeSeriesChart(cyclePts, yFormat = { it.toInt().toString() },
                        xStart = Fmt.isoDate(history.firstOrNull()?.timestampMs), xEnd = "now",
                        lineColor = Cell.AccentDeep,
                        threshold = BatteryAnalytics.REPLACE_CYCLES.toFloat(), thresholdLabel = "800")
                }
            }
        }

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
        Spacer(Modifier.height(80.dp))
    }
}

// ── Diagnose ─────────────────────────────────────────────────────────────────
@Composable
fun DiagnoseScreen(snapshot: BatterySnapshot?, analysis: Analysis?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Diagnose", color = Cell.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Battery spec")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Manufacturer", snapshot?.manufacturer?.ifBlank { "—" } ?: "—")
                KeyValueRow("Model", snapshot?.model?.ifBlank { "—" } ?: "—")
                KeyValueRow("Chemistry", snapshot?.technology ?: "—")
                KeyValueRow("Design capacity", analysis?.designCapacityMah?.let {
                    "$it mAh" + if (analysis.designIsFromDatabase) " (typical)" else ""
                } ?: "—")
                KeyValueRow("Current capacity", analysis?.currentCapacityMah?.let { "$it mAh" } ?: "—")
                KeyValueRow("Cycle count", analysis?.cycleCount?.let { "$it" } ?: "not reported")
                KeyValueRow("Temperature", snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—")
                KeyValueRow("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—")
                KeyValueRow("Health flag", snapshot?.health ?: "—")
                if (snapshot?.manufacturingEpoch != null) KeyValueRow("Manufactured", Fmt.isoDate(snapshot.manufacturingEpoch * 1000))
            }
        }

        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Care tips", color = Cell.Accent)
                Spacer(Modifier.height(8.dp))
                Tip("Keep the charge roughly between 20% and 80% for everyday use.")
                Tip("Avoid leaving it hot while charging — heat is the biggest cause of wear.")
                Tip("Occasional full charges are fine; constant 100% at high heat is not.")
            }
        }

        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Data source")
                Spacer(Modifier.height(8.dp))
                val full = snapshot?.sysfsAvailable == true
                Text(
                    if (full) "Reading exact values directly from the fuel gauge. State of Health is measured, not estimated."
                    else "Reading from Android's public battery APIs. State of Health is estimated against a database of design capacities, because this device doesn't expose the fuel gauge to apps.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                )
                if (!full) {
                    Spacer(Modifier.height(10.dp))
                    Text("Exact capacity, cycle count, manufacture date and the charge/temperature history become available if the device is rooted or paired with an ADB helper (Shizuku). Cellspan uses them automatically when present.",
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
