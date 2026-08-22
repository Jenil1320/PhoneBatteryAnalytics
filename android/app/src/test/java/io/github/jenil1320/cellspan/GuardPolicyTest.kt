package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.data.GuardPolicy
import io.github.jenil1320.cellspan.data.GuardPolicy.AlertKind
import io.github.jenil1320.cellspan.data.GuardPolicy.Config
import io.github.jenil1320.cellspan.data.GuardPolicy.Reading
import io.github.jenil1320.cellspan.data.GuardPolicy.State
import io.github.jenil1320.cellspan.data.GuardPolicy.Strictness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery guard's decision core.
 *
 * The bug this whole subsystem was rebuilt to fix is the first test here: an alert has to
 * fire on the exact level the user chose, not somewhere past it. Everything else guards
 * the ways a reminder can go wrong in the other direction — nagging forever, refusing to
 * clear, or waking someone at 3 a.m. about a battery that is fine.
 */
class GuardPolicyTest {

    private val band = Config(lowTarget = 20, highTarget = 80, strictness = Strictness.PERSISTENT, tempAlertC = null)
    private val t0 = 1_700_000_000_000L

    // ── the reported bug ──────────────────────────────────────────────────────

    @Test
    fun unplugAlertFiresExactlyAtTheTarget_notAbove() {
        assertNull("79% must not alert", GuardPolicy.kindFor(band, Reading(79, pluggedIn = true, temperatureC = null)))
        assertEquals("80% must alert", AlertKind.UNPLUG,
            GuardPolicy.kindFor(band, Reading(80, pluggedIn = true, temperatureC = null)))
    }

    @Test
    fun plugInAlertFiresExactlyAtTheTarget_notBelow() {
        assertNull("21% must not alert", GuardPolicy.kindFor(band, Reading(21, pluggedIn = false, temperatureC = null)))
        assertEquals("20% must alert", AlertKind.PLUG_IN,
            GuardPolicy.kindFor(band, Reading(20, pluggedIn = false, temperatureC = null)))
    }

    @Test
    fun everyLevelInTheBandIsSilent() {
        for (l in 21..79) {
            assertNull("$l% on battery", GuardPolicy.kindFor(band, Reading(l, false, null)))
            assertNull("$l% plugged in", GuardPolicy.kindFor(band, Reading(l, true, null)))
        }
    }

    // ── which end applies ─────────────────────────────────────────────────────

    @Test
    fun theUnplugEndOnlyAppliesWhilePluggedIn() {
        assertNull(GuardPolicy.kindFor(band, Reading(95, pluggedIn = false, temperatureC = null)))
    }

    @Test
    fun thePlugInEndOnlyAppliesOnBattery() {
        assertNull(GuardPolicy.kindFor(band, Reading(10, pluggedIn = true, temperatureC = null)))
    }

    @Test
    fun aRootHeldChargeDoesNotNag() {
        // The kernel cap already did what the reminder would have asked for.
        assertNull(GuardPolicy.kindFor(band, Reading(85, pluggedIn = true, temperatureC = null, capHeld = true)))
    }

    @Test
    fun anEndTurnedOffNeverFires() {
        val lowOnly = band.copy(highTarget = 0)
        assertNull(GuardPolicy.kindFor(lowOnly, Reading(100, pluggedIn = true, temperatureC = null)))
        val highOnly = band.copy(lowTarget = 0)
        assertNull(GuardPolicy.kindFor(highOnly, Reading(1, pluggedIn = false, temperatureC = null)))
    }

    // ── escalation ────────────────────────────────────────────────────────────

    @Test
    fun belowTheCriticalLevelTheReminderEscalates() {
        assertEquals(AlertKind.CRITICAL,
            GuardPolicy.kindFor(band, Reading(GuardPolicy.CRITICAL_LEVEL, false, null)))
        assertEquals(AlertKind.CRITICAL, GuardPolicy.kindFor(band, Reading(1, false, null)))
    }

    @Test
    fun criticalIgnoresTheUsersStrictnessChoice() {
        // A single gentle chime is not a proportionate response to a phone at 3%.
        val gentle = GuardPolicy.repeatMs(AlertKind.CRITICAL, Strictness.GENTLE)
        val insistent = GuardPolicy.repeatMs(AlertKind.CRITICAL, Strictness.INSISTENT)
        assertEquals(gentle, insistent)
        assertTrue(gentle < 5 * 60_000L)
    }

    @Test
    fun escalatingFromPlugInToCriticalAlertsImmediately() {
        var s = State()
        var d = GuardPolicy.evaluate(band, Reading(20, false, null), s, t0)
        assertTrue(d.audible); s = d.state
        // One minute later, well inside the persistent repeat gap, but now critical.
        d = GuardPolicy.evaluate(band, Reading(4, false, null), s, t0 + 60_000)
        assertEquals(AlertKind.CRITICAL, d.kind)
        assertTrue("a change of alert always speaks", d.audible)
    }

