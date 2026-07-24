@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.jenil1320.cellspan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.jenil1320.cellspan.R

/**
 * Fresh light palette from palette.svg (Coolors): sage → teal → slate greens.
 * Light surfaces, white cards, and a row of saturated KPI tiles — the same
 * structure as the reference dashboard.
 *
 *   #84a98c sage · #52796f teal · #354f52 slate · #2f3e46 charcoal
 */
object Cell {
    // Surfaces
    val Bg = Color(0xFFEAEEE6)          // page background (light sage-gray)
    val BgTop = Color(0xFFF1F4EE)
    val BgElevated = Color(0xFFFFFFFF)  // cards
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHi = Color(0xFFF4F7F1)

    val Border = Color(0x14354F52)      // subtle dark hairline on light
    val BorderHi = Color(0x24354F52)
    val BorderAccent = Color(0x3352796F)

    // Greens (primary brand)
    val Accent = Color(0xFF52796F)      // primary teal-green
    val AccentBright = Color(0xFF84A98C)
    val AccentLight = Color(0xFF84A98C)
    val AccentDeep = Color(0xFF354F52)
    val AccentSoft = Color(0x2284A98C)

    // KPI tile fills (the saturated stat cards) + text that sits on them
    val TileTeal = Color(0xFF52796F)
    val TileSlate = Color(0xFF354F52)
    val TileCharcoal = Color(0xFF2F3E46)
    val OnTile = Color(0xFFF3F6F1)
    val OnTileMuted = Color(0xCCEAEFEA)

    // Text on light surfaces
    val TextPrimary = Color(0xFF2F3E46)   // charcoal
    val TextSecondary = Color(0xFF4A5A56)
    val TextMuted = Color(0xFF77857D)
    val TextFaint = Color(0xFFA1ABA2)

    // Status / accents
    val Good = Color(0xFF52796F)
    val Warn = Color(0xFFA7754D)   // tan
    val Bad = Color(0xFFB24A3A)    // warm red, harmonised with the browns
    val Cool = Color(0xFF5B8BA0)   // muted cool tone for temperature

    val Track = Color(0xFFE3E8DF)  // chart / bar track on light
    val OrbBase = Color(0xFF2E3D38) // dark well behind the water orb, so light readout stays legible at any fill

    val liquid: Brush get() = Brush.verticalGradient(listOf(AccentLight, Accent))
    fun liquid(top: Color, bottom: Color): Brush = Brush.verticalGradient(listOf(top, bottom))
    val pageBackground: Brush get() = Brush.verticalGradient(listOf(BgTop, Bg))
    val glow: Brush get() = Brush.radialGradient(listOf(AccentSoft, Color.Transparent))
}

// Real design typefaces (bundled, OFL): DM Sans display + DM Mono data.
val DMSans = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.dm_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.dm_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.dm_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
val DMMono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
    Font(R.font.dm_mono_medium, FontWeight.SemiBold),
)

// Aliases used across the UI.
val Sans = DMSans
val Mono = DMMono

private val Scheme = lightColorScheme(
    primary = Cell.Accent,
    onPrimary = Color.White,
    background = Cell.Bg,
    onBackground = Cell.TextPrimary,
    surface = Cell.Surface,
    onSurface = Cell.TextPrimary,
)

@Composable
fun CellspanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
