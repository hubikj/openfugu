package org.hubik.openfugu.game

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.DeviceConnection
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.formatHPa
import kotlin.math.max
import kotlin.math.sin

// =============================================================================
// Constants (shared with single-player where applicable)
// =============================================================================

private const val BASE_SCROLL_SPEED_DP = 120f
private const val SPEED_INCREMENT = 0.02f
private const val GAP_SIZE = 0.25f
private const val OBSTACLE_SPACING_DP = 200
private const val FIRST_OBSTACLE_DP = 400
private const val SMOOTHING_FACTOR = 10f
private const val FISH_RADIUS_DP = 16
private const val OBSTACLE_WIDTH_DP = 40

private val BgColor = Color(0xFF0D1B2A)
private val SeabedColor = Color(0xFF1A2D40)
private val ObstacleColor = Color(0xFF2E8B57)
private val ObstacleEdge = Color(0xFF3CB371)

private data class ReefObstacle(
    var x: Float,
    val gapCenterY: Float,
    var scored: Boolean = false
)

// =============================================================================
// Player info passed to the game
// =============================================================================

data class MultiplayerPlayerInfo(
    val connection: DeviceConnection,
    val userProfile: UserProfile?,
    val savedDevice: SavedDevice,
    val color: Color,
    val displayName: String,
    val userName: String?
) {
    val pressureRange: Double get() = userProfile?.gamePressureRange ?: 40.0
    val negativeRange: Double get() = userProfile?.gameNegativeRange ?: 0.0
    val expertMode: Boolean get() = userProfile?.expertMode ?: false
}

// =============================================================================
// Mutable per-player game state
// =============================================================================

