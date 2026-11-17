package studio.oracle.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class OraclePalette(
    val canvas: Color, val surface: Color, val surfaceRaised: Color, val surfaceSunken: Color,
    val outline: Color, val outlineSoft: Color, val text: Color, val textSecondary: Color,
    val textTertiary: Color, val accent: Color, val onAccent: Color, val accentSoft: Color,
    val error: Color, val success: Color, val scrim: Color, val isDark: Boolean
)

val OracleDarkPalette = OraclePalette(
    canvas = Color(0xFF0A0A0C), surface = Color(0xFF101014), surfaceRaised = Color(0xFF18181E),
    surfaceSunken = Color(0xFF08080A), outline = Color(0xFF343139), outlineSoft = Color(0xFF25242B),
    text = Color(0xFFE8E7E3), textSecondary = Color(0xFFA5A1A8), textTertiary = Color(0xFF77747D),
    accent = Color(0xFFE2B15C), onAccent = Color(0xFF17130C), accentSoft = Color(0xFF302517),
    error = Color(0xFFE07A5F), success = Color(0xFF86C993), scrim = Color(0xCC000000), isDark = true
)

val OracleLightPalette = OraclePalette(
    canvas = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFF5F8FC),
    surfaceSunken = Color(0xFFEDF2F8), outline = Color(0xFFDCE4ED), outlineSoft = Color(0xFFEBF0F5),
    text = Color(0xFF111820), textSecondary = Color(0xFF536273), textTertiary = Color(0xFF718096),
    accent = Color(0xFF5798D2), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFEAF4FD),
    error = Color(0xFFB43B32), success = Color(0xFF287A55), scrim = Color(0x660D1115), isDark = false
)

val LocalOraclePalette = staticCompositionLocalOf { OracleDarkPalette }
