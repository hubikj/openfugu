package org.hubik.openfugu

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun ExercisesTab(
    connections: Map<String, DeviceConnection>,
    savedDevices: List<SavedDevice>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<DeviceUserPairing>,
    recentSessions: List<SessionIndexEntry> = emptyList(),
    onGameStart: (String, DeviceConnection) -> Unit,
    onMultiplayerGameStart: (String, List<DeviceConnection>) -> Unit = { _, _ -> },
    onSessionClick: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onPairUser: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Collect connection states keyed by address so Compose tracks each slot independently
    val connectionStates = mutableMapOf<String, DeviceConnectionState>()
    connections.forEach { (address, conn) ->
        key(address) {
            connectionStates[address] = conn.state.collectAsState().value
        }
    }
    val connectedList = connections.values.filter { connectionStates[it.address] is DeviceConnectionState.Connected }
    val hasConnecting = connectionStates.values.any { it is DeviceConnectionState.Connecting }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var showDevicePicker by remember { mutableStateOf(false) }

    // Auto-select: pick previously selected if still connected, else first connected
    val selectedConnection = connectedList.find { it.address == selectedAddress }
        ?: connectedList.firstOrNull()
    // Keep selectedAddress in sync
    LaunchedEffect(selectedConnection?.address) {
        selectedAddress = selectedConnection?.address
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (selectedConnection == null) {
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
            // Device selector (show when multiple connected)
            if (connectedList.size > 1) {
                val selectedSaved = savedDevices.find { it.address == selectedConnection.address }
                val pairedUserId = deviceUserPairings.find { it.deviceAddress == selectedConnection.address }?.userId
                val pairedUser = userProfiles.find { it.id == pairedUserId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDevicePicker = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedSaved?.colorArgb != null) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color(selectedSaved.colorArgb.toInt()), CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Filled.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        selectedSaved?.displayName ?: selectedConnection.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (pairedUser != null) {
                        Text(
                            "  ·  ${pairedUser.name}",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("Change", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text("Games", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGameStart("reef", selectedConnection) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
                        drawFugu(size.width / 2f, size.height / 2f, size.minDimension / 3f)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fugu Reef", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Navigate through reef obstacles using equalization pressure",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGameStart("feast", selectedConnection) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
                        drawEnemyFish(size.width / 2f, size.height / 2f, size.minDimension / 3f, isSmaller = false)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fugu Feast", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Eat smaller fish to grow, avoid bigger ones!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGameStart("cave", selectedConnection) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
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
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fugu Cave", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Navigate through narrowing cave passages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGameStart("flow", selectedConnection) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
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
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fugu Flow", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Follow a scrolling pressure pattern — rhythm and accuracy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Multiplayer section
            if (connectedList.size >= 2) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Multiplayer", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                var showMultiplayerPicker by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMultiplayerPicker = true }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(modifier = Modifier.size(48.dp)) {
                            val r = size.minDimension / 5f
                            drawFugu(size.width * 0.3f, size.height * 0.45f, r, bodyColor = Color(0xFFE53935))
                            drawFugu(size.width * 0.7f, size.height * 0.55f, r, bodyColor = Color(0xFF1E88E5))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Multiplayer Fugu Reef", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Race through the reef — last fugu standing wins!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showMultiplayerPicker) {
                    DevicePickerDialog(
                        connections = connections,
                        savedDevices = savedDevices,
                        userProfiles = userProfiles,
                        deviceUserPairings = deviceUserPairings,
                        multiSelect = true,
                        onMultiSelect = { selected ->
                            showMultiplayerPicker = false
                            onMultiplayerGameStart("multiplayer_reef", selected)
                        },
                        onPairUser = onPairUser,
                        onDismiss = { showMultiplayerPicker = false }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Exercises section
            val selectedPairedUserId = deviceUserPairings.find { it.deviceAddress == selectedConnection.address }?.userId
            val selectedUserProfile = userProfiles.find { it.id == selectedPairedUserId }
            val hasMinEq = selectedUserProfile?.minEqPressureHPa != null

            Text("Exercises", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Min EQ Practice
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGameStart("min_eq", selectedConnection) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
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
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Minimum Equalization", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Find your minimum pressure needed to equalize",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Constant EQ
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasMinEq) Modifier.clickable { onGameStart("constant_eq", selectedConnection) }
                        else Modifier
                    ),
                colors = if (hasMinEq) CardDefaults.cardColors()
                    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
                        val w = size.width
                        val h = size.height
                        val rangeColor = if (hasMinEq) AppColors.inRange else Color.Gray
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
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Constant Equalization", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hasMinEq) "Hold steady equalization pressure within a target range"
                            else "Requires minimum equalization calibration first",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

    if (showDevicePicker) {
        DevicePickerDialog(
            connections = connections,
            savedDevices = savedDevices,
            userProfiles = userProfiles,
            deviceUserPairings = deviceUserPairings,
            selectedAddress = selectedAddress,
            onSelect = { conn ->
                selectedAddress = conn.address
                showDevicePicker = false
            },
            onPairUser = onPairUser,
            onDismiss = { showDevicePicker = false }
        )
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
}
