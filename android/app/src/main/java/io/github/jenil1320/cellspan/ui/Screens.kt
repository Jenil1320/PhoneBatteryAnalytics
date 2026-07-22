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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.jenil1320.cellspan.analytics.DrainForecaster
import io.github.jenil1320.cellspan.analytics.SohForecaster
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.HistoryPoint
import io.github.jenil1320.cellspan.data.SocPoint
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import io.github.jenil1320.cellspan.data.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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
    fun duration(ms: Long?): String {
        if (ms == null || ms <= 0) return "—"
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
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
private fun levelColor(l: BatteryAnalytics.Level) = when (l) {
    BatteryAnalytics.Level.GOOD -> Cell.Good
    BatteryAnalytics.Level.WATCH -> Cell.Warn
    BatteryAnalytics.Level.BAD -> Cell.Bad
    BatteryAnalytics.Level.INFO -> Cell.TextPrimary
}
private fun orbColors(level: Int): Pair<Color, Color> = when {
    level <= 15 -> Cell.Bad to Color(0xFF7E2F24)
    level <= 30 -> Cell.Warn to Color(0xFF6E4A2A)
    else -> Cell.AccentLight to Cell.AccentDeep
}

private fun chartPoints(history: List<HistoryPoint>, selector: (HistoryPoint) -> Double?): List<Pair<Float, Float>> {
    val raw = history.mapNotNull { hp -> selector(hp)?.let { hp.timestampMs to it } }
    if (raw.isEmpty()) return emptyList()
    val t0 = raw.first().first
    return raw.map { (ts, v) -> ((ts - t0) / 86_400_000.0).toFloat() to v.toFloat() }
}

/** Map a forecast curve (epochMs, value) into a fresh "days since now" x-space for its
 *  own dedicated chart, so it never squashes the measured-history axis. */
private fun projectionPoints(curve: List<Pair<Long, Double>>): List<Pair<Float, Float>> {
    if (curve.size < 2) return emptyList()
    val t0 = curve.first().first
    return curve.map { (ts, v) -> ((ts - t0) / 86_400_000.0).toFloat() to v.toFloat() }
}

@Composable
private fun TriStat(value: String, label: String, color: Color = Cell.TextPrimary) {
    Column {
        Text(value, color = color, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(label.uppercase(), color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun SourceCol(label: String, value: String, note: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp)) {
        SectionLabel(label)
        Text(value, color = color, fontFamily = Mono, fontSize = 21.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(note, color = Cell.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).height(46.dp).background(Cell.Border))
}

@Composable
private fun EnduranceRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cell.TextSecondary, fontSize = 13.sp)
        Text(value, color = color, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** How far back the data export reaches. */
private enum class ExportRange(val label: String, val days: Int?) {
    WEEK("7 days", 7), MONTH("30 days", 30), YEAR("1 year", 365), ALL("All", null);
    fun sinceMs(nowMs: Long): Long = days?.let { nowMs - it * 86_400_000L } ?: 0L
}
private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun socToday(soc: List<SocPoint>): List<Pair<Float, Float>> {
    val m = startOfToday()
    val today = soc.filter { it.timestampMs >= m }
    return today.map { ((it.timestampMs - m) / 3_600_000f) to it.levelPct.toFloat() }
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
        val chargeRateLbl = BatteryAnalytics.chargeRate(snapshot?.currentUa, status)

        // Discharge slope + runtime, computed up front so the orb, the endurance card
        // and the charge-today forecast all share the same rate.
        val nowTs = snapshot?.timestampMs ?: System.currentTimeMillis()
        val recentSoc = socLog.filter { it.timestampMs >= nowTs - 90 * 60_000L }.map { it.timestampMs to it.levelPct }
        val dischargeRate = BatteryAnalytics.dischargeRatePctPerHour(recentSoc)
        val runtimeMs = BatteryAnalytics.runtimeLeftMs(level, dischargeRate)
            ?: BatteryAnalytics.instantaneousRuntimeMs(snapshot?.chargeCounterUah, snapshot?.currentUa, status)

        val statusLine = when {
            status == "Charging" && chargeRateLbl != "—" -> "Charging · $chargeRateLbl"
            status == "Full" -> "Full"
            status == "Charging" -> "Charging"
            dischargeRate != null && dischargeRate >= 0.5 -> "−${dischargeRate.roundToInt()}% / h"
            else -> "On battery"
        }
        Appear {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                WaterOrb(fraction = level / 100f, diameter = 210.dp, top = top, bottom = bottom) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("$level", color = Cell.OnTile, fontFamily = Sans, fontSize = 60.sp, fontWeight = FontWeight.Bold)
                            Text("%", color = Cell.OnTileMuted, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                        Text(statusLine.uppercase(), color = Cell.OnTileMuted, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }
            }
        }

        analysis?.let {
            val firm = it.capacitySource == CapacitySource.GAUGE || it.capacitySource == CapacitySource.SESSION
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (firm) ConditionPill(it.condition.label, conditionColor(it.condition))
                else ConditionPill("Estimating", Cell.TextMuted)
            }
        }

        // "Lasts for" at light / normal / heavy use (mockup 2c), scaled off the recent drain.
        if (status == "Discharging" && dischargeRate != null && dischargeRate > 0.0) {
            val lightMs = BatteryAnalytics.runtimeLeftMs(level, dischargeRate * 0.4)
            val normalMs = BatteryAnalytics.runtimeLeftMs(level, dischargeRate)
            val heavyMs = BatteryAnalytics.runtimeLeftMs(level, dischargeRate * 2.6)
            Spacer(Modifier.height(16.dp))
            Appear(60) {
                SurfaceCard {
                    Column {
                        SectionLabel("Lasts for")
                        Spacer(Modifier.height(6.dp))
                        EnduranceRow("Light use", Fmt.duration(lightMs), Cell.Good)
                        EnduranceRow("Normal use", Fmt.duration(normalMs), Cell.TextPrimary)
                        EnduranceRow("Heavy use", Fmt.duration(heavyMs), Cell.Warn)
                        Text("Estimated from your recent drain — actual time varies with what you do.",
                            color = Cell.TextFaint, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        } else if (status == "Discharging" && runtimeMs != null) {
            Spacer(Modifier.height(16.dp))
            Appear(60) {
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            SectionLabel("Lasts about")
                            Text("at your recent usage rate", color = Cell.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text(Fmt.duration(runtimeMs), color = Cell.Accent, fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        val band = BatteryAnalytics.tempBand(snapshot?.temperatureC)
        val power = BatteryAnalytics.powerWatts(snapshot?.voltageV, snapshot?.currentUa)
        val vR = BatteryAnalytics.voltageReading(snapshot?.voltageV)
        val cR = BatteryAnalytics.currentReading(snapshot?.currentUa, status)
        val pR = BatteryAnalytics.powerReading(power)
        Spacer(Modifier.height(20.dp))
        Appear(80) {
            Column {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Temperature", snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—", band.label,
                        Modifier.weight(1f).fillMaxHeight(), accent = tempColor(band),
                        metric = Metric.TEMPERATURE,
                        gauge = snapshot?.temperatureC?.let { (it / 45f).coerceIn(0f, 1f) })
                    VitalTile("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—", vR.note,
                        Modifier.weight(1f).fillMaxHeight(), accent = levelColor(vR.level),
                        metric = Metric.VOLTAGE,
                        gauge = snapshot?.voltageV?.let { ((it - 3.0f) / 1.45f).coerceIn(0f, 1f) })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Current", snapshot?.currentUa?.let { "${kotlin.math.abs(it / 1000)} mA" } ?: "—", cR.note,
                        Modifier.weight(1f).fillMaxHeight(), accent = levelColor(cR.level),
                        metric = Metric.CURRENT,
                        gauge = snapshot?.currentUa?.let { (kotlin.math.abs(it / 1000f) / 2500f).coerceIn(0f, 1f) })
                    VitalTile("Power", power?.let { String.format(Locale.US, "%.1f W", it) } ?: "—", pR.note,
                        Modifier.weight(1f).fillMaxHeight(), accent = levelColor(pR.level),
                        metric = Metric.POWER,
                        gauge = power?.let { (kotlin.math.abs(it).toFloat() / 12f).coerceIn(0f, 1f) })
                }
            }
        }

        val todayPts = socToday(socLog)
        if (todayPts.size >= 2) {
            // Rest-of-day drain projection (dashed, capped at 8h) when discharging.
            val drainRaw = if (status == "Discharging" && dischargeRate != null && dischargeRate > 0.0)
                DrainForecaster.restOfDayCurve(nowTs, level, dischargeRate, horizonMs = 8 * 3_600_000L) else emptyList()
            val drainFc = drainRaw.map { (ts, soc) -> ((ts - startOfToday()) / 3_600_000f) to soc.toFloat() }
            val drainEndMs = drainRaw.lastOrNull()?.first
            val reachesEmpty = (drainRaw.lastOrNull()?.second ?: 1.0) <= 0.0
            Spacer(Modifier.height(12.dp))
            Appear(140) {
                SurfaceCard {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Charge today")
                            if (drainFc.isNotEmpty()) SectionLabel("— — forecast", color = Cell.Accent)
                        }
                        Spacer(Modifier.height(10.dp))
                        TimeSeriesChart(todayPts, height = 108.dp, yFormat = { "${it.toInt()}%" },
                            xStart = Fmt.time(socLog.firstOrNull { it.timestampMs >= startOfToday() }?.timestampMs),
                            xEnd = when {
                                drainFc.isEmpty() -> "now"
                                reachesEmpty && drainEndMs != null -> "~${Fmt.time(drainEndMs)}"
                                else -> "8h+"
                            },
                            forecast = drainFc.ifEmpty { null })
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
fun HealthScreen(snapshot: BatterySnapshot?, analysis: Analysis?, history: List<HistoryPoint>, forecast: SohForecaster.Forecast? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("State of Health")
            Text(buildString {
                append(snapshot?.model ?: "Battery"); analysis?.ageDays?.let { append(" · ${Fmt.age(it)}") }
            }, color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
        }
        // "Firm" = a real measurement (fuel gauge or a completed charge session).
        // Otherwise the value is just the noisy instantaneous estimate, shown as ~approx.
        val firm = analysis?.let {
            it.capacitySource == CapacitySource.GAUGE || it.capacitySource == CapacitySource.SESSION
        } ?: false
        val sohInt = analysis?.sohPct?.toInt() ?: 0
        var target by remember { mutableIntStateOf(0) }
        LaunchedEffect(sohInt) { target = sohInt }
        val shown by animateIntAsState(target, tween(900), label = "sohCount")
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when {
                            analysis?.sohPct == null -> "—"
                            firm -> "$shown"
                            else -> "~$shown"
                        },
                        color = Cell.TextPrimary, fontFamily = Sans, fontSize = 68.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false,
                    )
                    Text("%", color = Cell.TextMuted, fontFamily = Sans, fontSize = 24.sp, maxLines = 1,
                        modifier = Modifier.padding(top = 10.dp))
                }
                Spacer(Modifier.height(8.dp))
                analysis?.let {
                    if (firm) ConditionPill(it.condition.label, conditionColor(it.condition))
                    else ConditionPill("Estimating", Cell.TextMuted)
                }
            }
            Column(Modifier.width(126.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Cycles", analysis?.cycleCount?.let { "$it" } ?: "—",
                    if ((analysis?.cycleCount ?: 0) >= 800) Cell.Warn else Cell.TileSlate, Modifier.fillMaxWidth())
                KpiTile("Capacity", analysis?.currentCapacityMah?.let { "$it" } ?: "—", Cell.TileTeal, Modifier.fillMaxWidth(),
                    sub = analysis?.designCapacityMah?.let { "of $it mAh" })
            }
        }
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
            color = Cell.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp),
        )
        val pts = chartPoints(history) { it.sohPct }
        if (pts.isNotEmpty()) {
            Spacer(Modifier.height(18.dp)); SectionLabel("Capacity trend"); Spacer(Modifier.height(10.dp))
            // Scatter + least-squares regression line — reads clean despite per-sample noise.
            val reg = if (pts.size >= 2) {
                val fit = SohForecaster.wls(pts.map { it.first.toDouble() }, pts.map { it.second.toDouble() }, DoubleArray(pts.size) { 1.0 })
                val x0 = pts.minOf { it.first }; val x1 = pts.maxOf { it.first }
                listOf(x0 to (fit.a + fit.b * x0).toFloat(), x1 to (fit.a + fit.b * x1).toFloat())
            } else null
            TimeSeriesChart(pts, yFormat = { "${it.toInt()}%" },
                xStart = Fmt.isoDate(history.firstOrNull { it.sohPct != null }?.timestampMs), xEnd = "now",
                threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%",
                scatter = true, regressionLine = reg)
        }

        // Measured vs modeled: is this cell aging normally for its cycle count?
        analysis?.modeledSohPct?.let { modeled ->
            Spacer(Modifier.height(20.dp))
            SurfaceCard {
                Column {
                    SectionLabel("State of Health · three sources")
                    Spacer(Modifier.height(14.dp))
                    val measured = analysis.sohPct
                    val srcSub = when (analysis.capacitySource) {
                        CapacitySource.GAUGE -> "fuel gauge"
                        CapacitySource.SESSION -> "${analysis.sessionCount} sessions"
                        CapacitySource.ESTIMATE -> "estimate"
                        else -> ""
                    }
                    val gaugeAvail = snapshot?.sysfsAvailable == true
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        SourceCol("Measured", measured?.let { "${it.toInt()}%" } ?: "—", srcSub,
                            conditionColor(analysis.condition), Modifier.weight(1f))
                        VDivider()
                        SourceCol("Modeled", "${modeled.toInt()}%", "typical", Cell.Cool, Modifier.weight(1f))
                        VDivider()
                        SourceCol("Gauge", if (gaugeAvail) (measured?.let { "${it.toInt()}%" } ?: "—") else "—",
                            if (gaugeAvail) "fuel IC" else "needs root", Cell.TextSecondary, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    if (measured != null) {
                        val delta = measured - modeled
                        Text(
                            when {
                                delta >= 3 -> "Your battery is holding up better than a typical cell at ${analysis.cycleCount ?: "this"} cycles."
                                delta <= -3 -> "Your battery is wearing a little faster than a typical cell at ${analysis.cycleCount ?: "this"} cycles."
                                else -> "Your battery is tracking the typical wear curve for its cycle count."
                            },
                            color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Text(
                        "A typical wear estimate for this many charge cycles, based on published lithium-battery research. It is a reference, not a manufacturer figure.",
                        color = Cell.TextFaint, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // Where the modeled wear comes from: cycling vs age/heat.
        analysis?.modeledCyclicLossPct?.let { cyc ->
            Spacer(Modifier.height(14.dp))
            SurfaceCard {
                Column {
                    SectionLabel("What's wearing it")
                    Spacer(Modifier.height(12.dp))
                    val cal = if (analysis.modeledHasCalendar) (analysis.modeledCalendarLossPct ?: 0.0) else 0.0
                    val total = (cal + cyc).coerceAtLeast(0.001)
                    val calFrac = (cal / total).toFloat()
                    Row(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(8.dp))) {
                        if (calFrac > 0.02f) Box(Modifier.weight(calFrac).fillMaxHeight().background(Cell.AccentDeep), contentAlignment = Alignment.Center) {
                            Text("${cal.roundToInt()}% cal", color = Cell.OnTile, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight((1f - calFrac).coerceAtLeast(0.02f)).fillMaxHeight().background(Cell.Warn), contentAlignment = Alignment.Center) {
                            Text("${cyc.roundToInt()}% cyc", color = Cell.OnTile, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Age + heat", color = Cell.TextFaint, fontSize = 11.sp)
                        Text("From charging", color = Cell.TextFaint, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("From charge cycles", String.format(Locale.US, "−%.1f pts", cyc))
                    if (analysis.modeledHasCalendar) {
                        KeyValueRow("From age & heat", String.format(Locale.US, "−%.1f pts", analysis.modeledCalendarLossPct ?: 0.0))
                    } else {
                        KeyValueRow("From age & heat", "needs battery age")
                    }
                    Text(
                        if (analysis.modeledHasCalendar)
                            "Charging wears the battery. Age and heat wear it too. Cellspan shows how much comes from each."
                        else "This is the wear from charging. Wear from age and heat needs the battery's make date, which Android hides from apps.",
                        color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Column {
                SectionLabel("What this means")
                Text("State of Health is how much charge the battery still holds versus new. Above 85% is healthy; 80–85% is aging; below 80% or 800 cycles is the usual point to consider a replacement.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        forecast?.takeIf { it.method != SohForecaster.Method.NONE }?.let { fc ->
            Spacer(Modifier.height(14.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Forecast", color = Cell.Accent)
                    if (fc.etaDateMs != null) {
                        Text("At your recent rate, capacity may reach 80% around ${Fmt.date(fc.etaDateMs)}.",
                            color = Cell.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 8.dp))
                        val lo = fc.etaLowMs; val hi = fc.etaHighMs
                        if (lo != null && hi != null && Fmt.date(lo) != Fmt.date(hi)) {
                            Text("Likely range: ${Fmt.date(lo)} – ${Fmt.date(hi)}.",
                                color = Cell.TextMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        val projPts = projectionPoints(fc.curve)
                        if (projPts.size >= 2) {
                            Spacer(Modifier.height(12.dp))
                            TimeSeriesChart(projPts, height = 96.dp, yFormat = { "${it.toInt()}%" },
                                xStart = "now", xEnd = Fmt.date(fc.etaDateMs),
                                threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%", lineColor = Cell.Accent)
                        }
                    } else {
                        Text("Aging slowly — not projected to reach 80% within the next five years.",
                            color = Cell.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TriStat(fc.fadePerYearPct?.let { if (it < 0.05) "≈0%" else String.format(Locale.US, "-%.1f%%", it) } ?: "—", "loss / yr")
                        TriStat(fc.etaDateMs?.let { Fmt.date(it) } ?: ">5 yr", "hits 80%")
                        TriStat(fc.r2?.let { String.format(Locale.US, "%.2f", it) } ?: "—", "fit R²")
                    }
                    val s = fc.scaleVsModel
                    val aging = when {
                        s == null -> null
                        s >= 1.08 -> "Your battery is aging a little faster than a typical one."
                        s <= 0.92 -> "Your battery is aging a little slower than a typical one."
                        else -> "Your battery is aging about as expected."
                    }
                    aging?.let {
                        Text(it, color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("An on-device trend projection from your history, not a guarantee — it shifts with temperature and how you charge.",
                        color = Cell.TextFaint, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
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
                        xStart = Fmt.isoDate(history.firstOrNull { it.cycleCount != null }?.timestampMs), xEnd = "now",
                        lineColor = Cell.AccentDeep, threshold = BatteryAnalytics.REPLACE_CYCLES.toFloat(), thresholdLabel = "800")
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
fun DiagnoseScreen(snapshot: BatterySnapshot?, analysis: Analysis?, onOpenInfo: (String) -> Unit = {}) {
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
                KeyValueRow("State of Health", analysis?.sohPct?.let {
                    val n = it.toInt()
                    when (analysis.capacitySource) {
                        CapacitySource.SESSION -> "$n% (measured)"
                        CapacitySource.ESTIMATE -> "~$n% (estimating)"
                        else -> "$n%"
                    }
                } ?: "—",
                    valueColor = if (analysis?.capacitySource == CapacitySource.ESTIMATE) Cell.TextMuted
                    else analysis?.let { conditionColor(it.condition) } ?: Cell.TextPrimary)
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

        // Data cadence + export so history survives an uninstall / new phone.
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var range by remember { mutableStateOf(ExportRange.ALL) }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    val now = System.currentTimeMillis()
                    val json = HistoryStore(ctx).use { it.exportJson(now, range.sinceMs(now)) }
                    val stream = ctx.contentResolver.openOutputStream(uri) ?: error("no output stream")
                    stream.use { os -> os.write(json.toByteArray()) }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, if (ok) "Battery data exported" else "Export failed — try another location", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Data & history")
                Spacer(Modifier.height(8.dp))
                Text("Cellspan records a health sample about every 6 hours and each time you open the app, plus a daily summary of temperature, voltage, current and cycles. Live vitals refresh about once a minute while the app is open.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(8.dp))
                Text("It stays in Cellspan's private on-device space and is kept for years (only the 3-day live log is trimmed). Export saves your battery usage data — every reading with its timestamp — to a file you can keep, open later, or move to a new phone. Choose how far back to include, or export everything.",
                    color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text("EXPORT RANGE", color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ExportRange.entries.forEach { r ->
                        val sel = r == range
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Cell.AccentSoft else Cell.SurfaceHi)
                                .border(1.dp, if (sel) Cell.BorderAccent else Cell.Border, RoundedCornerShape(8.dp))
                                .clickable { range = r }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(r.label, color = if (sel) Cell.Accent else Cell.TextMuted, fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Cell.AccentSoft)
                        .border(1.dp, Cell.BorderAccent, RoundedCornerShape(10.dp))
                        .clickable { exporter.launch("cellspan-battery-data.json") }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    Text("Export battery data", color = Cell.Accent, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Learn & privacy")
                Spacer(Modifier.height(4.dp))
                NavRow("Battery basics", "What the numbers mean, in plain words",
                    onClick = { onOpenInfo("basics") }, metric = Metric.HEALTH)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Cell.Border))
                NavRow("Privacy policy", "What Cellspan does with your data",
                    onClick = { onOpenInfo("privacy") }, metric = Metric.SOC)
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
