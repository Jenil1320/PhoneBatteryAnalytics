package io.github.jenil1320.cellspan.data

import android.content.Context

/**
 * Tracks the start of the current power segment (a continuous charge or discharge span)
 * across process death, so [PowerReceiver] can close each segment into a `sessions` row
 * on the next plug/unplug event.
 */
object SegmentTracker {
    private const val PREFS = "cellspan_prefs"
    private const val ACTIVE = "seg_active"
    private const val TS = "seg_start_ts"
    private const val LEVEL = "seg_start_level"
    private const val CC = "seg_start_cc"      // µAh, -1 when unknown
    private const val KIND = "seg_kind"        // "charge" | "discharge"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Segment(val startTs: Long, val startLevel: Int, val startCc: Long?, val isCharge: Boolean)

    fun begin(c: Context, ts: Long, level: Int, cc: Long?, isCharge: Boolean) {
        p(c).edit()
            .putBoolean(ACTIVE, true).putLong(TS, ts).putInt(LEVEL, level)
            .putLong(CC, cc ?: -1L).putString(KIND, if (isCharge) "charge" else "discharge")
            .apply()
    }

    fun current(c: Context): Segment? {
        val pr = p(c)
        if (!pr.getBoolean(ACTIVE, false)) return null
        val cc = pr.getLong(CC, -1L)
        return Segment(
            startTs = pr.getLong(TS, 0L),
            startLevel = pr.getInt(LEVEL, -1),
            startCc = if (cc > 0L) cc else null,
            isCharge = pr.getString(KIND, "discharge") == "charge",
        )
    }
}
