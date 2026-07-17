package org.hubik.openfugu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.util.fmt

// =============================================================================
// Shared colors used across exercises, calibration, and charts
// =============================================================================

object AppColors {
    val inRange = Color(0xFF43A047)
    val outOfRange = Color(0xFFE53935)
    val warning = Color(0xFFFFA726)
    val inRangeFill = Color(0xFF43A047).copy(alpha = 0.15f)
    val inRangeBorder = Color(0xFF43A047).copy(alpha = 0.4f)
}

// Hand-tuned in the color lab against the app's card surfaces (2026-07-17).
// Hue-locked HSL: lightness capped, saturation boosted, so yellow stays a
// vivid gold instead of draining to mustard. Chosen knowing yellow lands at
// ~1.5:1 — a truly legible yellow on light surfaces stops being yellow, so
// this is the pleasant point of that tradeoff, not an oversight.
private const val LIGHT_SURFACE_MAX_LIGHTNESS = 0.485f
private const val LIGHT_SURFACE_SATURATION_BOOST = 0.15f

/**
 * A device's assigned color as it should render on UI surfaces. Stored
 * colors are picked against dark surfaces; on the light theme the brightest
 * ones are deepened by an HSL lightness clamp, which preserves hue — yellow
 * must stay yellow, not shift orange. The dark theme and the game canvas
 * (always dark water — use the raw color there) are unaffected.
 */
@Composable
fun deviceDisplayColor(colorArgb: Long): Color {
    val stored = Color(colorArgb.toInt())
    val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    if (!lightTheme) return stored

    val r = stored.red
    val g = stored.green
    val b = stored.blue
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val lightness = (mx + mn) / 2f
    if (lightness <= LIGHT_SURFACE_MAX_LIGHTNESS) return stored

    val d = mx - mn
    if (d == 0f) return stored // achromatic: hue undefined, leave grays alone
    val saturation = if (lightness > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val hue = 60f * when (mx) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return Color.hsl(
        hue = hue % 360f,
        saturation = (saturation + LIGHT_SURFACE_SATURATION_BOOST).coerceAtMost(1f),
        lightness = LIGHT_SURFACE_MAX_LIGHTNESS,
        alpha = stored.alpha
    )
}

// =============================================================================
// Shared composables used across multiple screens
// =============================================================================

/**
 * Shown when the peak detector reports its stuck state: the pressure reading
 * has stayed elevated instead of returning to zero, so no peaks can be
 * detected until the pressure drops or the device baseline is re-zeroed.
 */
@Composable
fun BaselineDriftDialog(
    onRecalibrate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pressure Not Returning to Zero") },
        text = {
            Text(
                "The pressure reading has stayed elevated instead of returning to zero.\n\n" +
                    "Check that the sensor opening is clean and unobstructed, then " +
                    "recalibrate the baseline. Recalibrating clears the pressure chart " +
                    "for this device."
            )
        },
        confirmButton = {
            TextButton(onClick = onRecalibrate) { Text("Recalibrate") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    )
}

/** Label–value row used in stats, summaries, and calibration displays. */
@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Label–value row for optional hPa values. Shows "Skipped" or "---" when null. */
@Composable
fun HpaValueRow(
    label: String,
    value: Double?,
    nullText: String = "---",
    unit: String = "hPa"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (value != null) "${value.fmt(1)} $unit" else nullText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (value != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Confirmation dialog for a detected pressure peak. */
@Composable
fun PeakConfirmDialog(
    peakValueHPa: Double,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Peak Detected") },
        text = {
            Text("${peakValueHPa.fmt(1)} hPa\n\nWas this a successful equalization?")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("No") }
        }
    )
}
