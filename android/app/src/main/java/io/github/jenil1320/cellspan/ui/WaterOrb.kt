package io.github.jenil1320.cellspan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jenil1320.cellspan.ui.theme.Cell
import kotlin.math.PI
import kotlin.math.sin

/**
 * A circular liquid gauge whose fill level tracks [fraction] (0..1) and whose
 * surface wobbles with two offset sine waves. Center [content] is drawn on top.
 */
@Composable
fun WaterOrb(
    fraction: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 190.dp,
    top: Color = Cell.AccentLight,
    bottom: Color = Cell.AccentDeep,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val fill by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(1100),
        label = "fill",
    )
    val t = rememberInfiniteTransition(label = "waves")
    val twoPi = (2 * PI).toFloat()
    val phaseA by t.animateFloat(
        0f, twoPi, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "pa",
    )
    val phaseB by t.animateFloat(
        0f, twoPi, infiniteRepeatable(tween(3900, easing = LinearEasing)), label = "pb",
    )
    val bob by t.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(2300, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob",
    )

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val d = size.minDimension
            val r = d / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val circle = Path().apply {
                addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
            }
            clipPath(circle) {
                drawCircle(Cell.BgElevated, r, c)
                // Surface y: full at top when fill=1, at bottom when fill=0.
                val level = (c.y - r) + 2 * r * (1f - fill)
                val amp = (d * 0.05f).coerceAtLeast(3f)

                fun wave(phase: Float, a: Float, dy: Float, brush: Brush) {
                    val left = c.x - r
                    val steps = 48
                    val p = Path()
                    p.moveTo(left, c.y + r)
                    p.lineTo(left, level + dy)
                    for (i in 0..steps) {
                        val x = left + 2 * r * i / steps
                        val y = level + dy + a * sin(twoPi * 1.6f * i / steps + phase)
                        p.lineTo(x, y)
                    }
                    p.lineTo(left + 2 * r, c.y + r)
                    p.close()
                    drawPath(p, brush)
                }

                // Back wave (dimmer, gently bobbing) then front wave.
                wave(
                    phaseB, amp * 0.75f, bob * 2.5f,
                    Brush.verticalGradient(listOf(top.copy(alpha = 0.45f), bottom.copy(alpha = 0.45f))),
                )
                wave(
                    phaseA, amp, 0f,
                    Brush.verticalGradient(listOf(top, bottom)),
                )
            }
            // Rim.
            drawCircle(Cell.Border, r, c, style = Stroke(width = 2f))
            drawCircle(Cell.BorderAccent, r - 1f, c, style = Stroke(width = 1f))
        }
        content()
    }
}
