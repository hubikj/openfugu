package org.hubik.openfugu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Static brand schemes on the palette in Color.kt. Role mapping: primary =
// dive-light cyan (buttons, default chart trace), tertiary = the
// linked-entity accent (see CLAUDE.md) — fish amber in dark, water-teal in
// light. All surface/container slots are set explicitly:
// the darkColorScheme()/lightColorScheme() defaults are gray-purple baseline
// tones that would tint cards and bars away from the water column.
private val DarkColorScheme = darkColorScheme(
    primary = DiveCyan,
    onPrimary = Color(0xFF00303C),
    primaryContainer = Color(0xFF0F4A59),
    onPrimaryContainer = Color(0xFFBDEBF5),
    secondary = Color(0xFF8DA9BA),
    onSecondary = Color(0xFF16303F),
    secondaryContainer = Color(0xFF24425A),
    onSecondaryContainer = Color(0xFFCADFEC),
    tertiary = FuguAmber,
    onTertiary = Color(0xFF3A2400),
    tertiaryContainer = Color(0xFF7A4B00),
    onTertiaryContainer = Color(0xFFFFDCA8),
    background = OceanNavy,
    onBackground = InkLight,
    surface = OceanNavy,
    onSurface = InkLight,
    surfaceVariant = Color(0xFF1E3242),
    onSurfaceVariant = Color(0xFF8DA9BA),
    surfaceContainerLowest = OceanDepth,
    surfaceContainerLow = Color(0xFF12202F),
    surfaceContainer = Color(0xFF162636),
    surfaceContainerHigh = Color(0xFF1C2E40),
    surfaceContainerHighest = Color(0xFF24384C),
    outline = Color(0xFF5F7A8C),
    outlineVariant = Color(0xFF31485C),
    inverseSurface = InkLight,
    inverseOnSurface = Color(0xFF22303D),
    inversePrimary = DiveCyanDeep
)

private val LightColorScheme = lightColorScheme(
    primary = DiveCyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3E7F2),
    onPrimaryContainer = Color(0xFF06333F),
    secondary = Color(0xFF4D6B7E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4EE),
    onSecondaryContainer = Color(0xFF16303F),
    tertiary = WaterTeal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA9EDF6),
    onTertiaryContainer = Color(0xFF001F26),
    background = Color(0xFFFBFDFE),
    onBackground = InkDark,
    surface = Color(0xFFFBFDFE),
    onSurface = InkDark,
    surfaceVariant = Color(0xFFDDE9F0),
    onSurfaceVariant = Color(0xFF4D6B7E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F8FB),
    surfaceContainer = Color(0xFFECF4F8),
    surfaceContainerHigh = Color(0xFFE4EEF3),
    surfaceContainerHighest = Color(0xFFDCE8EF),
    outline = Color(0xFF6E8798),
    outlineVariant = Color(0xFFC2D3DD),
    inverseSurface = Color(0xFF22303D),
    inverseOnSurface = Color(0xFFEDF4F8),
    inversePrimary = DiveCyan
)

/**
 * Platform-provided color scheme, or null to use the static brand schemes
 * above. Android returns Material You dynamic colors; iOS has no equivalent
 * and stays static.
 */
@Composable
internal expect fun platformColorScheme(darkTheme: Boolean): ColorScheme?

@Composable
fun OpenFuguTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = platformColorScheme(darkTheme)
            ?: if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
