package org.hubik.openfugu.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette, shared with the website (openfugu-website/public/style.css):
// a water column — surface light to depth dark — with dive-light cyan for
// data and buoy orange as accent. Used by the static color schemes in
// Theme.kt — always on iOS; on Android only when "Use system colors" is
// turned off (Material You dynamic color is the default there).
val DiveCyan = Color(0xFF4EC4DE)       // --trace (dark): data, buttons, chart
val DiveCyanDeep = Color(0xFF2F7590)   // --trace (light)
// Accents deviate from the website's --accent on purpose: the fish's own
// amber sits clearly away from error-red (the site's coral flirts with
// "warning"), and warm tones dark enough for light surfaces read brown —
// so the light theme goes cool instead and red stays reserved for errors.
val FuguAmber = Color(0xFFFFB347)      // dark-theme accent: the fish body
val WaterTeal = Color(0xFF006874)      // light-theme accent
val OceanNavy = Color(0xFF0D1B2A)      // --bg-0 (dark), also the icon background
val OceanDepth = Color(0xFF050C14)     // --bg-1 (dark), deepest surface
val InkLight = Color(0xFFE4EFF5)       // --ink (dark)
val InkDark = Color(0xFF122B3D)        // --ink (light)
