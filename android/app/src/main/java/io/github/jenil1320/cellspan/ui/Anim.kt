package io.github.jenil1320.cellspan.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/** Fades and slides content up on first appearance (like the design's `rise`). */
@Composable
fun Appear(delayMillis: Int = 0, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(480), label = "appearAlpha")
    val dy by animateFloatAsState(if (shown) 0f else 18f, tween(480), label = "appearDy")
    Box(Modifier.alpha(a).graphicsLayer { translationY = dy * density }) {
        content()
    }
}
