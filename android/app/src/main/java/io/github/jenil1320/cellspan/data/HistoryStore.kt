package io.github.jenil1320.cellspan.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.jenil1320.cellspan.analytics.Analysis

/** One stored historical reading, used to draw trends over time. */
data class HistoryPoint(
    val timestampMs: Long,
    val sohPct: Double?,
    val cycleCount: Int?,
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
 * Local, on-device history of battery readings. Plain SQLite (no annotation
 * processing) so the app has no code-gen build steps.
 */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                soh REAL,
                cycles INTEGER,
                capacity_mah INTEGER,
                level INTEGER,
                temp_c REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts)")
        db.execSQL(DAILY_USAGE_DDL)
        db.execSQL(SOC_LOG_DDL)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_soc_ts ON soc_log(ts)")
        db.execSQL(SESSIONS_DDL)
        db.execSQL(DAILY_VITALS_DDL)
        db.execSQL(USE_SESSIONS_DDL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL(DAILY_USAGE_DDL)
        if (oldV < 3) {
            db.execSQL(SOC_LOG_DDL)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_soc_ts ON soc_log(ts)")
        } else if (oldV < 4) {
            db.execSQL("ALTER TABLE soc_log ADD COLUMN voltage_mv INTEGER")
            db.execSQL("ALTER TABLE soc_log ADD COLUMN current_ma INTEGER")
        }
        if (oldV < 5) db.execSQL(SESSIONS_DDL)
        if (oldV < 6) db.execSQL(DAILY_VITALS_DDL)
        if (oldV < 7) db.execSQL(USE_SESSIONS_DDL)
    }

    fun insert(snapshot: BatterySnapshot, analysis: Analysis) {
        val values = ContentValues().apply {
            put("ts", snapshot.timestampMs)
            analysis.sohPct?.let { put("soh", it) }
            analysis.cycleCount?.let { put("cycles", it) }
            analysis.currentCapacityMah?.let { put("capacity_mah", it) }
            put("level", snapshot.levelPct)
            snapshot.temperatureC?.let { put("temp_c", it) }
        }
        writableDatabase.insert("samples", null, values)
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
            "SELECT ts, soh, cycles FROM (" +
                "SELECT MAX(ts) AS ts, AVG(soh) AS soh, MAX(cycles) AS cycles " +
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
     * A stable displayed capacity: the median of the most recent [n] stored capacities
     * plus the live reading. The instantaneous estimate (charge ÷ level) jitters with the
     * coarse integer battery level; the median cancels that so the headline number stops
     * bouncing between refreshes.
     */
    fun medianCapacityMah(currentMah: Int, n: Int = 15): Int {
        val vals = ArrayList<Int>()
        readableDatabase.rawQuery(
            "SELECT capacity_mah FROM samples WHERE capacity_mah IS NOT NULL ORDER BY ts DESC LIMIT ?",
            arrayOf(n.toString())
        ).use { c -> while (c.moveToNext()) vals.add(c.getInt(0)) }
        vals.add(currentMah)
        vals.sort()
        return vals[vals.size / 2]
    }

    /**
     * The same steadied capacity when there is no live reading to fold in: the median of
     * the most recent [n] stored capacities, or null when nothing has been stored yet.
     */
    fun storedMedianCapacityMah(n: Int = 15): Int? {
        val vals = ArrayList<Int>()
        readableDatabase.rawQuery(
            "SELECT capacity_mah FROM samples WHERE capacity_mah IS NOT NULL ORDER BY ts DESC LIMIT ?",
            arrayOf(n.toString())
        ).use { c -> while (c.moveToNext()) vals.add(c.getInt(0)) }
        if (vals.isEmpty()) return null
        vals.sort()
        return vals[vals.size / 2]
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
        db.rawQuery("SELECT ts,soh,cycles,capacity_mah,level,temp_c FROM samples WHERE ts BETWEEN ? AND ? ORDER BY ts", tsArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                sb.append("{\"ts\":${c.getLong(0)},\"soh\":${num(c, 1)},\"cycles\":${num(c, 2)}," +
                    "\"capacity_mah\":${num(c, 3)},\"level\":${c.getInt(4)},\"temp_c\":${num(c, 5)}}")
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
        db.rawQuery("SELECT day,discharged FROM daily_usage WHERE day BETWEEN ? AND ? ORDER BY day", dayArgs).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(","); first = false
                sb.append("{\"day\":\"${c.getString(0)}\",\"discharged_pct\":${c.getDouble(1)}}")
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
     * (< 90 s) and bad level bounds so the History list stays meaningful.
     */
    fun recordSession(startMs: Long, endMs: Long, startLevel: Int, endLevel: Int, chargeDeltaUah: Long?, isCharge: Boolean) {
        if (endMs - startMs < 90_000L) return
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
        private const val DB_VERSION = 7
        private const val SOC_RETENTION_MS = 3L * 24 * 60 * 60 * 1000  // keep ~3 days
        private const val DAILY_USAGE_DDL =
            "CREATE TABLE IF NOT EXISTS daily_usage (day TEXT PRIMARY KEY, discharged REAL NOT NULL DEFAULT 0)"
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
