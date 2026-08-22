package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.CapacitySource
import io.github.jenil1320.cellspan.analytics.Confidence
import io.github.jenil1320.cellspan.analytics.DegradationModel
import io.github.jenil1320.cellspan.analytics.ScreenDrainAccumulator
import io.github.jenil1320.cellspan.data.BatteryReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The physics and measurement layer: the places where a wrong number is worse than no
 * number, because the user cannot tell it is wrong.
 */
class PhysicsTest {

    // ── degradation model ─────────────────────────────────────────────────────

    @Test
    fun cyclicWearDoesNotDependOnHowBigTheBatteryIs() {
        // Capacity fade is intensive: at the same cycle count a 6000 mAh pack and a
        // 3000 mAh one have each been cycled the same number of times, so the model must
        // predict the same fractional loss. Feeding the pack's own ampere-hours into a
        // coefficient fitted to a 2.05 Ah cell made a big battery look worse — 25% loss
        // against 18% for a small one, purely because it was bigger.
        val small = DegradationModel.cyclicLossFrac(800.0, 3000)
        val mid = DegradationModel.cyclicLossFrac(800.0, 4355)
        val large = DegradationModel.cyclicLossFrac(800.0, 6000)
        assertEquals(small, mid, 1e-9)
        assertEquals(mid, large, 1e-9)
    }

    @Test
    fun cyclicWearStillGrowsWithCyclesAndWithDepth() {
        assertTrue(DegradationModel.cyclicLossFrac(800.0, 4355) > DegradationModel.cyclicLossFrac(200.0, 4355))
        assertTrue(
            DegradationModel.cyclicLossFrac(800.0, 4355, avgDoD = 0.9) >
                DegradationModel.cyclicLossFrac(800.0, 4355, avgDoD = 0.2)
        )
    }

    @Test
    fun cyclicWearIsSubLinearInCycles() {
        // The published form is beta * sqrt(throughput), so four times the cycles must
        // give twice the loss, not four times.
        val a = DegradationModel.cyclicLossFrac(200.0, 4355)
        val b = DegradationModel.cyclicLossFrac(800.0, 4355)
        assertEquals(2.0, b / a, 0.01)
    }

    @Test
    fun cyclicWearIsZeroWithoutUsableInputs() {
        assertEquals(0.0, DegradationModel.cyclicLossFrac(0.0, 4355), 0.0)
        assertEquals(0.0, DegradationModel.cyclicLossFrac(-5.0, 4355), 0.0)
    }

    @Test
    fun anUnknownDesignCapacityNoLongerSuppressesTheModel() {
        // Design capacity does not enter the arithmetic any more, so demanding it was a
        // leftover from the old scaling — and it cost the whole "measured vs typical" card
        // on any phone whose ROM hides its PowerProfile and is absent from the table.
        assertTrue(DegradationModel.cyclicLossFrac(800.0, null) > 0.0)
        assertEquals(
            DegradationModel.cyclicLossFrac(800.0, 4355),
            DegradationModel.cyclicLossFrac(800.0, null),
            1e-12,
        )
        assertNotNull(DegradationModel.estimate(equivalentFullCycles = 800.0, designCapacityMah = null))
    }

    @Test
    fun calendarLossGrowsWithTimeAndWithHeat() {
        val young = DegradationModel.calendarLossFrac(100.0, 25.0)
        val old = DegradationModel.calendarLossFrac(1000.0, 25.0)
        assertTrue(old > young)
        // Sub-linear: t^0.75, so ten times the days is well under ten times the loss.
        assertTrue(old < 10 * young)
        assertTrue(DegradationModel.calendarLossFrac(500.0, 40.0) > DegradationModel.calendarLossFrac(500.0, 20.0))
    }

    @Test
    fun calendarLossIsZeroAtZeroAgeAndNeverNegative() {
        assertEquals(0.0, DegradationModel.calendarLossFrac(0.0, 25.0), 0.0)
        assertEquals(0.0, DegradationModel.calendarLossFrac(-10.0, 25.0), 0.0)
        assertTrue(DegradationModel.calendarLossFrac(1.0, -20.0) >= 0.0)
    }

    @Test
    fun calendarLossMatchesTheTermInsideTheFullEstimate() {
        // The forecaster calls calendarLossFrac directly while the Health card reads
        // estimate(); if they ever disagreed the two screens would contradict each other.
        val r = DegradationModel.estimate(0.0, 4355, ageDays = 700.0, avgTempC = 28.0)!!
        assertEquals(DegradationModel.calendarLossFrac(700.0, 28.0) * 100.0, r.calendarLossPct, 1e-9)
    }

