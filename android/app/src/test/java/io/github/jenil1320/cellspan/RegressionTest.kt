package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.DegradationModel
import io.github.jenil1320.cellspan.analytics.SohForecaster
import io.github.jenil1320.cellspan.data.BatterySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions found by review and fixed. Each test names the wrong behaviour it pins shut,
 * so a future change that reintroduces it fails here rather than on someone's phone.
 */
class RegressionTest {

    private val NOW = 1_700_000_000_000L
    private val DAY = 86_400_000L

    // ── the forecast used two different cycles-per-day rates ──────────────────

    /**
     * The loss callback must be asked about the same day the date axis is using.
     *
     * The caller used to convert cycles to days with the LIFETIME average rate while the
     * forecaster dated the same cycles with the RECENT rate it measured. On a phone whose
     * recent use is heavier than its lifetime average the two disagreed badly and the
     * bisection crossed the 80% line years early.
     */
    @Test
    fun theForecasterSuppliesTheAgeThatMatchesTheCycles() {
        val seen = ArrayList<Pair<Double, Double>>()
        // Three cycles a day over a fortnight, so the measured rate is unambiguous.
        val samples = (0..13).map { d ->
            SohForecaster.Sample(
                sohPct = 95.0 - d * 0.1,
                cycles = 300.0 + d * 3.0,
                epochMs = NOW - (13 - d) * DAY,
            )
        }
        SohForecaster.forecast(
            samples, cyclesNow = 339.0, nowMs = NOW,
            physicsLossFrac = { c, age -> seen.add(c to age); 0.0002 * c },
            ageDaysNow = 500.0,
        )
        assertTrue("callback was never used", seen.isNotEmpty())
        // For every point offered, the age must be consistent with 3 cycles/day.
        for ((cycles, age) in seen) {
            val expected = (500.0 + (cycles - 339.0) / 3.0).coerceAtLeast(0.0)
            assertEquals("age disagreed with cycles at $cycles", expected, age, 1.0)
        }
    }

    @Test
    fun historicalSamplesAreModelledAtTheAgeTheyActuallyHad() {
        // Older samples must get a SMALLER age, not today's. Using today's age for every
        // point inflated their modelled loss and biased the measured-versus-model ratio
        // toward "aging slower than typical".
        val ages = ArrayList<Double>()
        val samples = (0..13).map { d ->
            SohForecaster.Sample(90.0 - d * 0.2, 200.0 + d * 2.0, NOW - (13 - d) * DAY)
        }
        SohForecaster.forecast(
            samples, cyclesNow = 226.0, nowMs = NOW,
            physicsLossFrac = { c, age -> if (c <= 226.0) ages.add(age); 0.0002 * c },
            ageDaysNow = 400.0,
        )
        assertTrue("no historical points evaluated", ages.isNotEmpty())
        assertTrue("every past sample used today's age", ages.any { it < 399.0 })
    }

    @Test
    fun aMissingAgeStillProducesAForecast() {
        // ageDaysNow is optional; the caller falls back to a frozen calendar term.
        val samples = (0..13).map { d ->
            SohForecaster.Sample(90.0 - d * 0.2, 200.0 + d * 2.0, NOW - (13 - d) * DAY)
        }
        val f = SohForecaster.forecast(
            samples, cyclesNow = 226.0, nowMs = NOW,
            physicsLossFrac = { c, _ -> DegradationModel.cyclicLossFrac(c, 4355) },
        )
        assertNotNull(f)
    }

    // ── lifetime rates on a brand-new start date ──────────────────────────────

    /**
     * Setting the battery start date to today is the ordinary flow after a replacement.
     * It used to make the Health screen print "avg fade / year 4383 pts".
     */
    @Test
    fun lifetimeRatesAreWithheldUntilThereIsALifetime() {
        val a = BatteryAnalytics.analyze(
            snapshot(cycles = 812), NOW,
            sessionMeasuredMah = 4200, sessionCount = 5,
            batteryStartMs = NOW - 6 * 3_600_000L,     // six hours ago
        )
        assertNotNull("the age itself is still shown", a.ageDays)
        assertNull("no fade rate from six hours", a.avgFadePerYear)
        assertNull("no cycles-per-month from six hours", a.avgCyclesPerMonth)
    }

    @Test
    fun lifetimeRatesAppearOnceTheSpanIsMeaningful() {
        val a = BatteryAnalytics.analyze(
            snapshot(cycles = 300), NOW,
            sessionMeasuredMah = 4000, sessionCount = 5,
            batteryStartMs = NOW - 400L * DAY,
        )
        assertNotNull(a.avgCyclesPerMonth)
        assertNotNull(a.avgFadePerYear)
        assertTrue("fade should be a believable figure", a.avgFadePerYear!! in 0.0..60.0)
    }

    @Test
    fun anImplausibleFadeRateIsWithheldRatherThanPrinted() {
        // A cell reading 20% health at two months old means the age or the health is
        // wrong, not that it is losing 480 points a year.
        val a = BatteryAnalytics.analyze(
            snapshot(cycles = 50), NOW,
            sessionMeasuredMah = 900, sessionCount = 5,
            designOverrideMah = 4355,
            batteryStartMs = NOW - 60L * DAY,
        )
        assertNull("an absurd fade rate must not be shown", a.avgFadePerYear)
    }

    // ── the current-unit sniff had an unhandled band ──────────────────────────

    @Test
    fun everyRawMagnitudeFallsOnOneSideOfTheUnitTest() {
        // 5 000 to 19 999 used to be neither scaled nor treated as proof, so a 30 W charge
        // on a milliamp-bug ROM (raw 6 800) was published as 6.8 mA and labelled "Trickle".
        val scaled = io.github.jenil1320.cellspan.data.BatteryReader
            .normaliseCurrentUa(6_800L, "Charging", unitsKnownMicro = false)
        assertEquals(6_800_000L, scaled)
    }

    @Test
    fun aProvenMicroampDeviceIsStillLeftAloneInThatBand() {
        val untouched = io.github.jenil1320.cellspan.data.BatteryReader
            .normaliseCurrentUa(6_800L, "Charging", unitsKnownMicro = true)
        assertEquals(6_800L, untouched)
    }

    private fun snapshot(cycles: Int?) = BatterySnapshot(
        timestampMs = NOW, manufacturer = "Test", model = "TestPhone", device = "test",
        levelPct = 55, status = "Discharging", pluggedIn = false, health = "Good",
        technology = "Li-ion", temperatureC = 25f, voltageV = 3.9f, currentUa = -500_000L,
        chargeCounterUah = null, cycleCount = cycles,
        designCapacityFromProfileMah = 4355,
    )
}
