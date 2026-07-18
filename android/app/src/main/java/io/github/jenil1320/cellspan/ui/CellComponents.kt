package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Cell.Surface)
            .border(1.dp, Cell.Border, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = Cell.TextFaint,
        fontFamily = Mono,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    )
}

@Composable
fun ConditionPill(label: String, color: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Cell.AccentSoft.let { if (color == Cell.Good) it else color.copy(alpha = 0.14f) })
            .border(1.dp, color.copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = Cell.TextPrimary, fontFamily = Mono, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(label.uppercase(), color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.6.sp)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = Cell.TextMuted, fontSize = 13.sp)
        Text(value, color = Cell.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Minimal time-series line chart. [points] are (x, y) pairs already in value
 * space; the chart auto-scales. An optional [threshold] draws a dashed guide.
 */
@Composable
fun LineChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = Cell.Accent,
    threshold: Float? = null,
) {
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        if (points.isEmpty()) return@Canvas
        val xs = points.map { it.first }
        val ys = points.map { it.second }.toMutableList()
        threshold?.let { ys.add(it) }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val spanX = (maxX - minX).takeIf { it != 0f } ?: 1f
        val spanY = (maxY - minY).takeIf { it != 0f } ?: 1f
        val pad = 6.dp.toPx()
        fun px(x: Float) = pad + (x - minX) / spanX * (size.width - 2 * pad)
        fun py(y: Float) = pad + (maxY - y) / spanY * (size.height - 2 * pad)

        threshold?.let { t ->
            val y = py(t)
            var x = 0f
            while (x < size.width) {
                drawLine(Cell.Bad.copy(alpha = 0.6f), Offset(x, y), Offset(x + 6f, y), strokeWidth = 1.5f)
                x += 12f
            }
        }
        if (points.size == 1) {
            drawCircle(lineColor, radius = 5f, center = Offset(px(xs[0]), py(points[0].second)))
            return@Canvas
        }
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            val cx = px(x); val cy = py(y)
            if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
        }
        drawPath(path, lineColor, style = Stroke(width = 6f))
        drawCircle(lineColor, radius = 5f, center = Offset(px(xs.last()), py(points.last().second)))
    }
}

@Composable
fun BarRow(label: String, fraction: Float, valueText: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Cell.TextMuted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.size(width = 56.dp, height = 16.dp))
        Box(
            Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Cell.Track)
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(14.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Cell.Accent)
            )
        }
        Text(valueText, color = Cell.TextMuted, fontFamily = Mono, fontSize = 11.sp)
    }
}
