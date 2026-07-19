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

    fun get(context: Context): Int? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY)) p.getInt(KEY, -1).takeIf { it in 0..100 } else null
    }

    fun set(context: Context, level: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, level.coerceIn(0, 100)).apply()
    }
}
