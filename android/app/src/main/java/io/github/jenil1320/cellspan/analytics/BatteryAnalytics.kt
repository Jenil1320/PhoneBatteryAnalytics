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
    /**
     * Half-width of the State-of-Health figure's uncertainty, in health points, or null
     * when nothing is known. See [sohUncertaintyPts] for where each value comes from.
     */
    val sohUncertaintyPts: Double? = null,
    /** How much the individual session measurements disagree, as a percent. */
    val sessionSpreadPct: Double? = null,
)

/** How much a State-of-Health figure can be trusted, for a one-word label in the UI. */
enum class Confidence { NONE, LOW, MEDIUM, HIGH }

/**
 * Pure battery health math — no Android dependencies, so it is fully unit
 * testable on the JVM. Every function that needs "now" takes it as a parameter.
 */
object BatteryAnalytics {

    const val REPLACE_SOH = 80.0
    const val REPLACE_CYCLES = 800

    /**
     * How close to design capacity a gauge reading has to sit before it is suspected of
     * being a stub rather than a measurement. 2% — a real cell drifts below that within
     * its first few dozen cycles, so a gauge still sitting there while sessions measure
     * genuine wear is reporting the design figure, not the present one.
     */
    private const val GAUGE_STUB_TOLERANCE = 0.02

    /**
     * Shortest battery age from which a per-month or per-year rate may be derived. A month
     * is where the arithmetic stops amplifying a short span into a nonsense figure.
     */
    private const val MIN_DAYS_FOR_LIFETIME_RATES = 30.0

    /**
     * Above this, a fade rate is not a measurement of anything. A cell losing 60 points of
     * health in a year is already far past replacement, so a larger figure means the age or
     * the health reading is wrong rather than that the battery is dying that fast.
     */
    private const val MAX_PLAUSIBLE_FADE_PER_YEAR = 60.0

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

    /**
     * The error bar on a State-of-Health figure, in health points, given where it came
     * from and how much evidence is behind it.
     *
     * Every source has a different and knowable uncertainty, and the app used to present
     * all of them as one confident integer:
     *
     *  - **GAUGE** — the cell's own fuel gauge. Manufacturers quote roughly ±1–3% on
     *    reported full-charge capacity, so ±1.5 points.
     *  - **DEVICE** — the platform's own State of Health, an integer, so ±0.5 from
     *    rounding alone plus whatever the OEM's method costs. ±2 points.
     *  - **SESSION** — measured from real charge sessions. Here the uncertainty is
     *    *observed* rather than assumed: it is the scatter between sessions, divided by
     *    the square root of how many there are, because averaging independent
     *    measurements reduces their error that way. A floor of 1 point stops a lucky pair
     *    of agreeing sessions claiming impossible precision.
     *  - **ESTIMATE** — remaining charge ÷ a level quantised to whole percent. At 40% the
     *    quantisation alone is about 1/40, so ±2.5%, and gauge noise sits on top: ±6
     *    points, which is why it is shown with a tilde and never drives a verdict.
     *
     * @param designIsTypical true when the design capacity came from the published table
     *        rather than this phone. The table is accurate to a few percent, and that
     *        error lands directly on the ratio, so it is added in quadrature.
     */
    fun sohUncertaintyPts(
        source: CapacitySource,
        sessionCount: Int,
        sessionSpreadPct: Double?,
        designIsTypical: Boolean,
    ): Double? {
        val measurement = when (source) {
            CapacitySource.GAUGE -> 1.5
            CapacitySource.DEVICE -> 2.0
            CapacitySource.SESSION -> {
                val n = sessionCount.coerceAtLeast(1)
                val spread = sessionSpreadPct ?: 6.0     // a lone session: assume it is noisy
                (spread / kotlin.math.sqrt(n.toDouble())).coerceAtLeast(1.0)
            }
            CapacitySource.ESTIMATE -> 6.0
            CapacitySource.NONE -> return null
        }
        // Independent error sources add as the root of the sum of squares, not linearly.
        val design = if (designIsTypical) 3.0 else 0.0
        return kotlin.math.sqrt(measurement * measurement + design * design)
    }

