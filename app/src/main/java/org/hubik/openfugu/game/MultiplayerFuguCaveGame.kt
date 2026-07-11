package org.hubik.openfugu.game

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.DeviceConnectionState

// =============================================================================
// Constants (same tuning as single-player Fugu Cave)
// =============================================================================

private const val BASE_SCROLL_SPEED_DP = 150f   // dp/s starting speed
private const val SPEED_INCREMENT = 0.0008f      // speed multiplier increase per distance point
private const val SMOOTHING_FACTOR = 10f         // exponential smoothing rate
private const val FISH_RADIUS_DP = 16f           // dp

private val BgColor = Color(0xFF0D1B2A)

// =============================================================================
// Mutable per-player game state
// =============================================================================

private class CavePlayerState(
    val info: MultiplayerPlayerInfo,
    var fishY: Float = 0.5f,
    var alive: Boolean = true,
    var score: Int = 0
)

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerFuguCaveScreen(
    players: List<MultiplayerPlayerInfo>,
    onBack: () -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val density = LocalDensity.current

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var segments by remember { mutableStateOf(listOf<CaveSegment>()) }
    var playerStates by remember {
        mutableStateOf(players.map { CavePlayerState(it) })
    }
    // Actual canvas size in dp, reported from the Canvas draw scope (same
    // pattern as Fugu Cave) — never assume a hardcoded screen size.
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }

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
        playerStates = players.map { CavePlayerState(it) }
        scrollOffset = 0f
        segments = buildInitialCave(canvasSizeDp.first)
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
                val chartData = ps.info.connection.historySnapshot()
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
                type = org.hubik.openfugu.session.SessionType.MULTIPLAYER_CAVE_GAME,
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

                // The cave is shared, so distance — and with it speed and gap
                // narrowing — is shared too. A player's score freezes at the
                // distance where they died.
                val distance = (scrollOffset / 20f).toInt()
                val speed = BASE_SCROLL_SPEED_DP * (1f + distance * SPEED_INCREMENT)

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
                    ps.score = distance

                    // Check disconnection
                    if (ps.info.connection.state.value is DeviceConnectionState.Disconnected) {
                        ps.alive = false
                    }
                }

                // Scroll the cave
                val scrollDp = speed * clampedDt
                scrollOffset += scrollDp
                val (screenWDp, screenHDp) = canvasSizeDp
                val updated = advanceCave(segments, scrollDp, screenWDp, distance)
                segments = updated

                // Collision — all fish share the same x, so the gap is
                // sampled once and compared per player in dp space
                val fishXDp = 0.25f * screenWDp
                val gap = caveGapAt(updated, fishXDp)
                if (gap != null) {
                    val ceilingDp = gap.first * screenHDp
                    val floorDp = gap.second * screenHDp
                    alivePlayers.forEach { ps ->
                        val fishYDp = ps.fishY * screenHDp
                        if (fishYDp - FISH_RADIUS_DP < ceilingDp ||
                            fishYDp + FISH_RADIUS_DP > floorDp
                        ) {
                            ps.alive = false
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
                title = { Text("Multiplayer Fugu Cave") },
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
                val wDp = w / dpToPx
                val hDp = h / dpToPx
                if (canvasSizeDp.first != wDp || canvasSizeDp.second != hDp) {
                    canvasSizeDp = Pair(wDp, hDp)
                }
                val fishRadiusPx = FISH_RADIUS_DP * dpToPx
                val fishX = w * 0.25f

                // Background
                drawRect(BgColor)

                if (segments.size >= 2 &&
                    (gameState is GameState.Playing || gameState is GameState.GameOver)
                ) {
                    // Cave ceiling and floor
                    drawCaveSurface(
                        segments = segments,
                        dpToPx = dpToPx,
                        screenH = h,
                        screenW = w,
                        isCeiling = true,
                        scrollOffsetDp = scrollOffset
                    )
                    drawCaveSurface(
                        segments = segments,
                        dpToPx = dpToPx,
                        screenH = h,
                        screenW = w,
                        isCeiling = false,
                        scrollOffsetDp = scrollOffset
                    )

                    // Draw dead fish first (faded), then alive on top
                    playerStates.filter { !it.alive }.forEach { ps ->
                        drawFugu(fishX, h * ps.fishY, fishRadiusPx, bodyColor = ps.info.color, alpha = 0.3f)
                    }
                    playerStates.filter { it.alive }.forEach { ps ->
                        drawFugu(fishX, h * ps.fishY, fishRadiusPx, bodyColor = ps.info.color)
                    }

                    // Shared distance (top center)
                    drawScoreText((scrollOffset / 20f).toInt(), w)

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
                        textPaint.color = if (ps.alive) ps.info.color.toArgb()
                            else ps.info.color.copy(alpha = 0.4f).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(label, w - 12f * dpToPx, y, textPaint)
                    }
                }

                // Overlays
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        // Show all fugus in a row with names, in the upper
                        // quarter so the overlay text below stays readable
                        val spacing = w / (players.size + 1)
                        players.forEachIndexed { idx, info ->
                            val cx = spacing * (idx + 1)
                            drawFugu(cx, h * 0.25f, fishRadiusPx * 1.5f, bodyColor = info.color)
                            drawContext.canvas.nativeCanvas.drawText(
                                info.userName ?: info.displayName,
                                cx,
                                h * 0.25f + fishRadiusPx * 2.5f,
                                android.graphics.Paint().apply {
                                    color = info.color.toArgb()
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

            // Game over overlay with Compose UI (ranked scoreboard)
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
