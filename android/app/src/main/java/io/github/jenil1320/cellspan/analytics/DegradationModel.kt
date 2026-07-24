package io.github.jenil1320.cellspan.analytics

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Semi-empirical calendar + cyclic degradation model, giving a *modeled* (typical)
 * State of Health to sit beside the measured one. This answers "is my battery aging
 * normally?" — if the measured value tracks below the model, the cell is wearing
 * faster than a typical one; above, it is doing better.
 *
 * ## Provenance
 * The equations and coefficients are the **Schmalstieg holistic NMC model** as
 * implemented in SimSES (`nmc_sanyo_ur18650e`), the TU München open-source storage
 * simulator, itself a transcription of:
 *   Schmalstieg, Käbitz, Ecker, Sauer, "A holistic aging model for Li(NiMnCo)O2
 *   based 18650 lithium-ion batteries", J. Power Sources 257 (2014) 325–334
 *   (DOI 10.1016/j.jpowsour.2014.02.012).
 * Of the SimSES parameter sets it is the closest to a consumer LCO/NMC phone pouch
 * cell: NMC/graphite, a sloped voltage curve, and — unusually — parameterised in
 * cell *voltage*, which a phone actually measures. It is calibrated to a 4.2 V-max
 * 18650, so for modern phones charging to 4.4–4.45 V the numbers are approximate;
 * we present the output as "typical/modeled", never as a manufacturer figure.
 *
 * ## What the output is worth
 * At 800 equivalent full cycles on a 4355 mAh cell the cyclic term alone gives about
 * 21% loss. That figure is NOT a match for any manufacturer rating and must not be
 * treated as one: an OEM's "80% at 800 cycles" is a whole-life field number that
 * already contains years of calendar ageing, so anchoring the cyclic term to it and
 * then adding a calendar term on top would double-count.
 *
 * Two honest limits:
 *  - β carries units of Ah^-0.5 and was fitted to a single 2.05 Ah cell. Throughput is
 *    not normalised by cell size (SimSES does not either), so predicted fractional loss
 *    at a fixed cycle count grows with the square root of battery capacity: ~17.8% at
 *    3000 mAh, ~21.4% at 4356 mAh, ~25.1% at 6000 mAh. Absolute output is only
 *    calibrated near the reference cell size.
 *  - The coefficients come from a 2013 cell. Applied to a modern phone pouch cell they
 *    run pessimistic; the residual is real and is reported, not tuned away.
 */
object DegradationModel {

    // Model coefficients are from the paper. These four INPUTS are not: they are
    // representative values chosen for a phone, and they carry most of the result.
    private const val DEFAULT_CYCLE_V = 3.7    // mean cell voltage over a phone cycle
    private const val DEFAULT_REST_V = 3.8     // mean rest (OCV) voltage for calendar
    // Depth of discharge dominates the cyclic term (~79% of beta). The published 0.7 is
    // an EV/grid-storage figure; phones are topped up through the day and cycle much
    // shallower, so 0.7 over-predicts wear badly. This is only the fallback — the app
    // measures the real value from recorded discharge sessions when it has enough.
    private const val DEFAULT_DOD = 0.5
    private const val DEFAULT_TEMP_C = 25.0

    data class Result(
        /** Modeled State of Health, %, = 100 − (calendar + cyclic) loss. */
        val modeledSohPct: Double,
        /** Calendar (time/temperature/voltage) contribution to loss, percentage points. */
        val calendarLossPct: Double,
        /** Cyclic (throughput) contribution to loss, percentage points. */
        val cyclicLossPct: Double,
        /** True when battery age was known, so the calendar term is included. */
        val hasCalendar: Boolean,
        /** Depth of discharge actually used, so the UI can show the assumption. */
        val dodUsed: Double,
        /** Mean temperature actually used for the calendar term, °C. */
        val tempUsedC: Double,
    )

