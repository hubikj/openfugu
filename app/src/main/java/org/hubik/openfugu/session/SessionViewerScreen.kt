package org.hubik.openfugu.session

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hubik.openfugu.ChartLine
import org.hubik.openfugu.PressureChart
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.ui.AppColors
import org.hubik.openfugu.ui.HpaValueRow
import org.hubik.openfugu.ui.StatRow
import java.io.File
import org.hubik.openfugu.util.FileStampFormat
import org.hubik.openfugu.util.LongDateTimeFormat
import org.hubik.openfugu.util.fmt
import org.hubik.openfugu.util.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionViewerScreen(
    viewModel: EFuguViewModel,
    sessionId: String,
    onBack: () -> Unit
) {
    // Session files can be multi-MB (20 Hz traces) — load off the main thread
    // and show a spinner instead of freezing composition.
    var loading by remember(sessionId) { mutableStateOf(true) }
    var loadedSession by remember(sessionId) { mutableStateOf<Session?>(null) }
    LaunchedEffect(sessionId) {
        loadedSession = viewModel.loadSession(sessionId)
        loading = false
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val session = loadedSession
    if (session == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionTitle(session)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { shareSession(context, viewModel, sessionId, session) }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = AppColors.outOfRange)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Metadata
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("Date", formatTimestamp(session.timestamp, LongDateTimeFormat))
                    StatRow("Duration", formatDuration(session.durationMs))
                    StatRow("Device", session.deviceName)
                    session.userName?.let { StatRow("User", it) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chart
            val chartLines = when (session) {
                is Session.MultiplayerGameSession -> session.players.sortedBy { it.rank }.map { p ->
                    ChartLine(p.pressureTrace, p.colorArgb?.let { Color(it.toInt()) })
                }
                else -> if (session.pressureTrace.size >= 2) listOf(ChartLine(session.pressureTrace)) else emptyList()
            }
            if (chartLines.isNotEmpty() && chartLines.any { it.data.size >= 2 }) {
                PressureChart(
                    lines = chartLines,
                    staticFullRange = true,
                    peakMarkers = (session as? Session.MinEqSession)?.peakMarkers,
                    targetRange = when (session) {
                        is Session.ConstantEqSession -> Pair(session.lowerBound, session.upperBound)
                        is Session.GameSession -> if (session.expertMode && session.negativeRange > 0.0) {
                            Pair(-session.negativeRange, session.pressureRange)
                        } else {
                            Pair(0.0, session.pressureRange)
                        }
                        else -> null
                    },
                    activationThreshold = (session as? Session.ConstantEqSession)?.activationThreshold,
                    scoringStartMs = (session as? Session.ConstantEqSession)?.scoringStartMs ?: 0L,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Session-specific stats
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (session) {
                        is Session.MinEqSession -> {
                            Text("Minimum Equalization Results", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            StatRow("Mean pressure", "${session.mean.fmt(1)} hPa")
                            session.stddev?.let { StatRow("Standard deviation", "${it.fmt(1)} hPa") }
                            StatRow("Successful equalizations", "${session.successCount}")
                            StatRow("Failed/rejected", "${session.failCount}")
                            if (session.successCount + session.failCount > 0) {
                                val rate = session.successCount.toFloat() / (session.successCount + session.failCount)
                                StatRow("Success rate", "${(rate * 100).fmt(0)}%")
                            }
                        }
                        is Session.ConstantEqSession -> {
                            Text("Constant Equalization Results", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            StatRow("Time in range", "${(session.percentInRange * 100).fmt(0)}%")
                            StatRow("Best streak", formatDuration(session.bestStreakMs))
                            StatRow("Difficulty", session.difficultyLabel)
                            StatRow("Target range", "${session.lowerBound.fmt(1)} – ${session.upperBound.fmt(1)} hPa")
                            StatRow("Duration setting", session.durationSetting)
                        }
                        is Session.GameSession -> {
                            Text(sessionTypeDisplayName(session.type), style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            StatRow("Score", "${session.score}")
                            StatRow("Pressure range", "${session.pressureRange.fmt(0)} hPa")
                            if (session.expertMode && session.negativeRange > 0.0) {
                                StatRow("Negative range", "${session.negativeRange.fmt(0)} hPa")
                            }
                            if (session.expertMode) {
                                StatRow("Expert mode", "Yes")
                            }
                        }
                        is Session.MultiplayerGameSession -> {
                            Text("Multiplayer Results", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            StatRow("Players", "${session.players.size}")
                            session.players.sortedBy { it.rank }.forEach { p ->
                                val medal = when (p.rank) {
                                    1 -> "1st"
                                    2 -> "2nd"
                                    3 -> "3rd"
                                    else -> "${p.rank}th"
                                }
                                val name = p.userName ?: p.deviceName
                                StatRow("$medal — $name", "Score: ${p.score}")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Session") },
            text = { Text("Delete this session recording? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(sessionId)
                    showDeleteDialog = false
                    onBack()
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

private fun sessionTitle(session: Session): String = sessionTypeDisplayName(session.type)

private fun sessionTypeDisplayName(type: SessionType): String = when (type) {
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private suspend fun shareSession(context: android.content.Context, viewModel: EFuguViewModel, sessionId: String, session: Session) {
    val json = viewModel.exportSessionJson(sessionId)
    if (json == null) {
        viewModel.postUserMessage("Session file not found")
        return
    }
    try {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared_sessions")
            dir.mkdirs()
            // Old share copies accumulate otherwise; ones from previous shares
            // are no longer referenced by any in-flight intent we control.
            dir.listFiles()?.forEach { it.delete() }
            val userStr = session.userName?.replace(" ", "_") ?: "unknown"
            val dateStr = formatTimestamp(session.timestamp, FileStampFormat)
            val typeStr = sessionTypeDisplayName(session.type).replace(" ", "_")
            // .fugu extension (JSON inside): receiving apps resolve it to
            // application/octet-stream, which is what our import intent filter
            // matches — a plain .json file would offer OpenFugu for every JSON.
            val fileName = "OpenFugu_${userStr}_${dateStr}_${typeStr}.fugu"
            File(dir, fileName).apply { writeText(json) }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share session"))
    } catch (e: Exception) {
        viewModel.postUserMessage("Failed to share: ${e.message}")
    }
}
