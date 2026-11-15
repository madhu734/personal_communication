package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = SentEmeraldBubbleDark,
    onPrimaryContainer = Color(0xFFECFDF5),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = WarmMossBubble,
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = AmberAlert,
    background = DeepForestBg,
    onBackground = Color(0xFFECFDF5),
    surface = ElevatedPineSurface,
    onSurface = Color(0xFFECFDF5),
    surfaceVariant = ElevatedPineVariant,
    onSurfaceVariant = Color(0xFFA7F3D0),
    error = CoralHangup,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = SentEmeraldBubbleLight,
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = SoftMossVariant,
    onSecondaryContainer = Color(0xFF0F2922),
    tertiary = AmberAlert,
    background = MintAlabasterBg,
    onBackground = Color(0xFF0B1F17),
    surface = PureWhiteSurface,
    onSurface = Color(0xFF0B1F17),
    surfaceVariant = SoftMossVariant,
    onSurfaceVariant = Color(0xFF335446),
    error = CoralHangup,
    onError = Color.White
)

@Composable
fun ApunKaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    ApunKaTheme(darkTheme = darkTheme, content = content)
}
