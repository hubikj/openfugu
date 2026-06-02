package com.efugu.open.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

// =============================================================================
// Shared game state
// =============================================================================

sealed class GameState {
    data object WaitingToStart : GameState()
    data object Playing : GameState()
    data class GameOver(val score: Int) : GameState()
}

// =============================================================================
// Shared colors
// =============================================================================

val GameScoreColor = Color(0xCCFFFFFF)
val GamePressureColor = Color(0x99FFFFFF)
val GameOverlayBg = Color(0x88000000)

private val FishColor = Color(0xFFFFB347)
private val FishEyeWhite = Color(0xFFFFFFFF)
private val FishEyeBlack = Color(0xFF000000)
private val FishTailColor = Color(0xFFFF8C00)

// =============================================================================
// Shared game logic
// =============================================================================

/**
 * Maps current pressure to a normalized Y position (0=top, 1=bottom).
 * In expert mode with negative range, uses asymmetric mapping (0.5=center).
 */
fun calculateTargetY(
    currentPressure: Double,
    pressureRange: Double,
    negativeRange: Double,
    expertMode: Boolean
): Float {
    return if (expertMode && negativeRange > 0.0) {
        if (currentPressure >= 0) {
            0.5f - (currentPressure / (pressureRange * 2)).toFloat().coerceIn(0f, 0.5f)
        } else {
            0.5f + ((-currentPressure) / (negativeRange * 2)).toFloat().coerceIn(0f, 0.5f)
        }
    } else {
        1f - (currentPressure / pressureRange).toFloat().coerceIn(0f, 1f)
    }
}

// =============================================================================
// Shared drawing helpers
// =============================================================================

fun DrawScope.drawScoreText(score: Int, canvasWidth: Float) {
    drawContext.canvas.nativeCanvas.drawText(
        "$score",
        canvasWidth / 2f,
        80f,
        android.graphics.Paint().apply {
            color = GameScoreColor.hashCode()
            textSize = 64f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
    )
}

fun DrawScope.drawPressureText(text: String, canvasHeight: Float, dpToPx: Float) {
    drawContext.canvas.nativeCanvas.drawText(
        text,
        16f * dpToPx,
        canvasHeight - 16f * dpToPx,
        android.graphics.Paint().apply {
            color = GamePressureColor.hashCode()
            textSize = 13f * dpToPx
            isAntiAlias = true
        }
    )
}

fun DrawScope.drawOverlayText(w: Float, h: Float, text: String) {
    val lines = text.split("\n")
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 42f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val lineHeight = 52f
    val startY = h / 2f + lines.size * lineHeight / 2f + 80f
    lines.forEachIndexed { i, line ->
        drawContext.canvas.nativeCanvas.drawText(
            line, w / 2f, startY + i * lineHeight, paint
        )
    }
}

/** Darken a color by multiplying RGB by [factor] (0..1). */
fun darkenColor(color: Color, factor: Float = 0.7f): Color =
    Color(
        red = color.red * factor,
        green = color.green * factor,
        blue = color.blue * factor,
        alpha = color.alpha
    )

fun DrawScope.drawFugu(
    cx: Float,
    cy: Float,
    radius: Float,
    bodyColor: Color = FishColor,
    finColor: Color = darkenColor(bodyColor, 0.7f),
    alpha: Float = 1f
) {
    val tailPath = Path().apply {
        moveTo(cx - radius * 0.8f, cy)
        lineTo(cx - radius * 1.8f, cy - radius * 0.6f)
        lineTo(cx - radius * 1.8f, cy + radius * 0.6f)
        close()
    }
    drawPath(tailPath, finColor.copy(alpha = alpha))

    val dorsalPath = Path().apply {
        moveTo(cx - radius * 0.2f, cy - radius * 0.85f)
        lineTo(cx + radius * 0.1f, cy - radius * 1.4f)
        lineTo(cx + radius * 0.4f, cy - radius * 0.85f)
        close()
    }
    drawPath(dorsalPath, finColor.copy(alpha = alpha))

    drawCircle(bodyColor.copy(alpha = alpha), radius, Offset(cx, cy))

    drawCircle(FishEyeWhite.copy(alpha = alpha), radius * 0.3f, Offset(cx + radius * 0.35f, cy - radius * 0.2f))
    drawCircle(FishEyeBlack.copy(alpha = alpha), radius * 0.15f, Offset(cx + radius * 0.4f, cy - radius * 0.2f))

    drawLine(
        FishEyeBlack.copy(alpha = alpha),
        Offset(cx + radius * 0.8f, cy + radius * 0.1f),
        Offset(cx + radius * 0.6f, cy + radius * 0.15f),
        strokeWidth = 2f
    )
}
