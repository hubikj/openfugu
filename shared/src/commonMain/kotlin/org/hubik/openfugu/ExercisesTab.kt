package org.hubik.openfugu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.DeviceUserPairing
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.game.drawEnemyFish
import org.hubik.openfugu.game.drawFugu
import org.hubik.openfugu.session.SessionIndexEntry
import org.hubik.openfugu.session.SessionType
import org.hubik.openfugu.ui.AppColors
import org.hubik.openfugu.util.ShortDateTimeFormat
import org.hubik.openfugu.util.formatTimestamp
import kotlin.math.sin

// One entry per launchable game or exercise. Cards render from this list, and
// the device picker follows the player range: entries with maxPlayers > 1 get
// a checkbox picker (selecting one device runs the single-player version),
// single-player-only entries get a radio picker. The icon receives the card's
// enabled state so calibration-gated entries can draw greyed out.
private class ExerciseEntry(
    val id: String,
    val title: String,
    val description: String,
    val minPlayers: Int = 1,
    val maxPlayers: Int = 1,
    val requiresMinEqCalibration: Boolean = false,
    val icon: DrawScope.(enabled: Boolean) -> Unit
)

private val gameEntries = listOf(
    ExerciseEntry(
        id = "reef",
        title = "Fugu Reef",
        description = "Navigate through reef obstacles using equalization pressure",
        maxPlayers = 7,
        icon = { drawFugu(size.width / 2f, size.height / 2f, size.minDimension / 3f) }
    ),
    ExerciseEntry(
        id = "feast",
        title = "Fugu Feast",
        description = "Eat smaller fish to grow, avoid bigger ones!",
        maxPlayers = 7,
        icon = { drawEnemyFish(size.width / 2f, size.height / 2f, size.minDimension / 3f, edible = false) }
    ),
    ExerciseEntry(
        id = "cave",
        title = "Fugu Cave",
        description = "Navigate through narrowing cave passages",
        maxPlayers = 7,
        icon = {
            val w = size.width
            val h = size.height
            val caveColor = Color(0xFF4A3728)
            val ceilPath = Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, h * 0.28f)
                lineTo(w * 0.3f, h * 0.22f)
                lineTo(w * 0.6f, h * 0.32f)
                lineTo(w, h * 0.25f)
                lineTo(w, 0f)
                close()
            }
            drawPath(ceilPath, caveColor)
            val floorPath = Path().apply {
                moveTo(0f, h)
                lineTo(0f, h * 0.72f)
                lineTo(w * 0.35f, h * 0.78f)
                lineTo(w * 0.65f, h * 0.68f)
                lineTo(w, h * 0.75f)
                lineTo(w, h)
                close()
            }
            drawPath(floorPath, caveColor)
            drawFugu(w * 0.45f, h * 0.50f, size.minDimension / 6f)
        }
    ),
    ExerciseEntry(
        id = "flow",
        title = "Fugu Flow",
        description = "Follow a scrolling pressure pattern — rhythm and accuracy",
        icon = {
            val w = size.width
            val h = size.height
            // Mini sine wave with cursor dot
            val curvePath = Path().apply {
                for (i in 0..40) {
                    val x = w * i / 40f
                    val y = h * 0.5f - h * 0.3f * sin(x * 0.15f).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(curvePath, Color(0xFF4FC3F7), style = Stroke(width = 2.5f))
            // Cursor dot
            val dotX = w * 0.5f
            val dotY = h * 0.5f - h * 0.3f * sin(dotX * 0.15f).toFloat()
            drawCircle(Color(0xFF66BB6A), 4f, Offset(dotX, dotY))
        }
    )
)

/**
 * Why a calibration-gated entry cannot launch on a device. The distinction
 * matters: "assign a user" and "run the calibration wizard" are different
 * actions, and showing the wrong one sends people hunting for a problem
 * they don't have.
 */
private enum class CalibrationGate { NO_USER_ASSIGNED, USER_NOT_CALIBRATED }

private val exerciseEntries = listOf(
    ExerciseEntry(
        id = "min_eq",
        title = "Minimum Equalization",
        description = "Find your minimum pressure needed to equalize",
        icon = {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.1f, h * 0.7f)
                lineTo(w * 0.3f, h * 0.6f)
                lineTo(w * 0.5f, h * 0.15f)
                lineTo(w * 0.7f, h * 0.6f)
                lineTo(w * 0.9f, h * 0.7f)
            }
            drawPath(path, AppColors.inRange, style = Stroke(width = 3f))
        }
    ),
    ExerciseEntry(
        id = "constant_eq",
        title = "Constant Equalization",
        description = "Hold steady equalization pressure within a target range",
        requiresMinEqCalibration = true,
        icon = { enabled ->
            val w = size.width
            val h = size.height
            val rangeColor = if (enabled) AppColors.inRange else Color.Gray
            drawRect(
                rangeColor.copy(alpha = 0.2f),
                topLeft = Offset(0f, h * 0.35f),
                size = Size(w, h * 0.3f)
            )
            val linePath = Path().apply {
                moveTo(0f, h * 0.55f)
                cubicTo(w * 0.2f, h * 0.35f, w * 0.4f, h * 0.6f, w * 0.5f, h * 0.45f)
                cubicTo(w * 0.6f, h * 0.35f, w * 0.8f, h * 0.55f, w, h * 0.5f)
            }
            drawPath(linePath, rangeColor, style = Stroke(width = 3f))
        }
    )
)

