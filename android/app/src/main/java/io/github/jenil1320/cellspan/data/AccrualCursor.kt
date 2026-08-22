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
    @Synchronized
    fun accrue(context: Context, level: Int, commit: (Double) -> Boolean = { true }): Double {
        val prev = get(context)
        val drop = if (prev != null && level < prev) (prev - level).toDouble() else 0.0
        set(context, level)
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
