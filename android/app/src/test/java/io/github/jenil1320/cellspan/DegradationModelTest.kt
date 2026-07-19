package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.DegradationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradationModelTest {

    @Test
    fun cyclicOnly_matchesPixelAnchorAt800Cycles() {
        // Google rates the Pixel 7 at ~80% capacity after ~800 cycles. The Schmalstieg
        // cyclic term at a typical phone cycle-mean voltage/DoD should land near that.
        val r = DegradationModel.estimate(
            equivalentFullCycles = 800.0, designCapacityMah = 4355,
        )!!
        assertTrue("modeled SoH ~78% expected, got ${r.modeledSohPct}", r.modeledSohPct in 74.0..82.0)
        assertEquals(0.0, r.calendarLossPct, 0.0001)
        assertTrue(r.cyclicLossPct in 18.0..26.0)
        assertTrue("no age -> no calendar term", !r.hasCalendar)
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
