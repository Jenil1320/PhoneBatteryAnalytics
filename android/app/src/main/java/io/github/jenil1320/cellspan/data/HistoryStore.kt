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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL(DAILY_USAGE_DDL)
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

    companion object {
        private const val DB_NAME = "cellspan.db"
        private const val DB_VERSION = 2
        private const val DAILY_USAGE_DDL =
            "CREATE TABLE IF NOT EXISTS daily_usage (day TEXT PRIMARY KEY, discharged REAL NOT NULL DEFAULT 0)"
    }
}
