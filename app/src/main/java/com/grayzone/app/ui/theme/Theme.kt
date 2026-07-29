package com.grayzone.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.grayzone.app.PrefsKeys

fun resolveDarkTheme(context: Context, themeMode: String, systemDark: Boolean): Boolean =
    when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

fun isNightMode(context: Context): Boolean {
    val mode = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM
    val systemDark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    return resolveDarkTheme(context, mode, systemDark)
}

@Composable
fun GrayzoneTheme(
    themeMode: String = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = resolveDarkTheme(LocalContext.current, themeMode, systemDark)
    val palette = if (darkTheme) DarkGzPalette else LightGzPalette
    SideEffect {
        GzThemeState.palette = palette
    }

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = palette.primaryLight,
            secondary = palette.accent,
            onSecondary = palette.onPrimary,
            secondaryContainer = palette.primaryContainer,
            onSecondaryContainer = palette.primaryLight,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            outlineVariant = palette.borderSubtle,
            error = palette.red,
            onError = Color.White,
            errorContainer = palette.redContainer,
            onErrorContainer = palette.red,
            scrim = Color(0xCC000000)
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = palette.primaryDark,
            secondary = palette.accent,
            onSecondary = palette.onPrimary,
            secondaryContainer = palette.primaryContainer,
            onSecondaryContainer = palette.primaryDark,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            outlineVariant = palette.borderSubtle,
            error = palette.red,
            onError = Color.White,
            errorContainer = palette.redContainer,
            onErrorContainer = palette.red,
            scrim = Color(0x66000000)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
