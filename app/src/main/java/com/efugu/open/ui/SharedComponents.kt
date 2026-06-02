package com.efugu.open.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
            if (value != null) "${"%.1f".format(value)} $unit" else nullText,
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
            Text("${"%.1f".format(peakValueHPa)} hPa\n\nWas this a successful equalization?")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("No") }
        }
    )
}