    // ── current unit normalisation ────────────────────────────────────────────

    @Test
    fun currentSignFollowsTheChargeStatusNotTheDriver() {
        // Samsung and others invert the sign; the status string is reliable everywhere.
        assertTrue(BatteryReader.normaliseCurrentUa(-1_500_000L, "Charging") > 0)
        assertTrue(BatteryReader.normaliseCurrentUa(1_500_000L, "Discharging") < 0)
    }

    @Test
    fun aDriverReportingMilliampsIsScaledToMicroamps() {
        // 1500 in the microamp property is 1.5 mA, which no phone draws while charging.
        // It is really 1500 mA, and left uncorrected it made a 30 W charge read as 0.01 W.
        assertEquals(1_500_000L, BatteryReader.normaliseCurrentUa(1500L, "Charging"))
        assertEquals(-450_000L, BatteryReader.normaliseCurrentUa(450L, "Discharging"))
    }

    @Test
    fun aCorrectMicroampReadingIsLeftAlone() {
        assertEquals(1_500_000L, BatteryReader.normaliseCurrentUa(1_500_000L, "Charging"))
        assertEquals(-250_000L, BatteryReader.normaliseCurrentUa(-250_000L, "Discharging"))
        // Just above the suspicion ceiling: a genuine 6 mA idle draw stays 6 mA.
        assertEquals(6_000L, BatteryReader.normaliseCurrentUa(6_000L, "Charging"))
    }

    @Test
    fun onceTheDeviceHasProvedItReportsMicroampsSmallValuesAreNeverScaled() {
        // The real false positive: a phone held at a root charge cap, or in the last
        // minutes of the constant-voltage taper, genuinely reports a few milliamps. Scaling
        // 3000 µA would have claimed a 3 A, 12 W charge on a phone doing nothing.
        assertEquals(3_000L, BatteryReader.normaliseCurrentUa(3_000L, "Charging", unitsKnownMicro = true))
        assertEquals(-2_000L, BatteryReader.normaliseCurrentUa(-2_000L, "Discharging", unitsKnownMicro = true))
    }

    @Test
    fun aRestingPhoneIsNeverScaledWhateverTheDeviceHasProved() {
        // Full and Not charging both mean "near zero is the expected reading".
        assertEquals(400L, BatteryReader.normaliseCurrentUa(400L, "Full"))
        assertEquals(400L, BatteryReader.normaliseCurrentUa(400L, "Not charging"))
        assertEquals(400L, BatteryReader.normaliseCurrentUa(400L, "Unknown"))
    }

    @Test
    fun aReadingTooLargeToBeMilliampsProvesTheUnit() {
        // This is the discriminator: no phone battery carries 20 A, so a magnitude at or
        // above the proof threshold can only be microamps, and a buggy driver reporting
        // milliamps can never produce one.
        assertTrue(BatteryReader.MICRO_PROOF_UA > 15_000L)
        assertEquals(20_000L, BatteryReader.normaliseCurrentUa(20_000L, "Charging"))
    }

    @Test
    fun scalingIsSkippedWhenItWouldProduceAnImpossibleCurrent() {
        assertEquals(20_000L, BatteryReader.normaliseCurrentUa(20_000L, "Charging"))
    }

    @Test
    fun anUnknownStatusKeepsTheReportedDirection() {
        assertTrue(BatteryReader.normaliseCurrentUa(-800_000L, "Not charging") < 0)
        assertTrue(BatteryReader.normaliseCurrentUa(800_000L, "Full") > 0)
    }

    // ── temperature bands ─────────────────────────────────────────────────────

    @Test
    fun subZeroIsItsOwnHazardBandNotJustCold() {
        // Charging below freezing plates metallic lithium on the anode. That is permanent
        // and it is not the same as a chilly 10 °C morning.
        assertEquals(BatteryAnalytics.TempBand.FREEZING, BatteryAnalytics.tempBand(-1f))
        assertEquals(BatteryAnalytics.Level.BAD,
            BatteryAnalytics.tempLevel(BatteryAnalytics.tempBand(-5f)))
        assertEquals(BatteryAnalytics.TempBand.COOL, BatteryAnalytics.tempBand(1f))
    }

