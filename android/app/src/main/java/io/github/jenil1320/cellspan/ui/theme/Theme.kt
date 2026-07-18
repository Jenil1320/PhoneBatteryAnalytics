package io.github.jenil1320.cellspan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Palette taken from the design directions: dark, natural green, calm. */
object Cell {
    val Bg = Color(0xFF0B0D0B)
    val Surface = Color(0xFF151815)
    val SurfaceAlt = Color(0xFF141813)
    val Border = Color(0x14FFFFFF)
    val Accent = Color(0xFF86BD7A)
    val AccentSoft = Color(0x2986BD7A)
    val AccentDim = Color(0xFF5F9A52)
    val TextPrimary = Color(0xFFEDEFE9)
    val TextSecondary = Color(0xFFB9BFB2)
    val TextMuted = Color(0xFF8A9186)
    val TextFaint = Color(0xFF6A7268)
    val Good = Color(0xFF86BD7A)
    val Warn = Color(0xFFE0B15A)
    val Bad = Color(0xFFD46A5A)
    val Track = Color(0xFF23281F)
}

val Mono = FontFamily.Monospace
val Sans = FontFamily.SansSerif

private val Scheme = darkColorScheme(
    primary = Cell.Accent,
    onPrimary = Cell.Bg,
    background = Cell.Bg,
    onBackground = Cell.TextPrimary,
    surface = Cell.Surface,
    onSurface = Cell.TextPrimary,
)

@Composable
fun CellspanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
