package org.hubik.openfugu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
