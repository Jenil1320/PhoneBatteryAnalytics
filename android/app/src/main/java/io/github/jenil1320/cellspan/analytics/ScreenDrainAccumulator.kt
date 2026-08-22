package io.github.jenil1320.cellspan.analytics

/**
 * Splits battery drain between screen-on and screen-off time.
 *
 * This is the number every competing battery app leads with, and the one Cellspan could
 * not answer: "what is my screen actually costing me?" Answering it needs two things the
 * hourly sample cannot provide — the exact moment each percentage point is lost, and the
 * screen state at that moment. [io.github.jenil1320.cellspan.data.BatteryGuardService]
 * has both, because it is already listening to the battery broadcast.
 *
 * ## The attribution rule
 * The battery level is quantised to whole percent, so a drop is observed at the end of a
 * window rather than at the instant it happened. Within that window the screen may have
 * gone on and off several times. The drop is therefore divided between the two states in
 * proportion to the time spent in each, which is the standard treatment and is exact
 * whenever the drain rate is steady inside the window.
 *
 * Its one weakness is a short, intense burst — ten seconds of camera flash inside a
 * forty-minute idle window — where time-weighting under-credits the screen. That error
 * shrinks as windows get shorter, and windows are short precisely when drain is fast,
 * which is when the number matters.
 *
 * Pure and stateful-by-value, so the whole thing is unit-testable off-device.
 */
class ScreenDrainAccumulator {

    /** Milliseconds spent in each state since the last observed level change. */
    private var onMs = 0L
    private var offMs = 0L
    private var lastMarkMs = 0L
    private var lastLevel = -1
    private var screenOn = false
    private var started = false

    /** One completed attribution, ready to be written to a day's totals. */
    data class Split(val onPct: Double, val offPct: Double, val onMs: Long, val offMs: Long)

    /**
     * Longest window that can still be attributed. A phone in deep sleep for a day
     * produces one huge window whose screen history we did not observe (the process may
     * not even have been alive), and folding that in would corrupt both rates.
     */
    private val maxWindowMs = 6 * 60 * 60_000L

    /** Discard all pending state — after a charge, a reboot, or a clock jump. */
    fun reset(nowMs: Long, level: Int, isScreenOn: Boolean) {
        onMs = 0L; offMs = 0L
        lastMarkMs = nowMs
        lastLevel = level
        screenOn = isScreenOn
        started = true
    }

    /**
     * Record an observation.
     *
     * Call on every battery broadcast AND on every screen on/off transition — the second
     * is what makes the time-weighting meaningful, since without it a whole window is
     * credited to whichever state happened to be current when the level moved.
     *
     * @return a [Split] when this observation closed a window with a real drop in it, or
     *         null when there is nothing yet to record.
     */
    fun observe(nowMs: Long, level: Int, isScreenOn: Boolean, discharging: Boolean): Split? {
        if (!started) {
            reset(nowMs, level, isScreenOn)
            return null
        }
        // Time cannot run backwards; if it does, the clock moved and every pending
        // measurement is meaningless.
        val elapsed = nowMs - lastMarkMs
        if (elapsed < 0 || elapsed > maxWindowMs) {
            reset(nowMs, level, isScreenOn)
            return null
        }
        // Charging, or the level went up: nothing here is drain.
        if (!discharging || level > lastLevel) {
            reset(nowMs, level, isScreenOn)
            return null
        }

        if (screenOn) onMs += elapsed else offMs += elapsed
        lastMarkMs = nowMs
        screenOn = isScreenOn

        if (level == lastLevel) return null              // no drop yet, keep accumulating

        val dropped = (lastLevel - level).toDouble()
        val total = onMs + offMs
        val split = if (total <= 0L) {
            null
        } else {
            val onShare = onMs.toDouble() / total
            Split(dropped * onShare, dropped * (1.0 - onShare), onMs, offMs)
        }
        lastLevel = level
        onMs = 0L
        offMs = 0L
        return split
    }
}
