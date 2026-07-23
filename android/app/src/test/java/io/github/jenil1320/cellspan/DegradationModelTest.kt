package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.DegradationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradationModelTest {

    @Test
    fun cyclicOnly_staysInASaneRangeAt800Cycles() {
        // Deliberately a WIDE sanity range, not a calibration anchor. An OEM's
        // "80% after ~800 cycles" is a whole-life field rating that already contains
        // years of calendar ageing, so pinning the cyclic term to it and then adding a
        // calendar term on top would double-count. This only catches gross breakage.
        val r = DegradationModel.estimate(
            equivalentFullCycles = 800.0, designCapacityMah = 4355,
        )!!
        assertTrue("modeled SoH out of sane range: ${r.modeledSohPct}", r.modeledSohPct in 70.0..90.0)
        assertEquals(0.0, r.calendarLossPct, 0.0001)
        assertTrue("no age -> no calendar term", !r.hasCalendar)
    }

    @Test
    fun referenceCase_isNotWildlyPessimistic() {
        // The conditions an OEM rating and EU Reg. 2023/1670 Annex II actually describe:
        // 800 cycles, four years, 25 C. The model should land in the same neighbourhood
        // as a real phone rather than predicting a nearly dead cell. It stays somewhat
        // pessimistic by construction (2013 cell coefficients) — that residual is
        // reported in the UI, not tuned away, so this asserts a band, not a target.
        val r = DegradationModel.estimate(
            equivalentFullCycles = 800.0, designCapacityMah = 4355,
            ageDays = 4 * 365.25, avgTempC = 25.0,
        )!!
        assertTrue("reference case should be 68-88%, got ${r.modeledSohPct}", r.modeledSohPct in 68.0..88.0)
        assertTrue("calendar term must be included when an age is given", r.hasCalendar)
    }

    @Test
    fun depthOfDischarge_defaultsToAPhoneValueNotAnEvValue() {
        // 0.7 is an EV/grid figure and supplies ~79% of the cyclic coefficient; phones
        // cycle much shallower. Guards against the default drifting back up.
        val r = DegradationModel.estimate(800.0, 4355)!!
        assertTrue("default DoD should be phone-scale, got ${r.dodUsed}", r.dodUsed <= 0.55)
    }

    @Test
    fun measuredDoD_isUsedWhenSupplied() {
        val r = DegradationModel.estimate(800.0, 4355, avgDoD = 0.28)!!
        assertEquals(0.28, r.dodUsed, 1e-9)
    }

    @Test
    fun deeperDoD_increasesCyclicWear() {
        val shallow = DegradationModel.estimate(800.0, 4355, avgDoD = 0.3)!!
        val deep = DegradationModel.estimate(800.0, 4355, avgDoD = 1.0)!!
        assertTrue(deep.cyclicLossPct > shallow.cyclicLossPct)
    }

    @Test
    fun hotterCalendar_increasesLoss() {
        // The calendar term's only measured input is temperature; hotter => more loss.
        val cool = DegradationModel.estimate(800.0, 4355, ageDays = 1000.0, avgTempC = 20.0)!!
        val hot = DegradationModel.estimate(800.0, 4355, ageDays = 1000.0, avgTempC = 40.0)!!
        assertTrue(hot.calendarLossPct > cool.calendarLossPct)
    }

    @Test
    fun calendarIncludedWhenAgeKnown() {
        val noAge = DegradationModel.estimate(800.0, 4355)!!
        val withAge = DegradationModel.estimate(
            800.0, 4355, ageDays = 1000.0, avgTempC = 30.0,
        )!!
        assertTrue(withAge.hasCalendar)
        assertTrue("calendar adds loss", withAge.calendarLossPct > 0.0)
        assertTrue("more total loss with age", withAge.modeledSohPct < noAge.modeledSohPct)
    }

    @Test
    fun nullWhenNoCyclesAndNoAge() {
        assertNull(DegradationModel.estimate(equivalentFullCycles = 0.0, designCapacityMah = 4355))
        assertNull(DegradationModel.estimate(equivalentFullCycles = null, designCapacityMah = null))
    }
}
