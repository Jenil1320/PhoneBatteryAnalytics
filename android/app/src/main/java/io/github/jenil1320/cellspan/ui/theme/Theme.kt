@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.jenil1320.cellspan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.jenil1320.cellspan.R

/**
 * The app palette, in both light and dark.
 *
 * Built from palette.svg (Coolors): sage → teal → slate greens.
 *
 *   #84a98c sage · #52796f teal · #354f52 slate · #2f3e46 charcoal
 *
 * ## Why the colours are mutable state rather than constants
 * Every screen already reads them as `Cell.Accent`, `Cell.Bg` and so on — around a
 * thousand call sites. Threading a theme object through all of that, or moving to a
 * CompositionLocal, would mean touching every one of them for no behavioural gain.
 * Backing each colour with Compose snapshot state instead means [CellspanTheme] swaps the
 * whole palette in one place and every reader recomposes by itself, with not one call site
 * changed. The trade-off is that the palette is process-global, which is correct here:
 * there is exactly one theme, chosen by the system.
 */
object Cell {
    // Surfaces
    var Bg by mutableStateOf(Color(0xFFEAEEE6))          // page background (light sage-gray)
    var BgTop by mutableStateOf(Color(0xFFF1F4EE))
    var BgElevated by mutableStateOf(Color(0xFFFFFFFF))  // cards
    var Surface by mutableStateOf(Color(0xFFFFFFFF))
    var SurfaceHi by mutableStateOf(Color(0xFFF4F7F1))

    var Border by mutableStateOf(Color(0x14354F52))      // subtle dark hairline on light
    var BorderHi by mutableStateOf(Color(0x24354F52))
    var BorderAccent by mutableStateOf(Color(0x3352796F))

    // Greens (primary brand)
    var Accent by mutableStateOf(Color(0xFF52796F))      // primary teal-green
    var AccentBright by mutableStateOf(Color(0xFF84A98C))
    var AccentLight by mutableStateOf(Color(0xFF84A98C))
    var AccentDeep by mutableStateOf(Color(0xFF354F52))
    var AccentSoft by mutableStateOf(Color(0x2284A98C))

    // KPI tile fills (the saturated stat cards) + text that sits on them
    var TileTeal by mutableStateOf(Color(0xFF52796F))
    var TileSlate by mutableStateOf(Color(0xFF354F52))
    var TileCharcoal by mutableStateOf(Color(0xFF2F3E46))
    var OnTile by mutableStateOf(Color(0xFFF3F6F1))
    var OnTileMuted by mutableStateOf(Color(0xCCEAEFEA))

    // Text on the page surfaces
    var TextPrimary by mutableStateOf(Color(0xFF2F3E46))   // charcoal
    var TextSecondary by mutableStateOf(Color(0xFF4A5A56))
    var TextMuted by mutableStateOf(Color(0xFF77857D))
    var TextFaint by mutableStateOf(Color(0xFFA1ABA2))

    // Status / accents
    var Good by mutableStateOf(Color(0xFF52796F))
    var Warn by mutableStateOf(Color(0xFFA7754D))   // tan
    var Bad by mutableStateOf(Color(0xFFB24A3A))    // warm red, harmonised with the browns
    var Cool by mutableStateOf(Color(0xFF5B8BA0))   // muted cool tone for temperature

    var Track by mutableStateOf(Color(0xFFE3E8DF))  // chart / bar track
    // Dark well behind the water orb, so the light readout stays legible at any fill.
    var OrbBase by mutableStateOf(Color(0xFF2E3D38))

    /** True while the dark palette is loaded — for the system bars, which are not Compose. */
    var isDark by mutableStateOf(false)
        private set

    val liquid: Brush get() = Brush.verticalGradient(listOf(AccentLight, Accent))
    fun liquid(top: Color, bottom: Color): Brush = Brush.verticalGradient(listOf(top, bottom))
    val pageBackground: Brush get() = Brush.verticalGradient(listOf(BgTop, Bg))
    val glow: Brush get() = Brush.radialGradient(listOf(AccentSoft, Color.Transparent))

