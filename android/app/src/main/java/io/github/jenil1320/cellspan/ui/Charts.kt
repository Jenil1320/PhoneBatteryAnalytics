package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono

/**
 * Annotated time-series line chart: y-axis value ticks, x-axis start/end labels,
 * gridlines, a labelled threshold, a gradient area fill and the latest point.
 * [points] are (x, y) with x already normalised (e.g. days since first sample).
 */
@Composable
fun TimeSeriesChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    height: Dp = 152.dp,
    yFormat: (Float) -> String = { it.toInt().toString() },
    xStart: String = "",
    xEnd: String = "now",
    xMid: String = "",
    threshold: Float? = null,
    thresholdLabel: String? = null,
    forecast: List<Pair<Float, Float>>? = null,
    scatter: Boolean = false,
    regressionLine: List<Pair<Float, Float>>? = null,
    lineColor: Color = Cell.Accent,
    thresholdColor: Color = Cell.Bad,
    xValueLabel: ((Float) -> String)? = null,
) {
    val tm = rememberTextMeasurer()
    val lbl = TextStyle(color = Cell.TextMuted, fontFamily = Mono, fontSize = 10.sp)
    // Long-press then slide to scrub: shows a crosshair with the value (and time) there.
    var touchX by remember { mutableStateOf<Float?>(null) }
    val chartMod = modifier.fillMaxWidth().height(height).pointerInput(points) {
        detectDragGesturesAfterLongPress(
            onDragStart = { touchX = it.x },
            onDrag = { change, _ -> touchX = change.position.x; change.consume() },
            onDragEnd = { touchX = null },
            onDragCancel = { touchX = null },
        )
    }
    Canvas(chartMod) {
        if (points.isEmpty()) return@Canvas

        val fc = forecast?.takeIf { it.size >= 2 }
        val ys = points.map { it.second }.toMutableList().also { l ->
            threshold?.let { l.add(it) }
            fc?.let { f -> l.addAll(f.map { it.second }) }
        }
        var ymin = ys.min(); var ymax = ys.max()
        if (ymin == ymax) { ymin -= 1f; ymax += 1f }
        val padY = (ymax - ymin) * 0.12f; ymin -= padY; ymax += padY

        // Size the left margin to the widest y-label (units included), with a clear
        // gap between the numbers and the gridlines so nothing crowds the plot.
        val yTicks = listOf(ymax - padY, (ymin + ymax) / 2f, ymin + padY)
        val yGap = 12f
        val ml = yTicks.maxOf { tm.measure(yFormat(it), lbl).size.width }.toFloat() + yGap + 4f
        val mr = 14f; val mt = 14f; val mb = 24f
        val pw = size.width - ml - mr
        val ph = size.height - mt - mb

        val xs = points.map { it.first }
        val allX = xs + (fc?.map { it.first } ?: emptyList())
        var xmin = allX.min(); var xmax = allX.max()
        if (xmin == xmax) { xmin -= 1f; xmax += 1f }
        fun px(x: Float) = ml + (x - xmin) / (xmax - xmin) * pw
        fun py(y: Float) = mt + (ymax - y) / (ymax - ymin) * ph

        // Horizontal gridlines + y labels (top / mid / bottom of the real range).
        yTicks.forEach { tk ->
            val y = py(tk)
            drawLine(Cell.Border, Offset(ml, y), Offset(ml + pw, y), 1f)
            val lay = tm.measure(yFormat(tk), lbl)
            drawText(lay, topLeft = Offset(ml - yGap - lay.size.width, y - lay.size.height / 2f))
        }

        // Threshold guide.
        threshold?.let { th ->
            val y = py(th)
            var x = ml
            while (x < ml + pw) {
                drawLine(thresholdColor.copy(alpha = 0.7f), Offset(x, y), Offset(x + 6f, y), 1.5f); x += 11f
            }
            thresholdLabel?.let {
                val lay = tm.measure(it, lbl.copy(color = thresholdColor))
                drawText(lay, topLeft = Offset(ml + pw - lay.size.width, y - lay.size.height - 2))
            }
        }

        // Series: a scatter of points with a straight regression line, or a connected
        // area/line. Scatter reads cleaner for noisy per-sample data (e.g. capacity).
        if (scatter) {
            regressionLine?.takeIf { it.size >= 2 }?.let { rl ->
                val rp = Path()
                rl.forEachIndexed { i, (x, y) -> if (i == 0) rp.moveTo(px(x), py(y)) else rp.lineTo(px(x), py(y)) }
                drawPath(rp, lineColor, style = Stroke(3.5f, cap = StrokeCap.Round))
            }
            points.forEach { (x, y) -> drawCircle(lineColor.copy(alpha = 0.65f), 3.4f, Offset(px(x), py(y))) }
        } else if (points.size >= 2) {
            val line = Path(); val area = Path()
            points.forEachIndexed { i, (x, y) ->
                val cx = px(x); val cy = py(y)
                if (i == 0) { line.moveTo(cx, cy); area.moveTo(cx, mt + ph); area.lineTo(cx, cy) }
                else { line.lineTo(cx, cy); area.lineTo(cx, cy) }
            }
            area.lineTo(px(xs.last()), mt + ph); area.close()
            drawPath(area, Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.22f), Color.Transparent), startY = mt, endY = mt + ph))
            drawPath(line, lineColor, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Forecast overlay (dashed, muted) — a projection drawn from the latest point.
        fc?.let { f ->
            val fp = Path()
            fp.moveTo(px(xs.last()), py(points.last().second))
            f.forEach { (x, y) -> fp.lineTo(px(x), py(y)) }
            drawPath(fp, lineColor.copy(alpha = 0.55f), style = Stroke(3.5f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }

        // Latest point marker.
        val lx = px(xs.last()); val ly = py(points.last().second)
        drawCircle(lineColor.copy(alpha = 0.2f), 12f, Offset(lx, ly))
        drawCircle(lineColor, 5.5f, Offset(lx, ly))

        // X labels: start (left), optional mid (centre), end (right).
        val xBase = size.height - tm.measure("0", lbl).size.height.toFloat()
        if (xStart.isNotEmpty()) {
            drawText(tm.measure(xStart, lbl), topLeft = Offset(ml, xBase))
        }
        if (xMid.isNotEmpty()) {
            val mid = tm.measure(xMid, lbl)
            drawText(mid, topLeft = Offset(ml + pw / 2f - mid.size.width / 2f, xBase))
        }
        val end = tm.measure(xEnd, lbl)
        drawText(end, topLeft = Offset(ml + pw - end.size.width, xBase))

        // Scrub overlay: a crosshair at the nearest point, with a value/time tooltip.
        touchX?.let { tx ->
            val cxTouch = tx.coerceIn(ml, ml + pw)
            val near = points.minByOrNull { kotlin.math.abs(px(it.first) - cxTouch) } ?: return@let
            val nx = px(near.first); val ny = py(near.second)
            var yy = mt
            while (yy < mt + ph) { drawLine(Cell.TextMuted.copy(alpha = 0.5f), Offset(nx, yy), Offset(nx, yy + 5f), 1.2f); yy += 9f }
            drawCircle(lineColor.copy(alpha = 0.2f), 11f, Offset(nx, ny))
            drawCircle(lineColor, 5.5f, Offset(nx, ny))
            val tip = TextStyle(color = Cell.OnTile, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            val text = xValueLabel?.invoke(near.first)?.let { "$it   ${yFormat(near.second)}" } ?: yFormat(near.second)
            val lay = tm.measure(text, tip)
            val boxW = lay.size.width + 16f; val boxH = lay.size.height + 10f
            val boxX = (nx - boxW / 2f).coerceIn(ml, ml + pw - boxW)
            drawRoundRect(Cell.TileCharcoal, topLeft = Offset(boxX, mt), size = Size(boxW, boxH),
                cornerRadius = CornerRadius(6f, 6f))
            drawText(lay, topLeft = Offset(boxX + 8f, mt + 5f))
        }
    }
}

/** Vertical bar chart for daily usage: (label, value) pairs, newest last. */
@Composable
fun UsageBars(
    bars: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    height: Dp = 184.dp,
    valueFormat: (Float) -> String = { String.format("%.2f", it) },
    barColor: Color = Cell.Accent,
) {
    val tm = rememberTextMeasurer()
    val lbl = TextStyle(color = Cell.TextSecondary, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val valLbl = TextStyle(color = Cell.TextPrimary, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (bars.isEmpty()) return@Canvas
        // Reserve a clear band at the top for value labels and at the bottom for day
        // labels, so bars never overlap either. Sized to the measured text height.
        val labelH = tm.measure("Wg", lbl).size.height.toFloat()
        val valH = tm.measure("0.0", valLbl).size.height.toFloat()
        val mt = valH + 8f
        val mb = labelH + 14f
        val baseY = size.height - mb
        val ph = baseY - mt
        val maxV = (bars.maxOf { it.second }).coerceAtLeast(0.001f)
        val slot = size.width / bars.size
        val bw = slot * 0.52f

        // Faint baseline the bars sit on, separating them from the day labels.
        drawLine(Cell.Border, Offset(8f, baseY), Offset(size.width - 8f, baseY), 1.5f)

        bars.forEachIndexed { i, (label, v) ->
            val cx = slot * i + slot / 2f
            val bh = (v / maxV) * ph
            val topY = baseY - bh
            val left = cx - bw / 2f
            drawRoundRect(
                color = if (v > 0f) barColor else Cell.Track,
                topLeft = Offset(left, topY),
                size = androidx.compose.ui.geometry.Size(bw, bh.coerceAtLeast(2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f),
            )
            // Value in the top band, above the bar.
            if (v > 0f) {
                val vl = tm.measure(valueFormat(v), valLbl)
                drawText(vl, topLeft = Offset(cx - vl.size.width / 2f, (topY - vl.size.height - 4f).coerceAtLeast(0f)))
            }
            // Day label centred in the bottom band, below the baseline.
            val dl = tm.measure(label, lbl)
            drawText(dl, topLeft = Offset(cx - dl.size.width / 2f, baseY + (mb - dl.size.height) / 2f))
        }
    }
}
