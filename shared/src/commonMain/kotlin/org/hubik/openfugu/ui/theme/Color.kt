package org.hubik.openfugu.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette, shared with the website (openfugu-website/public/style.css):
// a water column — surface light to depth dark — with dive-light cyan for
// data and buoy orange as accent. Used by the static color schemes in
// Theme.kt — the only schemes iOS ever sees (Android normally uses Material
// You dynamic color instead).
val DiveCyan = Color(0xFF4EC4DE)       // --trace (dark): data, buttons, chart
val DiveCyanDeep = Color(0xFF2F7590)   // --trace (light)
val BuoyOrange = Color(0xFFFF8A5C)     // --accent (dark): linked-entity accent
val BuoyOrangeDeep = Color(0xFFBC4A10) // --accent (light)
val OceanNavy = Color(0xFF0D1B2A)      // --bg-0 (dark), also the icon background
val OceanDepth = Color(0xFF050C14)     // --bg-1 (dark), deepest surface
val InkLight = Color(0xFFE4EFF5)       // --ink (dark)
val InkDark = Color(0xFF122B3D)        // --ink (light)
