package com.grayzone.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Resolved theme preference stored in prefs. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

data class GzPalette(
    val isDark: Boolean,
    val primary: Color,
    val primaryLight: Color,
    val primaryDark: Color,
    val primaryContainer: Color,
    val accent: Color,
    val accentDim: Color,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHigh: Color,
    val border: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val green: Color,
    val greenContainer: Color,
    val red: Color,
    val redContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val chartBlue: Color,
    val chartCyan: Color,
    val chartPink: Color,
    val chartOrange: Color,
    val chartYellow: Color,
    val chartTeal: Color,
    val streakFire: Color,
    val streakGold: Color,
    val achievementBg: Color,
    val achievementBorder: Color,
    val focusBlue: Color,
    val focusBlueDim: Color,
    val onPrimary: Color,
)

// Calm, cool neutrals with a single deep-teal brand accent. Status colours are muted so the
// accent stays the only "loud" colour on screen; containers are tints, never saturated blocks.
val LightGzPalette = GzPalette(
    isDark = false,
    primary = Color(0xFF0F766E),
    primaryLight = Color(0xFF14B8A6),
    primaryDark = Color(0xFF0B5952),
    primaryContainer = Color(0xFFE3F3F0),
    accent = Color(0xFF0F766E),
    accentDim = Color(0x140F766E),
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF1F3F5),
    surfaceHigh = Color(0xFFE7EAEE),
    border = Color(0xFFE3E6EA),
    borderSubtle = Color(0xFFEEF0F3),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF4B5563),
    textTertiary = Color(0xFF9CA3AF),
    green = Color(0xFF15803D),
    greenContainer = Color(0xFFE8F5EC),
    red = Color(0xFFC62828),
    redContainer = Color(0xFFFCEDED),
    amber = Color(0xFFB45309),
    amberContainer = Color(0xFFFDF3E2),
    chartBlue = Color(0xFF3B82F6),
    chartCyan = Color(0xFF0891B2),
    chartPink = Color(0xFFDB2777),
    chartOrange = Color(0xFFEA580C),
    chartYellow = Color(0xFFCA8A04),
    chartTeal = Color(0xFF0D9488),
    streakFire = Color(0xFFEA580C),
    streakGold = Color(0xFFB7791F),
    achievementBg = Color(0xFFFDF3E2),
    achievementBorder = Color(0xFFF5E1BC),
    focusBlue = Color(0xFF2563EB),
    focusBlueDim = Color(0xFFE6EEFD),
    onPrimary = Color.White,
)

val DarkGzPalette = GzPalette(
    isDark = true,
    primary = Color(0xFF2DD4BF),
    primaryLight = Color(0xFF5EEAD4),
    primaryDark = Color(0xFF14B8A6),
    primaryContainer = Color(0xFF113330),
    accent = Color(0xFF2DD4BF),
    accentDim = Color(0x262DD4BF),
    background = Color(0xFF0E1113),
    surface = Color(0xFF161A1D),
    surfaceElevated = Color(0xFF1C2124),
    surfaceHigh = Color(0xFF262C31),
    border = Color(0xFF283036),
    borderSubtle = Color(0xFF1F252A),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFFA6B0BB),
    textTertiary = Color(0xFF6B7682),
    green = Color(0xFF4ADE80),
    greenContainer = Color(0xFF13261B),
    red = Color(0xFFF87171),
    redContainer = Color(0xFF2C1819),
    amber = Color(0xFFFBBF24),
    amberContainer = Color(0xFF2A2314),
    chartBlue = Color(0xFF60A5FA),
    chartCyan = Color(0xFF22D3EE),
    chartPink = Color(0xFFF472B6),
    chartOrange = Color(0xFFFB923C),
    chartYellow = Color(0xFFFACC15),
    chartTeal = Color(0xFF2DD4BF),
    streakFire = Color(0xFFFB923C),
    streakGold = Color(0xFFFACC15),
    achievementBg = Color(0xFF2A2314),
    achievementBorder = Color(0xFF3F341C),
    focusBlue = Color(0xFF60A5FA),
    focusBlueDim = Color(0xFF172437),
    onPrimary = Color(0xFF032B27),
)
/**
 * Snapshot-backed active palette so existing `GZ*` reads recompose when theme changes.
 */
object GzThemeState {
    var palette by mutableStateOf(LightGzPalette)
        internal set
}

// ── Compose-aware token accessors (read Snapshot state → recomposes on theme change) ──

val GZPrimary: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.primary
val GZPrimaryLight: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.primaryLight
val GZPrimaryDark: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.primaryDark
val GZPrimaryContainer: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.primaryContainer
val GZAccent: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.accent
val GZAccentDim: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.accentDim
val GZBackground: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.background
val GZSurface: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.surface
val GZSurfaceElevated: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.surfaceElevated
val GZSurfaceHigh: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.surfaceHigh
val GZBorder: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.border
val GZBorderSubtle: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.borderSubtle
val GZTextPrimary: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.textPrimary
val GZTextSecondary: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.textSecondary
val GZTextTertiary: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.textTertiary
val GZGreen: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.green
val GZGreenContainer: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.greenContainer
val GZRed: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.red
val GZRedContainer: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.redContainer
val GZAmber: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.amber
val GZAmberContainer: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.amberContainer
val GZChartBlue: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.chartBlue
val GZChartCyan: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.chartCyan
val GZChartPink: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.chartPink
val GZChartOrange: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.chartOrange
val GZChartYellow: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.chartYellow
val GZChartTeal: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.chartTeal
val GZChartColors: List<Color>
    @Composable @ReadOnlyComposable get() = listOf(
        GZPrimary, GZChartBlue, GZChartPink, GZChartOrange, GZChartCyan, GZChartTeal, GZChartYellow, GZAccent
    )
val GZStreakFire: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.streakFire
val GZStreakGold: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.streakGold
val GZAchievementBg: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.achievementBg
val GZAchievementBorder: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.achievementBorder
val GZFocusBlue: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.focusBlue
val GZFocusBlueDim: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.focusBlueDim
val GZOnPrimary: Color
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.onPrimary
/** True while the dark palette is active (dark surfaces use hairlines instead of shadows). */
val GZIsDark: Boolean
    @Composable @ReadOnlyComposable get() = GzThemeState.palette.isDark
