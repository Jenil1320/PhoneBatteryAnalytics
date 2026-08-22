package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.ThermalAdvisor
import io.github.jenil1320.cellspan.analytics.ThermalAdvisor.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery-temperature safety advice.
 *
 * The point of this feature is that the advice is *actionable and correct for the
 * situation*, so most of these tests are about what it tells the user to do rather than
 * about the number it prints.
 */
class ThermalAdvisorTest {

    // ── when it speaks at all ─────────────────────────────────────────────────

    @Test
    fun aComfortableBatterySaysNothing() {
        assertNull(ThermalAdvisor.advise(25f, charging = false))
        assertNull(ThermalAdvisor.advise(35f, charging = true))
        assertNull(ThermalAdvisor.advise(39.9f, charging = true))
    }

    @Test
    fun itSaysNothingWhenTheDeviceReportsNoTemperature() {
        assertNull(ThermalAdvisor.advise(null, charging = true))
    }

    @Test
    fun theBandsFollowTheCellSpecification() {
        // 45 C is the top of the charge specification for lithium-ion; 40 C is where
        // ageing becomes noticeable; the phone protects itself around 50 C.
        assertEquals(Level.ELEVATED, ThermalAdvisor.advise(40f, false)!!.level)
        assertEquals(Level.ELEVATED, ThermalAdvisor.advise(44.9f, false)!!.level)
        assertEquals(Level.HOT, ThermalAdvisor.advise(45f, false)!!.level)
        assertEquals(Level.CRITICAL, ThermalAdvisor.advise(50f, false)!!.level)
        assertEquals(Level.DANGER, ThermalAdvisor.advise(55f, false)!!.level)
        assertEquals(Level.DANGER, ThermalAdvisor.advise(70f, false)!!.level)
    }

    // ── the advice is conditional on the cause ────────────────────────────────

    @Test
    fun aHotChargingPhoneIsToldToUnplugFirst() {
        // Charging is both the usual dominant heat source and the worst thing to be doing
        // while hot, so it must be the first action, not buried in a list.
        val a = ThermalAdvisor.advise(47f, charging = true)!!
        assertTrue("first action was: ${a.actions.first()}",
            a.actions.first().contains("Unplug", ignoreCase = true))
    }

    @Test
    fun aHotPhoneOnBatteryIsNeverToldToUnplug() {
        // Telling someone with no charger attached to unplug is noise, and noise is how a
        // safety warning gets ignored.
        val a = ThermalAdvisor.advise(47f, charging = false)!!
        assertTrue(a.actions.none { it.contains("Unplug", ignoreCase = true) })
    }

    @Test
    fun aHeavyLoadIsNamedAsTheCause() {
        val a = ThermalAdvisor.advise(46f, charging = false, powerWatts = 6.5)!!
        assertTrue("actions were ${a.actions}",
            a.actions.any { it.contains("games", ignoreCase = true) || it.contains("load", ignoreCase = true) })
    }

    @Test
    fun anIdleHotPhoneIsPointedAtExternalHeat() {
        // Hot with no charger and almost no load means sunlight, a car mount or a case.
        val a = ThermalAdvisor.advise(46f, charging = false, powerWatts = 0.4, screenOn = false)!!
        assertTrue(a.actions.any { it.contains("sun", ignoreCase = true) })
        assertTrue(a.actions.any { it.contains("case", ignoreCase = true) })
    }

    @Test
    fun everyWarningAlwaysOffersSomethingToDo() {
        for (t in listOf(40f, 45f, 50f, 58f)) {
            for (charging in listOf(true, false)) {
                val a = ThermalAdvisor.advise(t, charging)!!
                assertTrue("no actions at $t C charging=$charging", a.actions.isNotEmpty())
                assertTrue("no explanation at $t C", a.why.isNotBlank())
                assertTrue("no headline at $t C", a.headline.isNotBlank())
            }
        }
    }

    @Test
    fun theFridgeWarningAppearsOnlyWhenSomeoneMightActuallyTryIt() {
        // Condensation does more damage than the heat. Worth saying when the user is
        // alarmed enough to improvise, not before.
        assertTrue(ThermalAdvisor.advise(52f, false)!!.actions.any { it.contains("fridge", ignoreCase = true) })
        assertTrue(ThermalAdvisor.advise(41f, false)!!.actions.none { it.contains("fridge", ignoreCase = true) })
    }

    // ── the cold end ──────────────────────────────────────────────────────────

    @Test
    fun belowFreezingIsWarnedAboutAndTheAdviceIsReversed() {
        val a = ThermalAdvisor.advise(-3f, charging = true)!!
        assertTrue(a.headline.contains("freezing", ignoreCase = true))
        assertTrue("must stop the charge", a.actions.first().contains("Unplug", ignoreCase = true))
        assertTrue("must warn against a heat source",
            a.actions.any { it.contains("heat source", ignoreCase = true) })
        assertTrue(a.why.contains("plat", ignoreCase = true))
    }

    @Test
    fun aColdPhoneNotOnChargeIsStillWarnedButNotToldToUnplug() {
        val a = ThermalAdvisor.advise(-3f, charging = false)!!
        assertTrue(a.actions.none { it.contains("Unplug", ignoreCase = true) })
        assertNotNull(a.why)
    }

    // ── the colour scale ──────────────────────────────────────────────────────

    @Test
    fun severityRisesSmoothlyAcrossTheHazardRange() {
        assertEquals(0f, ThermalAdvisor.severityFraction(30f), 1e-6f)
        assertEquals(0f, ThermalAdvisor.severityFraction(ThermalAdvisor.ELEVATED_C), 1e-6f)
        // Half way between elevated and danger.
        assertEquals(0.5f, ThermalAdvisor.severityFraction(47.5f), 0.01f)
        assertEquals(1f, ThermalAdvisor.severityFraction(ThermalAdvisor.DANGER_C), 1e-6f)
        assertEquals(1f, ThermalAdvisor.severityFraction(80f), 1e-6f)
    }

    @Test
    fun severityIsMonotonicSoAGaugeNeverGoesBackwards() {
        var last = -1f
        var t = 20f
        while (t <= 60f) {
            val s = ThermalAdvisor.severityFraction(t)
            assertTrue("dropped at $t C", s >= last)
            last = s
            t += 0.5f
        }
    }

    @Test
    fun severityHandlesAMissingReading() {
        assertEquals(0f, ThermalAdvisor.severityFraction(null), 1e-6f)
    }
}