private class PlayerGameState(
    val info: MultiplayerPlayerInfo,
    var fishY: Float = 0.85f,
    var alive: Boolean = true,
    var score: Int = 0
)

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerFuguReefScreen(
    players: List<MultiplayerPlayerInfo>,
    onBack: () -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val density = LocalDensity.current

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var obstacles by remember { mutableStateOf(listOf<ReefObstacle>()) }
    var playerStates by remember {
        mutableStateOf(players.map { PlayerGameState(it) })
    }

    // Max score among alive players drives speed
    val maxAliveScore = playerStates.filter { it.alive }.maxOfOrNull { it.score } ?: 0

    val fishMinY = 0.05f
    val fishMaxY = 0.95f

    // Collect pressure from all players
    val pressures = players.map { p ->
        p.connection.latestPressure.collectAsState().value
    }
    val calibrated = players.map { p ->
        p.connection.isCalibrated.collectAsState().value
    }
    val allCalibrated = calibrated.all { it }
    val allHavePressure = pressures.all { it != null }

    fun resetGame() {
        playerStates = players.map { PlayerGameState(it) }
        scrollOffset = 0f
        obstacles = emptyList()
        gameStartMs = System.currentTimeMillis()
        gameState = GameState.Playing
    }

    // Save session on game over
    LaunchedEffect(gameState) {
        val gs = gameState
        if (gs is GameState.GameOver && onSessionSave != null) {
            val endMs = System.currentTimeMillis()
            val ranked = playerStates.sortedByDescending { it.score }
            val playerResults = ranked.mapIndexed { idx, ps ->
                val chartData = ps.info.connection.chartData.value
                org.hubik.openfugu.session.Session.PlayerResult(
                    deviceName = ps.info.displayName,
                    userName = ps.info.userName,
                    colorArgb = ps.info.savedDevice.colorArgb,
                    score = ps.score,
                    rank = idx + 1,
                    pressureTrace = chartData.filter { it.timestamp in gameStartMs..endMs },
                    pressureRange = ps.info.pressureRange,
                    negativeRange = ps.info.negativeRange,
                    expertMode = ps.info.expertMode
                )
            }
            val winner = playerResults.firstOrNull()
            onSessionSave.invoke(org.hubik.openfugu.session.Session.MultiplayerGameSession(
                type = org.hubik.openfugu.session.SessionType.MULTIPLAYER_REEF_GAME,
                durationMs = endMs - gameStartMs,
                pressureTrace = winner?.pressureTrace ?: emptyList(),
                players = playerResults
            ))
        }
    }

    // Game loop
    LaunchedEffect(gameState) {
        if (gameState !is GameState.Playing) return@LaunchedEffect

        var lastNanos = 0L
        while (gameState is GameState.Playing) {
            withInfiniteAnimationFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0f
                else (nanos - lastNanos) / 1_000_000_000f
                lastNanos = nanos
                val clampedDt = dt.coerceAtMost(0.05f)

                val alivePlayers = playerStates.filter { it.alive }
                if (alivePlayers.isEmpty()) {
                    val topScore = playerStates.maxOfOrNull { it.score } ?: 0
                    gameState = GameState.GameOver(topScore)
                    return@withInfiniteAnimationFrameNanos
                }

                val currentMaxScore = alivePlayers.maxOf { it.score }
                val speed = BASE_SCROLL_SPEED_DP * (1f + currentMaxScore * SPEED_INCREMENT)

                // Update each alive player
                alivePlayers.forEach { ps ->
                    val pressure = ps.info.connection.latestPressure.value
                    val currentPressure = pressure?.relativeHPa ?: 0.0
                    val targetY = calculateTargetY(
                        currentPressure, ps.info.pressureRange,
                        ps.info.negativeRange, ps.info.expertMode
                    )
                    ps.fishY += (targetY - ps.fishY) * SMOOTHING_FACTOR * clampedDt
                    ps.fishY = ps.fishY.coerceIn(fishMinY, fishMaxY)

                    // Check disconnection
                    if (ps.info.connection.state.value is DeviceConnectionState.Disconnected) {
                        ps.alive = false
                    }
                }

                // Scroll obstacles
                scrollOffset += speed * clampedDt
                val updated = obstacles.toMutableList()
                updated.forEach { it.x -= speed * clampedDt }
                updated.removeAll { it.x < -(OBSTACLE_WIDTH_DP * 2) }

                // Spawn
                val screenWidthDp = with(density) { 400.dp.toPx() / density.density }.toFloat()
                val rightEdge = updated.maxOfOrNull { it.x }
                val spawnThreshold = screenWidthDp + OBSTACLE_WIDTH_DP
                if (rightEdge == null || rightEdge < spawnThreshold) {
                    val gapCenter = 0.2f + Math.random().toFloat() * 0.6f
                    val spawnX = if (updated.isEmpty()) {
                        screenWidthDp + FIRST_OBSTACLE_DP
                    } else {
                        rightEdge!! + OBSTACLE_SPACING_DP
                    }
                    updated.add(ReefObstacle(x = spawnX, gapCenterY = gapCenter))
                }
                obstacles = updated

                // Collision + scoring per player
                val fishX = screenWidthDp * 0.25f
                val fishRadiusDp = FISH_RADIUS_DP.toFloat()

                updated.forEach { obs ->
                    val obsLeftDp = obs.x
                    val obsRightDp = obs.x + OBSTACLE_WIDTH_DP
                    val gapTop = obs.gapCenterY - GAP_SIZE / 2f
                    val gapBottom = obs.gapCenterY + GAP_SIZE / 2f

                    // Scoring (once per obstacle)
                    if (!obs.scored && obsRightDp < fishX) {
                        obs.scored = true
                        alivePlayers.forEach { it.score++ }
                    }

                    // Collision per alive player
                    if (obsRightDp > fishX - fishRadiusDp && obsLeftDp < fishX + fishRadiusDp) {
                        alivePlayers.forEach { ps ->
                            if (!ps.alive) return@forEach
                            val fishYNorm = ps.fishY
                            // Check if fish is outside the gap (in normalized coords)
                            if (fishYNorm - fishRadiusDp / screenWidthDp < gapTop ||
                                fishYNorm + fishRadiusDp / screenWidthDp > gapBottom) {
                                // More precise circle-rect collision
                                val fishYScreen = fishYNorm * 1f // normalized
                                val topBarrierBottom = gapTop
                                val bottomBarrierTop = gapBottom
                                val fishR = fishRadiusDp / screenWidthDp
                                if (fishYScreen - fishR < topBarrierBottom || fishYScreen + fishR > bottomBarrierTop) {
                                    ps.alive = false
                                }
                            }
                        }
                    }
                }

                // Trigger recomposition by copying state
                playerStates = playerStates.toList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multiplayer Fugu Reef") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickable {
                    when (gameState) {
                        is GameState.WaitingToStart -> {
                            if (allCalibrated && allHavePressure) resetGame()
                        }
                        is GameState.GameOver -> resetGame()
                        else -> {}
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dpToPx = density.density
                val fishRadiusPx = FISH_RADIUS_DP * dpToPx
                val obstacleWidthPx = OBSTACLE_WIDTH_DP * dpToPx
                val fishX = w * 0.25f

                // Background
                drawRect(BgColor)

                // Seabed
                val seabedY = h * 0.85f
                val seabedPath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, seabedY)
                    var px = 0f
                    while (px <= w) {
                        val waveOffset = sin((px + scrollOffset * dpToPx * 0.5f) * 0.015f) * h * 0.02f
                        lineTo(px, seabedY + waveOffset)
                        px += 4f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(seabedPath, SeabedColor)

                if (gameState is GameState.Playing || gameState is GameState.GameOver) {
                    // Obstacles
                    obstacles.forEach { obs ->
                        val obsLeftPx = obs.x * dpToPx
                        val obsRightPx = obsLeftPx + obstacleWidthPx
                        val gapTop = h * (obs.gapCenterY - GAP_SIZE / 2f)
                        val gapBottom = h * (obs.gapCenterY + GAP_SIZE / 2f)

                        if (obsRightPx > 0f && obsLeftPx < w) {
                            drawRoundRect(
                                ObstacleColor,
                                topLeft = Offset(obsLeftPx, 0f),
                                size = Size(obstacleWidthPx, gapTop),
                                cornerRadius = CornerRadius(6f)
                            )
                            drawRoundRect(
                                ObstacleEdge,
                                topLeft = Offset(obsLeftPx - 4f, gapTop - 12f),
                                size = Size(obstacleWidthPx + 8f, 12f),
                                cornerRadius = CornerRadius(4f)
                            )
                            drawRoundRect(
                                ObstacleColor,
                                topLeft = Offset(obsLeftPx, gapBottom),
                                size = Size(obstacleWidthPx, h - gapBottom),
                                cornerRadius = CornerRadius(6f)
                            )
                            drawRoundRect(
                                ObstacleEdge,
                                topLeft = Offset(obsLeftPx - 4f, gapBottom),
                                size = Size(obstacleWidthPx + 8f, 12f),
                                cornerRadius = CornerRadius(4f)
                            )
                        }
                    }

                    // Draw dead fish first (faded), then alive on top
                    playerStates.filter { !it.alive }.forEach { ps ->
                        val fishYPx = (h * ps.fishY).coerceIn(fishRadiusPx * 1.5f, h - fishRadiusPx * 1.5f)
                        drawFugu(fishX, fishYPx, fishRadiusPx, bodyColor = ps.info.color, alpha = 0.3f)
                    }
                    playerStates.filter { it.alive }.forEach { ps ->
                        val fishYPx = (h * ps.fishY).coerceIn(fishRadiusPx * 1.5f, h - fishRadiusPx * 1.5f)
                        drawFugu(fishX, fishYPx, fishRadiusPx, bodyColor = ps.info.color)
                    }

                    // Scoreboard (top-right)
                    val textPaint = android.graphics.Paint().apply {
                        textSize = 14f * dpToPx
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                    val lineHeight = 20f * dpToPx
                    playerStates.sortedByDescending { it.score }.forEachIndexed { idx, ps ->
                        val y = 28f * dpToPx + idx * lineHeight
                        val label = "${ps.info.userName ?: ps.info.displayName}: ${ps.score}"
                        textPaint.color = if (ps.alive) ps.info.color.hashCode()
                            else ps.info.color.copy(alpha = 0.4f).hashCode()
                        drawContext.canvas.nativeCanvas.drawText(label, w - 12f * dpToPx, y, textPaint)
                    }
                }

                // Overlays
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        // Show all fugus in a row with names
                        val spacing = w / (players.size + 1)
                        players.forEachIndexed { idx, info ->
                            val cx = spacing * (idx + 1)
                            drawFugu(cx, h / 2f, fishRadiusPx * 1.5f, bodyColor = info.color)
                            drawContext.canvas.nativeCanvas.drawText(
                                info.userName ?: info.displayName,
                                cx,
                                h / 2f + fishRadiusPx * 2.5f,
                                android.graphics.Paint().apply {
                                    color = info.color.hashCode()
                                    textSize = 14f * dpToPx
                                    isAntiAlias = true
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                            )
                        }
                        drawOverlayText(
                            w, h,
                            if (allCalibrated && allHavePressure)
                                "Tap to start"
                            else
                                "Waiting for all devices..."
                        )
                    }
                    is GameState.GameOver -> {
                        drawRect(GameOverlayBg)
                        // Game over text handled by overlay below
                    }
                    else -> {}
                }
            }

            // Game over overlay with Compose UI (scrollable scoreboard)
            if (gameState is GameState.GameOver) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Game Over",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    val ranked = playerStates.sortedByDescending { it.score }
                    ranked.forEachIndexed { idx, ps ->
                        val medal = when (idx) {
                            0 -> "1st"
                            1 -> "2nd"
                            2 -> "3rd"
                            else -> "${idx + 1}th"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                medal,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (idx == 0) Color(0xFFFFD700) else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.width(40.dp)
                            )
                            Canvas(modifier = Modifier.size(20.dp)) {
                                drawCircle(ps.info.color)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                ps.info.userName ?: ps.info.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${ps.score}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = ps.info.color
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Tap to play again",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
