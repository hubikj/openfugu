package org.hubik.openfugu

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.DeviceConnection
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.DeviceUserPairing
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.game.drawEnemyFish
import org.hubik.openfugu.game.drawFugu
import org.hubik.openfugu.session.SessionIndexEntry
import org.hubik.openfugu.session.SessionType
import org.hubik.openfugu.ui.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    connections: Map<String, DeviceConnection>,
    savedDevices: List<SavedDevice>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<DeviceUserPairing>,
    recentSessions: List<SessionIndexEntry> = emptyList(),
    // Hoisted to the caller: this composable leaves composition whenever a game
    // runs or another tab is shown, so locally remembered state would reset
    lastUsedDeviceAddresses: List<String> = emptyList(),
    onGameStart: (String, List<DeviceConnection>) -> Unit,
    onSessionClick: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onPairUser: (String, String?) -> Unit,
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

    fun userFor(address: String): UserProfile? {
        val userId = deviceUserPairings.find { it.deviceAddress == address }?.userId
        return userProfiles.find { it.id == userId }
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
            // enabled and the picker greys out uncalibrated devices instead.
            val singleDevice = connectedList.singleOrNull()
            val singleDeviceHasMinEq = singleDevice != null &&
                userFor(singleDevice.address)?.minEqPressureHPa != null

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
                val enabled = !entry.requiresMinEqCalibration ||
                    singleDevice == null || singleDeviceHasMinEq
                ExerciseCard(
                    entry = entry,
                    enabled = enabled,
                    description = if (enabled) entry.description
                        else "Requires minimum equalization calibration first",
                    onClick = { launchEntry(entry) }
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
            val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
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
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        val color by animateColorAsState(
                            if (dismissState.targetValue != SwipeToDismissBoxValue.Settled)
                                AppColors.outOfRange
                            else MaterialTheme.colorScheme.surfaceVariant,
                            label = "swipeBg"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                                .background(color, MaterialTheme.shapes.medium),
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
                                        append(dateFormat.format(Date(entry.timestamp)))
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
            multiSelect = entry.maxPlayers > 1,
            minSelect = entry.minPlayers,
            preselected = lastUsedDeviceAddresses.toSet(),
            onSelect = { conn ->
                pickerEntry = null
                onGameStart(entry.id, listOf(conn))
            },
            onMultiSelect = { selected ->
                pickerEntry = null
                onGameStart(entry.id, selected)
            },
            disabledReason = { device ->
                if (entry.requiresMinEqCalibration && userFor(device.address)?.minEqPressureHPa == null)
                    "Requires calibration"
                else null
            },
            onPairUser = onPairUser,
            onDismiss = { pickerEntry = null }
        )
    }
}

@Composable
private fun ExerciseCard(
    entry: ExerciseEntry,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
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
}
