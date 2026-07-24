package io.github.jenil1320.cellspan.analytics

import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.DeviceCapacityTable

enum class Condition(val severity: Int, val label: String) {
    GOOD(0, "Good condition"),
    FAIR(1, "Aging"),
    REPLACE(2, "Consider replacing"),
    UNKNOWN(-1, "Not enough data"),
}

/**
 * The result of turning a [BatterySnapshot] into human-meaningful health numbers.
 * All values may be null when the underlying data isn't available on the device.
 */
enum class CapacitySource { DEVICE, GAUGE, SESSION, ESTIMATE, NONE }

data class Analysis(
    val sohPct: Double?,
    val capacitySource: CapacitySource,
    val sessionCount: Int,
    val currentCapacityMah: Int?,
    val designCapacityMah: Int?,
    val designIsFromDatabase: Boolean,
    val cycleCount: Int?,
    val condition: Condition,
    val ageDays: Double?,
    /** True when [ageDays] came from the date the user supplied, not the fuel gauge. */
    val ageIsUserSet: Boolean = false,
    val avgCyclesPerMonth: Double?,
    val avgFadePerYear: Double?,
    // Modeled ("typical") health from the semi-empirical degradation model.
    val modeledSohPct: Double? = null,
    val modeledCalendarLossPct: Double? = null,
    val modeledCyclicLossPct: Double? = null,
    val modeledHasCalendar: Boolean = false,
    /** Depth of discharge the model used, and whether it came from real sessions. */
    val modeledDoD: Double? = null,
    val modeledDoDIsMeasured: Boolean = false,
    /** Mean temperature the model used for the calendar term, °C. */
    val modeledTempC: Double? = null,
)

/**
 * Pure battery health math — no Android dependencies, so it is fully unit
 * testable on the JVM. Every function that needs "now" takes it as a parameter.
 */
object BatteryAnalytics {

    const val REPLACE_SOH = 80.0
    const val REPLACE_CYCLES = 800

    private const val MS_PER_DAY = 86_400_000.0
    private const val DAYS_PER_MONTH = 30.437
    private const val DAYS_PER_YEAR = 365.25

    /**
     * Estimate present full-charge capacity (mAh) from the remaining charge and
     * the reported level: full = remaining / (level / 100). Only meaningful when
     * the level is high enough that the gauge reading is stable.
     */
    fun estimateFullCapacityMah(chargeCounterUah: Long?, levelPct: Int): Int? {
        if (chargeCounterUah == null || chargeCounterUah <= 0) return null
        if (levelPct < 15 || levelPct > 100) return null
        val full = chargeCounterUah / (levelPct / 100.0)
        return (full / 1000.0).toInt()
    }

    fun conditionFor(soh: Double?, cycles: Int?): Condition {
        val bySoh = when {
            soh == null -> null
            soh >= 85 -> Condition.GOOD
            soh >= REPLACE_SOH -> Condition.FAIR
            else -> Condition.REPLACE
        }
        // Cycle count is a soft secondary signal only. Rated cycle life varies widely
        // by manufacturer (Google ~800; several 2024+ flagships 1000+; silicon-anode
        // packs ~1600–2000), so a high count nudges to "Aging" but never forces
        // "Consider replacing" on its own — measured State of Health drives that.
        val byCyc = when {
            cycles == null -> null
            cycles >= 600 -> Condition.FAIR
            else -> Condition.GOOD
        }
        return listOfNotNull(bySoh, byCyc).maxByOrNull { it.severity } ?: Condition.UNKNOWN
    }