    /**
     * A one-word verdict on the health figure, judged on its error bar rather than on the
     * source alone. A single shallow charge session used to read as authoritatively as ten
     * deep ones, because "is it a session measurement?" was the only question asked.
     */
    fun confidenceFor(uncertaintyPts: Double?): Confidence = when {
        uncertaintyPts == null -> Confidence.NONE
        uncertaintyPts <= 2.0 -> Confidence.HIGH
        uncertaintyPts <= 4.0 -> Confidence.MEDIUM
        else -> Confidence.LOW
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
        sessionSpreadPct: Double? = null,
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

        // The gauge normally wins — it is the cell's own measurement. But plenty of
        // drivers never update charge_full and simply mirror charge_full_design, which
        // pins health at exactly 100% for the life of the phone. That stub is
        // indistinguishable from a brand-new cell on a single reading, so it is caught by
        // its relationship to the design figure rather than by its value: within
        // GAUGE_STUB_TOLERANCE of design AND contradicted by a real session measurement
        // means the gauge is not measuring anything.
        val gaugeIsStub = currentFromSysfs != null && designMah != null && designMah > 0 &&
            currentFromSysfs >= designMah * (1.0 - GAUGE_STUB_TOLERANCE) &&
            sessionMeasuredMah != null &&
            sessionMeasuredMah < designMah * (1.0 - GAUGE_STUB_TOLERANCE)

        val currentMah: Int?
        val source: CapacitySource
        when {
            currentFromSysfs != null && !gaugeIsStub -> { currentMah = currentFromSysfs; source = CapacitySource.GAUGE }
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
        //
        // The two figures must also come from comparable places. Dividing a measured
        // present capacity by a *typical published* design figure compares this phone's
        // cell against a table entry, and the table is only accurate to a few percent — so
        // when design came from the table the result is a rough ratio, not a measurement.
        // The estimate path can also overshoot. Both cases used to be silently clamped to
        // 100%, which turned "we cannot tell" into a confident, wrong "perfect health".
        val soh: Double? =
            if (currentMah != null && designMah != null && designMah > 0) {
                val raw = currentMah.toDouble() / designMah * 100.0
                when {
                    raw !in 0.0..130.0 -> null                 // clearly bad data
                    // Above 105% something is inconsistent — most often a table design
                    // figure for the wrong variant. Report nothing rather than "100%".
                    raw > 105.0 && designIsFromDatabase -> null
                    else -> raw.coerceAtMost(100.0)
                }
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
                // Lifetime RATES need a lifetime to divide by. The age itself is fine at
                // any span, but dividing by a fraction of a day produced figures the user
                // could only read as broken: a start date set to today — the ordinary flow
                // after a battery replacement — gave "avg fade / year 4383 pts" and
                // "avg cycles / month 98860". Below a month there is no rate worth stating.
                if (days >= MIN_DAYS_FOR_LIFETIME_RATES) {
                    val months = days / DAYS_PER_MONTH
                    s.cycleCount?.let { c -> if (c > 0) cyclesPerMonth = c / months }
                    if (soh != null) {
                        fadePerYear = ((100.0 - soh) / (days / DAYS_PER_YEAR))
                            .takeIf { it in 0.0..MAX_PLAUSIBLE_FADE_PER_YEAR }
                    }
                }
            }
        }

        // Modeled ("typical") health from the semi-empirical degradation model.
        val model = DegradationModel.estimate(
            equivalentFullCycles = s.cycleCount?.toDouble(),
            designCapacityMah = designMah,
            avgDoD = avgDoD,
            ageDays = ageDays,
            avgTempC = lifetimeMeanTempC(avgTempC, s.temperatureC),
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
            sohUncertaintyPts = if (soh == null) null
            else sohUncertaintyPts(source, sessionCount, sessionSpreadPct, designIsFromDatabase),
            sessionSpreadPct = sessionSpreadPct,
        )
    }

    /**
     * Plausible range for a phone battery's *lifetime mean* temperature.
     *
     * Instantaneous readings legitimately run from about 5 °C to 50 °C, but no phone
     * averages either extreme across months. The calendar term is Arrhenius, so it is
     * exponential in 1/T and a wrong mean is not a small error: feeding it a single 45 °C
     * gaming reading in place of a lifetime mean roughly triples the modelled calendar
     * loss and made the app tell a healthy user their cell was aging badly.
     */
    private const val LIFETIME_TEMP_MIN_C = 18.0
    private const val LIFETIME_TEMP_MAX_C = 38.0

    /**
     * The mean temperature to hand the calendar-aging term.
     *
     * Prefers a genuine multi-day average when one exists. Otherwise it falls back to the
     * present reading, but clamped into [LIFETIME_TEMP_MIN_C]..[LIFETIME_TEMP_MAX_C]: a
     * phone that habitually runs warm should shift the model, while one momentary hot
     * reading must not be treated as how the cell has spent its whole life. Null when
     * there is nothing at all, which leaves the model on its own documented default.
     */
    fun lifetimeMeanTempC(measuredMeanC: Double?, instantaneousC: Float?): Double? {
        measuredMeanC?.let { return it.coerceIn(LIFETIME_TEMP_MIN_C, LIFETIME_TEMP_MAX_C) }
        return instantaneousC?.toDouble()?.coerceIn(LIFETIME_TEMP_MIN_C, LIFETIME_TEMP_MAX_C)
    }

    /** Instantaneous power draw/charge in watts (magnitude), or null. */
    fun powerWatts(voltageV: Float?, currentUa: Long?): Double? {
        if (voltageV == null || currentUa == null) return null
        return kotlin.math.abs(voltageV * (currentUa / 1_000_000.0))
    }

    enum class TempBand(val label: String) {
        FREEZING("Freezing"), COOL("Cool"), NORMAL("Normal"), WARM("Warm"), HOT("Hot"), UNKNOWN("—")
    }

