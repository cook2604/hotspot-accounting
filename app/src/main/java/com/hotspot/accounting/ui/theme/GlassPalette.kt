package com.hotspot.accounting.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Palette for the glass UI.
 *
 * A dark base is not a stylistic preference here, it is a requirement: translucent white panes need
 * something dark and saturated behind them to be visible at all. On a light backdrop the same panes
 * read as washed-out grey rectangles, and the blurred blooms that give the material its depth
 * disappear entirely.
 *
 * Accent colours are deliberately high-chroma so the radial blooms in `GlassBackdrop` survive being
 * blurred and tinted through several layers of glass.
 */
object GlassPalette {

    /** Near-black base. Slightly blue so the blooms blend without muddying. */
    val Base = Color(0xFF0A0B12)

    /** Raised surfaces behind glass, e.g. the sheet behind a dialog. */
    val Surface = Color(0xFF14161F)

    val Primary = Color(0xFF6E9BFF)
    val Secondary = Color(0xFF4FD1C5)
    val Tertiary = Color(0xFFFFB86B)

    val OnBase = Color(0xFFF2F4F8)
    val OnBaseMuted = Color(0xFFA8AEC0)

    /** Cost figures use a warmer, more affirmative tone than the generic accents. */
    val Money = Color(0xFF7BE495)

    val Error = Color(0xFFFF7A7A)
    val OnError = Color(0xFF2A0A0A)

    val Warning = Color(0xFFFFC773)
    val Success = Color(0xFF66E39A)
}

/** Dark scheme used when a dynamic (Material You) palette is not in play. */
val GlassDarkScheme: ColorScheme = darkColorScheme(
    primary = GlassPalette.Primary,
    onPrimary = Color(0xFF07122B),
    primaryContainer = GlassPalette.Primary.copy(alpha = 0.28f),
    onPrimaryContainer = GlassPalette.OnBase,

    secondary = GlassPalette.Secondary,
    onSecondary = Color(0xFF04211E),
    secondaryContainer = GlassPalette.Secondary.copy(alpha = 0.24f),
    onSecondaryContainer = GlassPalette.OnBase,

    tertiary = GlassPalette.Tertiary,
    onTertiary = Color(0xFF2A1704),

    // The app paints its own gradient backdrop, so these are only a fallback fill.
    background = GlassPalette.Base,
    onBackground = GlassPalette.OnBase,

    surface = GlassPalette.Surface,
    onSurface = GlassPalette.OnBase,
    surfaceVariant = Color(0xFF232735),
    onSurfaceVariant = GlassPalette.OnBaseMuted,

    error = GlassPalette.Error,
    onError = GlassPalette.OnError,
    errorContainer = GlassPalette.Error.copy(alpha = 0.24f),
    onErrorContainer = GlassPalette.OnBase,

    outline = Color.White.copy(alpha = 0.24f),
)
