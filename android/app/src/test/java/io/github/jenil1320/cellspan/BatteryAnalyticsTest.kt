package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.Condition
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.DeviceCapacityTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAnalyticsTest {

    // 2026-07-18 00:00:00 UTC in millis, used as a fixed "now" for determinism.
    private val nowMs = 1_784_332_800_000L
    private val pixel7Mfg = 1_667_865_600L // 2022-11-08

    private fun snapshot(
        level: Int = 52,
        chargeCounter: Long? = null,
        cycles: Int? = null,
        chargeFull: Long? = null,
        chargeFullDesign: Long? = null,
        mfg: Long? = null,
        model: String = "Pixel 7",
    ) = BatterySnapshot(
        timestampMs = nowMs, manufacturer = "Google", model = model, device = "panther",
        levelPct = level, status = "Discharging", health = "Good", technology = "Li-ion",
        temperatureC = 30f, voltageV = 4.0f, currentUa = -400000, chargeCounterUah = chargeCounter,
        cycleCount = cycles, chargeFullUah = chargeFull, chargeFullDesignUah = chargeFullDesign,
        manufacturingEpoch = mfg, sysfsAvailable = chargeFull != null && chargeFullDesign != null,
    )

    @Test
    fun estimateFullCapacity_fromCounterAndLevel() {
        // 1,264,000 µAh at 36% -> ~3511 mAh full.
        assertEquals(3511, BatteryAnalytics.estimateFullCapacityMah(1_264_000, 36))
    }

    @Test
    fun estimateFullCapacity_guardsLowLevelAndNulls() {
        assertNull(BatteryAnalytics.estimateFullCapacityMah(1_264_000, 5))
        assertNull(BatteryAnalytics.estimateFullCapacityMah(null, 50))
        assertNull(BatteryAnalytics.estimateFullCapacityMah(0, 50))
    }

    @Test
    fun condition_combinesSohAndCycles() {
        assertEquals(Condition.GOOD, BatteryAnalytics.conditionFor(90.0, 100))
        assertEquals(Condition.FAIR, BatteryAnalytics.conditionFor(83.0, 796))
        assertEquals(Condition.REPLACE, BatteryAnalytics.conditionFor(79.0, 500))
        assertEquals(Condition.REPLACE, BatteryAnalytics.conditionFor(90.0, 850))
        assertEquals(Condition.UNKNOWN, BatteryAnalytics.conditionFor(null, null))
    }

    @Test
    fun analyze_exactFromFuelGauge() {
        val a = BatteryAnalytics.analyze(
            snapshot(chargeFull = 3_642_000, chargeFullDesign = 4_386_000, mfg = pixel7Mfg, cycles = 796),
            nowMs,
        )
        assertEquals(83.0, a.sohPct!!, 0.3)
        assertFalse(a.sohIsEstimated)
        assertEquals(3642, a.currentCapacityMah)
        assertEquals(4386, a.designCapacityMah)
        assertFalse(a.designIsFromDatabase)
        assertEquals(Condition.FAIR, a.condition)
        assertTrue("age should be ~3.7y", a.ageDays!! > 1300 && a.ageDays!! < 1400)
        assertEquals(18.0, a.avgCyclesPerMonth!!, 1.0)
        assertNotNull("796<800 so a cycle ETA exists", a.etaCyclesMs)
        assertTrue(a.etaCyclesMs!! > nowMs)
    }

    @Test
    fun analyze_estimatedFromDatabase() {
        val a = BatteryAnalytics.analyze(
            snapshot(level = 36, chargeCounter = 1_264_000, cycles = 796, model = "Pixel 7"),
            nowMs,
        )
        assertTrue(a.sohIsEstimated)
        assertTrue(a.designIsFromDatabase)
        assertEquals(4355, a.designCapacityMah)      // from table
        assertEquals(3511, a.currentCapacityMah)      // estimated
        assertEquals(80.6, a.sohPct!!, 0.5)
        assertNull("no manufacture date -> no lifetime stats", a.ageDays)
    }

    @Test
    fun analyze_unknownModelHasNoDesign() {
        val a = BatteryAnalytics.analyze(
            snapshot(level = 50, chargeCounter = 2_000_000, model = "Totally Unknown X1"),
            nowMs,
        )
        assertNull(a.designCapacityMah)
        assertNull(a.sohPct)
        assertEquals(Condition.UNKNOWN, a.condition)
    }

    @Test
    fun soh_isCappedAt100ForFreshOrOvershootingCells() {
        // 4,500,000 / 4,386,000 = 102.6% -> shown as 100%.
        val a = BatteryAnalytics.analyze(
            snapshot(chargeFull = 4_500_000, chargeFullDesign = 4_386_000, cycles = 40),
            nowMs,
        )
        assertEquals(100.0, a.sohPct!!, 0.001)
    }

    @Test
    fun soh_rejectsImplausibleData() {
        // 9,000,000 / 4,386,000 = 205% -> treated as garbage, no SoH.
        val a = BatteryAnalytics.analyze(
            snapshot(chargeFull = 9_000_000, chargeFullDesign = 4_386_000),
            nowMs,
        )
        assertNull(a.sohPct)
    }

    @Test
    fun capacityTable_matchesRegionalSkuSuffixes() {
        assertEquals(3900, DeviceCapacityTable.designCapacityMah("SM-S911B")) // S23 intl
        assertEquals(3900, DeviceCapacityTable.designCapacityMah("SM-S911U")) // S23 US
        assertEquals(5000, DeviceCapacityTable.designCapacityMah("SM-S918W")) // S23 Ultra CA
        assertEquals(4355, DeviceCapacityTable.designCapacityMah("Pixel 7"))
        assertNull(DeviceCapacityTable.designCapacityMah("Made Up Z9"))
        assertNull(DeviceCapacityTable.designCapacityMah(null))
    }

    @Test
    fun parseCycleCounts_readsBands() {
        val bands = BatteryAnalytics.parseCycleCounts("2867 8354 11070 11313 11102 10334 9503 8209 6563 3748")
        assertEquals(10, bands!!.size)
        assertEquals(83063, bands.sum())
        assertNull(BatteryAnalytics.parseCycleCounts(null))
        assertNull(BatteryAnalytics.parseCycleCounts("   "))
    }
}
