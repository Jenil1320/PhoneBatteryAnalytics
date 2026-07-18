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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v1 only so far.
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
        readableDatabase.rawQuery(
            "SELECT ts, soh, cycles, capacity_mah, level, temp_c " +
                "FROM samples ORDER BY ts ASC LIMIT ?",
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

    companion object {
        private const val DB_NAME = "cellspan.db"
        private const val DB_VERSION = 1
    }
}
