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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.jenil1320.cellspan.analytics.Confidence
import io.github.jenil1320.cellspan.analytics.DrainForecaster
import io.github.jenil1320.cellspan.analytics.SohForecaster
import io.github.jenil1320.cellspan.analytics.ThermalAdvisor
import io.github.jenil1320.cellspan.data.AppPrefs
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.BatteryGuard
import io.github.jenil1320.cellspan.data.BatteryGuardNotifications
import io.github.jenil1320.cellspan.data.GuardPolicy
import io.github.jenil1320.cellspan.data.ChargeControl
import io.github.jenil1320.cellspan.data.SystemChargeLimit
import io.github.jenil1320.cellspan.data.HistoryPoint
import io.github.jenil1320.cellspan.data.SocPoint
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
    BatteryAnalytics.TempBand.FREEZING -> Cell.Bad
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
    /** "7 days" means today plus the six before it — midnight of day n-1, not n.
     *  Calendar, not fixed day arithmetic, so a daylight-saving change can't shift the cut. */
    fun sinceMs(): Long = days?.let { d ->
        Calendar.getInstance().apply {
            timeInMillis = startOfToday(); add(Calendar.DAY_OF_YEAR, -(d - 1))
        }.timeInMillis
    } ?: 0L
}
fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun socToday(soc: List<SocPoint>, dayStartMs: Long): List<Pair<Float, Float>> {
    val today = soc.filter { it.timestampMs >= dayStartMs }
    return today.map { ((it.timestampMs - dayStartMs) / 3_600_000f) to it.levelPct.toFloat() }
}
// ── Now ──────────────────────────────────────────────────────────────────────
@Composable
fun NowScreen(
    snapshot: BatterySnapshot?,
    dailyUsage: List<Pair<String, Double>>,
    socLog: List<SocPoint>,
    screenSplit: HistoryStore.ScreenSplit? = null,
    guardRunning: Boolean = false,
) {
    // Plain local, not remember: every battery broadcast recomposes this, and the boundary
    // has to roll over at midnight rather than stick at the value it was first composed with.
    val dayStart = startOfToday()
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


        // Charging: how long until it is full, and until the user's own limit.
        //
        // The charging half of this screen used to show nothing but a rate word. "Time to
        // full" is the single most asked-for figure while a phone is on the cable, and the
        // app already had every input it needed.
        if (status == "Charging") {
            val chargeRate = BatteryAnalytics.chargeRatePctPerHour(recentSoc)
            val toFull = BatteryAnalytics.timeToLevelMs(level, 100, chargeRate, status)
            val limit = AppPrefs.chargeAlarmTarget(LocalContext.current)
            val toLimit = if (limit in 1..99)
                BatteryAnalytics.timeToLevelMs(level, limit, chargeRate, status) else null
            if (toFull != null || toLimit != null) {
                Spacer(Modifier.height(16.dp))
                Appear(60) {
                    SurfaceCard {
                        Column {
                            SectionLabel("Time to charge")
                            Spacer(Modifier.height(6.dp))
                            toLimit?.let { EnduranceRow("To your $limit% limit", Fmt.duration(it), Cell.Accent) }
                            toFull?.let { EnduranceRow("To full", Fmt.duration(it), Cell.TextPrimary) }
                        }
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
        val charging = status == "Charging"

        // Battery safety comes first on the screen when there is something to say. A user
        // whose phone is cooking needs the instruction, not the analytics.
        val thermal = ThermalAdvisor.advise(snapshot?.temperatureC, charging, power)
        if (thermal != null) {
            Spacer(Modifier.height(16.dp))
            Appear(40) { ThermalCard(thermal, snapshot?.temperatureC) }
        }

        // One severity fraction per parameter, from the shared scale, so the same colour
        // means the same distance from trouble on every tile.
        val sTemp = Severity.temperature(snapshot?.temperatureC)
        val sVolt = Severity.voltage(snapshot?.voltageV)
        val sPower = Severity.power(power, charging)
        val sCurrent = if (charging) 0f else sPower

        Spacer(Modifier.height(20.dp))
        Appear(80) {
            Column {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Temperature", snapshot?.temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—", band.label,
                        Modifier.weight(1f).fillMaxHeight(), accent = Severity.color(sTemp),
                        metric = Metric.TEMPERATURE, severity = sTemp,
                        gauge = snapshot?.temperatureC?.let { (it / 55f).coerceIn(0f, 1f) })
                    VitalTile("Voltage", snapshot?.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—", vR.note,
                        Modifier.weight(1f).fillMaxHeight(), accent = Severity.color(sVolt),
                        metric = Metric.VOLTAGE, severity = sVolt,
                        gauge = snapshot?.voltageV?.let { ((it - 3.0f) / 1.50f).coerceIn(0f, 1f) })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile("Current", snapshot?.currentUa?.let { "${kotlin.math.abs(it / 1000)} mA" } ?: "—", cR.note,
                        Modifier.weight(1f).fillMaxHeight(),
                        // While charging a big current is the charger working, not a
                        // problem, so the tile stays on the brand colour rather than
                        // shading toward red for doing exactly what the user wanted.
                        accent = if (charging) Cell.Accent else Severity.color(sCurrent),
                        metric = Metric.CURRENT, severity = sCurrent,
                        gauge = snapshot?.currentUa?.let { (kotlin.math.abs(it / 1000f) / 2500f).coerceIn(0f, 1f) })
                    VitalTile("Power", power?.let { String.format(Locale.US, "%.1f W", it) } ?: "—",
                        if (charging) "Charging" else BatteryAnalytics.powerReading(power).note,
                        Modifier.weight(1f).fillMaxHeight(),
                        accent = if (charging) Cell.Accent else Severity.color(sPower),
                        metric = Metric.POWER, severity = sPower,
                        gauge = power?.let { (kotlin.math.abs(it).toFloat() / 12f).coerceIn(0f, 1f) })
                }
            }
        }

        val todayPts = socToday(socLog, dayStart)
        if (todayPts.size >= 2) {
            // Rest-of-day drain projection (dashed, capped at 8h) when discharging.
            val drainRaw = if (status == "Discharging" && dischargeRate != null && dischargeRate > 0.0)
                DrainForecaster.restOfDayCurve(nowTs, level, dischargeRate, horizonMs = 8 * 3_600_000L) else emptyList()
            val drainFc = drainRaw.map { (ts, soc) -> ((ts - dayStart) / 3_600_000f) to soc.toFloat() }
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

        Spacer(Modifier.height(12.dp))
        Appear(240) { ScreenDrainCard(screenSplit, guardRunning) }

        Spacer(Modifier.height(80.dp))
    }
}

/**
 * The battery-safety card: what is wrong, why it matters, and what to do about it.
 *
 * Shown only when there is something to act on, and placed above the analytics — a user
 * whose phone is at 52°C needs the instruction, not the charts. The advice is ordered by
 * how much each action actually removes rather than by how easy it is, so "unplug" comes
 * before "take the case off".
 */
@Composable
private fun ThermalCard(advice: ThermalAdvisor.Advice, tempC: Float?) {
    val urgent = advice.level >= ThermalAdvisor.Level.HOT
    val tint = if (urgent) Cell.Bad else Cell.Warn
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(advice.headline, color = tint, fontFamily = Sans, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                tempC?.let {
                    Text(String.format(Locale.US, "%.0f°C", it), color = tint,
                        fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(10.dp))
            advice.actions.forEach { action ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("→", color = tint, fontFamily = Mono, fontSize = 13.sp)
                    Text(action, color = Cell.TextPrimary, fontSize = 13.5.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(advice.why, color = Cell.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * What the screen costs, against what the phone spends while it is asleep.
 *
 * This is the comparison a user can act on — "eleven percent an hour on screen, half a
 * percent asleep" tells them whether to look at brightness or at a misbehaving background
 * app. It needs the battery guard running, because only the guard sees every one-point
 * change together with the screen state at that moment, so the card says so plainly rather
 * than sitting empty.
 */
@Composable
private fun ScreenDrainCard(split: HistoryStore.ScreenSplit?, guardRunning: Boolean) {
    val on = split?.onRate
    val off = split?.offRate
    SurfaceCard {
        Column {
            SectionLabel("Where the charge goes")
            Spacer(Modifier.height(10.dp))
            if (on == null && off == null) {
                Text(
                    if (guardRunning)
                        "Measuring. Once the phone has spent a while both awake and asleep on battery, the two rates appear here."
                    else
                        "Turn on the battery life saver in Settings and Cellspan can measure this — it needs to watch the level while the app is closed.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                )
                return@Column
            }
            EnduranceRow("Screen on", on?.let { String.format(Locale.US, "%.1f%% / h", it) } ?: "—", Cell.Warn)
            EnduranceRow("Screen off", off?.let { String.format(Locale.US, "%.1f%% / h", it) } ?: "—", Cell.Good)
            // The high-idle case is tested FIRST. When it was second it was unreachable,
            // because the ratio branch fired for any off-rate above 0.05 — so a phone being
            // held awake in the background (screen-on 0.4%/h, screen-off 1.2%/h) got
            // "your screen costs about 0 times what the phone uses asleep" instead of the
            // warning written for exactly that case.
            if (off != null && off > 2.0) {
                Spacer(Modifier.height(8.dp))
                Text("That is high for a sleeping phone — something is keeping it awake. Check Settings > Battery for an app using power in the background.",
                    color = Cell.Warn, fontSize = 12.sp, lineHeight = 18.sp)
            } else if (on != null && off != null && off > 0.05 && on / off >= 1.5) {
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(
                        Locale.US,
                        "Your screen costs about %.1f times what the phone uses asleep. Last 7 days.",
                        on / off,
                    ),
                    color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}

/** Half-width of the modelled-health band, in State-of-Health points. The band and the
 *  verdict must be judged on the same number, or the card contradicts itself. */
private const val MODEL_BAND_PTS = 6.0

// ── Health ───────────────────────────────────────────────────────────────────
@Composable
fun HealthScreen(
    snapshot: BatterySnapshot?,
    analysis: Analysis?,
    history: List<HistoryPoint>,
    forecast: SohForecaster.Forecast? = null,
    onOpenInfo: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("State of Health")
            Text(buildString {
                append(snapshot?.model ?: "Battery"); analysis?.ageDays?.let { append(" · ${Fmt.age(it)}") }
            }, color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
        }
        // How much this figure can be trusted, judged on its own error bar rather than on
        // "did it come from a session?". One 25-point charge used to be enough to drop the
        // tilde and print a confident integer, and the next session could then move it
        // several points with no explanation — which reads as the app being unreliable,
        // when in fact it was becoming more precise.
        val uncertainty = analysis?.sohUncertaintyPts
        val confidence = BatteryAnalytics.confidenceFor(uncertainty)
        val firm = confidence == Confidence.HIGH ||
            confidence == Confidence.MEDIUM
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
                // The evidence line: where the number came from, how much of it there is,
                // and the error bar. A reader can now tell "91%" backed by ten agreeing
                // sessions apart from "91%" backed by one shallow one.
                analysis?.let { a ->
                    val src = when (a.capacitySource) {
                        CapacitySource.DEVICE -> "reported by phone"
                        CapacitySource.GAUGE -> "fuel gauge"
                        CapacitySource.SESSION -> "${a.sessionCount} charge " +
                            if (a.sessionCount == 1) "session" else "sessions"
                        CapacitySource.ESTIMATE -> "live estimate"
                        CapacitySource.NONE -> null
                    }
                    if (src != null && a.sohPct != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            src + (uncertainty?.let { String.format(Locale.US, " · ±%.0f pts", it.coerceAtLeast(1.0)) } ?: ""),
                            color = Cell.TextMuted, fontFamily = Mono, fontSize = 10.5.sp,
                        )
                    }
                }
            }
            Column(Modifier.width(126.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Cycles", analysis?.cycleCount?.let { "$it" } ?: "—",
                    if ((analysis?.cycleCount ?: 0) >= 800) Cell.Warn else Cell.TileSlate, Modifier.fillMaxWidth())
                KpiTile("Capacity", analysis?.currentCapacityMah?.let { "$it" } ?: "—", Cell.TileTeal, Modifier.fillMaxWidth(),
                    sub = analysis?.designCapacityMah?.let { "of $it mAh" })
            }
        }
        // One sentence only when there is something to do about it; a firm measured
        // figure speaks for itself.
        if (analysis?.sohPct == null || analysis.capacitySource == CapacitySource.ESTIMATE) {
            Text("Charge by at least 25% without unplugging a few times — Cellspan will then measure your battery's real health.",
                color = Cell.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp))
        }
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
                    SectionLabel("Measured vs modeled")
                    Spacer(Modifier.height(14.dp))
                    val measured = analysis.sohPct
                    val srcSub = when (analysis.capacitySource) {
                        CapacitySource.DEVICE -> "reported by phone"
                        CapacitySource.GAUGE -> "fuel gauge"
                        CapacitySource.SESSION -> "${analysis.sessionCount} sessions"
                        CapacitySource.ESTIMATE -> "estimate"
                        else -> ""
                    }
                    // Without a battery start date the model drops its age/heat term, so this
                    // column compares cycle count alone and must not claim otherwise.
                    val hasCal = analysis.modeledHasCalendar
                    // A band, not a point, and its width has to cover BOTH uncertainties or
                    // the verdict underneath is unearned. The model's own assumptions (rest
                    // voltage, mean temperature, depth of discharge) move it by about
                    // MODEL_BAND_PTS; the measured figure carries its own error bar, which
                    // for a live estimate is another 6 points. Comparing a ±6 measurement
                    // against a ±6 model with only one of the two shown let this card
                    // announce "wearing faster than typical" on a difference smaller than
                    // either error — while the Forecast card below, using a different
                    // threshold, said the opposite.
                    val measuredErr = analysis.sohUncertaintyPts ?: 0.0
                    val band = kotlin.math.sqrt(MODEL_BAND_PTS * MODEL_BAND_PTS + measuredErr * measuredErr)
                    val loPts = (modeled - band).coerceAtLeast(0.0).toInt()
                    val hiPts = (modeled + band).coerceAtMost(100.0).toInt()
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        SourceCol("Measured", measured?.let { "${it.toInt()}%" } ?: "—", srcSub,
                            conditionColor(analysis.condition), Modifier.weight(1f))
                        VDivider()
                        SourceCol("Typical cell", "$loPts–$hiPts%",
                            if (hasCal) "same age & cycles" else "same cycles only", Cell.Cool, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    if (measured != null) {
                        val m = measured.toInt()
                        Text(
                            when {
                                m > hiPts -> "Holding up better than a typical cell at ${analysis.cycleCount ?: "this"} cycles."
                                m < loPts -> "Wearing a little faster than a typical cell at ${analysis.cycleCount ?: "this"} cycles."
                                else -> "Tracking the typical wear curve for its cycle count."
                            },
                            color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
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
                    // Round once, then derive the other half, or the two bars can print
                    // figures that don't add up to the total stated just above them.
                    val cycInt = shownCyc.roundToInt()
                    Text(
                        // Without a battery age the model cannot compute its age/heat term, so
                        // saying "you have lost <cyclic>%" would blame charging for all of it.
                        if (analysis.modeledHasCalendar)
                            "About ${shownTotal.roundToInt()}% of capacity lost so far:"
                        else "Charging accounts for about ${cyc.roundToInt()}% of loss — age and heat not counted yet:",
                        color = Cell.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(14.dp))
                    HBar("Cycles", (shownCyc / 100.0).toFloat(), "−$cycInt%", Cell.Warn)
                    if (analysis.modeledHasCalendar) {
                        val calInt = shownTotal.roundToInt() - cycInt
                        HBar("Age/heat", (shownCal / 100.0).toFloat(), "−$calInt%", Cell.AccentDeep)
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
                    if (!analysis.modeledHasCalendar) {
                        Text("Set a start date", color = Cell.Accent, fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onOpenInfo("age") }.padding(vertical = 15.dp))
                    }
                }
            }
        }

        forecast?.takeIf { it.method != SohForecaster.Method.NONE }?.let { fc ->
            Spacer(Modifier.height(14.dp))
            SurfaceCard {
                Column {
                    SectionLabel("Forecast", color = Cell.Accent)
                    // The forecaster already applies both halves of its reliability test, so
                    // read its verdict rather than re-checking the fit alone.
                    val trendy = fc.method == SohForecaster.Method.TREND
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
                    // Shown on BOTH paths. Gating it on the trend path was backwards: the
                    // physics path is taken precisely because the data was too thin to fit,
                    // so a bare date there read as more certain than the well-fitted one.
                    run {
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
    /** Called after a successful import, so the screens reload from the merged history. */
    onImported: () -> Unit = {},
) {
    // rememberSaveable, not remember: an open dialog and half-typed input are lost on a
    // rotation otherwise, and the design-capacity field is a number the user has usually
    // just looked up on another screen.
    var showDesignDialog by rememberSaveable { mutableStateOf(false) }
    var showAgeDialog by rememberSaveable { mutableStateOf(false) }
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
        Text("Settings", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(16.dp))
        BatteryLifeSaverCard(snapshot?.pluggedIn == true)

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
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(if (analysis?.ageIsUserSet == true) "Change battery age" else "Set battery age",
                            color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { showAgeDialog = true }.padding(vertical = 15.dp))
                        Text("How it works", color = Cell.TextMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onOpenInfo("age") }.padding(vertical = 15.dp))
                    }
                }
                // Padding after clickable, so the tap target reaches 48dp — this link is the
                // only way to correct the number the whole health calculation rests on.
                Text("Set design capacity", color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showDesignDialog = true }.padding(vertical = 15.dp))
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
                    val json = HistoryStore(ctx).use { it.exportJson(now, range.sinceMs()) }
                    val stream = ctx.contentResolver.openOutputStream(uri) ?: error("no output stream")
                    stream.use { os -> os.write(json.toByteArray()) }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, if (ok) "Battery data exported" else "Export failed — try another location", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // The other half of the promise the export card makes. Without it "your history
        // survives a new phone" was only ever half true — the file could be written and
        // never read back.
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) {
                // catch Throwable, not Exception. The obvious mistake here is picking the
                // wrong file, and a 300 MB video read into a String throws OutOfMemoryError,
                // which is an Error — it slipped straight past a catch on Exception and
                // killed the process instead of showing the failure toast.
                val msg = try {
                    val size = documentSizeBytes(ctx, uri)
                    require(size == null || size <= MAX_IMPORT_BYTES) { "too large" }
                    val text = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        // Bounded even when the provider would not tell us the size.
                        stream.reader().readAtMost(MAX_IMPORT_CHARS)
                    } ?: error("could not open the file")
                    val r = HistoryStore(ctx).use { store -> store.importJson(text) }
                    if (r.total == 0) "Nothing new to import — this data is already here."
                    else "Imported ${r.total} records. Your history is merged, nothing was replaced."
                } catch (e: IllegalArgumentException) {
                    if (e.message == "too large") "That file is too big to be a Cellspan export."
                    else "That is not a Cellspan export file."
                } catch (e: Throwable) {
                    "Could not read that file."
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    onImported()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Data & history")
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Cell.AccentSoft)
                            .border(1.dp, Cell.BorderAccent, RoundedCornerShape(10.dp))
                            .clickable { exporter.launch("cellspan-battery-data.json") }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text("Export", color = Cell.Accent, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Cell.SurfaceHi)
                            .border(1.dp, Cell.Border, RoundedCornerShape(10.dp))
                            .clickable { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text("Import", color = Cell.TextSecondary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Import merges a file you exported before — from this phone or an older one. It never replaces what is already here, and importing the same file twice changes nothing.",
                    color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Learn & privacy")
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
                "One reading an hour keeps your charts complete while the app is closed.",
                color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
            )
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                if (unrestricted) {
                    Text("Unrestricted — readings arrive on time.",
                        color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                } else {
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

/**
 * Battery life saver: keeps the cell inside its gentle band, and warns when it runs hot.
 *
 * The bottom end is a plug-in reminder. The top end is a real charge stop when root is
 * present ([ChargeControl]); otherwise a repeating unplug reminder, with the phone's own
 * limit linked as the only true cap a stock device has (see [SystemChargeLimit]).
 *
 * Every control here writes a preference and then calls [BatteryGuard.sync], which is the
 * one place that decides whether the guard service should be running. Nothing on this
 * screen schedules or cancels work directly any more: three call sites each holding their
 * own opinion about when a loop should run is how reminders went quiet unnoticed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatteryLifeSaverCard(plugged: Boolean) {
    val ctx = LocalContext.current
    var lowLevel by remember { mutableIntStateOf(AppPrefs.lowBatteryAlert(ctx)) }
    var target by remember { mutableIntStateOf(AppPrefs.chargeAlarmTarget(ctx)) }
    var strictness by remember { mutableStateOf(AppPrefs.strictness(ctx)) }
    var tempAlert by remember { mutableStateOf(AppPrefs.tempAlertC(ctx)) }
    // Both halves matter, and the old check only did the first: a user can leave app
    // notifications enabled and still switch off the one channel these alerts use, in
    // which case every post is dropped while the app believes all is well.
    var notifsOn by remember { mutableStateOf(BatteryGuardNotifications.anyAlertChannelReachable(ctx)) }
    // Whether this phone can genuinely stop its charge. Decides the wording: a "limit"
    // that only reminds must not say "stop".
    var canStop by remember { mutableStateOf(ChargeControl.kind(ctx) != ChargeControl.Kind.NONE) }
    // Probe once per install, not once per visit. probe() runs `su`, which raises the
    // root-permission dialog on a rooted phone; doing that on every trip to Settings
    // taught people to deny it.
    LaunchedEffect(Unit) {
        if (!ChargeControl.hasProbed(ctx)) {
            withContext(Dispatchers.IO) { canStop = ChargeControl.probe(ctx) != ChargeControl.Kind.NONE }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifsOn = BatteryGuardNotifications.anyAlertChannelReachable(ctx)
        BatteryGuard.sync(ctx)
    }
    val notifSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notifsOn = BatteryGuardNotifications.anyAlertChannelReachable(ctx)
        BatteryGuard.sync(ctx)
    }
    var unrestrictedBg by remember { mutableStateOf(SampleScheduler.isUnrestricted(ctx)) }
    // The phone's own charge limit. On a Pixel this is a real hardware stop at 80% that
    // Cellspan can flip directly once WRITE_SECURE_SETTINGS is granted over ADB.
    var sysState by remember { mutableStateOf(SystemChargeLimit.state(ctx)) }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestrictedBg = SampleScheduler.isUnrestricted(ctx)
        BatteryGuard.sync(ctx)
    }

    /**
     * Ask for the notification permission, but only while it can still be granted. Firing
     * the request again after two refusals is a no-op the user never sees, so the card
     * would sit there claiming alerts work while nothing had changed.
     */
    fun requestNotifsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !notifsOn) {
            permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun applyLow(v: Int) {
        lowLevel = v
        AppPrefs.setLowBatteryAlert(ctx, v)
        if (v > 0) requestNotifsIfNeeded()
        BatteryGuard.sync(ctx)
        notifsOn = BatteryGuardNotifications.anyAlertChannelReachable(ctx)
    }

    fun applyHigh(v: Int) {
        target = v
        AppPrefs.setChargeAlarmTarget(ctx, v)
        if (v > 0) requestNotifsIfNeeded()
        // The cap may be holding a charge right now; su must stay off the main thread.
        if (v <= 0) Thread { ChargeControl.releaseIfEngaged(ctx) }.start()
        BatteryGuard.sync(ctx)
        notifsOn = BatteryGuardNotifications.anyAlertChannelReachable(ctx)
    }

    fun applyStrictness(s: GuardPolicy.Strictness) {
        strictness = s
        AppPrefs.setStrictness(ctx, s)
        BatteryGuard.sync(ctx)
    }

    fun applyTemp(v: Float?) {
        tempAlert = v
        AppPrefs.setTempAlertC(ctx, v)
        if (v != null) requestNotifsIfNeeded()
        BatteryGuard.sync(ctx)
        notifsOn = BatteryGuardNotifications.anyAlertChannelReachable(ctx)
    }

    SurfaceCard {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Battery life saver")
                Text(
                    when {
                        lowLevel > 0 && target > 0 -> "$lowLevel–$target%"
                        lowLevel > 0 -> "from $lowLevel%"
                        target > 0 -> "to $target%"
                        else -> "off"
                    },
                    color = if (lowLevel > 0 || target > 0) Cell.Accent else Cell.TextFaint,
                    fontFamily = Mono, fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Remind me to charge at", color = Cell.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LevelChips(listOf(0, 15, 20, 25, 30), lowLevel, ::applyLow)

            Spacer(Modifier.height(16.dp))
            Text(if (canStop) "Stop charging at" else "Remind me to unplug at",
                color = Cell.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LevelChips(listOf(0, 80, 85, 90, 95), target, ::applyHigh)

            if (lowLevel > 0 || target > 0) {
                Spacer(Modifier.height(16.dp))
                Text("How insistent", color = Cell.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                ChoiceChips(
                    listOf(
                        GuardPolicy.Strictness.GENTLE to "Tell me once",
                        GuardPolicy.Strictness.PERSISTENT to "Remind me again",
                        GuardPolicy.Strictness.INSISTENT to "Keep at it",
                    ),
                    strictness, ::applyStrictness,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (strictness) {
                        GuardPolicy.Strictness.GENTLE ->
                            "One vibration, once. Cellspan then gets out of the way — it assumes you may not have a charger to hand."
                        GuardPolicy.Strictness.PERSISTENT ->
                            "Repeats every ten to fifteen minutes until you act, and stops the moment you do."
                        GuardPolicy.Strictness.INSISTENT ->
                            "Repeats every few minutes until you act. Choose this only if you regularly miss the first one."
                    },
                    color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("Reminders vibrate. None of them makes a sound.",
                    color = Cell.TextFaint, fontSize = 11.5.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(16.dp))
            Text("Warn me when the battery is hotter than",
                color = Cell.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            ChoiceChips(
                listOf(null to "Off", 40f to "40°C", 45f to "45°C", 50f to "50°C"),
                tempAlert, ::applyTemp,
            )
            Spacer(Modifier.height(8.dp))
            Text("Heat ages a cell faster than anything else you control — every extra 10°C roughly doubles the rate. Checked instantly while charging, and about once an hour otherwise.",
                color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)

            if (canStop) {
                Spacer(Modifier.height(10.dp))
                Text("Root detected — charging stops at the limit by itself.",
                    color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }

            if (lowLevel > 0 || target > 0 || tempAlert != null) {
                // Say plainly what the ongoing card is for. Android only lets an app see
                // every single percentage point while it has one, and a persistent
                // notification nobody explained reads as an app misbehaving.
                Spacer(Modifier.height(12.dp))
                Text("Near a limit, Cellspan puts a small card in your notification shade — while charging, or once the battery is within ${GuardPolicy.WATCH_MARGIN_PCT} points of your low reminder. That card is what lets it see every single percent, so the alert lands on exactly the level you picked. It goes away again as soon as it has told you.",
                    color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)

                // Some manufacturers kill background apps on their own schedule, and from
                // Android 12 an ordinary background job may not restart a foreground
                // service. Without the battery exemption the guard can therefore be killed
                // overnight and stay down until the app is next opened — so say so, rather
                // than letting the user believe their limits are still being watched.
                if (!unrestrictedBg) {
                    Spacer(Modifier.height(10.dp))
                    Text("Some phones stop background apps overnight. Allow unrestricted background so Cellspan can start watching again by itself.",
                        color = Cell.TextSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
                    Text("Allow unrestricted background", color = Cell.Accent, fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { openBatterySettings(ctx, systemSettings) }
                            .padding(vertical = 15.dp))
                }
            }

            if ((lowLevel > 0 || target > 0 || tempAlert != null) && !notifsOn) {
                Spacer(Modifier.height(12.dp))
                Text("Notifications are switched off for these alerts, so they cannot reach you.",
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

            if (!canStop) {
                Spacer(Modifier.height(14.dp))
                SystemLimitSection(sysState, target) { on ->
                    if (SystemChargeLimit.setOn(ctx, on)) {
                        sysState = SystemChargeLimit.state(ctx)
                    } else {
                        Toast.makeText(ctx, "The phone refused that change", Toast.LENGTH_SHORT).show()
                    }
                }
                run {
                    Text("Open the phone's battery settings", color = Cell.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                try {
                                    systemSettings.launch(SystemChargeLimit.settingsIntent())
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Open Settings > Battery", Toast.LENGTH_LONG).show()
                                }
                            }
                            // Padding after clickable, so the tap target reaches 48dp.
                            .padding(vertical = 15.dp))
                }
            }
        }
    }
}

/**
 * Largest file the importer will read: 64 MB.
 *
 * Ten years of hourly samples exports to a few megabytes, so this is generous for real
 * data and still far below what would exhaust the heap. The picker has to accept `* / *`
 * because file managers label JSON inconsistently, which means the user genuinely can
 * select a video by mistake.
 */
private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

/**
 * The same budget expressed in characters, for the streaming read. A third of the byte
 * limit, because a UTF-8 character can occupy up to three bytes in the range JSON uses —
 * so this can never hold more than MAX_IMPORT_BYTES worth of text in memory.
 */
private const val MAX_IMPORT_CHARS = MAX_IMPORT_BYTES / 3

/** The document's size in bytes, or null when the provider does not report one. */
private fun documentSizeBytes(ctx: android.content.Context, uri: Uri): Long? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
}.getOrNull()

/**
 * Read at most [limitChars] characters, so a provider that reports no size still cannot
 * exhaust memory.
 *
 * Counted in characters, not bytes, because that is what a Reader yields and what the
 * StringBuilder actually holds. The two differ for non-ASCII content — a UTF-8 file can be
 * three times its character count on disk — so the caller passes a character budget that
 * is deliberately smaller than the byte limit it checks against the provider.
 */
private fun java.io.Reader.readAtMost(limitChars: Long): String {
    val sb = StringBuilder()
    val buf = CharArray(8192)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        require(total <= limitChars) { "too large" }
        sb.appendRange(buf, 0, n)
    }
    return sb.toString()
}

/**
 * The phone's own charge limit — the only thing on an unrooted device that genuinely stops
 * a charge instead of asking the user to.
 *
 * Four states, and the wording has to be honest about which one applies, because the
 * difference between "your phone will stop at 80%" and "we will remind you at 80%" is the
 * whole point of the feature.
 */
@Composable
private fun SystemLimitSection(
    state: SystemChargeLimit.State,
    target: Int,
    onSet: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // The platform switch is fixed at 80%. Offering it while the user's target is 90 would
    // be promising something it cannot do.
    val targetMatches = target == SystemChargeLimit.SYSTEM_LIMIT_PCT

    when (state) {
        SystemChargeLimit.State.ON_WRITABLE,
        SystemChargeLimit.State.OFF_WRITABLE,
        SystemChargeLimit.State.UNKNOWN_WRITABLE -> {
            val on = state == SystemChargeLimit.State.ON_WRITABLE
            val neverSet = state == SystemChargeLimit.State.UNKNOWN_WRITABLE
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Really stop charging at 80%",
                        color = if (on) Cell.Accent else Cell.TextSecondary, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    Text(
                        when {
                            on -> "Your phone's own charge limit is on, so the charge genuinely stops. The reminder above stays as a check."
                            !targetMatches -> "Your phone can stop the charge, but only at 80%. Set the limit above to 80% to use it."
                            neverSet -> "Switches on your phone's own charge limit — a real stop, not a reminder. Cellspan has not set this yet, so check your phone's own screen if you think it is already on."
                            else -> "Switches on your phone's own charge limit — a real stop, not a reminder."
                        },
                        color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(checked = on, enabled = on || targetMatches,
                    onCheckedChange = { onSet(it) }, colors = cellSwitchColors())
            }
            Spacer(Modifier.height(8.dp))
            // Honesty about the one thing the platform will not allow. Android refuses to
            // let an ordinary app READ this key even with the permission that lets it
            // write, so the switch reflects what Cellspan last set, not what the phone
            // currently says. Where the two could differ, the phone wins, and the user is
            // told where to look.
            Text("Cellspan can set this but Android will not let it read the value back, so the switch shows what Cellspan last set. Settings › Battery › Battery health › Charging optimization is the final word.",
                color = Cell.TextFaint, fontSize = 11.5.sp, lineHeight = 17.sp)
        }

        SystemChargeLimit.State.NOT_PERMITTED -> {
            Text("Your phone can stop the charge at 80% by itself. Cellspan can switch that on for you, but only after one ADB command — Android has no in-app prompt for this permission.",
                color = Cell.TextSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Run this once from a computer, then reopen Cellspan:",
                color = Cell.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(6.dp))
            val cmd = SystemChargeLimit.grantCommand(ctx)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Cell.SurfaceHi)
                    .border(1.dp, Cell.Border, RoundedCornerShape(8.dp))
                    .clickable {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                        Toast.makeText(ctx, "Command copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(12.dp),
            ) {
                Text(cmd, color = Cell.TextSecondary, fontFamily = Mono, fontSize = 10.5.sp, lineHeight = 16.sp)
            }
            Text("Tap to copy. It grants one permission and nothing else, and it survives reboots. You can always set the limit by hand instead:",
                color = Cell.TextFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun cellSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Cell.Surface,
    checkedTrackColor = Cell.Accent,
    uncheckedThumbColor = Cell.TextFaint,
    uncheckedTrackColor = Cell.Track,
    uncheckedBorderColor = Cell.Border,
)

/**
 * Send the user to the battery-optimisation exemption screen, falling back through the
 * app's own details page and finally to written directions. Shared by the two places that
 * need the exemption: hourly sampling and the battery guard's ability to restart itself.
 */
private fun openBatterySettings(
    ctx: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    try {
        launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (e: Exception) {
        try {
            launcher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null))
            )
        } catch (e2: Exception) {
            Toast.makeText(ctx, "Open Settings > Apps > Cellspan > Battery", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * A row of level choices, with 0 shown as "Off". Flow, not a Row: at large font scales the
 * last chip would otherwise be squeezed to one character per line and become unpickable.
 */
@Composable
private fun LevelChips(options: List<Int>, selected: Int, onPick: (Int) -> Unit) =
    ChoiceChips(options.map { it to if (it == 0) "Off" else "$it%" }, selected, onPick)

/**
 * A row of choices of any type.
 *
 * `selectable` rather than `clickable`, so TalkBack announces each chip as a radio button
 * and says which one is chosen; the vertical padding takes the tap target past the 48dp
 * accessibility minimum, which the old 7dp version missed by a wide margin.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (sel) Cell.AccentSoft else Cell.SurfaceHi)
                    .border(1.dp, if (sel) Cell.BorderAccent else Cell.Border, RoundedCornerShape(8.dp))
                    .selectable(selected = sel, role = Role.RadioButton, onClick = { onPick(value) })
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Text(label, color = if (sel) Cell.Accent else Cell.TextMuted,
                    fontSize = 12.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

/** Lets the user set (or clear) a manual design-capacity override, AccuBattery-style. */
@Composable
private fun DesignCapacityDialog(current: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var textVal by rememberSaveable { mutableStateOf(current?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Design capacity", fontFamily = Sans, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("Your battery's rated capacity in mAh. Blank = detect automatically.",
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
