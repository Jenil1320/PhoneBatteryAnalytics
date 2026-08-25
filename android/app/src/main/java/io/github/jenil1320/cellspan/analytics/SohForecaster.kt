package io.github.jenil1320.cellspan.analytics

import kotlin.math.sqrt

/**
 * On-device State-of-Health forecast.
 *
 * A recency-weighted least-squares trend of measured SoH against charge cycles —
 * a plain regression, which is a textbook machine-learning method but runs as a
 * few lines of arithmetic on-device (no model file, no cloud). It is cross-checked
 * against the physics [DegradationModel] and projected to the 80% replacement line,
 * reported as a date with an honest "likely range". Pure JVM math, so it is fully
 * unit-testable off-device. We surface it as "on-device trend analysis / projection",
 * never as an exact date or "AI".
 *
 * Design follows the on-device forecasting research: half-life recency weighting,
 * a physics floor when data is sparse, the earlier of the trend and physics crossings
 * (to avoid being optimistic before the end-of-life "knee"), and a range from the
 * slope's standard error.
 */
object SohForecaster {

    /** One measured health point. [quality] downweights the noisy single-reading estimate. */
    data class Sample(val sohPct: Double, val cycles: Double, val epochMs: Long, val quality: Double = 1.0)

    enum class Method { NONE, PHYSICS_ONLY, TREND }

    data class Forecast(
        val method: Method,
        val etaDateMs: Long?,        // projected date at 80% (earlier of trend / physics)
        val etaLowMs: Long?,         // "likely range" near end (faster fade)
        val etaHighMs: Long?,        // "likely range" far end (slower fade)
        val fadePerYearPct: Double?, // projected fade rate, percentage points per year
        val scaleVsModel: Double?,   // s > 1 aging faster than typical, s < 1 slower
        val r2: Double? = null,      // goodness of fit of the trend line (0..1)
        val curve: List<Pair<Long, Double>>, // forecast SoH points from now to eta, for the chart
    )

    private const val DAY = 86_400_000.0
    private const val REPLACE = 80.0
    private const val MAX_HORIZON_DAYS = 5 * 365.25

    /**
     * Fractional uncertainty on the measured-versus-model scale factor, used to bracket
     * the physics-only projection. 25%: the scale is a weighted ratio over a handful of
     * health points whose own error bars run to several percent, and the model beneath it
     * carries a documented bias from being fitted to a 2013 cell.
     */
    private const val PHYSICS_SCALE_BAND = 0.25

    /**
     * Minimum span the cycles-per-day rate may be measured over.
     *
     * The cycle counter is an integer, so over half a day it typically moves by 0 or 1 —
     * a 100% quantisation error, and the projected replacement date is directly
     * proportional to it. Seven days is where that error falls to something a date in
     * months can survive.
     */
    private const val MIN_RATE_SPAN_DAYS = 7.0

