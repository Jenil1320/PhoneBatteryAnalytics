package io.github.jenil1320.cellspan.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.jenil1320.cellspan.analytics.Analysis

/**
 * One stored historical reading, used to draw trends over time.
 *
 * [quality] is how much the forecaster should trust this point, 0..1. A health figure that
 * came from the fuel gauge or a completed charge session is a measurement; one that came
 * from the instantaneous charge-÷-level estimate is a guess that jitters by several points
 * between refreshes. Weighting them equally let a long run of early guesses set the slope
 * of the projected replacement date.
 */
data class HistoryPoint(
    val timestampMs: Long,
    val sohPct: Double?,
    val cycleCount: Int?,
    val quality: Double = 1.0,
)

/** A frequently-sampled point of live vitals (for real-time charts). */
data class SocPoint(
    val timestampMs: Long,
    val levelPct: Int,
    val temperatureC: Float?,
    val voltageV: Float? = null,
    val currentMa: Int? = null,
)

/** One calendar day's aggregated vitals, used for the week/month trends. */
data class DailyVital(
    val day: String,
    val tempMin: Float?,
    val tempMax: Float?,
    val tempAvg: Float?,
    val voltAvg: Float?,
    val currAvg: Int?,
    val cycles: Int?,
)

/** One charge or discharge session (a continuous span between power plug events). */
data class Session(
    val startMs: Long,
    val endMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val chargeDeltaUah: Long?,   // signed µAh moved (+charge / −discharge), null if unknown
    val isCharge: Boolean,
)

/**
 * JSON null and "key absent" both have to become a Kotlin null on import, and
 * `optDouble` returns NaN for the first and 0.0 for the second — either of which would be
 * written to the database as a real measurement of zero.
 */
private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

private fun org.json.JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

