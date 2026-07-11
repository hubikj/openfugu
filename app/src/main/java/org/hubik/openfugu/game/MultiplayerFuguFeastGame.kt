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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.DeviceConnectionState
import kotlin.math.hypot
import kotlin.math.sin

// =============================================================================
// Constants (shared with single-player Fugu Feast where applicable)
// =============================================================================

private const val BASE_SPEED_DP = 140f
private const val SPEED_INCREMENT = 0.003f
private const val SMOOTHING_FACTOR = 10f
private const val PLAYER_START_RADIUS_DP = 18f
private const val GROWTH_PER_EAT = 1.5f
private const val MAX_PLAYER_RADIUS_DP = 50f
private const val ENEMY_MIN_RADIUS_DP = 8f
private const val ENEMY_MAX_RADIUS_DP = 70f
private const val ENEMY_SPAWN_INTERVAL = 0.6f
private const val ROCK_SPAWN_INTERVAL = 3.0f
private const val ROCK_WIDTH_DP = 160f
private const val ROCK_MIN_HEIGHT_DP = 60f
private const val ROCK_MAX_HEIGHT_DP = 140f

// Everyone shares one screen, so a fish's color must mean the same thing for
// every player: prey spawn smaller than the smallest alive player, predators
// larger than the largest alive player. These margins keep the size relation
// visually obvious even as players grow mid-flight.
private const val PREY_MAX_FRACTION = 0.85f      // of the smallest alive player's radius
private const val PREDATOR_MIN_FACTOR = 1.15f    // of the largest alive player's radius

private val BgColor = Color(0xFF0D1B2A)
private val SeabedColor = Color(0xFF1A2D40)

// =============================================================================
// Game data
// =============================================================================

// Predator/prey is fixed at spawn (relative to the players alive at that
// moment) and drives both color and collision outcome. Resolving collisions
// by live size comparison instead would let a growing player outrun the
// coloring — a "red" fish that is suddenly edible for the leader only.
private data class FeastEnemy(
    var x: Float,           // dp from left edge
    val y: Float,           // normalized 0..1
    val radius: Float,      // dp
    val speed: Float,       // dp/s
    val isPredator: Boolean,
    var eaten: Boolean = false
)

private data class FeastRock(
    var x: Float,           // dp from left edge
    val height: Float,      // dp
    val width: Float        // dp
)

