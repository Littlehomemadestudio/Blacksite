package studio.oracle.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A semantic palette rather than a collection of one-off screen colors.
 * Components consume this palette so dark and light surfaces stay coherent.
 */
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
    val warning: Color,
    val scrim: Color,
    val isDark: Boolean
)

val OracleDarkPalette = OraclePalette(
    canvas = Color(0xFF111315),
    surface = Color(0xFF191C1F),
    surfaceRaised = Color(0xFF22262A),
    surfaceSunken = Color(0xFF0C0E10),
    outline = Color(0xFF343A40),
    outlineSoft = Color(0xFF292E33),
    text = Color(0xFFF3F1EC),
    textSecondary = Color(0xFFA5A9AE),
    textTertiary = Color(0xFF858B92),
    accent = Color(0xFFE58A43),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF3C291B),
    error = Color(0xFFFF8E83),
    success = Color(0xFF73B895),
    warning = Color(0xFFF0B56B),
    scrim = Color(0x99000000),
    isDark = true
)

val OracleLightPalette = OraclePalette(
    canvas = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF5F8FC),
    surfaceSunken = Color(0xFFEDF2F8),
    outline = Color(0xFFDCE4ED),
    outlineSoft = Color(0xFFEBF0F5),
    text = Color(0xFF111820),
    textSecondary = Color(0xFF536273),
    textTertiary = Color(0xFF718096),
    accent = Color(0xFF5798D2),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEAF4FD),
    error = Color(0xFFB43B32),
    success = Color(0xFF287A55),
    warning = Color(0xFF8A5200),
    scrim = Color(0x660D1115),
    isDark = false
)

val LocalOraclePalette = staticCompositionLocalOf { OracleDarkPalette }
