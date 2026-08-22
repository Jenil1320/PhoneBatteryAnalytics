package io.github.jenil1320.cellspan.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics
import io.github.jenil1320.cellspan.analytics.ThermalAdvisor
import io.github.jenil1320.cellspan.ui.theme.Cell

/**
 * One continuous colour scale, shared by every live parameter on the screen.
 *
 * ## Why continuous rather than banded
 * The app used to pick a colour from a discrete band — good, watch, bad. That has two
 * problems a reader actually notices. A value creeping toward trouble looked identical to
 * one sitting comfortably in the middle of its band, so there was no warning until it
 * crossed and the tile changed all at once. And because each parameter had its own band
 * logic, the same colour meant different distances-from-trouble on different tiles.
 *
 * Every parameter now maps to a single **severity fraction** from 0 (comfortable) to 1
 * (at the limit), and that one number drives both the colour and the gauge fill. Green at
 * 0, amber through the middle, red at 1. A tile at 0.8 is visibly on its way to red before
 * it gets there, and 0.8 means the same thing on every tile.
 *
 * The mapping from each parameter to its fraction lives here, in one place, so the
 * thresholds cannot drift apart from the text labels the way they had.
 */
object Severity {

    /**
     * Colour for a severity fraction.
     *
     * Two segments rather than one, because a straight green-to-red interpolation passes
     * through a muddy olive in the middle that reads as neither safe nor alarming. Routing
     * it through the warning amber keeps every intermediate colour meaningful.
     */
    fun color(fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return if (f <= 0.5f) lerp(Cell.Good, Cell.Warn, f * 2f)
        else lerp(Cell.Warn, Cell.Bad, (f - 0.5f) * 2f)
    }

    /**
     * Temperature. Delegates to [ThermalAdvisor] so the tile colour and the safety warning
     * can never disagree about how hot is hot.
     *
     * Cold is folded in from the other end: below 5 °C the cell is heading for the
     * freezing hazard, and the tile should say so rather than reading as comfortably cool.
     */
    fun temperature(tempC: Float?): Float {
        if (tempC == null) return 0f
        if (tempC < 5f) return ((5f - tempC) / 10f).coerceIn(0f, 1f)
        return ThermalAdvisor.severityFraction(tempC)
    }

    /**
     * Terminal voltage, judged from both ends.
     *
     * A cell is comfortable between about 3.6 V and 4.3 V. Below that it is approaching
     * empty or sagging under load; above it, it is at the top of its charge window where
     * time spent costs the most life. The scale reaches 1 at the same 3.40 V and 4.50 V
     * bounds [BatteryAnalytics.voltageReading] uses for its text label.
     */
    fun voltage(v: Float?): Float {
        if (v == null) return 0f
        return when {
            v < 3.6f -> ((3.6f - v) / (3.6f - 3.40f)).coerceIn(0f, 1f)
            v > 4.30f -> ((v - 4.30f) / (4.50f - 4.30f)).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /**
     * Power at the battery, in watts.
     *
     * Only meaningful while discharging: a high charge power is the charger doing its job,
     * not a problem. On battery, sustained draw above about 2 W is heavy and 8 W is close
     * to the most a phone can pull, so the scale spans that.
     */
    fun power(watts: Double?, charging: Boolean): Float {
        if (watts == null || charging) return 0f
        return (((watts - 2.0) / 6.0).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Charge level. Only the bottom end is a concern, and it turns red as the phone
     * approaches the point where it shuts down rather than at some arbitrary round number.
     */
    fun level(pct: Int): Float {
        if (pct > 30) return 0f
        return ((30f - pct) / 30f).coerceIn(0f, 1f)
    }

    /**
     * State of health against the 80% replacement line. 0 at a new cell, 1 at the line —
     * so the number shades gradually over years rather than turning red the day it crosses.
     */
    fun health(sohPct: Double?): Float {
        if (sohPct == null) return 0f
        val replace = BatteryAnalytics.REPLACE_SOH
        return (((100.0 - sohPct) / (100.0 - replace)).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Cycle count against the rated life. Deliberately gentle: rated cycle life varies
     * enormously between manufacturers, so this shades toward amber and only reaches red
     * well past the point any maker would call end of life.
     */
    fun cycles(count: Int?): Float {
        if (count == null) return 0f
        return (count / 1200f).coerceIn(0f, 1f)
    }
}