private class FeastPlayerState(
    val info: MultiplayerPlayerInfo,
    var fishY: Float = 0.5f,
    var radius: Float = PLAYER_START_RADIUS_DP,
    var alive: Boolean = true,
    var score: Int = 0
)

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerFuguFeastScreen(
    players: List<MultiplayerPlayerInfo>,
    onBack: () -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val density = LocalDensity.current

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var enemies by remember { mutableStateOf(listOf<FeastEnemy>()) }
    var rocks by remember { mutableStateOf(listOf<FeastRock>()) }
    var enemySpawnTimer by remember { mutableFloatStateOf(0f) }
    var rockSpawnTimer by remember { mutableFloatStateOf(0f) }
    var playerStates by remember {
        mutableStateOf(players.map { FeastPlayerState(it) })
    }
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }

    val fishMinY = 0.05f
    val fishMaxY = 0.84f  // keep fish above seabed so rocks are obstacles

    val pressures = players.map { p ->
        p.connection.latestPressure.collectAsState().value
    }
    val calibrated = players.map { p ->
        p.connection.isCalibrated.collectAsState().value
    }
    val allCalibrated = calibrated.all { it }
    val allHavePressure = pressures.all { it != null }

    fun resetGame() {
        playerStates = players.map { FeastPlayerState(it) }
        scrollOffset = 0f
        enemies = emptyList()
        rocks = emptyList()
        enemySpawnTimer = 0f
        rockSpawnTimer = 0f
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
                type = org.hubik.openfugu.session.SessionType.MULTIPLAYER_FEAST_GAME,
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

                // Difficulty ramps with the leader among alive players
                val maxAliveScore = alivePlayers.maxOf { it.score }
                val speedMultiplier = 1f + maxAliveScore * SPEED_INCREMENT
                val baseSpeed = BASE_SPEED_DP * speedMultiplier

                // --- Fish positions from pressure ---
                alivePlayers.forEach { ps ->
                    val pressure = ps.info.connection.latestPressure.value
                    val currentPressure = pressure?.relativeHPa ?: 0.0
                    val targetY = calculateTargetY(
                        currentPressure, ps.info.pressureRange,
                        ps.info.negativeRange, ps.info.expertMode
                    )
                    ps.fishY += (targetY - ps.fishY) * SMOOTHING_FACTOR * clampedDt
                    ps.fishY = ps.fishY.coerceIn(fishMinY, fishMaxY)

                    if (ps.info.connection.state.value is DeviceConnectionState.Disconnected) {
                        ps.alive = false
                    }
                }

                // --- Scroll ---
                scrollOffset += baseSpeed * clampedDt

                // --- Update enemies ---
                val updatedEnemies = enemies.toMutableList()
                updatedEnemies.forEach { it.x -= it.speed * speedMultiplier * clampedDt }
                updatedEnemies.removeAll { it.x < -it.radius * 3 || it.eaten }

                // --- Spawn enemies ---
                val (screenWDp, screenHDp) = canvasSizeDp
                enemySpawnTimer += clampedDt
                // More mouths need more fish: spawn faster with more alive players
                val spawnInterval = ENEMY_SPAWN_INTERVAL /
                        (speedMultiplier.coerceAtLeast(0.5f) * (1f + 0.2f * (alivePlayers.size - 1)))
                if (enemySpawnTimer >= spawnInterval) {
                    enemySpawnTimer = 0f

                    val minAliveRadius = alivePlayers.minOf { it.radius }
                    val maxAliveRadius = alivePlayers.maxOf { it.radius }
                    val bigChance = (0.3f + maxAliveScore * 0.005f).coerceAtMost(0.6f)
                    val isPredator = Math.random().toFloat() < bigChance
                    val enemyRadius = if (isPredator) {
                        // Bigger than every alive player. The cap cannot invert
                        // the relation: players cap at 50 dp, so the minimum
                        // predator size (57.5 dp) stays below the 70 dp cap.
                        (maxAliveRadius * (PREDATOR_MIN_FACTOR + Math.random().toFloat() * 0.5f))
                            .coerceAtMost(ENEMY_MAX_RADIUS_DP)
                    } else {
                        // Smaller than every alive player
                        val upper = (minAliveRadius * PREY_MAX_FRACTION)
                            .coerceAtLeast(ENEMY_MIN_RADIUS_DP + 2f)
                        ENEMY_MIN_RADIUS_DP + Math.random().toFloat() * (upper - ENEMY_MIN_RADIUS_DP)
                    }

                    val y = 0.08f + Math.random().toFloat() * 0.76f  // avoid seabed
                    // Speed must be >= scroll speed so fish never go backwards
                    val speed = baseSpeed * (1.0f + Math.random().toFloat() * 0.6f)

                    updatedEnemies.add(
                        FeastEnemy(
                            x = screenWDp + enemyRadius,
                            y = y,
                            radius = enemyRadius,
                            speed = speed,
                            isPredator = isPredator
                        )
                    )
                }
                enemies = updatedEnemies

                // --- Update rocks ---
                val updatedRocks = rocks.toMutableList()
                updatedRocks.forEach { it.x -= baseSpeed * clampedDt }
                updatedRocks.removeAll { it.x < -ROCK_WIDTH_DP * 2 }

                // --- Spawn rocks ---
                rockSpawnTimer += clampedDt
                if (rockSpawnTimer >= ROCK_SPAWN_INTERVAL) {
                    rockSpawnTimer = 0f
                    val rockHeight = ROCK_MIN_HEIGHT_DP + Math.random().toFloat() *
                            (ROCK_MAX_HEIGHT_DP - ROCK_MIN_HEIGHT_DP)
                    val rockWidth = ROCK_WIDTH_DP * (0.6f + Math.random().toFloat() * 0.8f)
                    updatedRocks.add(FeastRock(x = screenWDp + rockWidth, height = rockHeight, width = rockWidth))
                }
                rocks = updatedRocks

                // --- Collisions (all in dp) ---
                val fishXDp = 0.25f * screenWDp
                val seabedYDp = 0.88f * screenHDp

                // Predators and rocks kill first, so a player eliminated this
                // frame cannot also snatch a prey fish
                alivePlayers.forEach { ps ->
                    val fishYDp = ps.fishY * screenHDp
                    updatedEnemies.forEach { enemy ->
                        if (!enemy.isPredator || enemy.eaten) return@forEach
                        val d = hypot(fishXDp - enemy.x, fishYDp - enemy.y * screenHDp)
                        if (d < (ps.radius + enemy.radius) * 0.7f) {
                            ps.alive = false
                        }
                    }
                    rocks.forEach { rock ->
                        if (feastRockHit(fishXDp, fishYDp, ps.radius,
                                rock.x, rock.width, rock.height, seabedYDp)) {
                            ps.alive = false
                        }
                    }
                }

                // Prey are contested: when several players overlap one fish in
                // the same frame, the closest mouth wins
                updatedEnemies.forEach { enemy ->
                    if (enemy.isPredator || enemy.eaten) return@forEach
                    val enemyYDp = enemy.y * screenHDp
                    val eater = alivePlayers
                        .filter { it.alive }
                        .map { ps -> ps to hypot(fishXDp - enemy.x, ps.fishY * screenHDp - enemyYDp) }
                        .filter { (ps, d) -> d < (ps.radius + enemy.radius) * 0.7f }
                        .minByOrNull { it.second }?.first
                    if (eater != null) {
                        enemy.eaten = true
                        eater.score++
                        eater.radius = (eater.radius + GROWTH_PER_EAT).coerceAtMost(MAX_PLAYER_RADIUS_DP)
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
                title = { Text("Multiplayer Fugu Feast") },
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
                val fishX = w * 0.25f
                val seabedY = h * 0.88f

                // Background
                drawRect(BgColor)

                if (gameState is GameState.Playing || gameState is GameState.GameOver) {
                    // --- Draw rocks (before seabed so sand covers their base) ---
                    rocks.forEach { rock ->
                        val rockLeftPx = rock.x * dpToPx
                        val rockWidthPx = rock.width * dpToPx
                        val rockHeightPx = rock.height * dpToPx
                        if (rockLeftPx + rockWidthPx > 0f && rockLeftPx < w) {
                            drawFeastRock(rockLeftPx, rockWidthPx, rockHeightPx, seabedY, h)
                        }
                    }
                }

                // Wavy seabed (covers rock bases)
                val seabedPath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, seabedY)
                    val waveFreq = 0.015f
                    val waveAmp = h * 0.015f
                    var px = 0f
                    while (px <= w) {
                        val waveOffset = sin((px + scrollOffset * dpToPx * 0.5f) * waveFreq) * waveAmp
                        lineTo(px, seabedY + waveOffset)
                        px += 4f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(seabedPath, SeabedColor)

                if (gameState is GameState.Playing || gameState is GameState.GameOver) {
                    // --- Draw enemies ---
                    enemies.forEach { enemy ->
                        if (enemy.eaten) return@forEach
                        val ex = enemy.x * dpToPx
                        val ey = h * enemy.y
                        val er = enemy.radius * dpToPx
                        if (ex + er > 0f && ex - er < w) {
                            drawEnemyFish(ex, ey, er, edible = !enemy.isPredator)
                        }
                    }

                    // --- Draw players: dead first (faded), then alive on top ---
                    playerStates.filter { !it.alive }.forEach { ps ->
                        val radiusPx = ps.radius * dpToPx
                        val fishYPx = (h * ps.fishY).coerceIn(radiusPx * 1.5f, seabedY - radiusPx)
                        drawFugu(fishX, fishYPx, radiusPx, bodyColor = ps.info.color, alpha = 0.3f)
                    }
                    playerStates.filter { it.alive }.forEach { ps ->
                        val radiusPx = ps.radius * dpToPx
                        val fishYPx = (h * ps.fishY).coerceIn(radiusPx * 1.5f, seabedY - radiusPx)
                        drawFugu(fishX, fishYPx, radiusPx, bodyColor = ps.info.color)
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
                        val fishRadiusPx = PLAYER_START_RADIUS_DP * dpToPx
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
                                "Tap to start\nEat green fish, avoid red fish and rocks!"
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
