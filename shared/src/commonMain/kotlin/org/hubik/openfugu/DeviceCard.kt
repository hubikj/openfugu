package org.hubik.openfugu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.UserProfile

// =============================================================================
// Device card (single-device view)
// =============================================================================

@Composable
fun DeviceCard(
    savedDevice: SavedDevice,
    batteryLevel: Int?,
    deviceInfo: Map<String, String> = emptyMap(),
    userProfiles: List<UserProfile> = emptyList(),
    pairedUserId: String? = null,
    isConnecting: Boolean = false,
    onDisconnect: () -> Unit,
    onNicknameSet: (String?) -> Unit,
    onColorSet: (Long?) -> Unit,
    onPairUser: (String?) -> Unit = {}
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val pairedUser = userProfiles.find { it.id == pairedUserId }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color dot or Bluetooth icon
            if (savedDevice.colorArgb != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(savedDevice.colorArgb.toInt()), CircleShape)
                )
            } else {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        savedDevice.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (pairedUser != null) {
                        Text(
                            "  ·  ${pairedUser.name}",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                val statusItems = buildList {
                    batteryLevel?.let { add("Battery: $it%") }
                    deviceInfo["Firmware"]?.let { add("FW $it") }
                    deviceInfo["Hardware"]?.let { add("HW $it") }
                }
                if (statusItems.isNotEmpty()) {
                    Text(
                        statusItems.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (savedDevice.nickname != null) "${savedDevice.name} — ${savedDevice.address}"
                    else savedDevice.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onDisconnect) {
                Text(if (isConnecting) "Cancel" else "Disconnect", fontSize = 12.sp)
            }
        }
    }

    if (showEditDialog) {
        DeviceEditDialog(
            currentNickname = savedDevice.nickname,
            currentColor = savedDevice.colorArgb,
            deviceName = savedDevice.name,
            userProfiles = userProfiles,
            currentPairedUserId = pairedUserId,
            onDismiss = { showEditDialog = false },
            onConfirm = { nickname, color, userId ->
                onNicknameSet(nickname)
                onColorSet(color)
                onPairUser(userId)
                showEditDialog = false
            }
        )
    }
}