    @Test
    fun theRemainingTemperatureBoundariesAreUnchanged() {
        assertEquals(BatteryAnalytics.TempBand.NORMAL, BatteryAnalytics.tempBand(15f))
        assertEquals(BatteryAnalytics.TempBand.NORMAL, BatteryAnalytics.tempBand(35f))
        assertEquals(BatteryAnalytics.TempBand.WARM, BatteryAnalytics.tempBand(36f))
        assertEquals(BatteryAnalytics.TempBand.HOT, BatteryAnalytics.tempBand(43f))
        assertEquals(BatteryAnalytics.TempBand.UNKNOWN, BatteryAnalytics.tempBand(null))
    }

    // ── lifetime mean temperature ─────────────────────────────────────────────

    @Test
    fun oneHotReadingCannotBecomeALifetimeMean() {
        // The calendar term is Arrhenius, so a 45 °C "lifetime mean" roughly triples the
        // modelled loss. A gaming session must not do that.
        val used = BatteryAnalytics.lifetimeMeanTempC(null, 45f)!!
        assertTrue("clamped, got $used", used <= 38.0)
        val cold = BatteryAnalytics.lifetimeMeanTempC(null, -5f)!!
        assertTrue("clamped, got $cold", cold >= 18.0)
    }

    @Test
    fun aMeasuredMeanIsPreferredAndStillBounded() {
        assertEquals(27.0, BatteryAnalytics.lifetimeMeanTempC(27.0, 45f)!!, 1e-9)
        assertTrue(BatteryAnalytics.lifetimeMeanTempC(60.0, null)!! <= 38.0)
    }

    @Test
    fun noTemperatureAtAllLeavesTheModelOnItsOwnDefault() {
        assertNull(BatteryAnalytics.lifetimeMeanTempC(null, null))
    }

    // ── time to full ──────────────────────────────────────────────────────────

    @Test
    fun timeToFullAllowsForTheConstantVoltageSlowdown() {
        // 50% at 60 points/hour. A straight line says 50 minutes to full; the real charger
        // tapers above the knee, so the honest answer is meaningfully longer.
        val linearMin = 50 / 60.0 * 60
        val ms = BatteryAnalytics.timeToLevelMs(50, 100, 60.0, "Charging")!!
        val min = ms / 60_000.0
        assertTrue("should exceed the linear estimate of $linearMin min, got $min", min > linearMin)
        assertTrue("but stay in the same order, got $min", min < 3 * linearMin)
    }

    @Test
    fun timeToAnEightyPercentLimitIsPurelyConstantCurrent() {
        // Below the knee there is no taper, so it is the straight-line answer exactly.
        val ms = BatteryAnalytics.timeToLevelMs(40, 80, 40.0, "Charging")!!
        assertEquals(60.0, ms / 60_000.0, 0.5)
    }

    @Test
    fun timeToFullIsNullWhenItCannotBeKnown() {
        assertNull("not charging", BatteryAnalytics.timeToLevelMs(50, 100, 60.0, "Discharging"))
        assertNull("no rate", BatteryAnalytics.timeToLevelMs(50, 100, null, "Charging"))
        assertNull("zero rate", BatteryAnalytics.timeToLevelMs(50, 100, 0.0, "Charging"))
        assertNull("already there", BatteryAnalytics.timeToLevelMs(100, 100, 60.0, "Charging"))
        assertNull("target passed", BatteryAnalytics.timeToLevelMs(90, 80, 60.0, "Charging"))
    }

    @Test
    fun anAbsurdlySlowRateGivesNoAnswerRatherThanAWeekLongCountdown() {
        assertNull(BatteryAnalytics.timeToLevelMs(5, 100, 0.2, "Charging"))
    }

    @Test
    fun chargeRateUsesOnlyTheMostRecentRise() {
        val h = 3_600_000L
        // A dip in the middle (heavy app while plugged in) must start a fresh run.
        val samples = listOf(0L to 40, h to 60, 2 * h to 50, 3 * h to 80)
        assertEquals(30.0, BatteryAnalytics.chargeRatePctPerHour(samples)!!, 0.01)
    }

    @Test
    fun chargeRateIsNullWithoutEnoughToMeasure() {
        assertNull(BatteryAnalytics.chargeRatePctPerHour(emptyList()))
        assertNull(BatteryAnalytics.chargeRatePctPerHour(listOf(0L to 50)))
        assertNull("span too short", BatteryAnalytics.chargeRatePctPerHour(listOf(0L to 50, 30_000L to 52)))
        assertNull("no gain", BatteryAnalytics.chargeRatePctPerHour(listOf(0L to 50, 3_600_000L to 50)))
    }

    // ── health confidence ─────────────────────────────────────────────────────

