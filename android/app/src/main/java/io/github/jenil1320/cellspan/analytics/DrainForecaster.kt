package io.github.jenil1320.cellspan.analytics

/**
 * On-device battery-drain forecast: a "typical daily drain" from recent history and a
 * projected rest-of-day state-of-charge curve. Classical time-series arithmetic
 * (median / exponential smoothing / forward projection) — no ML runtime — so it is
 * pure and unit-testable. Surfaced honestly as "at your recent usage", never as a
 * precise guarantee.
 */
object DrainForecaster {

    private const val HOUR = 3_600_000L

    /**
     * Typical daily discharge in percentage points — the median of recent days, which is
     * robust to the occasional heavy-gaming spike that would skew a mean.
     */
    fun typicalDailyDrainPct(dailyDischarged: List<Double>): Double? {
        val vals = dailyDischarged.filter { it > 0.0 }.sorted()
        if (vals.isEmpty()) return null
        val n = vals.size
        return if (n % 2 == 1) vals[n / 2] else (vals[n / 2 - 1] + vals[n / 2]) / 2.0
    }

    /** Light EWMA so a displayed rate/time-left doesn't jump on every 1% step. */
    fun smoothRate(prev: Double?, observed: Double, alpha: Double = 0.3): Double =
        if (prev == null) observed else alpha * observed + (1 - alpha) * prev

    /**
     * Predicted state-of-charge from now, walking forward at [ratePctPerHour] until empty
     * or [horizonMs] elapses. Returns (epochMs, soc) points including the start point;
     * the last point is the projected empty time when it reaches 0. Empty if not
     * discharging.
     */
    fun restOfDayCurve(
        nowMs: Long,
        socNow: Int,
        ratePctPerHour: Double,
        horizonMs: Long = 24 * HOUR,
        stepMin: Int = 30,
    ): List<Pair<Long, Double>> {
        if (ratePctPerHour <= 0.0 || socNow <= 0 || stepMin <= 0) return emptyList()
        val out = ArrayList<Pair<Long, Double>>()
        var t = nowMs
        var soc = socNow.toDouble()
        out.add(t to soc)
        val end = nowMs + horizonMs
        val stepH = stepMin / 60.0
        while (soc > 0.0 && t < end) {
            soc = (soc - ratePctPerHour * stepH).coerceAtLeast(0.0)
            t += stepMin * 60_000L
            out.add(t to soc)
            if (soc <= 0.0) break
        }
        return out
    }
}
