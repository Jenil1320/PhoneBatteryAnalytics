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
     * @param physicsLossFrac raw (unclamped) model capacity-loss fraction at a given
     *        total cycle count — supplied by the caller from [DegradationModel]. Must be
     *        monotone non-decreasing in cycles.
     */
    fun forecast(
        samples: List<Sample>,
        cyclesNow: Double,
        nowMs: Long,
        physicsLossFrac: (cyclesTotal: Double) -> Double,
        halfLifeDays: Double = 75.0,
    ): Forecast {
        val clean = samples.filter { it.sohPct in 1.0..100.0 && it.cycles >= 0.0 }
        val cpd = cyclesPerDay(clean)

        // Physics floor — always available from the model.
        val physicsEtaUnscaled = physicsCrossMs(1.0, cyclesNow, cpd, nowMs, physicsLossFrac)

        if (clean.size < 3 || cpd == null) {
            return Forecast(
                if (physicsEtaUnscaled != null) Method.PHYSICS_ONLY else Method.NONE,
                physicsEtaUnscaled, null, null, null, null, null,
                physicsCurve(cyclesNow, cpd, nowMs, physicsEtaUnscaled, physicsLossFrac),
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
            val m = physicsLossFrac(clean[i].cycles)
            val l = (100.0 - clean[i].sohPct) / 100.0
            num += w[i] * m * l; den += w[i] * m * m
        }
        val scale = if (den > 1e-12) (num / den).coerceIn(0.2, 5.0) else null

        val fadePerYear = if (fit.b < 0) -fit.b * cpd * 365.25 else 0.0

        val etaTrend = crossDate(cross(fit.a, fit.b), cyclesNow, cpd, nowMs)
        val etaFast = crossDate(cross(fit.a, fit.b - 1.3 * fit.slopeSe), cyclesNow, cpd, nowMs)
        val etaSlow = crossDate(cross(fit.a, fit.b + 1.3 * fit.slopeSe), cyclesNow, cpd, nowMs)
        val etaPhysics = physicsCrossMs(scale ?: 1.0, cyclesNow, cpd, nowMs, physicsLossFrac)

        // Report the earlier of trend and physics crossings (knee-optimism guard),
        // then keep the likely range bracketing it (physics can bind earlier than the
        // trend's fast bound).
        val eta = listOfNotNull(etaTrend, etaPhysics).minOrNull()
        val lo = listOfNotNull(etaFast, eta).minOrNull()
        val hi = listOfNotNull(etaSlow, eta).maxOrNull()

        val curve = if (fit.b < -1e-5)
            trendCurve(fit, cyclesNow, cpd, nowMs, eta)
        else physicsCurve(cyclesNow, cpd, nowMs, eta, physicsLossFrac)

        return Forecast(Method.TREND, eta, lo, hi, fadePerYear, scale, fit.r2, curve)
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

    private fun cyclesPerDay(samples: List<Sample>): Double? {
        if (samples.size < 2) return null
        val s = samples.sortedBy { it.epochMs }
        val days = (s.last().epochMs - s.first().epochMs) / DAY
        val dc = s.last().cycles - s.first().cycles
        return if (days > 0.5 && dc > 0.0) dc / days else null
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
        val end = etaMs ?: (nowMs + (MAX_HORIZON_DAYS.coerceAtMost(365.0) * DAY).toLong())
        if (end <= nowMs) return emptyList()
        val out = ArrayList<Pair<Long, Double>>()
        val steps = 10
        for (i in 0..steps) {
            val t = nowMs + (end - nowMs) * i / steps
            val cycles = cyclesNow + cpd * ((t - nowMs) / DAY)
            out.add(t to (fit.a + fit.b * cycles).coerceIn(0.0, 100.0))
        }
        return out
    }

    private fun physicsCurve(cyclesNow: Double, cpd: Double?, nowMs: Long, etaMs: Long?, lossFn: (Double) -> Double): List<Pair<Long, Double>> {
        if (cpd == null || etaMs == null || etaMs <= nowMs) return emptyList()
        val out = ArrayList<Pair<Long, Double>>()
        val steps = 10
        for (i in 0..steps) {
            val t = nowMs + (etaMs - nowMs) * i / steps
            val cycles = cyclesNow + cpd * ((t - nowMs) / DAY)
            out.add(t to (100.0 * (1.0 - lossFn(cycles))).coerceIn(0.0, 100.0))
        }
        return out
    }
}
