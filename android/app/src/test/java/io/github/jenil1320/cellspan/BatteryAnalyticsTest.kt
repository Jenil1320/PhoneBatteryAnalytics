package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.Condition
import io.github.jenil1320.cellspan.data.BatterySnapshot
import io.github.jenil1320.cellspan.data.DeviceCapacityTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        levelPct = level, status = "Discharging", pluggedIn = false, health = "Good", technology = "Li-ion",
        temperatureC = 30f, voltageV = 4.0f, currentUa = -400000, chargeCounterUah = chargeCounter,
        cycleCount = cycles, chargeFullUah = chargeFull, chargeFullDesignUah = chargeFullDesign,
        manufacturingEpoch = mfg,
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
        // Cycle count is a soft signal: a high count with healthy SoH is only
        // "Aging", never "Replace" — rated cycle life varies a lot by manufacturer.
        assertEquals(Condition.FAIR, BatteryAnalytics.conditionFor(90.0, 850))
        // Low SoH still drives "Replace" regardless of a low cycle count.
        assertEquals(Condition.REPLACE, BatteryAnalytics.conditionFor(70.0, 900))
        // Cycles alone (SoH unknown) never exceed "Aging".
        assertEquals(Condition.FAIR, BatteryAnalytics.conditionFor(null, 1500))
        assertEquals(Condition.UNKNOWN, BatteryAnalytics.conditionFor(null, null))
    }

    @Test
    fun analyze_exactFromFuelGauge() {
        val a = BatteryAnalytics.analyze(
            snapshot(chargeFull = 3_642_000, chargeFullDesign = 4_386_000, mfg = pixel7Mfg, cycles = 796),
            nowMs,
        )
        assertEquals(83.0, a.sohPct!!, 0.3)
        assertEquals(io.github.jenil1320.cellspan.analytics.CapacitySource.GAUGE, a.capacitySource)
        assertEquals(3642, a.currentCapacityMah)
        assertEquals(4386, a.designCapacityMah)
        assertFalse(a.designIsFromDatabase)
        assertEquals(Condition.FAIR, a.condition)
        assertTrue("age should be ~3.7y", a.ageDays!! > 1300 && a.ageDays!! < 1400)
        assertEquals(18.0, a.avgCyclesPerMonth!!, 1.0)
    }

    @Test
    fun analyze_estimatedFromDatabase() {
        val a = BatteryAnalytics.analyze(
            snapshot(level = 36, chargeCounter = 1_264_000, cycles = 796, model = "Pixel 7"),
            nowMs,
        )
        assertEquals(io.github.jenil1320.cellspan.analytics.CapacitySource.ESTIMATE, a.capacitySource)
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
    fun powerWatts_computesMagnitude() {
        assertEquals(4.0, BatteryAnalytics.powerWatts(4.0f, 1_000_000)!!, 0.01)
        assertEquals(5.925, BatteryAnalytics.powerWatts(3.95f, -1_500_000)!!, 0.01)
        assertNull(BatteryAnalytics.powerWatts(null, 1_000_000))
    }

    @Test
    fun tempBand_classifies() {
        assertEquals(BatteryAnalytics.TempBand.COOL, BatteryAnalytics.tempBand(10f))
        assertEquals(BatteryAnalytics.TempBand.NORMAL, BatteryAnalytics.tempBand(25f))
        assertEquals(BatteryAnalytics.TempBand.WARM, BatteryAnalytics.tempBand(38f))
        assertEquals(BatteryAnalytics.TempBand.HOT, BatteryAnalytics.tempBand(45f))
        assertEquals(BatteryAnalytics.TempBand.UNKNOWN, BatteryAnalytics.tempBand(null))
    }

    @Test
    fun chargeRate_gradedByWatts() {
        val v = 4.0f
        assertEquals("Very fast", BatteryAnalytics.chargeRate(6_500_000, v, "Charging")) // 26 W
        assertEquals("Fast", BatteryAnalytics.chargeRate(3_500_000, v, "Charging"))       // 14 W
        assertEquals("Normal", BatteryAnalytics.chargeRate(2_500_000, v, "Charging"))     // 10 W
        assertEquals("Slow", BatteryAnalytics.chargeRate(1_000_000, v, "Charging"))       // 4 W
        assertEquals("Trickle", BatteryAnalytics.chargeRate(400_000, v, "Charging"))      // 1.6 W
        assertEquals("—", BatteryAnalytics.chargeRate(3_000_000, v, "Discharging"))       // not charging
        assertEquals("—", BatteryAnalytics.chargeRate(3_000_000, null, "Charging"))       // no voltage
    }

    @Test
    fun equivalentCycles_isPercentOver100() {
        assertEquals(0.5, BatteryAnalytics.equivalentCycles(50.0), 0.0001)
    }

    @Test
    fun measuredCapacity_acceptsAQuarterChargeButNotLess() {
        // 25 points is the shallowest session we trust: a stricter bar means a user who
        // tops up little and often never records one at all. 70% -> 95% on a 4000 mAh
        // cell adds 1,000,000 uAh.
        assertEquals(4000, BatteryAnalytics.measuredCapacityMah(2_800_000, 3_800_000, 70, 95))
        // One point shallower is rejected, so the boundary cannot drift unnoticed.
        assertNull(BatteryAnalytics.measuredCapacityMah(2_800_000, 3_760_000, 70, 94))
    }

    @Test
    fun measuredCapacity_fromSessionEndpoints() {
        // Charged 50% -> 90% adding 1,742,000 µAh -> full 4355 mAh.
        assertEquals(4355, BatteryAnalytics.measuredCapacityMah(1_500_000, 3_242_000, 50, 90))
        // Too small a gain -> null.
        assertNull(BatteryAnalytics.measuredCapacityMah(3_000_000, 3_200_000, 80, 85))
        // Non-positive charge delta -> null.
        assertNull(BatteryAnalytics.measuredCapacityMah(3_242_000, 1_500_000, 50, 90))
    }

    @Test
    fun analyze_prefersSessionMeasuredOverEstimate() {
        val a = BatteryAnalytics.analyze(
            snapshot(level = 36, chargeCounter = 1_264_000, cycles = 796, model = "Pixel 7"),
            nowMs, sessionMeasuredMah = 3900, sessionCount = 4,
        )
        assertEquals(io.github.jenil1320.cellspan.analytics.CapacitySource.SESSION, a.capacitySource)
        assertEquals(3900, a.currentCapacityMah)
        assertEquals(4, a.sessionCount)
        assertEquals(89.6, a.sohPct!!, 0.5) // 3900/4355
    }

    @Test
    fun dischargeRate_computesPointsPerHour() {
        // 80% -> 60% over 2 hours = 10 pts/hr.
        val h = 3_600_000L
        val s = listOf(0L to 80, h to 70, 2 * h to 60)
        assertEquals(10.0, BatteryAnalytics.dischargeRatePctPerHour(s)!!, 0.001)
    }

    @Test
    fun dischargeRate_nullWhenChargingFlatOrTooShort() {
        val h = 3_600_000L
        // Net charging over the window -> null.
        assertNull(BatteryAnalytics.dischargeRatePctPerHour(listOf(0L to 50, h to 70)))
        // Flat -> null.
        assertNull(BatteryAnalytics.dischargeRatePctPerHour(listOf(0L to 50, h to 50)))
        // Window shorter than the 5-minute floor -> null.
        assertNull(BatteryAnalytics.dischargeRatePctPerHour(listOf(0L to 80, 60_000L to 79)))
        assertNull(BatteryAnalytics.dischargeRatePctPerHour(listOf(0L to 80)))
    }

    @Test
    fun dischargeRate_usesRunAfterMidWindowCharge() {
        // Sat at 55%, charged to 75% at t=40min, then drained to 52% by t=60min.
        // Endpoint-only slope would be a bogus (55-52)/1h ≈ 3%/hr; the fix must use the
        // post-charge run: (75-52) over 20 min = 69%/hr.
        val s = listOf(0L to 55, (40 * 60_000L) to 75, (60 * 60_000L) to 52)
        assertEquals(69.0, BatteryAnalytics.dischargeRatePctPerHour(s)!!, 1.0)
    }

    @Test
    fun runtimeLeft_dividesLevelByRate() {
        // 50% at 10 pts/hr -> 5 hours.
        assertEquals(5 * 3_600_000L, BatteryAnalytics.runtimeLeftMs(50, 10.0))
        assertNull(BatteryAnalytics.runtimeLeftMs(50, null))
        assertNull(BatteryAnalytics.runtimeLeftMs(50, 0.0))
        assertNull(BatteryAnalytics.runtimeLeftMs(0, 10.0))
    }

    @Test
    fun instantaneousRuntime_fromChargeAndCurrent() {
        // 2,000,000 µAh remaining at 1,000,000 µA draw -> 2 hours.
        assertEquals(2 * 3_600_000L,
            BatteryAnalytics.instantaneousRuntimeMs(2_000_000, -1_000_000, "Discharging"))
        // Charging -> null.
        assertNull(BatteryAnalytics.instantaneousRuntimeMs(2_000_000, 1_000_000, "Charging"))
        // Plugged in but holding ("Not charging") -> null, not an on-battery estimate.
        assertNull(BatteryAnalytics.instantaneousRuntimeMs(2_000_000, -500_000, "Not charging"))
        // No draw -> null.
        assertNull(BatteryAnalytics.instantaneousRuntimeMs(2_000_000, 0, "Discharging"))
    }

    @Test
    fun voltageReading_bands() {
        assertEquals(BatteryAnalytics.Level.WATCH, BatteryAnalytics.voltageReading(3.3f).level)
        assertEquals(BatteryAnalytics.Level.GOOD, BatteryAnalytics.voltageReading(3.8f).level)
        assertEquals(BatteryAnalytics.Level.INFO, BatteryAnalytics.voltageReading(null).level)
    }

    @Test
    fun voltageReading_treatsAModernFullChargeAsHealthy() {
        // Most 2022-and-later flagships charge to 4.45 V and several to 4.48 V. The old
        // 4.42 V ceiling flagged every one of them "High" at the end of every charge.
        assertEquals(BatteryAnalytics.Level.GOOD, BatteryAnalytics.voltageReading(4.45f).level)
        assertEquals(BatteryAnalytics.Level.GOOD, BatteryAnalytics.voltageReading(4.48f).level)
        // Above any shipping charge voltage is still a genuine fault.
        assertEquals(BatteryAnalytics.Level.WATCH, BatteryAnalytics.voltageReading(4.60f).level)
    }

    @Test
    fun currentReading_bands() {
        assertEquals(BatteryAnalytics.Level.GOOD, BatteryAnalytics.currentReading(500_000, "Charging").level)
        assertEquals(BatteryAnalytics.Level.WATCH, BatteryAnalytics.currentReading(-1_800_000, "Discharging").level)
        assertEquals(BatteryAnalytics.Level.INFO, BatteryAnalytics.currentReading(-300_000, "Discharging").level)
    }

    @Test
    fun powerReading_bands() {
        assertEquals(BatteryAnalytics.Level.WATCH, BatteryAnalytics.powerReading(7.0).level)
        assertEquals(BatteryAnalytics.Level.INFO, BatteryAnalytics.powerReading(3.0).level)
        assertEquals(BatteryAnalytics.Level.GOOD, BatteryAnalytics.powerReading(1.0).level)
    }

    @Test
    fun tempLevel_mapsBands() {
        assertEquals(BatteryAnalytics.Level.GOOD, BatteryAnalytics.tempLevel(BatteryAnalytics.TempBand.NORMAL))
        assertEquals(BatteryAnalytics.Level.WATCH, BatteryAnalytics.tempLevel(BatteryAnalytics.TempBand.WARM))
        assertEquals(BatteryAnalytics.Level.BAD, BatteryAnalytics.tempLevel(BatteryAnalytics.TempBand.HOT))
    }

    @Test
    fun levelDistribution_bucketsIntoTenBands() {
        val d = BatteryAnalytics.levelDistribution(listOf(5, 12, 15, 100, 0, 99))
        assertEquals(10, d.size)
        assertEquals(2, d[0])   // 5 and 0
        assertEquals(2, d[1])   // 12 and 15
        assertEquals(2, d[9])   // 100 (clamped) and 99
        assertEquals(6, d.sum())
    }
}