    // ── repetition and clearing ───────────────────────────────────────────────

    @Test
    fun gentleAlertsOnceAndThenStaysQuiet() {
        val cfg = band.copy(strictness = Strictness.GENTLE)
        var s = State()
        var d = GuardPolicy.evaluate(cfg, Reading(80, true, null), s, t0)
        assertTrue(d.audible); s = d.state
        // An hour later, still over the target: pinned, but silent.
        d = GuardPolicy.evaluate(cfg, Reading(84, true, null), s, t0 + 3_600_000)
        assertEquals(AlertKind.UNPLUG, d.kind)
        assertFalse(d.audible)
    }

    @Test
    fun persistentRepeatsOnlyAfterItsInterval() {
        var s = State()
        var d = GuardPolicy.evaluate(band, Reading(80, true, null), s, t0)
        assertTrue(d.audible); s = d.state
        // Every one-percent tick in between must be silent, or the phone buzzes constantly.
        d = GuardPolicy.evaluate(band, Reading(81, true, null), s, t0 + 60_000)
        assertFalse(d.audible); s = d.state
        d = GuardPolicy.evaluate(band, Reading(82, true, null), s, t0 + 6 * 60_000)
        assertTrue("due after five minutes", d.audible)
    }

    @Test
    fun theReminderClearsOnTheVeryFirstReadingThatResolvesIt() {
        var s = State()
        s = GuardPolicy.evaluate(band, Reading(80, true, null), s, t0).state
        val d = GuardPolicy.evaluate(band, Reading(80, pluggedIn = false, temperatureC = null), s, t0 + 1000)
        assertNull("unplugged: nothing to say", d.kind)
    }

    @Test
    fun resolvingResetsTheRepeatBudget() {
        var s = State()
        repeat(4) { i ->
            s = GuardPolicy.evaluate(band, Reading(80 + i, true, null), s, t0 + i * 6L * 60_000).state
        }
        assertTrue(s.audibleCount > 1)
        s = GuardPolicy.evaluate(band, Reading(70, false, null), s, t0 + 60 * 60_000).state
        assertEquals(0, s.audibleCount)
        assertNull(s.activeKind)
        // The next breach starts fresh rather than inheriting a spent budget.
        val d = GuardPolicy.evaluate(band, Reading(20, false, null), s, t0 + 90 * 60_000)
        assertTrue(d.audible)
    }

    @Test
    fun criticalStillSoundsAfterTheRepeatBudgetIsSpent() {
        // The failure this pins: an hour of ignored plug-in reminders on the insistent
        // setting spends the 20-repeat budget, and the escalation to "about to shut down"
        // then arrived SILENTLY — the loudest setting making the most important alert the
        // quietest. Starting from a fresh State() could never catch it.
        val cfg = band.copy(strictness = Strictness.INSISTENT)
        var s = State()
        var t = t0
        while (s.audibleCount < GuardPolicy.MAX_AUDIBLE_REPEATS) {
            s = GuardPolicy.evaluate(cfg, Reading(20, false, null), s, t).state
            t += 4 * 60_000
        }
        assertEquals(GuardPolicy.MAX_AUDIBLE_REPEATS, s.audibleCount)
        val d = GuardPolicy.evaluate(cfg, Reading(4, false, null), s, t)
        assertEquals(AlertKind.CRITICAL, d.kind)
        assertTrue("critical must never be silenced by the repeat cap", d.audible)
    }

    @Test
    fun criticalKeepsSoundingEvenIndefinitely() {
        // Once critical, the cap does not apply at all: the phone is minutes from dying.
        val cfg = band.copy(strictness = Strictness.PERSISTENT)
        var s = State()
        var audible = 0
        for (i in 0 until 60) {
            val d = GuardPolicy.evaluate(cfg, Reading(3, false, null), s, t0 + i * 3L * 60_000)
            if (d.audible) audible++
            s = d.state
        }
        assertTrue("expected many critical alerts, got $audible", audible > GuardPolicy.MAX_AUDIBLE_REPEATS)
    }

    @Test
    fun aStateRestoredFromPreferencesStillAlertsOnSchedule() {
        // After a service restart the guard reloads its persisted state. activeKind is
        // already UNPLUG, so `changed` is false and only the repeat interval can speak.
        val restored = State(activeKind = AlertKind.UNPLUG, lastAlertMs = t0, audibleCount = 3)
        // Inside the interval: silent, but still pinned.
        val soon = GuardPolicy.evaluate(band, Reading(85, true, null), restored, t0 + 60_000)
        assertEquals(AlertKind.UNPLUG, soon.kind)
        assertFalse(soon.audible)
        // Past it: speaks again.
        val later = GuardPolicy.evaluate(band, Reading(85, true, null), restored, t0 + 6 * 60_000)
        assertTrue(later.audible)
    }

