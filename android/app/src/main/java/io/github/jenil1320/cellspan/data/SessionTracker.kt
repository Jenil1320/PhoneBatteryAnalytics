package io.github.jenil1320.cellspan.data

import android.content.Context

/**
 * Persists the start of an in-progress charge session (charge counter + level)
 * so it survives the app being killed between plug-in and unplug.
 */
object SessionTracker {
    private const val PREFS = "cellspan_prefs"
    private const val ACTIVE = "sess_active"
    private const val CC = "sess_start_cc"
    private const val LEVEL = "sess_start_level"
    private const val TS = "sess_start_ts"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun begin(c: Context, chargeCounterUah: Long, level: Int, ts: Long) {
        p(c).edit().putBoolean(ACTIVE, true).putLong(CC, chargeCounterUah)
            .putInt(LEVEL, level).putLong(TS, ts).apply()
    }

    fun active(c: Context) = p(c).getBoolean(ACTIVE, false)
    fun startCc(c: Context) = p(c).getLong(CC, -1)
    fun startLevel(c: Context) = p(c).getInt(LEVEL, -1)
    fun clear(c: Context) = p(c).edit().putBoolean(ACTIVE, false).apply()
}
