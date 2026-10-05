package com.hotspot.accounting.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme.
 *
 * The glass UI forces two decisions that differ from a conventional Android app, so both are called
 * out rather than left implicit:
 *
 *  1. **Material You (dynamic colour) is off by default.** It remains available via [dynamicColor],
 *     but the glass look depends on a known, high-chroma accent set: the backdrop blooms are built
 *     from `primary`/`secondary`/`tertiary`, and a wallpaper-derived palette is often too muted to
 *     remain visible through several translucent layers. With dynamic colour enabled the panes read
 *     as flat grey.
 *
 *  2. **The palette is always dark.** Translucent white glass over a light background reads as flat
 *     grey and loses the blurred depth entirely — see [GlassPalette] for the full reasoning. The
 *     `darkTheme` parameter is therefore not consulted; it is retained so callers compile unchanged.
 */
@Composable
fun HotspotAccountingTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context)
        else -> GlassDarkScheme
    }

    MaterialTheme(colorScheme = colors, content = content)
}
