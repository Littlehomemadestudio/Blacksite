package studio.oracle.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class OraclePalette(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val outline: Color,
    val outlineSoft: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val error: Color,
    val success: Color,
    val scrim: Color,
    val isDark: Boolean
)

val OracleDarkPalette = OraclePalette(
    canvas = Color(0xFF101214),
    surface = Color(0xFF171A1D),
    surfaceRaised = Color(0xFF202428),
    surfaceSunken = Color(0xFF0C0E10),
    outline = Color(0xFF343B41),
    outlineSoft = Color(0xFF292F34),
    text = Color(0xFFE9E9E6),
    textSecondary = Color(0xFFA1A6AA),
    textTertiary = Color(0xFF747D83),
    accent = Color(0xFFB7C5B5),
    onAccent = Color(0xFF101512),
    accentSoft = Color(0xFF29332D),
    error = Color(0xFFE18B82),
    success = Color(0xFF8EB69C),
    scrim = Color(0x99000000),
    isDark = true
)

val OracleLightPalette = OraclePalette(
    canvas = Color(0xFFF5F5F2),
    surface = Color(0xFFFBFBF8),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEDEEEA),
    outline = Color(0xFFD8DAD5),
    outlineSoft = Color(0xFFE6E7E2),
    text = Color(0xFF202421),
    textSecondary = Color(0xFF626A64),
    textTertiary = Color(0xFF89918B),
    accent = Color(0xFF586F60),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE8EDE8),
    error = Color(0xFFB43B32),
    success = Color(0xFF287A55),
    scrim = Color(0x660D1115),
    isDark = false
)

val LocalOraclePalette = staticCompositionLocalOf { OracleDarkPalette }
