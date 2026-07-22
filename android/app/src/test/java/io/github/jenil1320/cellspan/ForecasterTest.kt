package io.github.jenil1320.cellspan

import io.github.jenil1320.cellspan.analytics.DegradationModel
import io.github.jenil1320.cellspan.analytics.DrainForecaster
import io.github.jenil1320.cellspan.analytics.SohForecaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecasterTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val physics = { c: Double -> DegradationModel.cyclicLossFrac(c, 4355) }

    // ── SoH forecaster ─────────────────────────────────────────────────────────
    @Test
    fun wls_fitsKnownLine() {
        val xs = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val ys = xs.map { 10.0 - 2.0 * it }
        val line = SohForecaster.wls(xs, ys, DoubleArray(xs.size) { 1.0 })
        assertEquals(10.0, line.a, 1e-6)
        assertEquals(-2.0, line.b, 1e-6)
    }

    @Test
    fun sohForecast_trendPredictsFutureDate() {
        val samples = listOf(
            SohForecaster.Sample(85.0, 700.0, now - 60 * day),
            SohForecaster.Sample(83.0, 750.0, now - 30 * day),
            SohForecaster.Sample(81.0, 800.0, now),
        )
        val f = SohForecaster.forecast(samples, 800.0, now, physics)
        assertEquals(SohForecaster.Method.TREND, f.method)
        assertNotNull("expected a projected 80% date", f.etaDateMs)
        assertTrue(f.etaDateMs!! > now)
        assertTrue(f.curve.isNotEmpty())
        assertNotNull(f.scaleVsModel)
        assertNotNull(f.fadePerYearPct)
        assertTrue("fade should be positive pts/yr", f.fadePerYearPct!! > 0.0)
    }

    @Test
    fun sohForecast_sparseFallsBackToPhysics() {
        val samples = listOf(
            SohForecaster.Sample(90.0, 400.0, now - 30 * day),
            SohForecaster.Sample(89.0, 450.0, now),
        )
        val f = SohForecaster.forecast(samples, 450.0, now, physics)
        assertEquals(SohForecaster.Method.PHYSICS_ONLY, f.method)
        assertNotNull(f.etaDateMs)
    }

    @Test
    fun sohForecast_noneWhenNoData() {
        val f = SohForecaster.forecast(emptyList(), 800.0, now, physics)
        assertEquals(SohForecaster.Method.NONE, f.method)
        assertNull(f.etaDateMs)
    }

    // ── Drain forecaster ───────────────────────────────────────────────────────
    @Test
    fun typicalDailyDrain_isMedian() {
        assertEquals(20.0, DrainForecaster.typicalDailyDrainPct(listOf(10.0, 20.0, 30.0))!!, 1e-6)
        assertEquals(25.0, DrainForecaster.typicalDailyDrainPct(listOf(10.0, 20.0, 30.0, 40.0))!!, 1e-6)
        assertNull(DrainForecaster.typicalDailyDrainPct(emptyList()))
    }

    @Test
    fun restOfDayCurve_projectsToEmpty() {
        // 50% at 10%/hr → empty in 5 hours.
        val curve = DrainForecaster.restOfDayCurve(now, 50, 10.0, stepMin = 60)
        assertEquals(50.0, curve.first().second, 1e-6)
        assertEquals(0.0, curve.last().second, 1e-6)
        assertEquals(now + 5 * 3_600_000L, curve.last().first)
    }

    @Test
    fun restOfDayCurve_emptyWhenNotDischarging() {
        assertTrue(DrainForecaster.restOfDayCurve(0L, 50, 0.0).isEmpty())
        assertTrue(DrainForecaster.restOfDayCurve(0L, 0, 10.0).isEmpty())
    }

    @Test
    fun smoothRate_ewma() {
        assertEquals(10.0, DrainForecaster.smoothRate(null, 10.0), 1e-6)
        assertEquals(0.3 * 20.0 + 0.7 * 10.0, DrainForecaster.smoothRate(10.0, 20.0), 1e-6)
    }
}
