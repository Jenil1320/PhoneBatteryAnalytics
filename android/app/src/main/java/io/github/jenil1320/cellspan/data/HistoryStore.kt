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
    val capacityMah: Int?,
    val levelPct: Int,
    val temperatureC: Float?,
)

/** A frequently-sampled point of live vitals (for real-time charts). */
data class SocPoint(
    val timestampMs: Long,
    val levelPct: Int,
    val temperatureC: Float?,
    val voltageV: Float? = null,
    val currentMa: Int? = null,
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
            CREATE TABLE samples (
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
        db.execSQL("CREATE INDEX idx_samples_ts ON samples(ts)")
        db.execSQL(DAILY_USAGE_DDL)
        db.execSQL(SOC_LOG_DDL)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_soc_ts ON soc_log(ts)")
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

    fun recent(limit: Int = 500): List<HistoryPoint> {
        val out = ArrayList<HistoryPoint>()
        // Take the NEWEST `limit` rows, then present them oldest-first for charting.
        readableDatabase.rawQuery(
            "SELECT ts, soh, cycles, capacity_mah, level, temp_c FROM " +
                "(SELECT * FROM samples ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HistoryPoint(
                        timestampMs = c.getLong(0),
                        sohPct = if (c.isNull(1)) null else c.getDouble(1),
                        cycleCount = if (c.isNull(2)) null else c.getInt(2),
                        capacityMah = if (c.isNull(3)) null else c.getInt(3),
                        levelPct = c.getInt(4),
                        temperatureC = if (c.isNull(5)) null else c.getFloat(5),
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

    /** Battery level of the most recent stored sample, or null if empty. */
    fun lastLevel(): Int? =
        readableDatabase.rawQuery("SELECT level FROM samples ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
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
            execSQL("INSERT INTO soc_log(ts, level, temp_c, voltage_mv, current_ma) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(ts, level, tempC, voltageMv, currentMa))
            execSQL("DELETE FROM soc_log WHERE ts < ?", arrayOf<Any?>(ts - SOC_RETENTION_MS))
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

    companion object {
        private const val DB_NAME = "cellspan.db"
        private const val DB_VERSION = 4
        private const val SOC_RETENTION_MS = 3L * 24 * 60 * 60 * 1000  // keep ~3 days
        private const val DAILY_USAGE_DDL =
            "CREATE TABLE IF NOT EXISTS daily_usage (day TEXT PRIMARY KEY, discharged REAL NOT NULL DEFAULT 0)"
        private const val SOC_LOG_DDL =
            "CREATE TABLE IF NOT EXISTS soc_log (ts INTEGER NOT NULL, level INTEGER NOT NULL, temp_c REAL, voltage_mv INTEGER, current_ma INTEGER)"
    }
}