    /**
     * Measured full-charge capacity (mAh) from one charge session's endpoints:
     * charge added ÷ fraction of battery gained. Null if the level gain is too
     * small to be reliable. This is AccuBattery's method and is far steadier
     * than the instantaneous estimate.
     *
     * [minGain] is 25 points rather than a stricter 40 because most people top up
     * little and often: at 40 a user who charges from 70% never records a session at
     * all and is stuck on the instantaneous estimate for good. A shallower session is
     * individually noisier — quantisation error runs at roughly 1/gain — so the caller
     * averages sessions WEIGHTED BY GAIN, which lets a deep session outvote a shallow
     * one instead of discarding the shallow one entirely.
     */
    fun measuredCapacityMah(
        startChargeUah: Long, endChargeUah: Long, startLevel: Int, endLevel: Int, minGain: Int = 25,
    ): Int? {
        val dLevel = endLevel - startLevel
        if (dLevel < minGain) return null
        val dCharge = endChargeUah - startChargeUah
        if (dCharge <= 0) return null
        return ((dCharge / (dLevel / 100.0)) / 1000.0).toInt()
    }

    fun analyze(
        s: BatterySnapshot,
        nowMs: Long,
        sessionMeasuredMah: Int? = null,
        sessionCount: Int = 0,
        avgTempC: Double? = null,
        avgDoD: Double? = null,
        designOverrideMah: Int? = null,
        batteryStartMs: Long? = null,
    ): Analysis {
        // Design capacity, best source first: a user-set override → the exact
        // fuel-gauge value → the ROM's PowerProfile (device-reported, works on almost
        // any phone) → our published table of typical capacities. Only the table is a
        // generic "typical" figure; the override is the user's own device figure.
        val designFromSysfs = s.chargeFullDesignUah?.let { (it / 1000.0).toInt() }
        val designFromProfile = s.designCapacityFromProfileMah?.takeIf { it > 0 }
        val designFromTable = DeviceCapacityTable.designCapacityMah(s.model)
        val designMah = designOverrideMah?.takeIf { it > 0 }
            ?: designFromSysfs ?: designFromProfile ?: designFromTable
        val designIsFromDatabase = designOverrideMah == null &&
            designFromSysfs == null && designFromProfile == null && designFromTable != null

        // Current full-charge capacity, best source first: exact gauge value →
        // session-measured average → the platform's own State of Health → instantaneous
        // estimate. The platform figure sits below our own measurements on purpose. Many
        // ROMs report a stub 100% for months and only admit real wear at some threshold,
        // so trusting it first makes health jump upward the day it changes (e.g. 92% to
        // 99%). That jump is then persisted into the history and flattens the trend.
        val deviceSoh = s.stateOfHealthPct?.takeIf { it in 1..100 }?.toDouble()
        val currentFromSysfs = s.chargeFullUah?.let { (it / 1000.0).toInt() }
        val currentEstimate = estimateFullCapacityMah(s.chargeCounterUah, s.levelPct)
        val currentMah: Int?
        val source: CapacitySource
        when {
            currentFromSysfs != null -> { currentMah = currentFromSysfs; source = CapacitySource.GAUGE }
            sessionMeasuredMah != null -> { currentMah = sessionMeasuredMah; source = CapacitySource.SESSION }
            deviceSoh != null -> {
                currentMah = designMah?.let { (it * deviceSoh / 100.0).toInt() }
                source = CapacitySource.DEVICE
            }
            currentEstimate != null -> { currentMah = currentEstimate; source = CapacitySource.ESTIMATE }
            else -> { currentMah = null; source = CapacitySource.NONE }
        }

        // Health always follows the capacity the chosen branch produced, so the number and
        // the source label can never describe different things.
        val soh: Double? =
            if (currentMah != null && designMah != null && designMah > 0) {
                val raw = currentMah.toDouble() / designMah * 100.0
                // A fresh cell can read slightly over its rated design capacity, and
                // the estimate path can overshoot; a health figure over 100% reads as
                // a bug. Cap the surfaced value at 100, but reject clearly bad data.
                if (raw in 0.0..130.0) raw.coerceAtMost(100.0) else null
            } else null

        val condition = conditionFor(soh, s.cycleCount)

        // Lifetime stats need a start date. The fuel gauge's manufacture date is the
        // best source but stock Android hides it, so fall back to the date the user
        // gave us — without one the wear model must drop its age/heat term entirely
        // and cycling wrongly absorbs the whole loss.
        var ageDays: Double? = null
        var cyclesPerMonth: Double? = null
        var fadePerYear: Double? = null

        val mfgFromGauge = s.manufacturingEpoch?.takeIf { it > 0 }
        val ageFromUser = mfgFromGauge == null && batteryStartMs != null && batteryStartMs < nowMs
        val mfg = mfgFromGauge ?: batteryStartMs?.takeIf { it < nowMs }?.let { it / 1000 }
        if (mfg != null && mfg > 0) {
            val days = (nowMs - mfg * 1000.0) / MS_PER_DAY
            if (days > 0) {
                ageDays = days
                val months = days / DAYS_PER_MONTH
                s.cycleCount?.let { c -> if (c > 0) cyclesPerMonth = c / months }
                if (soh != null) fadePerYear = (100.0 - soh) / (days / DAYS_PER_YEAR)
            }
        }

        // Modeled ("typical") health from the semi-empirical degradation model.
        val model = DegradationModel.estimate(
            equivalentFullCycles = s.cycleCount?.toDouble(),
            designCapacityMah = designMah,
            avgDoD = avgDoD,
            ageDays = ageDays,
            avgTempC = avgTempC ?: s.temperatureC?.toDouble(),
        )

        return Analysis(
            sohPct = soh,
            capacitySource = source,
            sessionCount = sessionCount,
            currentCapacityMah = currentMah,
            designCapacityMah = designMah,
            designIsFromDatabase = designIsFromDatabase,
            cycleCount = s.cycleCount,
            condition = condition,
            ageDays = ageDays,
            ageIsUserSet = ageFromUser,
            avgCyclesPerMonth = cyclesPerMonth,
            avgFadePerYear = fadePerYear,
            modeledSohPct = model?.modeledSohPct,
            modeledCalendarLossPct = model?.calendarLossPct,
            modeledCyclicLossPct = model?.cyclicLossPct,
            modeledHasCalendar = model?.hasCalendar ?: false,
            modeledDoD = model?.dodUsed,
            modeledDoDIsMeasured = avgDoD != null,
            modeledTempC = model?.tempUsedC,
        )
    }

