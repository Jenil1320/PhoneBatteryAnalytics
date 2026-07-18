package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// ── Formatting helpers ───────────────────────────────────────────────────────
object Fmt {
    fun age(days: Double?): String {
        if (days == null) return "—"
        val years = (days / 365.25).toInt()
        val months = ((days - years * 365.25) / 30.437).toInt()
        return when {
            years > 0 && months > 0 -> "$years yr $months mo"
            years > 0 -> "$years yr"
            else -> "$months mo"
        }
    }

    fun date(ms: Long?): String =
        if (ms == null) "—" else SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun isoDate(ms: Long?): String =
        if (ms == null) "—" else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    fun soh(a: Analysis?): String = a?.sohPct?.let { "${it.toInt()}" } ?: "—"
}

fun conditionColor(c: Condition) = when (c) {
    Condition.GOOD -> Cell.Good
    Condition.FAIR -> Cell.Warn
    Condition.REPLACE -> Cell.Bad
    Condition.UNKNOWN -> Cell.TextMuted
}

/**
 * Build (x, y) chart points, normalising x to *days since the first sample* so
 * the millisecond timestamps don't overflow Float's precision (a Float can't
 * distinguish two epoch-ms values only minutes apart).
 */
private fun chartPoints(
    history: List<HistoryPoint>,
    selector: (HistoryPoint) -> Double?,
): List<Pair<Float, Float>> {
    val raw = history.mapNotNull { hp -> selector(hp)?.let { hp.timestampMs to it } }
    if (raw.isEmpty()) return emptyList()
    val t0 = raw.first().first
    return raw.map { (ts, v) -> ((ts - t0) / 86_400_000.0).toFloat() to v.toFloat() }
}

private fun sohPoints(history: List<HistoryPoint>): List<Pair<Float, Float>> =
    chartPoints(history) { it.sohPct }

// ── Health ───────────────────────────────────────────────────────────────────
@Composable
fun HealthScreen(snapshot: BatterySnapshot?, analysis: Analysis?, history: List<HistoryPoint>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("State of Health")
            Text(
                buildString {
                    append(snapshot?.model ?: "Battery")
                    analysis?.ageDays?.let { append(" · ${Fmt.age(it)}") }
                },
                color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp,
            )
        }

        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Top) {
            Text(
                Fmt.soh(analysis),
                color = Cell.TextPrimary, fontFamily = Sans, fontSize = 108.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("%", color = Cell.TextMuted, fontFamily = Sans, fontSize = 34.sp,
                fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 14.dp))
        }

        analysis?.let {
            Spacer(Modifier.height(6.dp))
            ConditionPill(it.condition.label, conditionColor(it.condition))
        }

        val holds = analysis?.sohPct?.toInt()
        Text(
            when {
                holds != null && analysis.sohIsEstimated ->
                    "Your battery holds about $holds% of the charge it did new — estimated from its design capacity."
                holds != null ->
                    "Your battery holds $holds% of the charge it did new. That's normal for its age."
                else ->
                    "Exact State of Health needs fuel-gauge access this device doesn't grant to apps. Showing what's available below."
            },
            color = Cell.TextSecondary, fontSize = 14.5.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 18.dp),
        )

        val pts = sohPoints(history)
        if (pts.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Capacity trend")
            Spacer(Modifier.height(10.dp))
            LineChart(points = pts, threshold = BatteryAnalytics.REPLACE_SOH.toFloat())
        }

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(analysis?.currentCapacityMah?.let { "$it" } ?: "—", "mAh now")
            StatTile(analysis?.designCapacityMah?.let { "$it" } ?: "—", "mAh new")
            StatTile(analysis?.cycleCount?.let { "$it" } ?: "—", "cycles")
        }

        analysis?.etaSohMs?.let { eta ->
            Spacer(Modifier.height(24.dp))
            SurfaceCard {
                Column {
                    Text("Looking ahead", color = Cell.TextPrimary, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium)
                    Text(
                        "At its lifetime-average rate, this battery reaches 80% — the usual " +
                            "replacement mark — around ${Fmt.date(eta)}.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Trends ───────────────────────────────────────────────────────────────────
@Composable
fun TrendsScreen(analysis: Analysis?, history: List<HistoryPoint>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text("Trends", color = Cell.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        analysis?.ageDays?.let {
            Spacer(Modifier.height(16.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Since manufacture")
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Battery age", Fmt.age(it))
                    analysis.avgCyclesPerMonth?.let { c ->
                        KeyValueRow("Avg cycles / month", String.format(Locale.US, "%.1f", c))
                    }
                    analysis.avgFadePerYear?.let { f ->
                        KeyValueRow("Avg fade / year", String.format(Locale.US, "%.1f pts", f))
                    }
                    analysis.etaCyclesMs?.let { KeyValueRow("Reaches 800 cycles", Fmt.date(it)) }
                }
            }
        }

        val sohPts = sohPoints(history)
        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("State of Health over time")
                Spacer(Modifier.height(12.dp))
                if (sohPts.size >= 1) {
                    LineChart(points = sohPts, threshold = BatteryAnalytics.REPLACE_SOH.toFloat())
                    Spacer(Modifier.height(6.dp))
                    Text("${sohPts.size} sample(s)", color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp)
                } else {
                    Text(
                        "Trends build as Cellspan samples your battery over the coming days. " +
                            "Check back after a week.",
                        color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
                    )
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
                    LineChart(points = cyclePts, lineColor = Cell.AccentDim,
                        threshold = BatteryAnalytics.REPLACE_CYCLES.toFloat())
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Diagnose ─────────────────────────────────────────────────────────────────
@Composable
fun DiagnoseScreen(snapshot: BatterySnapshot?, analysis: Analysis?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text("Diagnose", color = Cell.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Battery spec")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Manufacturer", snapshot?.manufacturer?.ifBlank { "—" } ?: "—")
                KeyValueRow("Model", snapshot?.model?.ifBlank { "—" } ?: "—")
                KeyValueRow("Chemistry", snapshot?.technology ?: "—")
                KeyValueRow(
                    "Design capacity",
                    analysis?.designCapacityMah?.let {
                        "$it mAh" + if (analysis.designIsFromDatabase) " (typical)" else ""
                    } ?: "—",
                )
                KeyValueRow("Current capacity", analysis?.currentCapacityMah?.let { "$it mAh" } ?: "—")
                KeyValueRow("Cycle count", analysis?.cycleCount?.let { "$it" } ?: "not reported")
                KeyValueRow("Temperature", snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—")
                KeyValueRow("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—")
                KeyValueRow("Health flag", snapshot?.health ?: "—")
                if (snapshot?.manufacturingEpoch != null) {
                    KeyValueRow("Manufactured", Fmt.isoDate(snapshot.manufacturingEpoch * 1000))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("Data source")
                Spacer(Modifier.height(8.dp))
                val full = snapshot?.sysfsAvailable == true
                Text(
                    if (full)
                        "Reading exact values directly from the fuel gauge. State of Health is measured, not estimated."
                    else
                        "Reading from Android's public battery APIs. State of Health is estimated against a " +
                            "database of design capacities, because this device doesn't expose the fuel gauge to apps.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                )
                if (!full) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Exact capacity, cycle count, manufacture date and the charge/temperature " +
                            "history become available if the device is rooted or paired with an ADB " +
                            "helper (Shizuku). Cellspan uses them automatically when present.",
                        color = Cell.TextFaint, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
