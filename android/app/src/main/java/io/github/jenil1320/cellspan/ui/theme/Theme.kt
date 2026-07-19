@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.jenil1320.cellspan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.jenil1320.cellspan.R

/**
 * Palette from docs/design-directions.html: dark, natural green, calm — with a
 * bit more depth (elevated surfaces, gradients, a soft glow) so it reads as a
 * designed instrument rather than a flat template.
 */
object Cell {
    val Bg = Color(0xFF0A0C0A)
    val BgTop = Color(0xFF0F1310)
    val BgElevated = Color(0xFF12160F)
    val Surface = Color(0xFF161A14)
    val SurfaceHi = Color(0xFF1B2018)
    val Border = Color(0x16FFFFFF)
    val BorderHi = Color(0x22FFFFFF)
    val BorderAccent = Color(0x3386BD7A)

    val Accent = Color(0xFF86BD7A)
    val AccentBright = Color(0xFFA6D89A)
    val AccentLight = Color(0xFF8EC47E)
    val AccentDeep = Color(0xFF4F8A45)
    val AccentSoft = Color(0x2286BD7A)

    val TextPrimary = Color(0xFFEDEFE9)
    val TextSecondary = Color(0xFFBAC0B3)
    val TextStatus = Color(0xFFCFD3CA)
    val TextMuted = Color(0xFF868D80)
    val TextFaint = Color(0xFF636A5D)

    val Good = Color(0xFF86BD7A)
    val Warn = Color(0xFFD9B45A)
    val Bad = Color(0xFFD3705C)
    val Cool = Color(0xFF6FB6CC)

    val Track = Color(0xFF1E241A)

    val liquid: Brush get() = Brush.verticalGradient(listOf(AccentLight, AccentDeep))
    fun liquid(top: Color, bottom: Color): Brush = Brush.verticalGradient(listOf(top, bottom))
    val pageBackground: Brush get() = Brush.verticalGradient(listOf(BgTop, Bg))
    val glow: Brush get() = Brush.radialGradient(listOf(AccentSoft, Color.Transparent))
}

// Real design typefaces (bundled, OFL): Space Grotesk display + IBM Plex Mono data.
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

// Aliases used across the UI.
val Sans = SpaceGrotesk
val Mono = PlexMono

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