    @Test
    fun oneShallowSessionIsNotAsTrustworthyAsTen() {
        val one = BatteryAnalytics.sohUncertaintyPts(CapacitySource.SESSION, 1, null, false)!!
        val ten = BatteryAnalytics.sohUncertaintyPts(CapacitySource.SESSION, 10, 4.0, false)!!
        assertTrue("one session: $one, ten: $ten", one > ten)
    }

    @Test
    fun aTypicalDesignFigureWidensTheErrorBar() {
        val exact = BatteryAnalytics.sohUncertaintyPts(CapacitySource.GAUGE, 0, null, false)!!
        val typical = BatteryAnalytics.sohUncertaintyPts(CapacitySource.GAUGE, 0, null, true)!!
        assertTrue(typical > exact)
    }

    @Test
    fun theLiveEstimateIsNeverHighConfidence() {
        val u = BatteryAnalytics.sohUncertaintyPts(CapacitySource.ESTIMATE, 0, null, false)
        assertEquals(Confidence.LOW, BatteryAnalytics.confidenceFor(u))
    }

    @Test
    fun theFuelGaugeIsHighConfidence() {
        val u = BatteryAnalytics.sohUncertaintyPts(CapacitySource.GAUGE, 0, null, false)
        assertEquals(Confidence.HIGH, BatteryAnalytics.confidenceFor(u))
    }

    @Test
    fun noSourceMeansNoConfidenceAndNoErrorBar() {
        assertNull(BatteryAnalytics.sohUncertaintyPts(CapacitySource.NONE, 0, null, false))
        assertEquals(Confidence.NONE, BatteryAnalytics.confidenceFor(null))
    }

    @Test
    fun widelyDisagreeingSessionsAreReportedAsLowConfidence() {
        val u = BatteryAnalytics.sohUncertaintyPts(CapacitySource.SESSION, 2, 20.0, false)
        assertEquals(Confidence.LOW, BatteryAnalytics.confidenceFor(u))
    }

    // ── screen drain attribution ──────────────────────────────────────────────

    private val min = 60_000L

    @Test
    fun drainIsSplitInProportionToTimeInEachState() {
        val a = ScreenDrainAccumulator()
        a.reset(0L, 50, isScreenOn = true)
        // 10 minutes awake, then 10 asleep, then the level ticks down one point.
        assertNull(a.observe(10 * min, 50, isScreenOn = false, discharging = true))
        val s = a.observe(20 * min, 49, isScreenOn = false, discharging = true)!!
        assertEquals(0.5, s.onPct, 1e-9)
        assertEquals(0.5, s.offPct, 1e-9)
        assertEquals(10 * min, s.onMs)
        assertEquals(10 * min, s.offMs)
    }

    @Test
    fun aDropEntirelyOnScreenIsCreditedEntirelyToTheScreen() {
        val a = ScreenDrainAccumulator()
        a.reset(0L, 80, isScreenOn = true)
        val s = a.observe(5 * min, 79, isScreenOn = true, discharging = true)!!
        assertEquals(1.0, s.onPct, 1e-9)
        assertEquals(0.0, s.offPct, 1e-9)
    }

    @Test
    fun chargingProducesNoAttributionAtAll() {
        val a = ScreenDrainAccumulator()
        a.reset(0L, 50, isScreenOn = false)
        assertNull(a.observe(10 * min, 51, isScreenOn = false, discharging = false))
    }

    @Test
    fun aClockJumpDiscardsThePendingWindowInsteadOfCorruptingIt() {
        val a = ScreenDrainAccumulator()
        a.reset(1_000_000L, 50, isScreenOn = true)
        // Time moves backwards — NTP correcting a phone that booted in 1970.
        assertNull(a.observe(0L, 49, isScreenOn = true, discharging = true))
        // And it recovers cleanly afterwards.
        assertNull(a.observe(5 * min, 49, isScreenOn = true, discharging = true))
        assertNotNull(a.observe(10 * min, 48, isScreenOn = true, discharging = true))
    }

    @Test
    fun aWindowLongerThanTheProcessCouldHaveWatchedIsDiscarded() {
        val a = ScreenDrainAccumulator()
        a.reset(0L, 60, isScreenOn = false)
        // Twelve hours in deep sleep: the screen history across it was never observed.
        assertNull(a.observe(12 * 60 * min, 55, isScreenOn = false, discharging = true))
    }

    @Test
    fun multipleDropsAccumulateWithoutDoubleCounting() {
        val a = ScreenDrainAccumulator()
        a.reset(0L, 50, isScreenOn = true)
        val first = a.observe(10 * min, 49, isScreenOn = true, discharging = true)!!
        val second = a.observe(20 * min, 48, isScreenOn = true, discharging = true)!!
        assertEquals(1.0, first.onPct, 1e-9)
        assertEquals(1.0, second.onPct, 1e-9)
        // The window resets between drops, so the second is not credited the first's time.
        assertEquals(10 * min, second.onMs)
    }

