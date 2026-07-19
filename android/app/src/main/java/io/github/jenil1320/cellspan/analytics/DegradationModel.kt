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
 *   (DOI 10.1016/j.jpowsour.2013.09.143).
 * Of the SimSES parameter sets it is the closest to a consumer LCO/NMC phone pouch
 * cell: NMC/graphite, a sloped voltage curve, and — unusually — parameterised in
 * cell *voltage*, which a phone actually measures. It is calibrated to a 4.2 V-max
 * 18650, so for modern phones charging to 4.4–4.45 V the numbers are approximate;
 * we present the output as "typical/modeled", never as a manufacturer figure.
 *
 * ## Calibration cross-check
 * With a typical phone mean voltage (~3.7 V) and depth-of-discharge, the cyclic
 * term alone predicts ≈19–20% capacity loss at 800 equivalent full cycles — which
 * matches Google's published Pixel rating of "up to 80% capacity for ~800 cycles".
 */
object DegradationModel {

    // Fixed constants from the model / SimSES source.
    private const val DEFAULT_CYCLE_V = 3.7    // mean cell voltage over a phone cycle
    private const val DEFAULT_REST_V = 3.8     // mean rest (OCV) voltage for calendar
    private const val DEFAULT_DOD = 0.7        // representative depth of discharge
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
        // Cyclic capacity loss (per-unit): β_cap · sqrt(Ah throughput). Schmalstieg's
        // β_cap wants the MEAN cell voltage over a cycle (~3.7 V for a phone spanning
        // its charge range), whose parabola bottoms at 3.667 V. We deliberately use a
        // fixed representative cycle-mean rather than a measured average: the recent
        // sample average is dominated by whatever state the phone has been in (e.g.
        // ~4.3 V while charging), which is a calendar-at-high-SoC condition, not the
        // cycle mean — feeding it here would wildly over-predict cyclic wear.
        var cyclic = 0.0
        if (equivalentFullCycles != null && equivalentFullCycles > 0 && designCapacityMah != null && designCapacityMah > 0) {
            val v = DEFAULT_CYCLE_V
            val dod = (avgDoD ?: DEFAULT_DOD).coerceIn(0.0, 1.0)
            val beta = 7.348e-3 * (v - 3.667).pow(2) + 7.6e-4 + 4.081e-3 * dod
            val throughputAh = equivalentFullCycles * (designCapacityMah / 1000.0)
            cyclic = beta * sqrt(throughputAh)
        }

        // Calendar capacity loss (per-unit): α_cap · days^0.75, only if age is known.
        // Uses a fixed representative rest voltage (see note above) and the measured
        // mean temperature — temperature is legitimate here, since calendar aging
        // genuinely accrues at whatever temperature the cell sits at.
        var calendar = 0.0
        var hasCalendar = false
        if (ageDays != null && ageDays > 0) {
            val tK = (avgTempC ?: DEFAULT_TEMP_C) + 273.15
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
        )
    }
}