/**
 * Local, on-device history of battery readings. Plain SQLite (no annotation
 * processing) so the app has no code-gen build steps.
 */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SAMPLES_DDL)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts)")
        db.execSQL(DAILY_USAGE_DDL)
        db.execSQL(SOC_LOG_DDL)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_soc_ts ON soc_log(ts)")
        db.execSQL(SESSIONS_DDL)
        db.execSQL(DAILY_VITALS_DDL)
        db.execSQL(USE_SESSIONS_DDL)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_end ON sessions(end_ts)")
        addScreenColumns(db)
    }

    /**
     * Every step is its own `if (oldV < n)`, applied in order, and none is an `else`.
     *
     * That matters: version 3 and version 4 used to be chained with `else if`, so a
     * database that had reached exactly 3 took the ALTER branch and one that was older
     * created the table from the current DDL — which happened to be correct only because
     * the DDL constant kept the newer columns. Any future pair written the same way would
     * silently leave one starting version short a column, and the failure would only show
     * up on the subset of installs that happened to be at that version.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL(DAILY_USAGE_DDL)
        if (oldV < 3) {
            db.execSQL(SOC_LOG_DDL)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_soc_ts ON soc_log(ts)")
        }
        if (oldV == 3) {
            // Only a database that stopped at 3 is missing these; one created by the
            // step above already has them, because SOC_LOG_DDL carries the current shape.
            addColumnIfMissing(db, "soc_log", "voltage_mv", "INTEGER")
            addColumnIfMissing(db, "soc_log", "current_ma", "INTEGER")
        }
        if (oldV < 5) db.execSQL(SESSIONS_DDL)
        if (oldV < 6) db.execSQL(DAILY_VITALS_DDL)
        if (oldV < 7) db.execSQL(USE_SESSIONS_DDL)
        if (oldV < 8) {
            addColumnIfMissing(db, "samples", "quality", "REAL")
            // Both queries that scan these tables order by a timestamp and cap the result;
            // without an index that is a full scan of a table which grows forever.
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_end ON sessions(end_ts)")
        }
        if (oldV < 9) addScreenColumns(db)
    }

    /**
     * The screen-on / screen-off drain split, kept as four running totals per day rather
     * than as individual spans. The question users actually ask is "what does my screen
     * cost me per hour", and that is a ratio of two daily sums — storing every span would
     * be a table that grows forever to answer it.
     */
    private fun addScreenColumns(db: SQLiteDatabase) {
        addColumnIfMissing(db, "daily_usage", "on_pct", "REAL NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "daily_usage", "off_pct", "REAL NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "daily_usage", "on_ms", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "daily_usage", "off_ms", "INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * Add a column only when it is genuinely absent.
     *
     * SQLite has no `ADD COLUMN IF NOT EXISTS`, and a duplicate ALTER throws — which
     * inside onUpgrade aborts the whole migration and leaves the database at its old
     * version, retrying and failing on every single open from then on.
     */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, type: String) {
        // A PRAGMA that cannot be read tells us nothing, and "nothing" must not be treated
        // as "the column is missing" — it would send us into an ALTER that then throws.
        val present = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == column }
        }
        // Deliberately NOT wrapped: a failed ALTER has to abort the migration.
        //
        // Swallowing it let SQLiteOpenHelper commit the upgrade and stamp the new
        // user_version on a database that was missing the column. From then on the `if
        // (oldV < n)` guard never fired again, so the column could never be added, and
        // every query touching it threw "no such column" forever — caught and logged by
        // the callers, so the feature was simply dead with no visible error. Letting it
        // throw leaves the version where it was, and the next open retries.
        if (!present) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    fun insert(snapshot: BatterySnapshot, analysis: Analysis) {
        val values = ContentValues().apply {
            put("ts", snapshot.timestampMs)
            analysis.sohPct?.let { put("soh", it) }
            analysis.cycleCount?.let { put("cycles", it) }
            analysis.currentCapacityMah?.let { put("capacity_mah", it) }
            put("level", snapshot.levelPct)
            snapshot.temperatureC?.let { put("temp_c", it) }
            // Stored with the point, not derived later: the source that produced a reading
            // is only knowable at the moment it was taken.
            put("quality", qualityOf(analysis.capacitySource))
        }
        writableDatabase.insert("samples", null, values)
    }

    /**
     * How much a stored health point deserves to be trusted, 0..1.
     *
     * The gauge and the platform's own figure are direct readings. A session measurement
     * divides real charge by a real level gain, so it is close behind. The instantaneous
     * estimate divides by a level quantised to whole percent, which at 40% carries about
     * 2.5% error before any gauge noise, so it gets a quarter of the weight.
     */
    private fun qualityOf(source: io.github.jenil1320.cellspan.analytics.CapacitySource): Double =
        when (source) {
            io.github.jenil1320.cellspan.analytics.CapacitySource.GAUGE,
            io.github.jenil1320.cellspan.analytics.CapacitySource.DEVICE -> 1.0
            io.github.jenil1320.cellspan.analytics.CapacitySource.SESSION -> 0.85
            io.github.jenil1320.cellspan.analytics.CapacitySource.ESTIMATE -> 0.25
            io.github.jenil1320.cellspan.analytics.CapacitySource.NONE -> 0.1
        }

    /**
     * The last [days] calendar days of trend points, one aggregated row per day, oldest
     * first. Sampling is hourly, so a plain row cap would silently stop the trends growing
     * after a few weeks; a day is the finest resolution these charts ever draw anyway.
     * Every column is aggregated — a bare column under GROUP BY picks an arbitrary row.
     */
    fun recent(days: Int = 730): List<HistoryPoint> {
        val out = ArrayList<HistoryPoint>()
        readableDatabase.rawQuery(
            "SELECT ts, soh, cycles, quality FROM (" +
                "SELECT MAX(ts) AS ts, AVG(soh) AS soh, MAX(cycles) AS cycles, " +
                // MAX, not AVG: a day that produced even one real measurement should not
                // have its weight dragged down by the estimates around it.
                "MAX(quality) AS quality " +
                "FROM samples GROUP BY date(ts/1000,'unixepoch','localtime') " +
                "ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
            arrayOf(days.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HistoryPoint(
                        timestampMs = c.getLong(0),
                        sohPct = if (c.isNull(1)) null else c.getDouble(1),
                        cycleCount = if (c.isNull(2)) null else c.getInt(2),
                        // Rows written before this column existed are unlabelled. They get
                        // the estimate weight, which is what they almost certainly were.
                        quality = if (c.isNull(3)) 0.25 else c.getDouble(3),
                    )
                )
            }
        }
        return out
    }

    /** Timestamp of the most recent stored sample, or null if empty. */
    fun lastSampleMs(): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM samples", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** Ensure a calendar day exists in the usage table (so its bar renders at 0). */
    fun ensureDay(dayKey: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO daily_usage(day, discharged) VALUES(?, 0)", arrayOf(dayKey))
    }

    /** Add discharged percentage points to a calendar day's running total. */
    fun addDischarge(dayKey: String, pct: Double) {
        if (pct <= 0.0) return
        writableDatabase.apply {
            execSQL("INSERT OR IGNORE INTO daily_usage(day, discharged) VALUES(?, 0)", arrayOf(dayKey))
            execSQL("UPDATE daily_usage SET discharged = discharged + ? WHERE day = ?", arrayOf(pct, dayKey))
        }
    }

    /** The last [days] calendar days of discharge totals (percentage points), oldest first. */
    fun dailyUsage(days: Int = 7): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        readableDatabase.rawQuery(
            "SELECT day, discharged FROM (SELECT * FROM daily_usage ORDER BY day DESC LIMIT ?) ORDER BY day ASC",
            arrayOf(days.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getDouble(1))
        }
        return out
    }

    /**
     * Attribute a discharge to the screen being on or off.
     *
     * Both the percentage points and the milliseconds are needed, because the useful
     * figure is a *rate* — "your screen costs 11% an hour" — and a rate needs both halves.
     * Recorded by [BatteryGuardService], the only component that sees every single point
     * of level change along with the screen state at the time.
     */
    fun addScreenDischarge(dayKey: String, onPct: Double, offPct: Double, onMs: Long, offMs: Long) {
        if (onPct <= 0.0 && offPct <= 0.0 && onMs <= 0L && offMs <= 0L) return
        writableDatabase.apply {
            execSQL("INSERT OR IGNORE INTO daily_usage(day, discharged) VALUES(?, 0)", arrayOf(dayKey))
            execSQL(
                "UPDATE daily_usage SET on_pct = on_pct + ?, off_pct = off_pct + ?, " +
                    "on_ms = on_ms + ?, off_ms = off_ms + ? WHERE day = ?",
                arrayOf<Any?>(onPct, offPct, onMs, offMs, dayKey),
            )
        }
    }

    /** One day's screen-on / screen-off drain accounting. */
    data class ScreenSplit(
        val onPct: Double, val offPct: Double, val onMs: Long, val offMs: Long,
    ) {
        /** Points per hour with the screen on, or null when there is too little to divide by. */
        val onRate: Double? get() = if (onMs > MIN_SPLIT_MS) onPct / (onMs / 3_600_000.0) else null
        val offRate: Double? get() = if (offMs > MIN_SPLIT_MS) offPct / (offMs / 3_600_000.0) else null

        companion object {
            /** Under ten minutes in a state, the rate is quantisation noise, not a measurement. */
            const val MIN_SPLIT_MS = 10 * 60_000L
        }
    }

    /**
     * The screen-on / screen-off split summed over the last [days] days.
     *
     * Summed rather than averaged per day: rates divide totals, and averaging daily rates
     * would weight a day with ten minutes of screen time the same as a day with six hours.
     */
    fun screenSplit(days: Int = 7): ScreenSplit =
        readableDatabase.rawQuery(
            "SELECT SUM(on_pct), SUM(off_pct), SUM(on_ms), SUM(off_ms) FROM " +
                "(SELECT * FROM daily_usage ORDER BY day DESC LIMIT ?)",
            arrayOf(days.toString()),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0))
                ScreenSplit(c.getDouble(0), c.getDouble(1), c.getLong(2), c.getLong(3))
            else ScreenSplit(0.0, 0.0, 0L, 0L)
        }

    /** Append a frequently-sampled vitals point and prune old rows. */
    fun appendSoc(ts: Long, level: Int, tempC: Float?, voltageMv: Int?, currentMa: Int?) {
        writableDatabase.apply {
            // Prune from the older of the new and the previously-newest timestamp: a clock
            // jumped forwards would otherwise delete every live-chart row in one insert.
            val previousMax = rawQuery("SELECT MAX(ts) FROM soc_log", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else ts
            }
            execSQL("INSERT INTO soc_log(ts, level, temp_c, voltage_mv, current_ma) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(ts, level, tempC, voltageMv, currentMa))
            execSQL("DELETE FROM soc_log WHERE ts < ?", arrayOf<Any?>(minOf(ts, previousMax) - SOC_RETENTION_MS))
        }
    }

    /** Vitals points since [sinceMs], oldest first. */
    fun recentSoc(sinceMs: Long): List<SocPoint> {
        val out = ArrayList<SocPoint>()
        readableDatabase.rawQuery(
            "SELECT ts, level, temp_c, voltage_mv, current_ma FROM soc_log WHERE ts >= ? ORDER BY ts ASC",
            arrayOf(sinceMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(SocPoint(
                    c.getLong(0), c.getInt(1),
                    if (c.isNull(2)) null else c.getFloat(2),
                    if (c.isNull(3)) null else c.getInt(3) / 1000f,
                    if (c.isNull(4)) null else c.getInt(4),
                ))
            }
        }
        return out
    }

    /** Record one measured charge-session capacity. */
    fun insertSession(ts: Long, startLevel: Int, endLevel: Int, measuredMah: Int) {
        val values = ContentValues().apply {
            put("ts", ts); put("start_level", startLevel); put("end_level", endLevel)
            put("measured_mah", measuredMah)
        }
        writableDatabase.insert("capacity_sessions", null, values)
    }

    /** Average measured capacity (mAh) over the most recent [n] sessions, or null. */
    /**
     * Measured capacity across the most recent [n] charge sessions, weighted by how much
     * each one actually charged.
     *
     * A session's error scales roughly with 1/gain, because the level it divides by is a
     * whole percent. Weighting by gain therefore lets a deep charge outvote a shallow one
     * in proportion to how much better it measures, which is what makes it safe to accept
     * sessions from 25 points upward instead of only deep ones. Rows written before the
     * endpoints were recorded have no usable gain, so they fall back to a plain mean.
     */
    fun sessionCapacityMah(n: Int = 10): Int? {
        val weighted = readableDatabase.rawQuery(
            "SELECT SUM(measured_mah * g), SUM(g) FROM (" +
                "SELECT measured_mah, (end_level - start_level) AS g FROM capacity_sessions " +
                "WHERE start_level IS NOT NULL AND end_level IS NOT NULL AND end_level > start_level " +
                "ORDER BY ts DESC LIMIT ?)",
            arrayOf(n.toString()),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1) && c.getDouble(1) > 0.0) {
                (c.getDouble(0) / c.getDouble(1)).toInt()
            } else null
        }
        if (weighted != null) return weighted
        return readableDatabase.rawQuery(
            "SELECT AVG(measured_mah) FROM (SELECT measured_mah FROM capacity_sessions ORDER BY ts DESC LIMIT ?)",
            arrayOf(n.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0).toInt() else null }
    }

    fun sessionCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM capacity_sessions", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * How much the last [n] session measurements disagree with each other, as a percentage
     * of their mean, or null with fewer than two to compare.
     *
     * This is the honest error bar on a session-measured State of Health. Every session
     * measures the same physical capacity, so the scatter between them *is* the
     * measurement's uncertainty — nothing has to be assumed. Two sessions reading 4100 and
     * 4180 mAh say "about 4140, give or take 1%"; two reading 3700 and 4400 say the figure
     * is not yet worth quoting to the nearest point.
     */
    fun sessionSpreadPct(n: Int = 10): Double? {
        val vals = ArrayList<Double>()
        readableDatabase.rawQuery(
            "SELECT measured_mah FROM capacity_sessions ORDER BY ts DESC LIMIT ?",
            arrayOf(n.toString()),
        ).use { c -> while (c.moveToNext()) vals.add(c.getDouble(0)) }
        if (vals.size < 2) return null
        val mean = vals.average()
        if (mean <= 0.0) return null
        // Population standard deviation: these are all the measurements there are, not a
        // sample drawn from a larger set.
        val sd = kotlin.math.sqrt(vals.sumOf { (it - mean) * (it - mean) } / vals.size)
        return sd / mean * 100.0
    }

    /**
     * A stable displayed capacity: the median of the most recent [n] stored capacities
     * plus the live reading. The instantaneous estimate (charge ÷ level) jitters with the
     * coarse integer battery level; the median cancels that so the headline number stops
     * bouncing between refreshes.
     */
    fun medianCapacityMah(currentMah: Int, n: Int = 15): Int {
        val vals = recentCapacities(n)
        vals.add(currentMah)
        vals.sort()
        return vals[vals.size / 2]
    }

    /**
     * The same steadied capacity when there is no live reading to fold in: the median of
     * the most recent [n] stored capacities, or null when nothing recent has been stored.
     */
    fun storedMedianCapacityMah(n: Int = 15): Int? {
        val vals = recentCapacities(n)
        if (vals.isEmpty()) return null
        vals.sort()
        return vals[vals.size / 2]
    }

    /**
     * The last [n] stored capacities, but only from within [SMOOTH_WINDOW_MS].
     *
     * The row cap alone is not a bound on age. A phone that has been off, or an app the
     * user has not opened, leaves fifteen readings that are weeks old, and the median of
     * those became the headline health figure with nothing on screen saying how old it
     * was. Capacity genuinely moves over weeks, so a stale median is a wrong answer, not
     * a smoothed one. The window makes the smoother do only the job it was added for —
     * cancelling the jitter of the last few readings.
     */
    private fun recentCapacities(n: Int): ArrayList<Int> {
        val vals = ArrayList<Int>()
        val since = System.currentTimeMillis() - SMOOTH_WINDOW_MS
        readableDatabase.rawQuery(
            "SELECT capacity_mah FROM samples WHERE capacity_mah IS NOT NULL AND ts >= ? ORDER BY ts DESC LIMIT ?",
            arrayOf(since.toString(), n.toString()),
        ).use { c -> while (c.moveToNext()) vals.add(c.getInt(0)) }
        return vals
    }

    /**
     * Serialise every long-term table to JSON for the user to save externally, so the
     * history survives an uninstall or device change (all these tables are kept forever
     * on-device; only the high-resolution soc_log is pruned). One self-describing object
     * with an array per table — trivially parseable months or years later.
     */
    fun exportJson(nowMs: Long, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): String {
        val db = readableDatabase
        fun num(c: android.database.Cursor, i: Int) = if (c.isNull(i)) "null" else c.getString(i)
        // Optional time window. Timestamp tables filter on ts; the per-day tables filter
        // on the yyyy-MM-dd day string. All bounds are bound parameters (no injection).
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val sinceDay = if (sinceMs > 0L) fmt.format(java.util.Date(sinceMs)) else "0000-00-00"
        val untilDay = if (untilMs < Long.MAX_VALUE) fmt.format(java.util.Date(untilMs)) else "9999-12-31"
        val tsArgs = arrayOf(sinceMs.toString(), untilMs.toString())
        val dayArgs = arrayOf(sinceDay, untilDay)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("\"app\":\"cellspan\",\"schema\":$DB_VERSION,\"exported_ms\":$nowMs,\"since_ms\":$sinceMs,\n")

        sb.append("\"samples\":[")
        // quality travels with the point. It records whether the reading was a real
        // measurement or the noisy live estimate, and the forecaster weights its trend line
        // by it — so leaving it out meant a restored history came back uniformly downgraded
        // and the projected replacement date moved after a restore.
        db.rawQuery("SELECT ts,soh,cycles,capacity_mah,level,temp_c,quality FROM samples WHERE ts BETWEEN ? AND ? ORDER BY ts", tsArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                sb.append("{\"ts\":${c.getLong(0)},\"soh\":${num(c, 1)},\"cycles\":${num(c, 2)}," +
                    "\"capacity_mah\":${num(c, 3)},\"level\":${c.getInt(4)},\"temp_c\":${num(c, 5)}," +
                    "\"quality\":${num(c, 6)}}")
            }
        }
        sb.append("],\n\"daily_vitals\":[")
        db.rawQuery("SELECT day,temp_min,temp_max,temp_sum,temp_n,volt_sum,volt_n,curr_sum,curr_n,cycles FROM daily_vitals WHERE day BETWEEN ? AND ? ORDER BY day", dayArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                val tn = c.getInt(4); val vn = c.getInt(6); val cn = c.getInt(8)
                val tAvg = if (tn > 0) (c.getDouble(3) / tn).toString() else "null"
                val vAvg = if (vn > 0) (c.getDouble(5) / vn).toString() else "null"
                val cAvg = if (cn > 0) (c.getDouble(7) / cn).toString() else "null"
                sb.append("{\"day\":\"${c.getString(0)}\",\"temp_min\":${num(c, 1)},\"temp_max\":${num(c, 2)}," +
                    "\"temp_avg\":$tAvg,\"volt_avg\":$vAvg,\"curr_avg\":$cAvg,\"cycles\":${num(c, 9)}}")
            }
        }
        sb.append("],\n\"daily_usage\":[")
        // Including the screen-on / screen-off split. It accumulates over weeks and cannot
        // be recreated after a device change, so leaving it out of the export would have
        // made "your history survives a new phone" quietly untrue for the newest feature.
        db.rawQuery("SELECT day,discharged,on_pct,off_pct,on_ms,off_ms FROM daily_usage WHERE day BETWEEN ? AND ? ORDER BY day", dayArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                sb.append("{\"day\":\"${c.getString(0)}\",\"discharged_pct\":${c.getDouble(1)}," +
                    "\"screen_on_pct\":${c.getDouble(2)},\"screen_off_pct\":${c.getDouble(3)}," +
                    "\"screen_on_ms\":${c.getLong(4)},\"screen_off_ms\":${c.getLong(5)}}")
            }
        }
        sb.append("],\n\"capacity_sessions\":[")
        db.rawQuery("SELECT ts,start_level,end_level,measured_mah FROM capacity_sessions WHERE ts BETWEEN ? AND ? ORDER BY ts", tsArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                sb.append("{\"ts\":${c.getLong(0)},\"start_level\":${num(c, 1)},\"end_level\":${num(c, 2)},\"measured_mah\":${c.getInt(3)}}")
            }
        }
        sb.append("],\n\"sessions\":[")
        db.rawQuery("SELECT start_ts,end_ts,start_level,end_level,charge_delta_uah,kind FROM sessions WHERE end_ts BETWEEN ? AND ? ORDER BY end_ts", tsArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                sb.append("{\"start_ts\":${c.getLong(0)},\"end_ts\":${c.getLong(1)},\"start_level\":${c.getInt(2)}," +
                    "\"end_level\":${c.getInt(3)},\"charge_delta_uah\":${num(c, 4)},\"kind\":\"${c.getString(5)}\"}")
            }
        }
        sb.append("]\n}")
        return sb.toString()
    }

    /** What an import actually did, so the user can be told rather than just reassured. */
    data class ImportResult(val samples: Int, val vitals: Int, val usage: Int, val capacity: Int, val sessions: Int) {
        val total get() = samples + vitals + usage + capacity + sessions
    }

    /**
     * Read back a file written by [exportJson].
     *
     * Export without import is a promise the app could not keep: the Settings card offered
     * to make history "survive an uninstall or a new phone", and then there was no way to
     * put it back. A battery history is the one kind of data a user genuinely cannot
     * regenerate — it takes months to accumulate — so this closes the loop.
     *
     * ## Merge, never replace
     * Rows are added with INSERT OR IGNORE and per-day totals take the larger of the two
     * values. Importing therefore cannot destroy anything already on the device, and
     * importing the same file twice leaves the database exactly as it was after the first
     * time. That matters because the obvious user mistake is to import the wrong file, or
     * the same file again, and neither should cost them their history.
     *
     * @throws IllegalArgumentException when the file is not a Cellspan export. Callers
     *         should show that to the user rather than silently importing nothing.
     */
    fun importJson(json: String): ImportResult {
        val root = org.json.JSONObject(json)
        require(root.optString("app") == "cellspan") { "Not a Cellspan export file" }

        var samples = 0; var vitals = 0; var usage = 0; var capacity = 0; var sessions = 0
        val db = writableDatabase
        // One transaction: a half-imported history is worse than none, and it is also
        // dramatically faster than several thousand individual commits.
        db.beginTransaction()
        try {
            root.optJSONArray("samples")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ts = o.optLong("ts", 0L)
                    if (ts <= 0L) continue
                    // Deduplicated on the timestamp, which is unique per reading in
                    // practice, so re-importing does not double every point on the chart.
                    val exists = db.rawQuery("SELECT 1 FROM samples WHERE ts = ? LIMIT 1",
                        arrayOf(ts.toString())).use { it.moveToFirst() }
                    if (exists) continue
                    db.execSQL(
                        "INSERT INTO samples(ts, soh, cycles, capacity_mah, level, temp_c, quality) VALUES(?,?,?,?,?,?,?)",
                        // A file written before quality existed (schema < 8) carries none.
                        // Those points came from the era when the live estimate was the
                        // usual source, so the estimate weight is the honest default.
                        arrayOf<Any?>(ts, o.optDoubleOrNull("soh"), o.optIntOrNull("cycles"),
                            o.optIntOrNull("capacity_mah"), o.optInt("level", 0),
                            o.optDoubleOrNull("temp_c"), o.optDoubleOrNull("quality") ?: 0.25),
                    )
                    samples++
                }
            }
            root.optJSONArray("daily_usage")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val day = o.optString("day")
                    if (day.isBlank()) continue
                    val pct = o.optDouble("discharged_pct", 0.0)
                    val onPct = o.optDouble("screen_on_pct", 0.0)
                    val offPct = o.optDouble("screen_off_pct", 0.0)
                    val onMs = o.optLong("screen_on_ms", 0L)
                    val offMs = o.optLong("screen_off_ms", 0L)
                    // Count only rows that actually add something, so re-importing the same
                    // file honestly reports "nothing new" instead of claiming it merged
                    // hundreds of records it did not touch.
                    val changes = db.rawQuery(
                        "SELECT 1 FROM daily_usage WHERE day = ? AND discharged >= ? AND on_pct >= ? " +
                            "AND off_pct >= ? AND on_ms >= ? AND off_ms >= ? LIMIT 1",
                        arrayOf(day, pct.toString(), onPct.toString(), offPct.toString(),
                            onMs.toString(), offMs.toString()),
                    ).use { c -> !c.moveToFirst() }
                    db.execSQL("INSERT OR IGNORE INTO daily_usage(day, discharged) VALUES(?, 0)", arrayOf(day))
                    // MAX, not sum, on every column: the same day imported twice must not double.
                    db.execSQL(
                        "UPDATE daily_usage SET discharged = MAX(discharged, ?), on_pct = MAX(on_pct, ?), " +
                            "off_pct = MAX(off_pct, ?), on_ms = MAX(on_ms, ?), off_ms = MAX(off_ms, ?) WHERE day = ?",
                        arrayOf<Any?>(pct, onPct, offPct, onMs, offMs, day),
                    )
                    if (changes) usage++
                }
            }
            root.optJSONArray("daily_vitals")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val day = o.optString("day")
                    if (day.isBlank()) continue
                    // The export stores means, not the sums the table keeps. Restoring them
                    // as a single-observation day is lossy in the counts but exact in the
                    // averages, which is all the charts read.
                    val exists = db.rawQuery("SELECT 1 FROM daily_vitals WHERE day = ? LIMIT 1",
                        arrayOf(day)).use { it.moveToFirst() }
                    if (exists) continue
                    db.execSQL(
                        "INSERT INTO daily_vitals(day, temp_min, temp_max, temp_sum, temp_n, volt_sum, volt_n, curr_sum, curr_n, cycles) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(day, o.optDoubleOrNull("temp_min"), o.optDoubleOrNull("temp_max"),
                            o.optDoubleOrNull("temp_avg"), if (o.optDoubleOrNull("temp_avg") != null) 1 else 0,
                            o.optDoubleOrNull("volt_avg"), if (o.optDoubleOrNull("volt_avg") != null) 1 else 0,
                            o.optDoubleOrNull("curr_avg"), if (o.optDoubleOrNull("curr_avg") != null) 1 else 0,
                            o.optIntOrNull("cycles")),
                    )
                    vitals++
                }
            }
            root.optJSONArray("capacity_sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ts = o.optLong("ts", 0L)
                    val mah = o.optInt("measured_mah", 0)
                    if (ts <= 0L || mah <= 0) continue
                    val exists = db.rawQuery("SELECT 1 FROM capacity_sessions WHERE ts = ? LIMIT 1",
                        arrayOf(ts.toString())).use { it.moveToFirst() }
                    if (exists) continue
                    db.execSQL("INSERT INTO capacity_sessions(ts, start_level, end_level, measured_mah) VALUES(?,?,?,?)",
                        arrayOf<Any?>(ts, o.optIntOrNull("start_level"), o.optIntOrNull("end_level"), mah))
                    capacity++
                }
            }
            root.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val start = o.optLong("start_ts", 0L)
                    val end = o.optLong("end_ts", 0L)
                    if (start <= 0L || end <= start) continue
                    val exists = db.rawQuery("SELECT 1 FROM sessions WHERE start_ts = ? AND end_ts = ? LIMIT 1",
                        arrayOf(start.toString(), end.toString())).use { it.moveToFirst() }
                    if (exists) continue
                    db.execSQL(
                        "INSERT INTO sessions(start_ts, end_ts, start_level, end_level, charge_delta_uah, kind) VALUES(?,?,?,?,?,?)",
                        arrayOf<Any?>(start, end, o.optInt("start_level", 0), o.optInt("end_level", 0),
                            o.optLongOrNull("charge_delta_uah"), o.optString("kind", "discharge")),
                    )
                    sessions++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportResult(samples, vitals, usage, capacity, sessions)
    }

    /**
     * Fold one live reading into its calendar day's running aggregates (min/max/mean
     * temperature, mean voltage, mean current, latest cycle count). Uses INSERT-OR-IGNORE
     * then UPDATE so it stays portable to API 26 (no SQLite UPSERT before API 30).
     */
    fun recordVitals(dayKey: String, tempC: Float?, voltageV: Float?, currentMa: Int?, cycles: Int?) {
        writableDatabase.apply {
            execSQL("INSERT OR IGNORE INTO daily_vitals(day) VALUES(?)", arrayOf(dayKey))
            if (tempC != null) execSQL(
                "UPDATE daily_vitals SET temp_min=MIN(COALESCE(temp_min,?),?), temp_max=MAX(COALESCE(temp_max,?),?), " +
                    "temp_sum=COALESCE(temp_sum,0)+?, temp_n=temp_n+1 WHERE day=?",
                arrayOf<Any?>(tempC, tempC, tempC, tempC, tempC, dayKey))
            if (voltageV != null) execSQL(
                "UPDATE daily_vitals SET volt_sum=COALESCE(volt_sum,0)+?, volt_n=volt_n+1 WHERE day=?",
                arrayOf<Any?>(voltageV, dayKey))
            if (currentMa != null) execSQL(
                "UPDATE daily_vitals SET curr_sum=COALESCE(curr_sum,0)+?, curr_n=curr_n+1 WHERE day=?",
                arrayOf<Any?>(currentMa, dayKey))
            if (cycles != null) execSQL(
                "UPDATE daily_vitals SET cycles=? WHERE day=?", arrayOf<Any?>(cycles, dayKey))
        }
    }

    /** The last [days] calendar days of aggregated vitals, oldest first. */
    fun dailyVitals(days: Int): List<DailyVital> {
        val out = ArrayList<DailyVital>()
        readableDatabase.rawQuery(
            "SELECT day, temp_min, temp_max, temp_sum, temp_n, volt_sum, volt_n, curr_sum, curr_n, cycles FROM " +
                "(SELECT * FROM daily_vitals ORDER BY day DESC LIMIT ?) ORDER BY day ASC",
            arrayOf(days.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val tempN = c.getInt(4); val voltN = c.getInt(6); val currN = c.getInt(8)
                out.add(DailyVital(
                    day = c.getString(0),
                    tempMin = if (c.isNull(1)) null else c.getFloat(1),
                    tempMax = if (c.isNull(2)) null else c.getFloat(2),
                    tempAvg = if (tempN > 0) (c.getDouble(3) / tempN).toFloat() else null,
                    voltAvg = if (voltN > 0) (c.getDouble(5) / voltN).toFloat() else null,
                    currAvg = if (currN > 0) (c.getDouble(7) / currN).toInt() else null,
                    cycles = if (c.isNull(9)) null else c.getInt(9),
                ))
            }
        }
        return out
    }

    /**
     * Depth-weighted mean depth of discharge (0–1) across recent discharge sessions, or
     * null when there is too little history to trust.
     *
     * The wear model's cyclic term is dominated by this one number, and its published
     * default (0.7) is an EV/grid-storage figure. Phones are topped up through the day and
     * cycle far shallower, so assuming 0.7 badly over-states wear. Weighting each session
     * by its own depth stops a run of tiny top-ups from dragging the mean to nothing —
     * deep discharges both do more damage and deserve more say.
     */
    fun avgDischargeDoD(sinceMs: Long, minSessions: Int = 5): Double? {
        var weighted = 0.0
        var weight = 0.0
        var n = 0
        readableDatabase.rawQuery(
            "SELECT start_level, end_level FROM sessions " +
                "WHERE kind='discharge' AND end_ts >= ? AND start_level > end_level",
            arrayOf(sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val d = (c.getInt(0) - c.getInt(1)) / 100.0
                if (d > 0.0) { weighted += d * d; weight += d; n++ }
            }
        }
        return if (n >= minSessions && weight > 0.0) (weighted / weight).coerceIn(0.05, 1.0) else null
    }

    /**
     * Record one completed charge/discharge session. Skips implausibly short spans
     * (< 90 s), implausibly long ones, and bad level bounds so the History list stays
     * meaningful.
     */
    fun recordSession(startMs: Long, endMs: Long, startLevel: Int, endLevel: Int, chargeDeltaUah: Long?, isCharge: Boolean) {
        val span = endMs - startMs
        if (span < 90_000L) return
        // The upper bound guards against the system clock moving, which it does routinely:
        // a phone with a dead RTC boots in 1970 and jumps forward when NTP answers, and
        // the segment that was open across that moment becomes a "charge" lasting decades.
        // One such row rescaled every duration axis in History to nothing.
        if (span > MAX_SESSION_MS) return
        if (startLevel !in 0..100 || endLevel !in 0..100) return
        val values = ContentValues().apply {
            put("start_ts", startMs); put("end_ts", endMs)
            put("start_level", startLevel); put("end_level", endLevel)
            chargeDeltaUah?.let { put("charge_delta_uah", it) }
            put("kind", if (isCharge) "charge" else "discharge")
        }
        writableDatabase.insert("sessions", null, values)
    }

    /** The most recent [n] sessions, newest first. */
    fun recentSessions(n: Int = 20): List<Session> {
        val out = ArrayList<Session>()
        readableDatabase.rawQuery(
            "SELECT start_ts, end_ts, start_level, end_level, charge_delta_uah, kind FROM sessions ORDER BY end_ts DESC LIMIT ?",
            arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Session(
                    startMs = c.getLong(0), endMs = c.getLong(1),
                    startLevel = c.getInt(2), endLevel = c.getInt(3),
                    chargeDeltaUah = if (c.isNull(4)) null else c.getLong(4),
                    isCharge = c.getString(5) == "charge",
                ))
            }
        }
        return out
    }

    companion object {
        private const val DB_NAME = "cellspan.db"
        private const val DB_VERSION = 9
        private const val SOC_RETENTION_MS = 3L * 24 * 60 * 60 * 1000  // keep ~3 days

        /**
         * Longest span accepted as one charge or discharge session: 14 days. Generous
         * enough for a phone left in a drawer, short enough that a clock jump cannot
         * write a session measured in decades.
         */
        private const val MAX_SESSION_MS = 14L * 24 * 60 * 60 * 1000

        /** How far back the capacity smoother may reach: three days. */
        private const val SMOOTH_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        private const val SAMPLES_DDL =
            "CREATE TABLE IF NOT EXISTS samples (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "soh REAL, cycles INTEGER, capacity_mah INTEGER, level INTEGER, temp_c REAL, quality REAL)"
        private const val DAILY_USAGE_DDL =
            "CREATE TABLE IF NOT EXISTS daily_usage (day TEXT PRIMARY KEY, discharged REAL NOT NULL DEFAULT 0, " +
                "on_pct REAL NOT NULL DEFAULT 0, off_pct REAL NOT NULL DEFAULT 0, " +
                "on_ms INTEGER NOT NULL DEFAULT 0, off_ms INTEGER NOT NULL DEFAULT 0)"
        private const val SOC_LOG_DDL =
            "CREATE TABLE IF NOT EXISTS soc_log (ts INTEGER NOT NULL, level INTEGER NOT NULL, temp_c REAL, voltage_mv INTEGER, current_ma INTEGER)"
        private const val SESSIONS_DDL =
            "CREATE TABLE IF NOT EXISTS capacity_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, start_level INTEGER, end_level INTEGER, measured_mah INTEGER NOT NULL)"
        private const val DAILY_VITALS_DDL =
            "CREATE TABLE IF NOT EXISTS daily_vitals (day TEXT PRIMARY KEY, temp_min REAL, temp_max REAL, " +
                "temp_sum REAL, temp_n INTEGER NOT NULL DEFAULT 0, volt_sum REAL, volt_n INTEGER NOT NULL DEFAULT 0, " +
                "curr_sum REAL, curr_n INTEGER NOT NULL DEFAULT 0, cycles INTEGER)"
        private const val USE_SESSIONS_DDL =
            "CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER NOT NULL, " +
                "end_ts INTEGER NOT NULL, start_level INTEGER NOT NULL, end_level INTEGER NOT NULL, " +
                "charge_delta_uah INTEGER, kind TEXT NOT NULL)"
    }
}
