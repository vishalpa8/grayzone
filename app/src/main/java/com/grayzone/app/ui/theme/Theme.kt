package com.grayzone.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val GrayzoneColorScheme = darkColorScheme(
    primary              = GZPrimary,
    onPrimary            = Color.White,
    primaryContainer     = GZPrimaryContainer,
    onPrimaryContainer   = GZPrimaryLight,

    secondary            = GZAccent,
    onSecondary          = Color(0xFF003322),
    secondaryContainer   = Color(0xFF003322),
    onSecondaryContainer = GZAccent,

    background           = GZBackground,
    onBackground         = GZTextPrimary,

    surface              = GZSurface,
    onSurface            = GZTextPrimary,
    surfaceVariant       = GZSurfaceElevated,
    onSurfaceVariant     = GZTextSecondary,

    outline              = GZBorder,
    outlineVariant       = GZBorderSubtle,

    error                = GZRed,
    onError              = Color.White,
    errorContainer       = GZRedContainer,
    onErrorContainer     = GZRed,

    scrim                = Color(0xCC000000)
)

@Composable
fun GrayzoneTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = GZBackground.toArgb()
            window.navigationBarColor = GZBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = GrayzoneColorScheme,
        typography = Typography,
        content = content
    )
}
