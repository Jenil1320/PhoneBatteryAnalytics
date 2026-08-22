package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jenil1320.cellspan.ui.theme.Cell

/** A battery parameter, each with its own hand-drawn glyph. */
enum class Metric { TEMPERATURE, VOLTAGE, CURRENT, POWER, HEALTH, SOC, CYCLES }

/**
 * Small line/solid glyph for a [Metric], drawn directly on a Canvas so the app
 * needs no icon-font dependency and the strokes match the palette's weight. All
 * coordinates are fractions of the box, so the same paths scale to any size.
 */
@Composable
fun MetricGlyph(metric: Metric, tint: Color, modifier: Modifier = Modifier, glyphSize: Dp = 15.dp) {
    Canvas(modifier.size(glyphSize)) {
        val w = size.width
        val sw = w * 0.11f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (metric) {
            Metric.TEMPERATURE -> {
                drawLine(tint, p(0.5f, 0.16f), p(0.5f, 0.64f), sw * 1.25f, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.15f, center = p(0.5f, 0.78f))
            }
            Metric.VOLTAGE -> drawPath(
                pathOf(0.56f to 0.10f, 0.30f to 0.54f, 0.48f to 0.54f,
                    0.42f to 0.90f, 0.72f to 0.44f, 0.53f to 0.44f), tint)
            Metric.CURRENT -> {
                val h = size.height
                val path = Path().apply {
                    moveTo(0.08f * w, 0.55f * h)
                    quadraticTo(0.26f * w, 0.20f * h, 0.44f * w, 0.50f * h)
                    quadraticTo(0.62f * w, 0.80f * h, 0.82f * w, 0.46f * h)
                }
                drawPath(path, tint, style = stroke)
            }
            Metric.POWER -> {
                drawArc(tint, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(0.15f * w, 0.34f * size.height),
                    size = Size(0.70f * w, 0.70f * size.height), style = stroke)
                drawLine(tint, p(0.5f, 0.69f), p(0.35f, 0.46f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.055f, center = p(0.5f, 0.69f))
            }
            Metric.HEALTH -> {
                val heart = Path().apply {
                    moveTo(0.5f * w, 0.32f * size.height)
                    cubicTo(0.42f * w, 0.17f * size.height, 0.15f * w, 0.21f * size.height, 0.15f * w, 0.43f * size.height)
                    cubicTo(0.15f * w, 0.61f * size.height, 0.40f * w, 0.73f * size.height, 0.5f * w, 0.86f * size.height)
                    cubicTo(0.60f * w, 0.73f * size.height, 0.85f * w, 0.61f * size.height, 0.85f * w, 0.43f * size.height)
                    cubicTo(0.85f * w, 0.21f * size.height, 0.58f * w, 0.17f * size.height, 0.5f * w, 0.32f * size.height)
                    close()
                }
                drawPath(heart, tint)
            }
            Metric.SOC -> {
                drawRoundRect(tint, topLeft = Offset(0.16f * w, 0.30f * size.height),
                    size = Size(0.60f * w, 0.40f * size.height),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f), style = stroke)
                drawRoundRect(tint, topLeft = Offset(0.78f * w, 0.42f * size.height),
                    size = Size(0.07f * w, 0.16f * size.height),
                    cornerRadius = CornerRadius(w * 0.03f, w * 0.03f))
                drawRoundRect(tint, topLeft = Offset(0.23f * w, 0.38f * size.height),
                    size = Size(0.30f * w, 0.24f * size.height),
                    cornerRadius = CornerRadius(w * 0.03f, w * 0.03f))
            }
            Metric.CYCLES -> {
                drawArc(tint, startAngle = 35f, sweepAngle = 285f, useCenter = false,
                    topLeft = Offset(0.22f * w, 0.22f * size.height),
                    size = Size(0.56f * w, 0.56f * size.height), style = stroke)
                // arrowhead at the open end (~320°)
                drawPath(strokePath(0.66f to 0.24f, 0.80f to 0.34f, 0.72f to 0.48f), tint, style = stroke)
            }
        }
    }
}

/** Fraction helper → an Offset in the current DrawScope's pixel space. */
private fun DrawScope.p(fx: Float, fy: Float) = Offset(fx * size.width, fy * size.height)

/** Build a filled polygon from fractional (x,y) vertices. */
private fun DrawScope.pathOf(vararg pts: Pair<Float, Float>): Path = Path().apply {
    pts.forEachIndexed { i, (x, y) ->
        if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height)
    }
    close()
}

/** Build an open polyline from fractional (x,y) vertices (for arrowheads / chevrons). */
private fun DrawScope.strokePath(vararg pts: Pair<Float, Float>): Path = Path().apply {
    pts.forEachIndexed { i, (x, y) ->
        if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height)
    }
}

/**
 * A slim range gauge: a rounded track with a coloured fill up to [fraction]. The
 * colour already encodes the status (good/warn/bad), so the bar just shows where
 * the reading sits within its normal span.
 */
@Composable
fun MetricGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    /**
     * When supplied, the bar is painted as a gradient that runs from the safe colour up to
     * [color], instead of a flat fill. That turns the bar itself into the warning: a value
     * on its way to trouble shows amber creeping in along its length before it ever
     * reaches red, rather than staying green and then switching all at once.
     */
    severity: Float? = null,
) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Cell.Track)) {
        val fill: Modifier = if (severity != null && severity > 0.02f) {
            Modifier.background(
                Brush.horizontalGradient(
                    listOf(androidx.compose.ui.graphics.lerp(Cell.Good, color, 0.35f), color)
                )
            )
        } else {
            Modifier.background(color)
        }
        Box(Modifier.fillMaxWidth(f).height(5.dp).clip(CircleShape).then(fill))
    }
}
