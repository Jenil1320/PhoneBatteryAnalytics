package io.github.jenil1320.cellspan.analytics

import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.DeviceCapacityTable

enum class Condition(val severity: Int, val label: String) {
    GOOD(0, "Good condition"),
    FAIR(1, "Aging"),
    REPLACE(2, "Consider replacing"),
    UNKNOWN(-1, "Not enough data"),
}

/**
 * The result of turning a [BatterySnapshot] into human-meaningful health numbers.
 * All values may be null when the underlying data isn't available on the device.
 */
data class Analysis(
    val sohPct: Double?,
    val sohIsEstimated: Boolean,
    val currentCapacityMah: Int?,
    val designCapacityMah: Int?,
    val designIsFromDatabase: Boolean,
    val cycleCount: Int?,
    val condition: Condition,
    val ageDays: Double?,
    val avgCyclesPerMonth: Double?,
    val avgFadePerYear: Double?,
    val etaSohMs: Long?,
    val etaCyclesMs: Long?,
)

/**
 * Pure battery health math — no Android dependencies, so it is fully unit
 * testable on the JVM. Every function that needs "now" takes it as a parameter.
 */
object BatteryAnalytics {

    const val REPLACE_SOH = 80.0
    const val REPLACE_CYCLES = 800

    private const val MS_PER_DAY = 86_400_000.0
    private const val DAYS_PER_MONTH = 30.437
    private const val DAYS_PER_YEAR = 365.25

    /**
     * Estimate present full-charge capacity (mAh) from the remaining charge and
     * the reported level: full = remaining / (level / 100). Only meaningful when
     * the level is high enough that the gauge reading is stable.
     */
    fun estimateFullCapacityMah(chargeCounterUah: Long?, levelPct: Int): Int? {
        if (chargeCounterUah == null || chargeCounterUah <= 0) return null
        if (levelPct < 15 || levelPct > 100) return null
        val full = chargeCounterUah / (levelPct / 100.0)
        return (full / 1000.0).toInt()
    }

    fun conditionFor(soh: Double?, cycles: Int?): Condition {
        val bySoh = when {
            soh == null -> null
            soh >= 85 -> Condition.GOOD
            soh >= REPLACE_SOH -> Condition.FAIR
            else -> Condition.REPLACE
        }
        val byCyc = when {
            cycles == null -> null
            cycles >= REPLACE_CYCLES -> Condition.REPLACE
            cycles >= 600 -> Condition.FAIR
            else -> Condition.GOOD
        }
        return listOfNotNull(bySoh, byCyc).maxByOrNull { it.severity } ?: Condition.UNKNOWN
    }

    fun analyze(s: BatterySnapshot, nowMs: Long): Analysis {
        // Design capacity: prefer the exact fuel-gauge value, else the table.
        val designFromSysfs = s.chargeFullDesignUah?.let { (it / 1000.0).toInt() }
        val designFromTable = DeviceCapacityTable.designCapacityMah(s.model)
        val designMah = designFromSysfs ?: designFromTable
        val designIsFromDatabase = designFromSysfs == null && designFromTable != null

        // Current full-charge capacity: exact if available, else estimated.
        val currentFromSysfs = s.chargeFullUah?.let { (it / 1000.0).toInt() }
        val currentEstimate = estimateFullCapacityMah(s.chargeCounterUah, s.levelPct)
        val currentMah = currentFromSysfs ?: currentEstimate

        val sohIsEstimated = !(s.chargeFullUah != null && s.chargeFullDesignUah != null)
        val soh: Double? =
            if (currentMah != null && designMah != null && designMah > 0)
                (currentMah.toDouble() / designMah * 100.0).coerceIn(0.0, 120.0)
            else null

        val condition = conditionFor(soh, s.cycleCount)

        // Lifetime stats need the manufacture date (fuel-gauge only).
        var ageDays: Double? = null
        var cyclesPerMonth: Double? = null
        var fadePerYear: Double? = null
        var etaSohMs: Long? = null
        var etaCyclesMs: Long? = null

        val mfg = s.manufacturingEpoch
        if (mfg != null && mfg > 0) {
            val days = (nowMs - mfg * 1000.0) / MS_PER_DAY
            if (days > 0) {
                ageDays = days
                val months = days / DAYS_PER_MONTH
                s.cycleCount?.let { c -> if (c > 0) cyclesPerMonth = c / months }
                if (soh != null) {
                    fadePerYear = (100.0 - soh) / (days / DAYS_PER_YEAR)
                    val fadePerMonth = (100.0 - soh) / months
                    if (fadePerMonth > 0 && soh > REPLACE_SOH) {
                        val m = (soh - REPLACE_SOH) / fadePerMonth
                        etaSohMs = nowMs + (m * DAYS_PER_MONTH * MS_PER_DAY).toLong()
                    }
                }
                val cpm = cyclesPerMonth
                val c = s.cycleCount
                if (cpm != null && cpm > 0 && c != null && c < REPLACE_CYCLES) {
                    val m = (REPLACE_CYCLES - c) / cpm
                    etaCyclesMs = nowMs + (m * DAYS_PER_MONTH * MS_PER_DAY).toLong()
                }
            }
        }

        return Analysis(
            sohPct = soh,
            sohIsEstimated = sohIsEstimated,
            currentCapacityMah = currentMah,
            designCapacityMah = designMah,
            designIsFromDatabase = designIsFromDatabase,
            cycleCount = s.cycleCount,
            condition = condition,
            ageDays = ageDays,
            avgCyclesPerMonth = cyclesPerMonth,
            avgFadePerYear = fadePerYear,
            etaSohMs = etaSohMs,
            etaCyclesMs = etaCyclesMs,
        )
    }

    /** Parse the fuel gauge's `cycle_counts` string into charge-level bands. */
    fun parseCycleCounts(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val vals = raw.replace(",", " ").trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
        return vals.ifEmpty { null }
    }
}
