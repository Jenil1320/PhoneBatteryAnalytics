package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    threshold: Float? = null,
    thresholdLabel: String? = null,
    forecast: List<Pair<Float, Float>>? = null,
    scatter: Boolean = false,
    regressionLine: List<Pair<Float, Float>>? = null,
    lineColor: Color = Cell.Accent,
) {
    val tm = rememberTextMeasurer()
    val lbl = TextStyle(color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.sp)
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (points.isEmpty()) return@Canvas
        val ml = 46f; val mr = 12f; val mt = 12f; val mb = 22f
        val pw = size.width - ml - mr
        val ph = size.height - mt - mb

        val fc = forecast?.takeIf { it.size >= 2 }
        val ys = points.map { it.second }.toMutableList().also { l ->
            threshold?.let { l.add(it) }
            fc?.let { f -> l.addAll(f.map { it.second }) }
        }
        var ymin = ys.min(); var ymax = ys.max()
        if (ymin == ymax) { ymin -= 1f; ymax += 1f }
        val padY = (ymax - ymin) * 0.12f; ymin -= padY; ymax += padY
        val xs = points.map { it.first }
        val allX = xs + (fc?.map { it.first } ?: emptyList())
        var xmin = allX.min(); var xmax = allX.max()
        if (xmin == xmax) { xmin -= 1f; xmax += 1f }
        fun px(x: Float) = ml + (x - xmin) / (xmax - xmin) * pw
        fun py(y: Float) = mt + (ymax - y) / (ymax - ymin) * ph

        // Horizontal gridlines + y labels (top / mid / bottom of the real range).
        listOf(ymax - padY, (ymin + ymax) / 2f, ymin + padY).forEach { tk ->
            val y = py(tk)
            drawLine(Cell.Border, Offset(ml, y), Offset(ml + pw, y), 1f)
            val lay = tm.measure(yFormat(tk), lbl)
            drawText(lay, topLeft = Offset(ml - 6 - lay.size.width, y - lay.size.height / 2f))
        }

        // Threshold guide.
        threshold?.let { th ->
            val y = py(th)
            var x = ml
            while (x < ml + pw) {
                drawLine(Cell.Bad.copy(alpha = 0.7f), Offset(x, y), Offset(x + 6f, y), 1.5f); x += 11f
            }
            thresholdLabel?.let {
                val lay = tm.measure(it, lbl.copy(color = Cell.Bad))
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

        // X labels.
        if (xStart.isNotEmpty()) {
            val lay = tm.measure(xStart, lbl)
            drawText(lay, topLeft = Offset(ml, size.height - lay.size.height))
        }
        val end = tm.measure(xEnd, lbl)
        drawText(end, topLeft = Offset(ml + pw - end.size.width, size.height - end.size.height))
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
