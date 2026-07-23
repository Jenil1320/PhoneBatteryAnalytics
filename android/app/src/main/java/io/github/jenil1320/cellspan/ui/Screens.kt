package io.github.jenil1320.cellspan.ui

import android.os.Build
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.analytics.Analysis
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.CapacitySource
import io.github.jenil1320.cellspan.analytics.Condition
import io.github.jenil1320.cellspan.analytics.DrainForecaster
import io.github.jenil1320.cellspan.analytics.SohForecaster
import io.github.jenil1320.cellspan.data.AppPrefs
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.ChargeAlarm
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
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.core.app.NotificationManagerCompat
import io.github.jenil1320.cellspan.data.HistoryStore
import io.github.jenil1320.cellspan.data.SampleScheduler
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
        val chargeRateLbl = BatteryAnalytics.chargeRate(snapshot?.currentUa, snapshot?.voltageV, status)

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
                        // "~100" plus the "%" overflows this column at large font sizes, so let the
                        // figure give way first and keep a reserved slot for the percent sign.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("%", color = Cell.TextMuted, fontFamily = Sans, fontSize = 24.sp, maxLines = 1,
                        modifier = Modifier.widthIn(min = 20.dp).padding(top = 10.dp))
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
            Text("Each dot is one day's average health reading; the line is the trend through them. Days still scatter because any one day is only an estimate — the trend is what matters.",
                color = Cell.TextFaint, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // Measured vs modeled: is this cell aging normally for its cycle count?
        analysis?.modeledSohPct?.let { modeled ->
            Spacer(Modifier.height(20.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Measured vs modeled")
                    Spacer(Modifier.height(14.dp))
                    val measured = analysis.sohPct
                    val srcSub = when (analysis.capacitySource) {
                        CapacitySource.GAUGE -> "fuel gauge"
                        CapacitySource.SESSION -> "${analysis.sessionCount} sessions"
                        CapacitySource.ESTIMATE -> "estimate"
                        else -> ""
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        SourceCol("Measured", measured?.let { "${it.toInt()}%" } ?: "—", srcSub,
                            conditionColor(analysis.condition), Modifier.weight(1f))
                        VDivider()
                        // A band, not a point: ±0.1 V assumed rest voltage, ±3 °C and
                        // ±0.15 depth of discharge together move this by roughly 6 points,
                        // so a single figure would claim precision the model hasn't got.
                        SourceCol("Modeled",
                            "${(modeled - 6).coerceAtLeast(0.0).toInt()}–${(modeled + 6).coerceAtMost(100.0).toInt()}%",
                            "typical range", Cell.Cool, Modifier.weight(1f))
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
                    // Name the inputs. Two of them are assumptions, and the model is only
                    // as good as they are — hiding that would overstate the number.
                    val dodTxt = analysis.modeledDoD?.let {
                        "${(it * 100).roundToInt()}%" + if (analysis.modeledDoDIsMeasured) " (measured)" else " (assumed)"
                    } ?: "—"
                    val tempTxt = analysis.modeledTempC?.let { String.format(Locale.US, "%.0f°C", it) } ?: "—"
                    Text(
                        "A typical wear estimate for this many charge cycles, based on published lithium-battery research. " +
                            "It assumes a discharge depth of $dodTxt, a mean temperature of $tempTxt and a resting voltage of 3.8 V. " +
                            "The research it comes from was fitted to a 2013 cell, so it runs a little pessimistic for a modern phone. " +
                            "It is a reference, not a manufacturer figure.",
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
                    SectionLabel("What's aging your battery")
                    Spacer(Modifier.height(10.dp))
                    val calModel = if (analysis.modeledHasCalendar) (analysis.modeledCalendarLossPct ?: 0.0) else 0.0
                    val modelTotal = cyc + calModel
                    // The model's ABSOLUTE loss is what a typical cell of this age and cycle
                    // count would have lost — rarely what this cell actually lost. So when a
                    // measured State of Health exists, show the real loss and use the model
                    // only for the split between the two causes. Showing the model's total
                    // here would contradict the State-of-Health figure further up the screen.
                    val measuredLoss = analysis.sohPct?.let { 100.0 - it }
                    val fromMeasured = analysis.modeledHasCalendar && measuredLoss != null && modelTotal > 0.0
                    val shownTotal = if (fromMeasured) measuredLoss!! else modelTotal
                    val shownCyc = if (fromMeasured) measuredLoss!! * (cyc / modelTotal) else cyc
                    val shownCal = if (fromMeasured) measuredLoss!! * (calModel / modelTotal) else calModel
                    Text(
                        // Without a battery age the model cannot compute its age/heat term, so
                        // saying "you have lost <cyclic>%" would blame charging for all of it.
                        if (analysis.modeledHasCalendar)
                            "Your battery has lost about ${shownTotal.roundToInt()}% of its capacity so far. Here's what caused it:"
                        else "Charging alone accounts for about ${cyc.roundToInt()}% of capacity loss. Age and heat add more on top — they are not counted here yet:",
                        color = Cell.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(14.dp))
                    HBar("Cycles", (shownCyc / 100.0).toFloat(), "−${shownCyc.roundToInt()}%", Cell.Warn)
                    if (analysis.modeledHasCalendar) {
                        HBar("Age/heat", (shownCal / 100.0).toFloat(), "−${shownCal.roundToInt()}%", Cell.AccentDeep)
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Age/heat", color = Cell.TextMuted, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(58.dp))
                            Text("not counted — needs a start date", color = Cell.TextFaint, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (analysis.modeledHasCalendar)
                            "The total is your own measured loss. How it splits between charging and age/heat comes from the wear model, using your cycle count, the battery's age and the temperatures Cellspan has recorded."
                        else "Android hides the battery's make date, so Cellspan cannot work out the age-and-heat share on its own. Tell it roughly when the phone was bought, under Settings, and both figures appear.",
                        color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                }
            }
        }

        forecast?.takeIf { it.method != SohForecaster.Method.NONE }?.let { fc ->
            Spacer(Modifier.height(14.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Forecast", color = Cell.Accent)
                    val trendy = (fc.r2 ?: 0.0) >= 0.25
                    val atThreshold = (analysis?.sohPct ?: 100.0) <= 80.5
                    Text(
                        when {
                            fc.etaDateMs == null ->
                                "Aging slowly — not projected to reach 80% within the next five years."
                            atThreshold ->
                                "Your battery is around the 80% mark now — the point often used to consider a replacement."
                            trendy ->
                                "At your recent rate, capacity may reach 80% around ${Fmt.date(fc.etaDateMs)}."
                            else ->
                                "By the typical-wear model, capacity may reach 80% around ${Fmt.date(fc.etaDateMs)}."
                        },
                        color = Cell.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (trendy) {
                        val lo = fc.etaLowMs; val hi = fc.etaHighMs
                        if (lo != null && hi != null && Fmt.date(lo) != Fmt.date(hi)) {
                            Text("Likely range: ${Fmt.date(lo)} – ${Fmt.date(hi)}.",
                                color = Cell.TextMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    val projPts = projectionPoints(fc.curve)
                    if (projPts.size >= 2) {
                        Spacer(Modifier.height(12.dp))
                        TimeSeriesChart(projPts, height = 96.dp, yFormat = { "${it.toInt()}%" },
                            xStart = "now", xEnd = fc.etaDateMs?.let { Fmt.date(it) } ?: "5 yr",
                            threshold = BatteryAnalytics.REPLACE_SOH.toFloat(), thresholdLabel = "80%", lineColor = Cell.Accent)
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
                    Text(
                        if (trendy) "An on-device trend projection from your history, not a guarantee — it shifts with temperature and how you charge."
                        else "Your health readings are still too noisy for a reliable trend, so this leans on the typical-wear model. A rough guide, not a guarantee.",
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    snapshot: BatterySnapshot?,
    analysis: Analysis?,
    onOpenInfo: (String) -> Unit = {},
    onSetDesignCapacity: (Int?) -> Unit = {},
    onSetBatteryStart: (Long?) -> Unit = {},
) {
    var showDesignDialog by remember { mutableStateOf(false) }
    var showAgeDialog by remember { mutableStateOf(false) }
    if (showDesignDialog) {
        DesignCapacityDialog(
            current = analysis?.designCapacityMah,
            onDismiss = { showDesignDialog = false },
            onSave = { onSetDesignCapacity(it); showDesignDialog = false },
        )
    }
    if (showAgeDialog) {
        BatteryAgeDialog(
            onDismiss = { showAgeDialog = false },
            onSave = { onSetBatteryStart(it); showAgeDialog = false },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        val status = snapshot?.status ?: "—"
        Text("Settings", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(16.dp))
        ChargeAlarmCard(status)

        Spacer(Modifier.height(16.dp))
        BackgroundSamplingCard()

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("This device")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Model",
                    listOfNotNull(snapshot?.manufacturer?.ifBlank { null }, snapshot?.model?.ifBlank { null })
                        .joinToString(" ").ifBlank { "—" })
                KeyValueRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                KeyValueRow("Chemistry", snapshot?.technology ?: "—")
                KeyValueRow("Design capacity",
                    analysis?.designCapacityMah?.let { "$it mAh" + if (analysis.designIsFromDatabase) " (typical)" else "" } ?: "—")
                KeyValueRow("Cycle count", analysis?.cycleCount?.let { "$it" } ?: "not reported")
                // When the battery reports its own manufacture date there is nothing to ask
                // and nothing to override, so the control disappears entirely.
                val ageFromDevice = analysis?.ageDays != null && !analysis.ageIsUserSet
                KeyValueRow("Battery age", analysis?.ageDays?.let {
                    Fmt.age(it) + if (ageFromDevice) " (from battery)" else " (you set)"
                } ?: "not set")
                if (!ageFromDevice) {
                    Spacer(Modifier.height(6.dp))
                    // Only asked for because this phone won't hand over the date. Without
                    // one the wear model drops its age/heat term and charging looks
                    // responsible for the entire loss.
                    Text(if (analysis?.ageIsUserSet == true) "Change battery age" else "Set battery age",
                        color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { showAgeDialog = true }.padding(vertical = 15.dp))
                }
                // Padding after clickable, so the tap target reaches 48dp — this link is the
                // only way to correct the number the whole health calculation rests on.
                Text("Set design capacity", color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showDesignDialog = true }.padding(vertical = 15.dp))
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
                Text("Save your battery data — every reading with its timestamp — to a file you can keep or move to a new phone. Pick a range, or export everything.",
                    color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text("EXPORT RANGE", color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                // Flow, not a Row: at large font scales the last chip would otherwise be
                // squeezed to one character per line and become unpickable.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
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
                Spacer(Modifier.height(6.dp))
                Text("As well as battery data, Cellspan reads whether Wi-Fi, Bluetooth, location services, battery saver and airplane mode are on or off, plus the screen brightness level — the on/off state only, never your position or any location data. Notifications are optional and are used only for the charge alarm.",
                    color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(6.dp))
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

/**
 * Asks roughly when the battery started life. Stock Android hides the manufacture date,
 * and without a date the wear model has to drop its age-and-heat term entirely — which
 * makes charging look responsible for the whole loss. An approximate date is plenty:
 * calendar ageing goes with days^0.75, so being a month or two out barely moves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryAgeDialog(onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val current = AppPrefs.batteryStartMs(ctx)
    val state = rememberDatePickerState(initialSelectedDateMillis = current)
    val now = System.currentTimeMillis()
    val picked = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = picked != null && picked < now, onClick = { onSave(picked) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(
            state = state,
            // The prompt goes in the title slot; a sibling Text above the picker is
            // swallowed by the dialog's own layout.
            title = {
                Text("Roughly when did you get this phone? A month either way is fine.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
            },
        )
    }
}

/**
 * Background sampling control. The hourly reading is the user's choice, so the switch
 * genuinely cancels the work rather than leaving it queued.
 *
 * Android throttles background work under Doze, which stretches the hourly rhythm on an
 * idle phone. The honest fix is for the user to mark the app unrestricted, so we send
 * them to the system screen. Cellspan never requests REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * itself — that permission is restricted on Google Play, and asking for it isn't needed
 * to read the current state.
 */
@Composable
private fun BackgroundSamplingCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(AppPrefs.backgroundSampling(ctx)) }
    var unrestricted by remember { mutableStateOf(SampleScheduler.isUnrestricted(ctx)) }
    // Fires when the user comes back from the system screen, so the status re-reads itself.
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = SampleScheduler.isUnrestricted(ctx)
    }
    SurfaceCard {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Background sampling")
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        AppPrefs.setBackgroundSampling(ctx, it)
                        SampleScheduler.apply(ctx)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Cell.Surface,
                        checkedTrackColor = Cell.Accent,
                        uncheckedThumbColor = Cell.TextFaint,
                        uncheckedTrackColor = Cell.Track,
                        uncheckedBorderColor = Cell.Border,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cellspan takes one battery reading about every hour, so your charts stay complete when the app is closed. It never uses the network and costs very little power.",
                color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
            )
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                if (unrestricted) {
                    Text("Unrestricted — readings arrive on time.",
                        color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                } else {
                    Text(
                        "Android may delay readings while the phone sits idle. Allow unrestricted background use to keep the hourly rhythm.",
                        color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Allow unrestricted background", color = Cell.Accent, fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                // The battery-optimisation list first; app details as a fallback
                                // for OEMs that don't ship the former.
                                try {
                                    settingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                } catch (e: Exception) {
                                    try {
                                        settingsLauncher.launch(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", ctx.packageName, null))
                                        )
                                    } catch (e2: Exception) {
                                        Toast.makeText(ctx, "Open Settings > Apps > Cellspan > Battery", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            // Padding after clickable, so the tap target reaches 48dp.
                            .padding(vertical = 15.dp))
                }
            }
        }
    }
}

/** Charge-alarm control on the Now screen: pick a target level or turn it off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargeAlarmCard(status: String) {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(AppPrefs.chargeAlarmTarget(ctx)) }
    // A blocked notification makes the alarm silently useless, so the card has to say so.
    var notifsOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    val notifSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notifsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    fun apply(v: Int) {
        target = v
        AppPrefs.setChargeAlarmTarget(ctx, v)
        if (v > 0) {
            if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            if (status == "Charging" || status == "Full") ChargeAlarm.scheduleCheck(ctx, 0)
        } else {
            ChargeAlarm.cancel(ctx)
            ChargeAlarm.cancelNotification(ctx)
        }
        notifsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    SurfaceCard {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Charge alarm")
                Text(if (target > 0) "at $target%" else "off",
                    color = if (target > 0) Cell.Accent else Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("A reminder to unplug at a set level, so the battery spends less time full. Android gives apps no way to stop charging, so this notifies you — it does not cap the charge itself.",
                color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
            if (target > 0 && !notifsOn) {
                Spacer(Modifier.height(8.dp))
                Text("Notifications are switched off for Cellspan, so this alarm cannot reach you.",
                    color = Cell.Bad, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Text("Turn on notifications", color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            runCatching {
                                notifSettings.launch(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                )
                            }
                        }
                        .padding(vertical = 15.dp))
            }
            Spacer(Modifier.height(10.dp))
            // Flow, not a Row: at large font scales the last chip would otherwise be
            // squeezed to one character per line and become unpickable.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                listOf(0, 80, 85, 90, 95).forEach { v ->
                    val sel = v == target
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Cell.AccentSoft else Cell.SurfaceHi)
                            .border(1.dp, if (sel) Cell.BorderAccent else Cell.Border, RoundedCornerShape(8.dp))
                            .clickable { apply(v) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(if (v == 0) "Off" else "$v%", color = if (sel) Cell.Accent else Cell.TextMuted,
                            fontSize = 12.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/** Lets the user set (or clear) a manual design-capacity override, AccuBattery-style. */
@Composable
private fun DesignCapacityDialog(current: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var textVal by remember { mutableStateOf(current?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Design capacity", fontFamily = Sans, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("Set your battery's rated capacity in mAh if the detected value is wrong. Leave it blank to let Cellspan detect it automatically.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = textVal,
                    onValueChange = { s -> textVal = s.filter { it.isDigit() }.take(5) },
                    label = { Text("mAh") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (current != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Current value: $current mAh.", color = Cell.TextFaint, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(textVal.toIntOrNull()) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Use automatic") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
