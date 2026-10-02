package com.hotspot.accounting.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Blue = Color(0xFF2F6FED)
private val Teal = Color(0xFF0E9F9F)
private val Amber = Color(0xFFB4690E)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Teal,
    tertiary = Amber,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC0FF),
    secondary = Color(0xFF57D6D6),
    tertiary = Color(0xFFF0B65E),
)

@Composable
fun HotspotAccountingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // Material You on Android 12+, so the app matches the ROM's accent colour.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
