package studio.oracle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun OracleTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) OracleDarkPalette else OracleLightPalette
    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.textSecondary,
            background = palette.canvas,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.outline,
            error = palette.error
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.textSecondary,
            background = palette.canvas,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.outline,
            error = palette.error
        )
    }

    CompositionLocalProvider(LocalOraclePalette provides palette) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = OracleTypography,
            content = content
        )
    }
}
