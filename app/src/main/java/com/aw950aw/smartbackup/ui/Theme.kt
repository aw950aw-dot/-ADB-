package com.aw950aw.smartbackup.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E73),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEF0),
    onPrimaryContainer = Color(0xFF00282A),
    secondary = Color(0xFF3F5F8A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE8F7),
    onSecondaryContainer = Color(0xFF0B1F38),
    background = Color(0xFFF3F6FA),
    onBackground = Color(0xFF101820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFE6ECF3),
    onSurfaceVariant = Color(0xFF43505E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF74808E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD6D0),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF0B4F54),
    onPrimaryContainer = Color(0xFFCDEEF0),
    secondary = Color(0xFFA9C4EC),
    onSecondary = Color(0xFF12304F),
    secondaryContainer = Color(0xFF2A4670),
    onSecondaryContainer = Color(0xFFDDE8F7),
    background = Color(0xFF0D1520),
    onBackground = Color(0xFFE3E9F0),
    surface = Color(0xFF16212E),
    onSurface = Color(0xFFE3E9F0),
    surfaceVariant = Color(0xFF223242),
    onSurfaceVariant = Color(0xFFB5C0CC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8793A0)
)

/** Hebrew app: the whole UI is laid out right-to-left. */
@Composable
fun SmartBackupTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
