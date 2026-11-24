package com.askfirst.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = WarmClay,
    onPrimary = Color.White,
    primaryContainer = AccentAmber,
    onPrimaryContainer = WarmInk,
    secondary = AccentAmberDim,
    onSecondary = Color.White,
    background = WarmSand,
    onBackground = WarmInk,
    surface = WarmSand,
    onSurface = WarmInk,
    surfaceVariant = Color(0xFFE7DAC7),
    onSurfaceVariant = WarmClayDark,
    outline = Color(0xFF9C8B77),
)

private val DarkColors = darkColorScheme(
    primary = NightClay,
    onPrimary = NightWalnut,
    primaryContainer = AccentAmberDim,
    onPrimaryContainer = NightSand,
    secondary = AccentAmber,
    onSecondary = NightWalnut,
    background = NightWalnut,
    onBackground = NightSand,
    surface = Color(0xFF262220),
    onSurface = NightSand,
    surfaceVariant = Color(0xFF3A332C),
    onSurfaceVariant = Color(0xFFD8CBB8),
    outline = Color(0xFF8E8071),
)

@Composable
fun AskFirstTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AskFirstTypography,
        content = content,
    )
}