    /**
     * The light palette. Kept as an explicit function rather than as the field
     * initialisers so that switching back from dark restores every value — a partial
     * palette is the classic way a themed app ends up with unreadable text.
     */
    fun applyLight() {
        Bg = Color(0xFFEAEEE6); BgTop = Color(0xFFF1F4EE); BgElevated = Color(0xFFFFFFFF)
        Surface = Color(0xFFFFFFFF); SurfaceHi = Color(0xFFF4F7F1)
        Border = Color(0x14354F52); BorderHi = Color(0x24354F52); BorderAccent = Color(0x3352796F)
        Accent = Color(0xFF52796F); AccentBright = Color(0xFF84A98C)
        AccentLight = Color(0xFF84A98C); AccentDeep = Color(0xFF354F52); AccentSoft = Color(0x2284A98C)
        TileTeal = Color(0xFF52796F); TileSlate = Color(0xFF354F52); TileCharcoal = Color(0xFF2F3E46)
        OnTile = Color(0xFFF3F6F1); OnTileMuted = Color(0xCCEAEFEA)
        TextPrimary = Color(0xFF2F3E46); TextSecondary = Color(0xFF4A5A56)
        TextMuted = Color(0xFF77857D); TextFaint = Color(0xFFA1ABA2)
        Good = Color(0xFF52796F); Warn = Color(0xFFA7754D); Bad = Color(0xFFB24A3A); Cool = Color(0xFF5B8BA0)
        Track = Color(0xFFE3E8DF); OrbBase = Color(0xFF2E3D38)
        isDark = false
    }

    /**
     * The dark palette.
     *
     * Not an inversion. Two things change beyond swapping light for dark:
     *  - the greens are lifted, because #52796F on a near-black surface fails contrast
     *    badly — the accent has to carry text, so it moves to the brighter sage;
     *  - the status colours are lightened for the same reason. A dark-mode warning printed
     *    in #B24A3A is barely distinguishable from the body text.
     */
    fun applyDark() {
        Bg = Color(0xFF121714); BgTop = Color(0xFF171D1A); BgElevated = Color(0xFF1C2320)
        Surface = Color(0xFF1C2320); SurfaceHi = Color(0xFF232B27)
        Border = Color(0x1FA9C0B4); BorderHi = Color(0x33A9C0B4); BorderAccent = Color(0x5584A98C)
        Accent = Color(0xFF95BE9D); AccentBright = Color(0xFFAFD3B6)
        AccentLight = Color(0xFF84A98C); AccentDeep = Color(0xFF4B6B62); AccentSoft = Color(0x2A84A98C)
        TileTeal = Color(0xFF3E5B54); TileSlate = Color(0xFF2C4245); TileCharcoal = Color(0xFF26323A)
        OnTile = Color(0xFFEAF1EB); OnTileMuted = Color(0xCCC5D2C7)
        TextPrimary = Color(0xFFE7EEE8); TextSecondary = Color(0xFFB8C5BC)
        TextMuted = Color(0xFF8B9A90); TextFaint = Color(0xFF6A776F)
        Good = Color(0xFF8FC2A2); Warn = Color(0xFFD9A574); Bad = Color(0xFFE07E6A); Cool = Color(0xFF7FB3C8)
        Track = Color(0xFF2A332E); OrbBase = Color(0xFF0E1512)
        isDark = true
    }
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

@Composable
fun CellspanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Applied before the Material scheme is built, so both halves describe the same theme.
    if (dark) Cell.applyDark() else Cell.applyLight()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Cell.Accent, onPrimary = Color(0xFF13201A),
            background = Cell.Bg, onBackground = Cell.TextPrimary,
            surface = Cell.Surface, onSurface = Cell.TextPrimary,
        )
    } else {
        lightColorScheme(
            primary = Cell.Accent, onPrimary = Color.White,
            background = Cell.Bg, onBackground = Cell.TextPrimary,
            surface = Cell.Surface, onSurface = Cell.TextPrimary,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