    /** Instantaneous power draw/charge in watts (magnitude), or null. */
    fun powerWatts(voltageV: Float?, currentUa: Long?): Double? {
        if (voltageV == null || currentUa == null) return null
        return kotlin.math.abs(voltageV * (currentUa / 1_000_000.0))
    }

    enum class TempBand(val label: String) { COOL("Cool"), NORMAL("Normal"), WARM("Warm"), HOT("Hot"), UNKNOWN("—") }

    /** Classify battery temperature into a comfort band. */
    fun tempBand(c: Float?): TempBand = when {
        c == null -> TempBand.UNKNOWN
        c < 15f -> TempBand.COOL
        c <= 35f -> TempBand.NORMAL
        c <= 42f -> TempBand.WARM
        else -> TempBand.HOT
    }

    /**
     * Human label for how fast the battery is charging (only while charging),
     * graded by actual power in watts (voltage × current) so it holds across
     * devices and charge levels rather than by raw current alone.
     */
    fun chargeRate(currentUa: Long?, voltageV: Float?, status: String): String {
        if (status != "Charging" || currentUa == null) return "—"
        val watts = powerWatts(voltageV, currentUa) ?: return "—"
        return when {
            watts >= 25.0 -> "Very fast"
            watts >= 13.0 -> "Fast"
            watts >= 7.0 -> "Normal"
            watts >= 2.5 -> "Slow"
            watts > 0.0 -> "Trickle"
            else -> "—"
        }
    }

    /** Equivalent full cycles represented by a day's discharged percentage points. */
    fun equivalentCycles(dischargedPct: Double): Double = dischargedPct / 100.0

    // ── Parameter status (traffic-light color coding) ──────────────────────────
    /** A parameter's health band, mapped to a color at the UI layer. */
    enum class Level { GOOD, WATCH, BAD, INFO }

    /** A status band plus a short human note for a live parameter. */
    data class Reading(val level: Level, val note: String)

