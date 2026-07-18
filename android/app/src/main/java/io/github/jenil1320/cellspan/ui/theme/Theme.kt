package io.github.jenil1320.cellspan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Palette lifted from docs/design-directions.html: dark, natural green, calm.
 * The healthy state is green-forward; warn/replace are tuned to sit in-palette.
 */
object Cell {
    val Bg = Color(0xFF0B0D0B)
    val BgElevated = Color(0xFF101310)
    val Surface = Color(0xFF151815)
    val SurfaceAlt = Color(0xFF141813)
    val Border = Color(0x14FFFFFF)
    val BorderAccent = Color(0x2986BD7A)

    val Accent = Color(0xFF86BD7A)
    val AccentBright = Color(0xFF9ECF90)
    val AccentLight = Color(0xFF8EC47E)
    val AccentDeep = Color(0xFF5F9A52)
    val AccentSoft = Color(0x1F86BD7A)

    val TextPrimary = Color(0xFFEDEFE9)
    val TextSecondary = Color(0xFFB9BFB2)
    val TextStatus = Color(0xFFCFD3CA)
    val TextMuted = Color(0xFF8A9186)
    val TextFaint = Color(0xFF6A7268)

    val Good = Color(0xFF86BD7A)
    val Warn = Color(0xFFD8B25A)
    val Bad = Color(0xFFCE6E5C)
    val Cool = Color(0xFF6FB3C9)

    val Track = Color(0xFF1C2119)

    /** Vertical liquid gradient used by the water orb and fills. */
    val liquid: Brush
        get() = Brush.verticalGradient(listOf(AccentLight, AccentDeep))

    fun liquid(top: Color, bottom: Color): Brush = Brush.verticalGradient(listOf(top, bottom))

    /** Soft radial glow behind hero elements. */
    val glow: Brush
        get() = Brush.radialGradient(listOf(AccentSoft, Color.Transparent))
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
