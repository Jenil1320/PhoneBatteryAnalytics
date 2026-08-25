package io.github.jenil1320.cellspan.data

import android.content.Context

/**
 * A single shared "last accrued charge level" cursor used by every discharge-
 * accrual path (foreground tick, app-open refresh, background worker). Because
 * all paths diff against — and advance — this one value, each drop in level is
 * counted toward daily usage exactly once, never twice.
 */
object AccrualCursor {
    private const val PREFS = "cellspan_prefs"
    private const val KEY = "accrual_level"

    private fun get(context: Context): Int? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY)) p.getInt(KEY, -1).takeIf { it in 0..100 } else null
    }

    private fun set(context: Context, level: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, level.coerceIn(0, 100)).apply()
    }

    /** When the cursor last moved. Absent (0) means "no window", handled by the floor. */
    private fun lastAt(context: Context): Long {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
        // A missing or future timestamp gives no usable window; fall back to the floor by
        // returning "now", which the caller's coerceAtLeast then widens.
        return if (at <= 0L || at > System.currentTimeMillis()) System.currentTimeMillis() else at
    }

    private fun setLastAt(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, ms).apply()
    }

    /**
     * Advance the cursor to [level] and return the drop that was consumed (0.0
     * when the level did not fall, or when there is no previous value). This is
     * the only safe way to accrue: the worker and the UI both accrue, and an
     * unsynchronised read-compare-write lets them count the same drop twice.
     *
     * @param commit run the caller's database write while still holding the lock. It must
     *        return true only if the write actually landed; on false the cursor is rolled
     *        back so the same drop is offered again next time.
     *
     * Without the rollback the cursor advanced first and the write happened afterwards, so
     * a failed write — a locked database, a full disk, an exception anywhere downstream —
     * destroyed that drop permanently. Nothing retried it, because as far as the cursor
     * was concerned it had already been counted, and the day's usage total was quietly
     * short by however much the phone had discharged in that window.
     */
    /**
     * Largest single-step drop that is believed to be real usage.
     *
     * A fuel gauge does not only report discharge. It also **recalibrates** — after a deep
     * discharge, after a firmware update, or when its own capacity estimate is corrected —
     * and when it does, the reported level can jump by tens of points in one reading. That
     * is a change of opinion about the same charge, not charge that was consumed.
     *
     * Booking it as usage put impossible numbers on screen: a day showing "156% discharged
     * · 1.56 cycles" from a single jump, and a cliff in the charge-today chart that made
     * the rest of the day unreadable. Twenty-five points is comfortably above any genuine
     * gap between two samples — the hourly worker would need a 25%/hour drain to reach it,
     * and the foreground tick runs every sixty seconds — while still catching the jumps.
     */
    private const val MAX_DROP_PER_HOUR = 25.0

    /**
     * Floor on the elapsed window, so a burst of readings seconds apart still allows a
     * point or two rather than rejecting everything outright.
     */
    private const val MIN_WINDOW_HOURS = 0.25

    /** When the cursor last moved, so a drop can be judged as a rate. */
    private const val KEY_AT = "accrual_at"

    @Synchronized
    fun accrue(context: Context, level: Int, commit: (Double) -> Boolean = { true }): Double {
        val prev = get(context)
        val now = System.currentTimeMillis()
        val raw = if (prev != null && level < prev) (prev - level).toDouble() else 0.0
        // Bound the drop by a plausible RATE, not by a fixed number of points.
        //
        // A flat 25-point ceiling threw the whole step away, and that lost real usage: the
        // phone sits in Doze from 23:00 at 100%, the next sample runs at 07:00 at 60%, and
        // all forty points vanished from the day's total with no way to recover them —
        // the cursor had already moved on. Judging by rate keeps both cases right. A gauge
        // recalibration arrives seconds after the previous reading, so its implied rate is
        // absurd and it is still rejected; an eight-hour gap is allowed its full forty
        // points because 5 points an hour is perfectly ordinary.
        val hours = ((now - lastAt(context)) / 3_600_000.0).coerceAtLeast(MIN_WINDOW_HOURS)
        val ceiling = hours * MAX_DROP_PER_HOUR
        val drop = if (raw > 0.0 && raw <= ceiling) raw else 0.0
        set(context, level)
        setLastAt(context, now)
        if (drop > 0.0 && !commit(drop)) {
            // Put the cursor back where it was so the drop is not lost.
            if (prev != null) set(context, prev) else clear(context)
            return 0.0
        }
        return drop
    }

    private fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