    /** Terminal-voltage status: healthy mid-range, watch when unusually low/high. */
    fun voltageReading(v: Float?): Reading = when {
        v == null -> Reading(Level.INFO, "—")
        v < 3.40f -> Reading(Level.WATCH, "Low")
        v > 4.42f -> Reading(Level.WATCH, "High")
        else -> Reading(Level.GOOD, "Healthy")
    }

    /** Current status: charging is good; a heavy sustained draw is worth watching. */
    fun currentReading(currentUa: Long?, status: String): Reading = when {
        currentUa == null -> Reading(Level.INFO, "—")
        status == "Charging" -> Reading(Level.GOOD, "Charging")
        kotlin.math.abs(currentUa / 1000) >= 1500 -> Reading(Level.WATCH, "Heavy draw")
        else -> Reading(Level.INFO, "On battery")
    }

    /** Instantaneous power draw band. */
    fun powerReading(watts: Double?): Reading = when {
        watts == null -> Reading(Level.INFO, "—")
        watts >= 6.0 -> Reading(Level.WATCH, "High")
        watts >= 2.5 -> Reading(Level.INFO, "Moderate")
        else -> Reading(Level.GOOD, "Low")
    }

    /** Temperature band mapped to the shared status levels. */
    fun tempLevel(band: TempBand): Level = when (band) {
        TempBand.COOL -> Level.INFO
        TempBand.NORMAL -> Level.GOOD
        TempBand.WARM -> Level.WATCH
        TempBand.HOT -> Level.BAD
        TempBand.UNKNOWN -> Level.INFO
    }

    /**
     * Net discharge rate in percentage points per hour from a series of (timestampMs,
     * level) samples ordered oldest-first. Trims to the most recent continuous discharge
     * run — everything up to and including the last point where the level rose (a charge
     * event) is dropped — so a mid-window top-up cannot flatten the slope and inflate the
     * runtime. Returns null when that run is shorter than [minSpanMs] or shows no drop.
     */
    fun dischargeRatePctPerHour(samples: List<Pair<Long, Int>>, minSpanMs: Long = 5 * 60_000L): Double? {
        if (samples.size < 2) return null
        var startIdx = 0
        for (i in 1 until samples.size) {
            if (samples[i].second > samples[i - 1].second) startIdx = i   // a charge event
        }
        val run = samples.subList(startIdx, samples.size)
        if (run.size < 2) return null
        val first = run.first()
        val last = run.last()
        val spanMs = last.first - first.first
        if (spanMs < minSpanMs) return null
        val dropped = first.second - last.second   // positive while discharging
        if (dropped <= 0) return null
        return dropped / (spanMs / 3_600_000.0)
    }

    /** Milliseconds of runtime left for [currentLevel]% at [ratePctPerHour], or null. */
    fun runtimeLeftMs(currentLevel: Int, ratePctPerHour: Double?): Long? {
        if (ratePctPerHour == null || ratePctPerHour <= 0.0 || currentLevel <= 0) return null
        return ((currentLevel / ratePctPerHour) * 3_600_000.0).toLong()
    }

    /**
     * Instantaneous runtime estimate from the fuel-gauge draw: remaining charge ÷
     * present current. Only meaningful while discharging; null otherwise. Noisier than
     * the level-slope method, so it is used only as a first-run fallback.
     */
    fun instantaneousRuntimeMs(chargeCounterUah: Long?, currentUa: Long?, status: String): Long? {
        if (status != "Discharging") return null   // exclude Charging/Full/Not charging
        if (chargeCounterUah == null || chargeCounterUah <= 0 || currentUa == null) return null
        val drawUa = kotlin.math.abs(currentUa)
        if (drawUa <= 0L) return null
        return ((chargeCounterUah.toDouble() / drawUa) * 3_600_000.0).toLong()
    }

    /** Distribute charge-level samples into ten 10%-wide bands (0–10 … 90–100). */
    fun levelDistribution(levels: List<Int>): List<Int> {
        val bands = IntArray(10)
        for (l in levels) bands[(l.coerceIn(0, 100) / 10).coerceAtMost(9)]++
        return bands.toList()
    }
}
