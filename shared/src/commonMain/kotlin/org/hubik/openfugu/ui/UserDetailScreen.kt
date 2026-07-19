package org.hubik.openfugu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.EFuguStore
import org.hubik.openfugu.ble.MockDeviceConnection
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.util.fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    store: EFuguStore,
    userId: String,
    onBack: () -> Unit,
    onStartCalibration: (userId: String) -> Unit,
    onDeleted: () -> Unit
) {
    val userProfiles by store.userProfiles.collectAsState()
    val savedDevices by store.savedDevices.collectAsState()
    val deviceUserPairings by store.deviceUserPairings.collectAsState()
    val appSettings by store.appSettings.collectAsState()
    val profile = userProfiles.find { it.id == userId }

    if (profile == null) {
        // User was deleted
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var showEditNameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = AppColors.outOfRange
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Name
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { showEditNameDialog = true }) {
                        Text("Edit")
                    }
                }
            }

            // Assigned devices
            SectionHeader("Assigned Devices")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val pairedDevices = deviceUserPairings
                        .filter { it.userId == profile.id }
                        .mapNotNull { pairing -> savedDevices.find { it.address == pairing.deviceAddress } }
                        .filter { appSettings.showSimulatedDevices || !MockDeviceConnection.isMockAddress(it.address) }
                    if (pairedDevices.isEmpty()) {
                        Text(
                            "No device assigned.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        pairedDevices.forEachIndexed { index, device ->
                            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                device.colorArgb?.let { colorArgb ->
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(deviceDisplayColor(colorArgb), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { store.unpairDevice(device.address) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }

                    // Saved devices not yet assigned to this user can be assigned
                    // right here; assigning re-pairs a device that belonged to
                    // another user (a device has at most one user).
                    val assignableDevices = savedDevices.filter { saved ->
                        deviceUserPairings.none { it.deviceAddress == saved.address && it.userId == profile.id }
                    }
                    if (savedDevices.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Connect your device on the Devices tab first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (assignableDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Assign Device")
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                assignableDevices.forEach { device ->
                                    val currentUser = deviceUserPairings
                                        .find { it.deviceAddress == device.address }
                                        ?.let { pairing -> userProfiles.find { it.id == pairing.userId } }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (currentUser != null) "${device.displayName}  ·  ${currentUser.name}"
                                                else device.displayName
                                            )
                                        },
                                        onClick = {
                                            store.pairDeviceToUser(device.address, profile.id)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Calibration
            SectionHeader("Calibration")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (profile.lastCalibratedAt != null) {
                        HpaValueRow("Minimum Equalization Pressure", profile.minEqPressureHPa)
                        HpaValueRow("Maximum Positive", profile.maxPositiveHPa)
                        HpaValueRow("Maximum Negative", profile.maxNegativeHPa)
                    } else {
                        Text(
                            "Run the calibration wizard to measure your equalization pressure range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onStartCalibration(userId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (profile.lastCalibratedAt != null) "Re-Calibrate" else "Run Calibration Wizard")
                    }
                }
            }

            // Game Pressure Range
            SectionHeader("Game Pressure Range")

            PressureRangeCard(
                profile = profile,
                onUpdate = { store.updateUser(it) }
            )

            // Expert Mode
            SectionHeader("Expert Mode")

            ExpertModeCard(
                profile = profile,
                onUpdate = { store.updateUser(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Edit name dialog
    if (showEditNameDialog) {
        EditNameDialog(
            currentName = profile.name,
            onDismiss = { showEditNameDialog = false },
            onConfirm = { newName ->
                store.updateUser(profile.copy(name = newName))
                showEditNameDialog = false
            }
        )
    }

    // Delete confirmation
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete User") },
            text = { Text("Delete \"${profile.name}\" and all their calibration data?") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteUser(profile.id)
                    showDeleteDialog = false
                    onDeleted()
                }) {
                    Text("Delete", color = AppColors.outOfRange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun EditNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PressureRangeCard(
    profile: UserProfile,
    onUpdate: (UserProfile) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto from calibration (80% of max)", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = profile.useAutoRange,
                    onCheckedChange = { auto ->
                        var updated = profile.copy(useAutoRange = auto)
                        if (!auto) {
                            if (updated.gamePressureRangeManual == null)
                                updated = updated.copy(gamePressureRangeManual = profile.gamePressureRange)
                            if (updated.gameNegativeRangeManual == null && profile.expertMode)
                                updated = updated.copy(gameNegativeRangeManual = profile.gameNegativeRange.coerceAtLeast(15.0))
                        }
                        onUpdate(updated)
                    }
                )
            }

            if (!profile.useAutoRange) {
                Spacer(modifier = Modifier.height(8.dp))
                val manualRange = profile.gamePressureRangeManual ?: 40.0
                Text(
                    "Positive range: ${manualRange.fmt(0)} hPa",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = manualRange.toFloat(),
                    onValueChange = {
                        onUpdate(profile.copy(gamePressureRangeManual = it.toDouble()))
                    },
                    valueRange = 5f..100f,
                    steps = 18
                )
                val positiveCap = profile.maxPositiveHPa
                if (positiveCap != null && manualRange > positiveCap) {
                    Text(
                        "Capped at ${positiveCap.fmt(0)} hPa — your calibrated " +
                            "comfortable maximum. Games never require pressure above it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (profile.expertMode) {
                    val manualNeg = profile.gameNegativeRangeManual ?: 15.0
                    Text(
                        "Negative range: ${manualNeg.fmt(0)} hPa",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = manualNeg.toFloat(),
                        onValueChange = {
                            onUpdate(profile.copy(gameNegativeRangeManual = it.toDouble()))
                        },
                        valueRange = 5f..50f,
                        steps = 8
                    )
                    val negativeCap = profile.maxNegativeHPa
                    if (negativeCap != null && manualNeg > negativeCap) {
                        Text(
                            "Capped at ${negativeCap.fmt(0)} hPa — your calibrated " +
                                "comfortable maximum. Games never require pressure above it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            Text(
                "Effective range: ${profile.gamePressureRange.fmt(0)} hPa" +
                    if (profile.expertMode) " / -${profile.gameNegativeRange.fmt(0)} hPa" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExpertModeCard(
    profile: UserProfile,
    onUpdate: (UserProfile) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bidirectional pressure", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Zero = center, positive = up, negative = down",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = profile.expertMode,
                    onCheckedChange = { expert ->
                        var updated = profile.copy(expertMode = expert)
                        if (expert && !profile.useAutoRange && updated.gameNegativeRangeManual == null) {
                            updated = updated.copy(gameNegativeRangeManual = 15.0)
                        }
                        onUpdate(updated)
                    }
                )
            }
            if (profile.expertMode && profile.gameNegativeRange <= 0.0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Warning: negative pressure range not calibrated. Floor will be 0 hPa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.outOfRange
                )
            }
        }
    }
}
