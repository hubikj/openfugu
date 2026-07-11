package org.hubik.openfugu.session

import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.exercise.PeakMarker
import java.util.UUID

enum class SessionType {
    MIN_EQ, CONSTANT_EQ,
    REEF_GAME, FEAST_GAME, CAVE_GAME, FLOW_GAME,
    MULTIPLAYER_REEF_GAME, MULTIPLAYER_FEAST_GAME
}

sealed class Session {
    abstract val id: String
    abstract val type: SessionType
    abstract val timestamp: Long
    abstract val durationMs: Long
    abstract val deviceName: String
    abstract val userName: String?
    abstract val pressureTrace: List<PressureReading>

    data class MinEqSession(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val durationMs: Long,
        override val deviceName: String,
        override val userName: String?,
        override val pressureTrace: List<PressureReading>,
        val peakMarkers: List<PeakMarker>,
        val mean: Double,
        val stddev: Double?,
        val successCount: Int,
        val failCount: Int
    ) : Session() {
        override val type = SessionType.MIN_EQ
    }

    data class ConstantEqSession(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val durationMs: Long,
        override val deviceName: String,
        override val userName: String?,
        override val pressureTrace: List<PressureReading>,
        val lowerBound: Double,
        val upperBound: Double,
        val activationThreshold: Double,
        val scoringStartMs: Long,
        val percentInRange: Float,
        val bestStreakMs: Long,
        val difficultyLabel: String,
        val durationSetting: String
    ) : Session() {
        override val type = SessionType.CONSTANT_EQ
    }

    data class GameSession(
        override val id: String = UUID.randomUUID().toString(),
        override val type: SessionType,
        override val timestamp: Long = System.currentTimeMillis(),
        override val durationMs: Long,
        override val deviceName: String,
        override val userName: String?,
        override val pressureTrace: List<PressureReading>,
        val score: Int,
        val pressureRange: Double = 40.0,
        val negativeRange: Double = 0.0,
        val expertMode: Boolean = false
    ) : Session() {
    }

    data class PlayerResult(
        val deviceName: String,
        val userName: String?,
        val colorArgb: Long?,
        val score: Int,
        val rank: Int,
        val pressureTrace: List<PressureReading>,
        val pressureRange: Double,
        val negativeRange: Double,
        val expertMode: Boolean
    )

    data class MultiplayerGameSession(
        override val id: String = UUID.randomUUID().toString(),
        override val type: SessionType,
        override val timestamp: Long = System.currentTimeMillis(),
        override val durationMs: Long,
        override val pressureTrace: List<PressureReading>, // winner's trace
        val players: List<PlayerResult>
    ) : Session() {
        override val deviceName: String get() = players.firstOrNull { it.rank == 1 }?.deviceName ?: ""
        override val userName: String? get() = players.firstOrNull { it.rank == 1 }?.userName
    }
}

/** Lightweight index entry for listing sessions without loading full pressure traces. */
data class SessionIndexEntry(
    val id: String,
    val type: SessionType,
    val timestamp: Long,
    val durationMs: Long,
    val deviceName: String,
    val userName: String?,
    val summaryText: String
)