    /**
     * @param physicsLossFrac raw (unclamped) model capacity-loss fraction at a given
     *        cycle count AND the battery age that goes with it — supplied by the caller
     *        from [DegradationModel]. Must be monotone non-decreasing in cycles.
     *
     *        The age is the second parameter for a reason. The model has two terms and they
     *        live on different axes: the cyclic term is a function of cycles, the calendar
     *        term of days. Only this class knows how the two relate, because only it holds
     *        the measured cycles-per-day. When the caller worked the days out for itself
     *        from a *different* rate — the lifetime average rather than the recent one —
     *        the two axes disagreed and the bisection crossed the 80% line at the wrong
     *        date. On a phone whose recent use is heavier than its lifetime average that
     *        pushed the replacement date years too early.
     * @param ageDaysNow battery age today, or null when it is unknown. Passing it lets the
     *        calendar term be evaluated at the age each point actually had, instead of at
     *        today's age for every point — which inflated the modelled loss for older
     *        samples and biased the measured-versus-model ratio toward "aging slower".
     */
    fun forecast(
        samples: List<Sample>,
        cyclesNow: Double,
        nowMs: Long,
        physicsLossFrac: (cyclesTotal: Double, ageDays: Double) -> Double,
        ageDaysNow: Double? = null,
        halfLifeDays: Double = 75.0,
    ): Forecast {
        val clean = samples.filter { it.sohPct in 1.0..100.0 && it.cycles >= 0.0 }
        val cpd = cyclesPerDay(clean)

        /**
         * The battery's age at a given cycle count, on the SAME rate used to turn cycles
         * into dates everywhere else in this class. Without a known age or rate there is
         * nothing to offset, so today's age stands.
         */
        fun ageAt(cycles: Double): Double {
            val now = ageDaysNow ?: 0.0
            if (cpd == null || cpd <= 0.0) return now
            return (now + (cycles - cyclesNow) / cpd).coerceAtLeast(0.0)
        }
        val loss = { cycles: Double -> physicsLossFrac(cycles, ageAt(cycles)) }

        // Physics floor — always available from the model.
        val physicsEtaUnscaled = physicsCrossMs(1.0, cyclesNow, cpd, nowMs, loss)

        if (clean.size < 3 || cpd == null) {
            return Forecast(
                if (physicsEtaUnscaled != null) Method.PHYSICS_ONLY else Method.NONE,
                physicsEtaUnscaled, null, null, null, null, null,
                physicsCurve(1.0, cyclesNow, cpd, nowMs, physicsEtaUnscaled, loss),
            )
        }

        // Recency + quality weights.
        val w = DoubleArray(clean.size) { i ->
            val ageDays = (nowMs - clean[i].epochMs) / DAY
            Math.pow(2.0, -ageDays / halfLifeDays) * clean[i].quality.coerceIn(0.1, 1.0)
        }

        val fit = wls(clean.map { it.cycles }, clean.map { it.sohPct }, w)

        // Physics scale s: measured loss vs model loss (weighted, through the origin).
        var num = 0.0; var den = 0.0
        for (i in clean.indices) {
            val m = loss(clean[i].cycles)
            val l = (100.0 - clean[i].sohPct) / 100.0
            num += w[i] * m * l; den += w[i] * m * m
        }
        val scale = if (den > 1e-12) (num / den).coerceIn(0.2, 5.0) else null

        // The recency-weighted trend is only trustworthy when it actually explains the
        // data. With a short or noisy (estimate-only) history the fit is essentially
        // flat noise (R² ≈ 0); extrapolating its slope linearly would invent an alarming,
        // wrong fade rate. When the fit is weak, fall back to the physics model's own
        // sub-linear projection, which is far more honest for an already-aged cell.
        val reliableTrend = fit.r2 >= 0.25 && fit.b < -1e-5

        // Physics-projected loss over the next year (percentage points), scaled to the
        // user's measured-vs-model ratio. Sub-linear, so it stays small for a worn cell.
        val physicsFadePerYear = (
            (loss(cyclesNow + cpd * 365.25) - loss(cyclesNow)) * 100.0 * (scale ?: 1.0)
            ).coerceAtLeast(0.0)
        val etaPhysics = physicsCrossMs(scale ?: 1.0, cyclesNow, cpd, nowMs, loss)

        val fadePerYear: Double
        val eta: Long?
        val lo: Long?
        val hi: Long?
        val curve: List<Pair<Long, Double>>
        if (reliableTrend) {
            fadePerYear = -fit.b * cpd * 365.25
            val etaTrend = crossDate(cross(fit.a, fit.b), cyclesNow, cpd, nowMs)
            val etaFast = crossDate(cross(fit.a, fit.b - 1.3 * fit.slopeSe), cyclesNow, cpd, nowMs)
            val etaSlow = crossDate(cross(fit.a, fit.b + 1.3 * fit.slopeSe), cyclesNow, cpd, nowMs)
            // Earlier of trend and physics crossings (knee-optimism guard); range brackets it.
            eta = listOfNotNull(etaTrend, etaPhysics).minOrNull()
            lo = listOfNotNull(etaFast, eta).minOrNull()
            hi = listOfNotNull(etaSlow, eta).maxOrNull()
            curve = trendCurve(fit, cyclesNow, cpd, nowMs, eta)
        } else {
            fadePerYear = physicsFadePerYear
            eta = etaPhysics
            // The physics path used to publish a bare date with no range at all, which
            // read as more certain than the trend path that at least showed a bracket —
            // exactly backwards, since this branch is taken *because* the data was too
            // weak to fit. The range comes from the scale factor's own uncertainty:
            // re-crossing at scale × (1 ± PHYSICS_SCALE_BAND) brackets a wear rate that
            // is that much faster or slower than measured.
            val s = scale ?: 1.0
            lo = physicsCrossMs(s * (1.0 + PHYSICS_SCALE_BAND), cyclesNow, cpd, nowMs, loss)
            hi = physicsCrossMs(s * (1.0 - PHYSICS_SCALE_BAND), cyclesNow, cpd, nowMs, loss)
            curve = physicsCurve(s, cyclesNow, cpd, nowMs, eta, loss)
        }

        return Forecast(
            if (reliableTrend) Method.TREND else Method.PHYSICS_ONLY,
            eta, lo, hi, fadePerYear, scale, fit.r2, curve,
        )
    }

    // ── math helpers (pure) ────────────────────────────────────────────────────
    data class Line(val a: Double, val b: Double, val slopeSe: Double, val r2: Double)