    @Test
    fun aRestoredStateForAConditionThatHasResolvedClearsCleanly() {
        val restored = State(activeKind = AlertKind.PLUG_IN, lastAlertMs = t0, audibleCount = 2)
        val d = GuardPolicy.evaluate(band, Reading(60, false, null), restored, t0 + 60_000)
        assertNull(d.kind)
        assertEquals(0, d.state.audibleCount)
        assertNull(d.state.activeKind)
    }

    @Test
    fun audibleRepeatsAreCappedSoAPhoneCannotBuzzForever() {
        var s = State()
        var audible = 0
        // Ten hours of an unplug reminder being ignored.
        for (i in 0 until 200) {
            val d = GuardPolicy.evaluate(band, Reading(85, true, null), s, t0 + i * 6L * 60_000)
            if (d.audible) audible++
            s = d.state
        }
        assertEquals(GuardPolicy.MAX_AUDIBLE_REPEATS, audible)
        // Still pinned though — the condition has not gone away.
        assertEquals(AlertKind.UNPLUG, GuardPolicy.evaluate(band, Reading(85, true, null), s, t0 + 99_999_999).kind)
    }

    // ── temperature ───────────────────────────────────────────────────────────

    private val hot = band.copy(tempAlertC = 45f)

    @Test
    fun heatAlertsAtTheThresholdAndNotBelow() {
        assertFalse(GuardPolicy.evaluate(hot, Reading(50, false, 44.9f), State(), t0).tempAlert)
        assertTrue(GuardPolicy.evaluate(hot, Reading(50, false, 45.0f), State(), t0).tempAlert)
    }

    @Test
    fun heatDoesNotReAlertUntilTheCellHasCooledAndTimeHasPassed() {
        var s = State()
        var d = GuardPolicy.evaluate(hot, Reading(50, false, 47f), s, t0)
        assertTrue(d.tempAlert); s = d.state
        // Still hot a minute later: no second alert.
        d = GuardPolicy.evaluate(hot, Reading(50, false, 48f), s, t0 + 60_000)
        assertFalse(d.tempAlert); s = d.state
        // Cools to below the hysteresis floor, re-arming it.
        d = GuardPolicy.evaluate(hot, Reading(50, false, 40f), s, t0 + 120_000)
        assertFalse(d.tempAlert); s = d.state
        assertTrue(s.tempArmed)
        // Hot again, and past the quiet window.
        d = GuardPolicy.evaluate(hot, Reading(50, false, 46f), s, t0 + GuardPolicy.TEMP_REALERT_MS + 200_000)
        assertTrue(d.tempAlert)
    }

    @Test
    fun heatIsJudgedIndependentlyOfTheChargeBand() {
        // A phone can cook at 55% while gaming, nowhere near either end of the band.
        val d = GuardPolicy.evaluate(hot, Reading(55, false, 50f), State(), t0)
        assertNull(d.kind)
        assertTrue(d.tempAlert)
    }

    @Test
    fun heatAlertIsSilentWhenTheDeviceReportsNoTemperature() {
        assertFalse(GuardPolicy.evaluate(hot, Reading(50, false, null), State(), t0).tempAlert)
    }

    @Test
    fun heatAlertIsSilentWhenTurnedOff() {
        assertFalse(GuardPolicy.evaluate(band, Reading(50, false, 60f), State(), t0).tempAlert)
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    fun aFullBatteryOnTheCableStillAsksToUnplug() {
        assertEquals(AlertKind.UNPLUG, GuardPolicy.kindFor(band, Reading(100, true, null)))
    }

    @Test
    fun zeroPercentOnBatteryIsCritical() {
        assertEquals(AlertKind.CRITICAL, GuardPolicy.kindFor(band, Reading(0, false, null)))
    }

    @Test
    fun aBandWhoseEndsTouchDoesNotProduceContradictoryAlerts() {
        // Nothing stops a user picking a low reminder above the unplug target; the two
        // ends still depend on opposite plug states, so they can never both fire.
        val crossed = Config(lowTarget = 50, highTarget = 30, strictness = Strictness.PERSISTENT, tempAlertC = null)
        assertEquals(AlertKind.UNPLUG, GuardPolicy.kindFor(crossed, Reading(40, true, null)))
        assertEquals(AlertKind.PLUG_IN, GuardPolicy.kindFor(crossed, Reading(40, false, null)))
    }
}
