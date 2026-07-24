package io.github.jenil1320.cellspan.analytics

/**
 * On-device battery-drain forecast: a projected rest-of-day state-of-charge curve.
 * Plain forward projection at the recent discharge rate — no ML runtime — so it is
 * pure and unit-testable. Surfaced honestly as "at your recent usage", never as a
 * precise guarantee.
 */
object DrainForecaster {

    private const val HOUR = 3_600_000L

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