@Composable
fun ExercisesTab(
    connections: Map<String, PressureSource>,
    savedDevices: List<SavedDevice>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<DeviceUserPairing>,
    recentSessions: List<SessionIndexEntry> = emptyList(),
    // Hoisted to the caller: this composable leaves composition whenever a game
    // runs or another tab is shown, so locally remembered state would reset
    lastUsedDeviceAddresses: List<String> = emptyList(),
    onGameStart: (String, List<PressureSource>) -> Unit,
    onSessionClick: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onPairUser: (String, String?) -> Unit,
    onCreateAndPairUser: (deviceAddress: String, name: String) -> Unit = { _, _ -> },
    onStartCalibration: (userId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Collect connection states keyed by address so Compose tracks each slot independently
    val connectionStates = mutableMapOf<String, DeviceConnectionState>()
    connections.forEach { (address, conn) ->
        key(address) {
            connectionStates[address] = conn.state.collectAsState().value
        }
    }
    val connectedList = connections.values.filter { connectionStates[it.address] is DeviceConnectionState.Connected }
    val hasConnecting = connectionStates.values.any { it is DeviceConnectionState.Connecting }

    // Which entry the device picker is open for (null = closed)
    var pickerEntry by remember { mutableStateOf<ExerciseEntry?>(null) }
    // Which device address the calibration-gate dialog is open for (null = closed)
    var gateDialogAddress by remember { mutableStateOf<String?>(null) }

    fun userFor(address: String): UserProfile? {
        val userId = deviceUserPairings.find { it.deviceAddress == address }?.userId
        return userProfiles.find { it.id == userId }
    }

    fun calibrationGateFor(entry: ExerciseEntry, address: String): CalibrationGate? {
        if (!entry.requiresMinEqCalibration) return null
        val user = userFor(address)
        return when {
            user == null -> CalibrationGate.NO_USER_ASSIGNED
            user.minEqPressureHPa == null -> CalibrationGate.USER_NOT_CALIBRATED
            else -> null
        }
    }

    // One connected device: launch on it directly (unless the entry needs
    // more players). Several: pick devices per launch.
    fun launchEntry(entry: ExerciseEntry) {
        val single = connectedList.singleOrNull()
        if (single != null && entry.minPlayers == 1) {
            onGameStart(entry.id, listOf(single))
        } else {
            pickerEntry = entry
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (connectedList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hasConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Connecting...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "No devices connected.\nGo to the Devices tab to connect.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else { Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
            // With exactly one device connected the calibration requirement
            // gates the card itself; with several devices the card stays
            // enabled and the picker greys out ineligible devices instead.
            val singleDevice = connectedList.singleOrNull()

            Text("Games", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            gameEntries.forEachIndexed { index, entry ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                ExerciseCard(
                    entry = entry,
                    enabled = true,
                    description = entry.description,
                    onClick = { launchEntry(entry) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Exercises", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            exerciseEntries.forEachIndexed { index, entry ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                val gate = singleDevice?.let { calibrationGateFor(entry, it.address) }
                ExerciseCard(
                    entry = entry,
                    enabled = gate == null,
                    description = when (gate) {
                        null -> entry.description
                        CalibrationGate.NO_USER_ASSIGNED ->
                            "Assign a user to your device first"
                        CalibrationGate.USER_NOT_CALIBRATED ->
                            "Requires minimum equalization calibration first"
                    },
                    // A gated card stays tappable: the tap opens a dialog that
                    // resolves the gate instead of a dead end.
                    onClick = {
                        if (gate == null) launchEntry(entry)
                        else gateDialogAddress = singleDevice?.address
                    }
                )
            }
        } }

        // History section — always visible
        if (recentSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "History",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            var pendingDeletes by remember { mutableStateOf(setOf<String>()) }

            // If the tab leaves composition during the undo window, commit the
            // pending deletes — otherwise they silently reappear on return.
            DisposableEffect(Unit) {
                onDispose { pendingDeletes.forEach { onDeleteSession(it) } }
            }

            // Commit all pending deletes after snackbar timeout
            LaunchedEffect(pendingDeletes) {
                if (pendingDeletes.isEmpty()) return@LaunchedEffect
                val toDelete = pendingDeletes.toSet()
                val count = toDelete.size
                val result = snackbarHostState.showSnackbar(
                    message = if (count == 1) "Session deleted" else "$count sessions deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    pendingDeletes = pendingDeletes - toDelete
                } else {
                    toDelete.forEach { onDeleteSession(it) }
                    pendingDeletes = pendingDeletes - toDelete
                }
            }

            recentSessions.take(20).forEach { entry ->
                if (entry.id in pendingDeletes) return@forEach
                // key() gives each row identity by session id — with positional
                // slots, a swipe after a delete targets the wrong session.
                key(entry.id) {
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            pendingDeletes = pendingDeletes + entry.id
                            false
                        } else true
                    },
                    // The 56 dp default fires on slightly diagonal list
                    // scrolls; deleting must take a committed swipe across
                    // most of the row.
                    positionalThreshold = { totalDistance -> totalDistance * 0.6f }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    // One direction is enough for delete and halves the
                    // surface for accidental swipes.
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        // Constant red: the revealed area itself signals how
                        // far along the delete is — a card-colored background
                        // made the swipe nearly invisible.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                                .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                "Delete",
                                modifier = Modifier.padding(end = 16.dp),
                                color = MaterialTheme.colorScheme.onError,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clickable { onSessionClick(entry.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    sessionTypeLabel(entry.type),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    buildString {
                                        append(formatTimestamp(entry.timestamp, ShortDateTimeFormat))
                                        append("  ·  ")
                                        val secs = entry.durationMs / 1000
                                        if (secs >= 60) append("${secs / 60}m ${secs % 60}s")
                                        else append("${secs}s")
                                        entry.userName?.let { append("  ·  $it") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                entry.summaryText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                } // key(entry.id)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    } // Box

    pickerEntry?.let { entry ->
        DevicePickerDialog(
            connections = connections,
            savedDevices = savedDevices,
            userProfiles = userProfiles,
            deviceUserPairings = deviceUserPairings,
            multiSelect = true,
            minSelect = entry.minPlayers,
            maxSelect = entry.maxPlayers,
            preselected = lastUsedDeviceAddresses.toSet(),
            onMultiSelect = { selected ->
                pickerEntry = null
                onGameStart(entry.id, selected)
            },
            disabledReason = { device ->
                when (calibrationGateFor(entry, device.address)) {
                    null -> null
                    CalibrationGate.NO_USER_ASSIGNED -> "No user assigned"
                    CalibrationGate.USER_NOT_CALIBRATED -> "Requires calibration"
                }
            },
            onPairUser = onPairUser,
            onDismiss = { pickerEntry = null }
        )
    }

    gateDialogAddress?.let { address ->
        val deviceUser = userFor(address)
        if (deviceUser?.minEqPressureHPa != null) {
            // Resolved (user assigned and calibrated) — the card is enabled
            // now and the next tap launches the exercise.
            LaunchedEffect(Unit) { gateDialogAddress = null }
        } else {
            CalibrationGateDialog(
                deviceUser = deviceUser,
                userProfiles = userProfiles,
                onAssignUser = { userId -> onPairUser(address, userId) },
                onCreateUser = { name -> onCreateAndPairUser(address, name) },
                onCalibrate = { userId ->
                    gateDialogAddress = null
                    onStartCalibration(userId)
                },
                onDismiss = { gateDialogAddress = null }
            )
        }
    }
}

/**
 * Resolves a calibration gate in place instead of leaving a greyed-out dead
 * end. Branches on the live pairing state, so assigning an uncalibrated user
 * flips the dialog straight to the calibration offer.
 */
@Composable
private fun CalibrationGateDialog(
    deviceUser: UserProfile?,
    userProfiles: List<UserProfile>,
    onAssignUser: (userId: String) -> Unit,
    onCreateUser: (name: String) -> Unit,
    onCalibrate: (userId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var newUserName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deviceUser == null) "Assign a User" else "Calibration Needed") },
        text = {
            if (deviceUser == null) {
                Column {
                    Text(
                        "This exercise builds its target range from the calibration " +
                            "of the user assigned to your device."
                    )
                    if (userProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        userProfiles.forEach { profile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssignUser(profile.id) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(profile.name, modifier = Modifier.weight(1f))
                                Text(
                                    if (profile.minEqPressureHPa != null) "Calibrated"
                                    else "Not calibrated",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newUserName,
                            onValueChange = { newUserName = it },
                            label = { Text("New user") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onCreateUser(newUserName.trim())
                                newUserName = ""
                            },
                            enabled = newUserName.isNotBlank()
                        ) { Text("Create") }
                    }
                }
            } else {
                Text(
                    "${deviceUser.name} has no minimum equalization calibration yet. " +
                        "The calibration wizard measures it, and this exercise builds " +
                        "its target range from the result."
                )
            }
        },
        confirmButton = {
            if (deviceUser == null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else {
                TextButton(onClick = { onCalibrate(deviceUser.id) }) { Text("Calibrate Now") }
            }
        },
        dismissButton = {
            if (deviceUser != null) {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
    )
}

@Composable
private fun ExerciseCard(
    entry: ExerciseEntry,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit
) {
    Card(
        // Clickable even when gated — the caller routes the tap to a dialog
        // that resolves the gate (assign a user / calibrate).
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (enabled) CardDefaults.cardColors()
            else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(48.dp)) { entry.icon(this, enabled) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                    if (entry.maxPlayers > 1) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "1–${entry.maxPlayers} players",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun sessionTypeLabel(type: SessionType): String = when (type) {
    SessionType.MIN_EQ -> "Minimum Equalization"
    SessionType.CONSTANT_EQ -> "Constant Equalization"
    SessionType.REEF_GAME -> "Fugu Reef"
    SessionType.FEAST_GAME -> "Fugu Feast"
    SessionType.CAVE_GAME -> "Fugu Cave"
    SessionType.FLOW_GAME -> "Fugu Flow"
    SessionType.MULTIPLAYER_REEF_GAME -> "Multiplayer Fugu Reef"
    SessionType.MULTIPLAYER_FEAST_GAME -> "Multiplayer Fugu Feast"
    SessionType.MULTIPLAYER_CAVE_GAME -> "Multiplayer Fugu Cave"
}