    @Test
    fun aMultiPointDropIsSplitAcrossBothStates() {
        val a = ScreenDrainAccumulator()
        a.reset(0L, 50, isScreenOn = true)
        assertNull(a.observe(30 * min, 50, isScreenOn = false, discharging = true))
        val s = a.observe(90 * min, 47, isScreenOn = false, discharging = true)!!
        assertEquals(3.0, s.onPct + s.offPct, 1e-9)
        assertEquals(1.0, s.onPct, 1e-9)   // 30 of 90 minutes awake
        assertEquals(2.0, s.offPct, 1e-9)
    }

    // ── existing algebra, re-pinned ───────────────────────────────────────────

    @Test
    fun stubGaugeContradictedByRealSessionsIsNotBelieved() {
        // A driver that mirrors design into charge_full pins health at exactly 100%
        // forever. It is caught by the contradiction, not by the value.
        val snap = snapshot(chargeFullUah = 4_355_000L, designFromProfile = 4355)
        val a = BatteryAnalytics.analyze(snap, NOW, sessionMeasuredMah = 3900, sessionCount = 6)
        assertEquals(CapacitySource.SESSION, a.capacitySource)
        assertEquals(3900, a.currentCapacityMah)
    }

    @Test
    fun aGenuinelyNewCellStillReadsFromTheGauge() {
        // Same near-design gauge value, but the sessions agree with it — nothing is wrong.
        val snap = snapshot(chargeFullUah = 4_355_000L, designFromProfile = 4355)
        val a = BatteryAnalytics.analyze(snap, NOW, sessionMeasuredMah = 4340, sessionCount = 6)
        assertEquals(CapacitySource.GAUGE, a.capacitySource)
    }

    @Test
    fun aWornGaugeReadingIsAlwaysBelieved() {
        val snap = snapshot(chargeFullUah = 3_800_000L, designFromProfile = 4355)
        val a = BatteryAnalytics.analyze(snap, NOW, sessionMeasuredMah = 3700, sessionCount = 6)
        assertEquals(CapacitySource.GAUGE, a.capacitySource)
    }

    private val NOW = 1_700_000_000_000L

    private fun snapshot(
        chargeFullUah: Long? = null,
        designFromProfile: Int? = null,
        level: Int = 50,
    ) = io.github.jenil1320.cellspan.data.BatterySnapshot(
        timestampMs = NOW, manufacturer = "Test", model = "TestPhone", device = "test",
        levelPct = level, status = "Discharging", pluggedIn = false, health = "Good",
        technology = "Li-ion", temperatureC = 25f, voltageV = 3.9f, currentUa = -500_000L,
        chargeCounterUah = null, cycleCount = 200,
        designCapacityFromProfileMah = designFromProfile,
        chargeFullUah = chargeFullUah,
    )

    @Test
    fun healthIsWithheldRatherThanClampedWhenTheDesignFigureIsOnlyTypical() {
        // A table entry for the wrong variant makes the ratio exceed 100%. Printing
        // "100%" there is a confident wrong answer; printing nothing is the honest one.
        val snap = io.github.jenil1320.cellspan.data.BatterySnapshot(
            timestampMs = NOW, manufacturer = "Google", model = "Pixel 7", device = "panther",
            levelPct = 50, status = "Discharging", pluggedIn = false, health = "Good",
            technology = "Li-ion", temperatureC = 25f, voltageV = 3.9f, currentUa = -500_000L,
            chargeCounterUah = null, cycleCount = 10,
        )
        // Pixel 7 is in the table at 4355 mAh; claim a much larger measured capacity.
        val a = BatteryAnalytics.analyze(snap, NOW, sessionMeasuredMah = 5200, sessionCount = 4)
        assertTrue("design should come from the table", a.designIsFromDatabase)
        assertNull("implausible ratio must not be clamped to 100%", a.sohPct)
    }

    @Test
    fun aSlightOverReadingOnAKnownDesignFigureIsStillCappedAtOneHundred() {
        val snap = snapshot(chargeFullUah = 4_400_000L, designFromProfile = 4355)
        val a = BatteryAnalytics.analyze(snap, NOW)
        assertFalse(a.designIsFromDatabase)
        assertEquals(100.0, a.sohPct!!, 1e-9)
    }
}
