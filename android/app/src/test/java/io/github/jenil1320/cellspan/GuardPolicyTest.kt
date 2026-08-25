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

    private val band = Config(lowTarget = 20, highTarget = 80, strictness = Strictness.GENTLE, tempAlertC = null)
    private val repeating = band.copy(strictness = Strictness.PERSISTENT)
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
    fun criticalRespectsTheUsersStrictnessChoice() {
        // "Tell me once" means once, even at 3%. Overriding it was paternalistic: the usual
        // reason someone has not plugged in is that they cannot, and a phone that keeps
        // buzzing at them about it is exactly how an app gets muted for good.
        assertEquals(Long.MAX_VALUE, GuardPolicy.repeatMs(AlertKind.CRITICAL, Strictness.GENTLE))
        assertTrue(GuardPolicy.repeatMs(AlertKind.CRITICAL, Strictness.INSISTENT) < 5 * 60_000L)
    }

    @Test
    fun tellMeOnceNeverRepeatsForAnyAlert() {
        for (k in AlertKind.entries) {
            if (k == AlertKind.OVERHEAT) continue    // heat has its own re-alert window
            assertEquals("$k repeats on GENTLE", Long.MAX_VALUE, GuardPolicy.repeatMs(k, Strictness.GENTLE))
        }
    }

    @Test
    fun escalatingFromPlugInToCriticalAlertsImmediately() {
        var s = State()
        var d = GuardPolicy.evaluate(repeating, Reading(20, false, null), s, t0)
        assertTrue(d.audible); s = d.state
        // One minute later, well inside the persistent repeat gap, but now critical.
        d = GuardPolicy.evaluate(repeating, Reading(4, false, null), s, t0 + 60_000)
        assertEquals(AlertKind.CRITICAL, d.kind)
        assertTrue("a change of alert always speaks", d.audible)
    }

    // ── repetition and clearing ───────────────────────────────────────────────

    @Test
    fun gentleAlertsOnceAndThenStaysQuiet() {
        val cfg = band
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
        var d = GuardPolicy.evaluate(repeating, Reading(80, true, null), s, t0)
        assertTrue(d.audible); s = d.state
        // Every one-percent tick in between must be silent, or the phone buzzes constantly.
        d = GuardPolicy.evaluate(repeating, Reading(81, true, null), s, t0 + 60_000)
        assertFalse(d.audible); s = d.state
        d = GuardPolicy.evaluate(repeating, Reading(82, true, null), s, t0 + 11 * 60_000)
        assertTrue("due after the unplug interval", d.audible)
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
            s = GuardPolicy.evaluate(repeating, Reading(80 + i, true, null), s, t0 + i * 11L * 60_000).state
        }
        assertTrue(s.audibleCount > 1)
        s = GuardPolicy.evaluate(repeating, Reading(70, false, null), s, t0 + 60 * 60_000).state
        assertEquals(0, s.audibleCount)
        assertNull(s.activeKind)
        // The next breach starts fresh rather than inheriting a spent budget.
        val d = GuardPolicy.evaluate(repeating, Reading(20, false, null), s, t0 + 90 * 60_000)
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
            t += 6 * 60_000
        }
        assertEquals(GuardPolicy.MAX_AUDIBLE_REPEATS, s.audibleCount)
        val d = GuardPolicy.evaluate(cfg, Reading(4, false, null), s, t)
        assertEquals(AlertKind.CRITICAL, d.kind)
        assertTrue("critical must never be silenced by the repeat cap", d.audible)
    }

    @Test
    fun theRepeatBudgetStillBoundsTheLoudestSetting() {
        // Even on the loudest setting the phone must eventually stop buzzing, or what the
        // user silences is the app rather than the alert.
        val cfg = band.copy(strictness = Strictness.INSISTENT)
        var s = State()
        var audible = 0
        for (i in 0 until 200) {
            val d = GuardPolicy.evaluate(cfg, Reading(3, false, null), s, t0 + i * 6L * 60_000)
            if (d.audible) audible++
            s = d.state
        }
        assertEquals(GuardPolicy.MAX_AUDIBLE_REPEATS, audible)
    }

    @Test
    fun aStateRestoredFromPreferencesStillAlertsOnSchedule() {
        // After a service restart the guard reloads its persisted state. activeKind is
        // already UNPLUG, so `changed` is false and only the repeat interval can speak.
        val restored = State(activeKind = AlertKind.UNPLUG, lastAlertMs = t0, audibleCount = 3)
        // Inside the interval: silent, but still pinned.
        val soon = GuardPolicy.evaluate(repeating, Reading(85, true, null), restored, t0 + 60_000)
        assertEquals(AlertKind.UNPLUG, soon.kind)
        assertFalse(soon.audible)
        // Past it: speaks again.
        val later = GuardPolicy.evaluate(repeating, Reading(85, true, null), restored, t0 + 11 * 60_000)
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
            val d = GuardPolicy.evaluate(repeating, Reading(85, true, null), s, t0 + i * 11L * 60_000)
            if (d.audible) audible++
            s = d.state
        }
        assertEquals(GuardPolicy.MAX_AUDIBLE_REPEATS, audible)
        // Still pinned though — the condition has not gone away.
        assertEquals(AlertKind.UNPLUG, GuardPolicy.evaluate(repeating, Reading(85, true, null), s, t0 + 99_999_999).kind)
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

    // ── when the guard runs at all ─────────────────────────────────────────────

    @Test
    fun theGuardDoesNotWatchWhenNothingIsNear() {
        // The whole point of the change: at 70% on battery with a 20% target, nothing can
        // happen for hours, so there must be no service and no card in the shade.
        assertFalse(GuardPolicy.shouldWatch(band, Reading(70, false, null), deliveredFor = null))
        assertFalse(GuardPolicy.shouldWatch(band, Reading(36, false, null), deliveredFor = null))
    }

    @Test
    fun theGuardStartsWatchingOnceTheLowTargetIsInReach() {
        assertTrue(GuardPolicy.shouldWatch(band, Reading(35, false, null), deliveredFor = null))
        assertTrue(GuardPolicy.shouldWatch(band, Reading(21, false, null), deliveredFor = null))
    }

    @Test
    fun theGuardWatchesTheWholeChargeBecauseChargingIsFast() {
        // A 65 W charger crosses ten points in under three minutes, so there is no useful
        // margin to wait for — once the cable is in, watch all the way up.
        assertTrue(GuardPolicy.shouldWatch(band, Reading(5, true, null), deliveredFor = null))
        assertTrue(GuardPolicy.shouldWatch(band, Reading(79, true, null), deliveredFor = null))
    }

    @Test
    fun pluggingInAlreadyAboveTheTargetStillWarns() {
        // The moment the reminder is MOST useful. Requiring the level to be below the
        // target first meant the guard never started for this user, so they were never
        // told at all — they simply left it on the charger at 95%.
        assertTrue(GuardPolicy.shouldWatch(band, Reading(95, true, null), deliveredFor = null))
        assertEquals(AlertKind.UNPLUG, GuardPolicy.kindFor(band, Reading(95, true, null)))
    }

    @Test
    fun theGuardStandsDownOnceTheReminderHasBeenGiven() {
        // Not the level that stops it — the delivered reminder does, one reading later.
        assertFalse(GuardPolicy.shouldWatch(band, Reading(80, true, null), deliveredFor = GuardPolicy.episodeKey(band, Reading(50, true, null))))
        assertFalse(GuardPolicy.shouldWatch(band, Reading(20, false, null), deliveredFor = null))
    }

    @Test
    fun aStaleKeyDoesNotSilenceTheGuardForEver() {
        // The failure this pins shut: a plain "already done" flag could only be cleared by
        // code that was running, and the flag's whole purpose was to stop the guard
        // running. One plug event missed while the app was asleep left it stuck, and no
        // reminder ever fired again at any level. A key from a DIFFERENT situation simply
        // stops matching, so the guard wakes up on its own.
        val unpluggedKey = GuardPolicy.episodeKey(band, Reading(30, false, null))
        assertTrue("a key from the other cable state must not silence this one",
            GuardPolicy.shouldWatch(band, Reading(60, true, null), deliveredFor = unpluggedKey))
        // And moving a target changes the key too.
        val moved = band.copy(highTarget = 90)
        assertTrue(GuardPolicy.shouldWatch(moved, Reading(60, true, null),
            deliveredFor = GuardPolicy.episodeKey(band, Reading(60, true, null))))
    }

    @Test
    fun aDeliveredReminderStopsTheGuardEvenMidCharge() {
        assertFalse(GuardPolicy.shouldWatch(band, Reading(50, true, null), deliveredFor = GuardPolicy.episodeKey(band, Reading(50, true, null))))
        assertFalse(GuardPolicy.shouldWatch(band, Reading(25, false, null),
            deliveredFor = GuardPolicy.episodeKey(band, Reading(25, false, null))))
    }

    @Test
    fun anEndTurnedOffIsNeverWatched() {
        val lowOnly = band.copy(highTarget = 0)
        assertFalse(GuardPolicy.shouldWatch(lowOnly, Reading(50, true, null), deliveredFor = null))
        val highOnly = band.copy(lowTarget = 0)
        assertFalse(GuardPolicy.shouldWatch(highOnly, Reading(25, false, null), deliveredFor = null))
    }

    // ── heat is evaluated without disturbing the band ──────────────────────────

    @Test
    fun evaluateHeatLeavesTheBandStateAlone() {
        // The hourly worker uses this. If it went through evaluate() it would rewrite the
        // band fields and could mark a reminder as already given without ever posting one.
        val hotCfg = band.copy(tempAlertC = 45f)
        val mid = State(activeKind = AlertKind.UNPLUG, lastAlertMs = t0, audibleCount = 4)
        val d = GuardPolicy.evaluateHeat(hotCfg, Reading(90, true, 48f), mid, t0 + 60_000)
        assertTrue("heat should fire", d.tempAlert)
        assertEquals("band alert must be untouched", AlertKind.UNPLUG, d.state.activeKind)
        assertEquals("repeat clock must be untouched", t0, d.state.lastAlertMs)
        assertEquals("repeat budget must be untouched", 4, d.state.audibleCount)
        assertFalse("heat never makes the band audible", d.audible)
    }

    @Test
    fun evaluateHeatSharesTheHysteresisWithTheService() {
        val hotCfg = band.copy(tempAlertC = 45f)
        val first = GuardPolicy.evaluateHeat(hotCfg, Reading(50, false, 47f), State(), t0)
        assertTrue(first.tempAlert)
        // A second check a minute later must not warn again about the same episode.
        val second = GuardPolicy.evaluateHeat(hotCfg, Reading(50, false, 48f), first.state, t0 + 60_000)
        assertFalse(second.tempAlert)
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