    /**
     * @param equivalentFullCycles device-reported cycle count (equivalent full cycles).
     * @param designCapacityMah design capacity, to turn cycles into Ah throughput.
     * @param avgDoD representative depth of discharge as a fraction (0–1).
     * @param ageDays battery age in days; when null the calendar term is omitted
     *        (a sandboxed app usually cannot read the manufacture date).
     * @param avgTempC mean battery temperature for the calendar Arrhenius term.
     * Returns null when neither a cyclic nor a calendar term can be computed.
     *
     * Note: cell voltage is deliberately NOT taken from measured data. Both terms
     * want a mean voltage the phone cannot cleanly observe — the cycle mean for the
     * cyclic term, the rest/storage mean for the calendar term — and the recent
     * sample average is dominated by charging voltage (~4.3–4.45 V), which pushes
     * both stress functions far off. We use fixed representative values instead.
     */
    fun estimate(
        equivalentFullCycles: Double?,
        designCapacityMah: Int?,
        avgDoD: Double? = null,
        ageDays: Double? = null,
        avgTempC: Double? = null,
    ): Result? {
        // Cyclic capacity loss (per-unit) comes from [cyclicLossFrac], which owns the one
        // transcription of the term. Clamp the DoD here as well, because it is reported
        // as dodUsed even on the calendar-only path where no cyclic term is computed.
        val dod = (avgDoD ?: DEFAULT_DOD).coerceIn(0.05, 1.0)
        val tempC = avgTempC ?: DEFAULT_TEMP_C
        val cyclic = cyclicLossFrac(equivalentFullCycles ?: 0.0, designCapacityMah, dod)

        // Calendar capacity loss (per-unit): α_cap · days^0.75, only if age is known.
        // Uses a fixed representative rest voltage (see note above) and the measured
        // mean temperature — temperature is legitimate here, since calendar aging
        // genuinely accrues at whatever temperature the cell sits at.
        var calendar = 0.0
        var hasCalendar = false
        if (ageDays != null && ageDays > 0) {
            val tK = tempC + 273.15
            val v = DEFAULT_REST_V
            val alpha = (7.543 * v - 23.75) * 1e6 * exp(-6976.0 / tK)
            calendar = (alpha * ageDays.pow(0.75)).coerceAtLeast(0.0)
            hasCalendar = true
        }

        if (cyclic <= 0.0 && !hasCalendar) return null

        val totalLoss = (calendar + cyclic).coerceIn(0.0, 1.0)
        return Result(
            modeledSohPct = 100.0 * (1.0 - totalLoss),
            calendarLossPct = calendar * 100.0,
            cyclicLossPct = cyclic * 100.0,
            hasCalendar = hasCalendar,
            dodUsed = dod,
            tempUsedC = tempC,
        )
    }

    /**
     * Raw (unclamped) cyclic capacity-loss fraction: β_cap · sqrt(Ah throughput). This is
     * the ONLY transcription of the cyclic term — [estimate] calls it too, so the reported
     * figure and the forecaster's projection cannot drift apart. Monotone increasing in
     * cycles, so the SoH forecaster can bisect it to find the projected 80% crossing.
     *
     * Schmalstieg's β_cap wants the MEAN cell voltage over a cycle (~3.7 V for a phone
     * spanning its charge range), whose parabola bottoms at 3.667 V. We deliberately use a
     * fixed representative cycle-mean rather than a measured average: the recent sample
     * average is dominated by whatever state the phone has been in (e.g. ~4.3 V while
     * charging), which is a calendar-at-high-SoC condition, not the cycle mean — feeding
     * it here would wildly over-predict cyclic wear.
     */
    fun cyclicLossFrac(equivalentFullCycles: Double, designCapacityMah: Int?, avgDoD: Double? = null): Double {
        if (equivalentFullCycles <= 0.0 || designCapacityMah == null || designCapacityMah <= 0) return 0.0
        val v = DEFAULT_CYCLE_V
        val dod = (avgDoD ?: DEFAULT_DOD).coerceIn(0.05, 1.0)
        val beta = 7.348e-3 * (v - 3.667).pow(2) + 7.6e-4 + 4.081e-3 * dod
        val throughputAh = equivalentFullCycles * (designCapacityMah / 1000.0)
        return beta * sqrt(throughputAh)
    }
}
