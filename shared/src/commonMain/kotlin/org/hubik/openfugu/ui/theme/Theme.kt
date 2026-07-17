package org.hubik.openfugu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Static brand schemes on the palette in Color.kt. All surface/container
// slots are set explicitly: darkColorScheme() defaults are gray-purple
// baseline tones that would clash with the navy family. Tertiary is the
// linked-entity highlight (see CLAUDE.md) and must read brighter than
// onSurface: aqua against the orange primary.
private val DarkColorScheme = darkColorScheme(
    primary = FuguOrange,
    onPrimary = Color(0xFF3A2400),
    primaryContainer = Color(0xFF7A4B00),
    onPrimaryContainer = Color(0xFFFFDCA8),
    secondary = Color(0xFF8FC3DC),
    onSecondary = Color(0xFF0E2938),
    secondaryContainer = Color(0xFF1E4356),
    onSecondaryContainer = Color(0xFFC9E6F5),
    tertiary = Color(0xFF86D9E4),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF1F4E55),
    onTertiaryContainer = Color(0xFFB5EBF2),
    background = OceanNavy,
    onBackground = Color(0xFFE2E8EF),
    surface = OceanNavy,
    onSurface = Color(0xFFE2E8EF),
    surfaceVariant = Color(0xFF23344A),
    onSurfaceVariant = Color(0xFFBCC8D6),
    surfaceContainerLowest = Color(0xFF081220),
    surfaceContainerLow = Color(0xFF122236),
    surfaceContainer = Color(0xFF16283E),
    surfaceContainerHigh = Color(0xFF1C3049),
    surfaceContainerHighest = Color(0xFF233852),
    outline = Color(0xFF8494A7),
    outlineVariant = Color(0xFF3A4C61),
    inverseSurface = Color(0xFFE2E8EF),
    inverseOnSurface = Color(0xFF2A3648),
    inversePrimary = FuguOrangeDeep
)

private val LightColorScheme = lightColorScheme(
    primary = FuguOrangeDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2E1800),
    secondary = Color(0xFF3E6377),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC5E1F2),
    onSecondaryContainer = Color(0xFF0F2B3C),
    tertiary = Color(0xFF006874),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9EEFF9),
    onTertiaryContainer = Color(0xFF001F24),
    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8FAFD),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E)
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