    /** Weighted least-squares y ~ a + b·x, with the slope's standard error and R². */
    fun wls(xs: List<Double>, ys: List<Double>, w: DoubleArray): Line {
        var wSum = 0.0; var sx = 0.0; var sy = 0.0
        for (i in xs.indices) { wSum += w[i]; sx += w[i] * xs[i]; sy += w[i] * ys[i] }
        if (wSum <= 0.0) return Line(if (ys.isNotEmpty()) ys.first() else 0.0, 0.0, Double.MAX_VALUE, 0.0)
        val xbar = sx / wSum; val ybar = sy / wSum
        var sxx = 0.0; var sxy = 0.0
        for (i in xs.indices) { val dx = xs[i] - xbar; sxx += w[i] * dx * dx; sxy += w[i] * dx * (ys[i] - ybar) }
        val b = if (sxx > 1e-12) sxy / sxx else 0.0
        val a = ybar - b * xbar
        var ssr = 0.0; var w2 = 0.0; var sst = 0.0
        for (i in xs.indices) {
            val r = ys[i] - (a + b * xs[i]); ssr += w[i] * r * r; w2 += w[i] * w[i]
            val d = ys[i] - ybar; sst += w[i] * d * d
        }
        val nEff = if (w2 > 0) (wSum * wSum) / w2 else xs.size.toDouble()
        val s2 = if (nEff > 2) ssr / (nEff - 2) else ssr
        val se = if (sxx > 1e-12) sqrt(s2 / sxx) else Double.MAX_VALUE
        val r2 = if (sst > 1e-12) (1.0 - ssr / sst).coerceIn(0.0, 1.0) else 0.0
        return Line(a, b, se, r2)
    }

    private fun cross(a: Double, b: Double): Double? = if (b < -1e-5) (REPLACE - a) / b else null

    private fun crossDate(cyclesAt80: Double?, cyclesNow: Double, cpd: Double, nowMs: Long): Long? {
        if (cyclesAt80 == null || cpd <= 0.0) return null
        val dCycles = cyclesAt80 - cyclesNow
        if (dCycles <= 0.0) return nowMs
        val days = dCycles / cpd
        if (days > MAX_HORIZON_DAYS) return null
        return nowMs + (days * DAY).toLong()
    }

    /**
     * How many charge cycles this phone accumulates per day.
     *
     * The whole replacement date scales linearly with this one number, so the span it is
     * measured over has to be long enough for the integer cycle counter to have moved
     * meaningfully. The old floor was half a day, over which the counter moves by 0 or 1
     * — so a user who happened to complete one cycle in twelve hours was projected at
     * two cycles a day, roughly double reality, and the date came out roughly half as far
     * away as it should. [MIN_RATE_SPAN_DAYS] fixes the floor at a week.
     */
    private fun cyclesPerDay(samples: List<Sample>): Double? {
        if (samples.size < 2) return null
        val s = samples.sortedBy { it.epochMs }
        val days = (s.last().epochMs - s.first().epochMs) / DAY
        val dc = s.last().cycles - s.first().cycles
        return if (days >= MIN_RATE_SPAN_DAYS && dc > 0.0) dc / days else null
    }

    /** Bisection: date at which scale·lossFn(cycles) crosses the 20% loss line. */
    private fun physicsCrossMs(
        scale: Double, cyclesNow: Double, cpd: Double?, nowMs: Long, lossFn: (Double) -> Double,
    ): Long? {
        if (cpd == null || cpd <= 0.0) return null
        var lo = cyclesNow
        var hi = cyclesNow + cpd * MAX_HORIZON_DAYS
        if (scale * lossFn(hi) < 0.20) return null            // won't reach 80% within horizon
        if (scale * lossFn(lo) >= 0.20) return nowMs           // already there
        repeat(40) { val mid = (lo + hi) / 2; if (scale * lossFn(mid) < 0.20) lo = mid else hi = mid }
        val days = (hi - cyclesNow) / cpd
        return nowMs + (days * DAY).toLong()
    }

    private fun trendCurve(fit: Line, cyclesNow: Double, cpd: Double, nowMs: Long, etaMs: Long?): List<Pair<Long, Double>> {
        // Always project at least ~6 months so the chart has a visible line, even when
        // the battery is already at/near the 80% line (eta ≈ now).
        val minEnd = nowMs + (180.0 * DAY).toLong()
        val end = maxOf(etaMs ?: (nowMs + (365.0 * DAY).toLong()), minEnd)
        val out = ArrayList<Pair<Long, Double>>()
        val steps = 10
        for (i in 0..steps) {
            val t = nowMs + (end - nowMs) * i / steps
            val cycles = cyclesNow + cpd * ((t - nowMs) / DAY)
            out.add(t to (fit.a + fit.b * cycles).coerceIn(0.0, 100.0))
        }
        return out
    }

    /**
     * [scale] must be the same measured-vs-model ratio used for the projected date, or the
     * drawn line would miss the 80% guide it is labelled with.
     */
    private fun physicsCurve(scale: Double, cyclesNow: Double, cpd: Double?, nowMs: Long, etaMs: Long?, lossFn: (Double) -> Double): List<Pair<Long, Double>> {
        if (cpd == null) return emptyList()
        // Project to eta, but always at least ~6 months so the chart shows a line.
        val minEnd = nowMs + (180.0 * DAY).toLong()
        val end = maxOf(etaMs ?: (nowMs + (365.0 * DAY).toLong()), minEnd)
        val out = ArrayList<Pair<Long, Double>>()
        val steps = 10
        for (i in 0..steps) {
            val t = nowMs + (end - nowMs) * i / steps
            val cycles = cyclesNow + cpd * ((t - nowMs) / DAY)
            out.add(t to (100.0 * (1.0 - scale * lossFn(cycles))).coerceIn(0.0, 100.0))
        }
        return out
    }
}