    /**
     * Classify battery temperature into a comfort band.
     *
     * FREEZING is a real hazard rather than merely "cold". Below 0 °C lithium plating
     * during charge deposits metallic lithium on the anode instead of intercalating it,
     * which is permanent damage and, at worst, an internal short — which is why phone
     * makers slow or stop charging in the cold. Reporting sub-zero as the same benign
     * "Cool" as a 10 °C morning understated the one temperature condition that can hurt a
     * cell in a single charge.
     */
    fun tempBand(c: Float?): TempBand = when {
        c == null -> TempBand.UNKNOWN
        c < 0f -> TempBand.FREEZING
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

    /**
     * Terminal-voltage status: healthy mid-range, watch when genuinely out of range.
     *
     * The upper bound is 4.50 V, not the 4.42 V it used to be. Phone cells have moved: a
     * 4.2 V maximum was standard a decade ago, but most 2022-and-later flagships charge to
     * 4.45 V and several go to 4.48 V. At 4.42 V the app labelled a perfectly normal full
     * charge "High" on those phones, every time they finished charging — and contradicted
     * its own voltage gauge, which is drawn on a 3.00–4.45 V scale. 4.50 V leaves headroom
     * above the highest shipping charge voltage while still catching a genuine fault.
     *
     * The lower bound stays at 3.40 V: a resting cell there is near empty, and a loaded
     * one that sags below it is being asked for more current than it can comfortably give.
     */
    fun voltageReading(v: Float?): Reading = when {
        v == null -> Reading(Level.INFO, "—")
        v < 3.40f -> Reading(Level.WATCH, "Low")
        v > 4.50f -> Reading(Level.WATCH, "High")
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
        // Sub-zero is as bad as overheating, not merely cold: charging a frozen cell
        // plates metallic lithium on the anode, and that damage does not come back.
        TempBand.FREEZING -> Level.BAD
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

    /**
     * Net charge rate in percentage points per hour from a series of (timestampMs, level)
     * samples ordered oldest-first. The mirror of [dischargeRatePctPerHour]: it trims to
     * the most recent continuous *rise*, so a dip caused by a heavy app while plugged in
     * cannot flatten the slope.
     */
    fun chargeRatePctPerHour(samples: List<Pair<Long, Int>>, minSpanMs: Long = 3 * 60_000L): Double? {
        if (samples.size < 2) return null
        var startIdx = 0
        for (i in 1 until samples.size) {
            if (samples[i].second < samples[i - 1].second) startIdx = i   // a drop: new run
        }
        val run = samples.subList(startIdx, samples.size)
        if (run.size < 2) return null
        val spanMs = run.last().first - run.first().first
        if (spanMs < minSpanMs) return null
        val gained = run.last().second - run.first().second
        if (gained <= 0) return null
        return gained / (spanMs / 3_600_000.0)
    }

    /**
     * State of charge above which lithium-ion charging leaves the constant-current phase.
     * Below this the level climbs at a steady rate; above it the charger holds the voltage
     * and the current tapers, so the same phone takes far longer per point.
     */
    const val CV_KNEE_PCT = 80

    /**
     * How much slower the level climbs above [CV_KNEE_PCT], as a fraction of the
     * constant-current rate.
     *
     * A straight-line projection is the obvious way to estimate time to full and it is
     * badly wrong at the top: charging is constant-current then constant-voltage, and in
     * the constant-voltage phase the current decays roughly exponentially. Across
     * published fast-charge curves the last twenty points take about as long as the thirty
     * before them, which puts the effective rate at a bit under half. 0.45 is that ratio.
     */
    private const val CV_RATE_FACTOR = 0.45

    /**
     * Milliseconds until the battery reaches [targetPct], at the observed
     * [ratePctPerHour], accounting for the constant-voltage slowdown near the top.
     *
     * Returns null when not charging, when the rate is unknown, or when the target is
     * already reached — the UI must show nothing rather than a fabricated countdown.
     */
    fun timeToLevelMs(
        currentLevel: Int,
        targetPct: Int,
        ratePctPerHour: Double?,
        status: String,
    ): Long? {
        if (status != "Charging") return null
        if (ratePctPerHour == null || ratePctPerHour <= 0.0) return null
        if (currentLevel >= targetPct || targetPct !in 1..100) return null

        // The observed rate describes whichever phase the phone is in right now. Recover
        // the constant-current rate from it, then spend it across the two phases.
        val ccRate = if (currentLevel >= CV_KNEE_PCT) ratePctPerHour / CV_RATE_FACTOR else ratePctPerHour

        val ccPoints = (minOf(targetPct, CV_KNEE_PCT) - currentLevel).coerceAtLeast(0)
        val cvPoints = (targetPct - maxOf(currentLevel, CV_KNEE_PCT)).coerceAtLeast(0)
        val hours = ccPoints / ccRate + cvPoints / (ccRate * CV_RATE_FACTOR)
        if (hours <= 0.0 || hours.isNaN() || hours.isInfinite()) return null
        // A projection past a day is not information, it is a broken reading.
        return (hours * 3_600_000.0).toLong().takeIf { it in 1..86_400_000L }
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
