package org.hubik.openfugu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// No dynamic color source on iOS — the static OpenFugu palette applies.
@Composable
internal actual fun platformColorScheme(darkTheme: Boolean): ColorScheme? = null

actual val hasPlatformColorScheme: Boolean = false
