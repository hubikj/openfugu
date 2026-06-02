package com.efugu.open

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.efugu.open.ble.PressureReading
import com.efugu.open.exercise.PeakMarker
import com.efugu.open.ui.AppColors

/**
 * Unified pressure chart used across Live tab, calibration wizard, and exercises.
 *
 * Features: Card frame, Y/X gridlines, pause/scroll/zoom, optional overlays:
 * - [peakMarkers]: diamond markers at detected peaks (green/red)
 * - [targetRange]: horizontal range overlay (lower..upper hPa)
 * - [activationThreshold]: dashed horizontal line (shown when [targetRange] not yet active)
 * - [scoringStartMs]: timestamp after which line is colored green/red based on target range
 */
/** A single line to draw on the chart. */
data class ChartLine(
    val data: List<PressureReading>,
    val color: Color? = null // null = use theme default
)

@Composable
fun PressureChart(
    lines: List<ChartLine>,
    modifier: Modifier = Modifier,
    onVisibleRangeChanged: ((min: Double, max: Double) -> Unit)? = null,
    peakMarkers: List<PeakMarker>? = null,
    targetRange: Pair<Double, Double>? = null,
    activationThreshold: Double? = null,
    showTargetRange: Boolean = true,
    scoringStartMs: Long = 0L,
    staticFullRange: Boolean = false
) {
    // Primary data = first line (used for scroll/zoom, time axis, overlays)
    val data = lines.firstOrNull()?.data ?: emptyList()
    val themeDefault = MaterialTheme.colorScheme.primary
    val defaultLineColor = lines.firstOrNull()?.color ?: themeDefault
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val pausedColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    val density = LocalDensity.current

    // Pause/scroll/zoom state
    val staticWindowSec = if (staticFullRange && data.size >= 2) {
        ((data.last().timestamp - data.first().timestamp) / 1000f).coerceAtLeast(5f)
    } else 10f
    var isPaused by remember { mutableStateOf(staticFullRange) }
    var pausedTs by remember { mutableLongStateOf(if (staticFullRange && data.isNotEmpty()) data.last().timestamp else 0L) }
    var scrollOffsetSec by remember { mutableFloatStateOf(0f) }
    var windowSec by remember { mutableFloatStateOf(staticWindowSec) }

    val rightEdgeTs = if (isPaused) pausedTs else data.lastOrNull()?.timestamp ?: 0L

    Card(modifier = modifier) {
        if (data.size < 2) return@Card

        val viewRightMs = rightEdgeTs - (scrollOffsetSec * 1000f).toLong()
        val viewLeftMs = viewRightMs - (windowSec * 1000f).toLong()

        val marginMs = (windowSec * 100f).toLong()
        val visibleData = data.filter { it.timestamp in (viewLeftMs - marginMs)..viewRightMs }

        val values = visibleData.map { it.relativeHPa.toFloat() }
        val rawMin = if (values.isNotEmpty()) values.min() else -1f
        val rawMax = if (values.isNotEmpty()) values.max() else 1f

        // Extend Y range to include target range and threshold if present
        var minVal = (rawMin - 1f).coerceAtMost(-1f)
        var maxVal = (rawMax + 1f).coerceAtLeast(1f)
        targetRange?.let { (lo, hi) ->
            minVal = minVal.coerceAtMost(lo.toFloat() - 1f)
            maxVal = maxVal.coerceAtLeast(hi.toFloat() + 1f)
        }
        activationThreshold?.let {
            maxVal = maxVal.coerceAtLeast(it.toFloat() + 1f)
        }
        val range = maxVal - minVal

        onVisibleRangeChanged?.invoke(rawMin.toDouble(), rawMax.toDouble())

        val earliestTs = data.first().timestamp
        val maxScrollSec = ((rightEdgeTs - earliestTs) / 1000f - windowSec).coerceAtLeast(0f)

        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 40.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)
                    .pointerInput(isPaused) {
                        if (!isPaused) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newWindowSec = (windowSec / zoom).coerceIn(2f, 120f)
                            windowSec = newWindowSec
                            val currentMaxScroll = ((rightEdgeTs - earliestTs) / 1000f - newWindowSec).coerceAtLeast(0f)
                            val secPerPx = newWindowSec / size.width.toFloat()
                            scrollOffsetSec = (scrollOffsetSec + pan.x * secPerPx)
                                .coerceIn(0f, currentMaxScroll)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val labelTextSize = with(density) { 10.sp.toPx() }

                val textPaint = android.graphics.Paint().apply {
                    color = labelColor.hashCode()
                    textSize = labelTextSize
                    isAntiAlias = true
                }

                fun hPaToY(hPa: Float): Float = h * (1f - (hPa - minVal) / range)
                fun tsToX(ts: Long): Float {
                    val secFromRight = (viewRightMs - ts) / 1000f
                    return w * (1f - secFromRight / windowSec)
                }

                // Y-axis grid + labels
                val gridStep = when {
                    range > 80f -> 20f
                    range > 40f -> 10f
                    range > 15f -> 5f
                    range > 6f -> 2f
                    else -> 1f
                }
                var gridVal = (kotlin.math.ceil(minVal / gridStep) * gridStep).toFloat()
                while (gridVal <= maxVal) {
                    val y = hPaToY(gridVal)
                    val lineAlpha = if (gridVal == 0f) zeroLineColor else gridColor
                    val lineWidth = if (gridVal == 0f) 1.5f else 0.5f
                    drawLine(lineAlpha, Offset(0f, y), Offset(w, y), strokeWidth = lineWidth)

                    drawContext.canvas.nativeCanvas.drawText(
                        "${gridVal.toInt()}",
                        -with(density) { 36.dp.toPx() },
                        y + labelTextSize / 3f,
                        textPaint
                    )
                    gridVal += gridStep
                }

                // X-axis labels — session time (M:SS)
                val xLabelY = h + with(density) { 16.dp.toPx() }
                val sessionStartMs = data.first().timestamp
                val viewLeftSec = (viewLeftMs - sessionStartMs) / 1000f
                val viewRightSec = (viewRightMs - sessionStartMs) / 1000f

                val labelStep = when {
                    windowSec > 60f -> 20f
                    windowSec > 30f -> 10f
                    windowSec > 15f -> 5f
                    windowSec > 5f -> 2f
                    else -> 1f
                }
                var tickSec = (kotlin.math.ceil(viewLeftSec.coerceAtLeast(0f) / labelStep) * labelStep).toFloat()
                while (tickSec <= viewRightSec) {
                    val x = w * ((tickSec - viewLeftSec) / windowSec)
                    val mins = (tickSec / 60f).toInt()
                    val secs = (tickSec % 60f).toInt()
                    val label = "%d:%02d".format(mins, secs)
                    drawContext.canvas.nativeCanvas.drawText(label, x, xLabelY, textPaint)
                    tickSec += labelStep
                }

                // --- Overlays ---

                // Activation threshold line (dashed, shown when target range not yet visible)
                if (activationThreshold != null && !showTargetRange) {
                    val thresholdY = hPaToY(activationThreshold.toFloat())
                    drawLine(
                        AppColors.warning,
                        Offset(0f, thresholdY),
                        Offset(w, thresholdY),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                    )
                }

                // Target range overlay
                if (targetRange != null && showTargetRange) {
                    val rangeTop = hPaToY(targetRange.second.toFloat())
                    val rangeBottom = hPaToY(targetRange.first.toFloat())
                    drawRect(
                        color = AppColors.inRangeFill,
                        topLeft = Offset(0f, rangeTop),
                        size = Size(w, rangeBottom - rangeTop)
                    )
                    drawLine(AppColors.inRangeBorder, Offset(0f, rangeTop), Offset(w, rangeTop), strokeWidth = 1f)
                    drawLine(AppColors.inRangeBorder, Offset(0f, rangeBottom), Offset(w, rangeBottom), strokeWidth = 1f)
                }

                // Pressure line — colored segments if scoring, single color otherwise
                if (scoringStartMs > 0 && targetRange != null) {
                    // Draw segment-by-segment with color based on in-range
                    var prevX = Float.NaN
                    var prevY = Float.NaN
                    data.forEach { reading ->
                        val x = tsToX(reading.timestamp)
                        if (x >= -10f && x <= w + 10f) {
                            val y = hPaToY(reading.relativeHPa.toFloat())
                            if (!prevX.isNaN()) {
                                val color = if (reading.timestamp >= scoringStartMs) {
                                    val inRange = reading.relativeHPa in targetRange.first..targetRange.second
                                    if (inRange) AppColors.inRange else AppColors.outOfRange
                                } else {
                                    defaultLineColor
                                }
                                drawLine(color, Offset(prevX, prevY), Offset(x, y), strokeWidth = 2.5f)
                            }
                            prevX = x
                            prevY = y
                        }
                    }
                    // Current position dot — always device color for multiplayer identity
                    if (!prevX.isNaN()) {
                        drawCircle(defaultLineColor, radius = 5f, center = Offset(prevX, prevY))
                    }
                } else {
                    // Single-color line
                    val path = Path()
                    var started = false
                    data.forEach { reading ->
                        val x = tsToX(reading.timestamp)
                        if (x >= -10f && x <= w + 10f) {
                            val y = hPaToY(reading.relativeHPa.toFloat())
                            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                        }
                    }
                    if (started) drawPath(path, defaultLineColor, style = Stroke(width = 2.5f))
                }

                // Additional lines (for multiplayer overlays etc.)
                lines.drop(1).forEach { line ->
                    val lineColor = line.color ?: themeDefault
                    val linePath = Path()
                    var lineStarted = false
                    line.data.forEach { reading ->
                        val x = tsToX(reading.timestamp)
                        if (x >= -10f && x <= w + 10f) {
                            val y = hPaToY(reading.relativeHPa.toFloat())
                            if (!lineStarted) { linePath.moveTo(x, y); lineStarted = true }
                            else linePath.lineTo(x, y)
                        }
                    }
                    if (lineStarted) drawPath(linePath, lineColor, style = Stroke(width = 2.5f))
                }

                // Peak markers
                peakMarkers?.forEach { marker ->
                    val x = tsToX(marker.timestamp)
                    if (x >= -10f && x <= w + 10f) {
                        val y = hPaToY(marker.valueHPa.toFloat())
                        val color = if (marker.successful) AppColors.inRange else AppColors.outOfRange
                        val markerSize = 8f
                        val diamondPath = Path().apply {
                            moveTo(x, y - markerSize)
                            lineTo(x + markerSize, y)
                            lineTo(x, y + markerSize)
                            lineTo(x - markerSize, y)
                            close()
                        }
                        drawPath(diamondPath, color)
                    }
                }
            }

            // Pause/play button (hidden in static replay mode)
            if (!staticFullRange) IconButton(
                onClick = {
                    if (!isPaused) {
                        isPaused = true
                        pausedTs = data.lastOrNull()?.timestamp ?: 0L
                        scrollOffsetSec = 0f
                    } else {
                        isPaused = false
                        scrollOffsetSec = 0f
                        windowSec = 10f
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "Resume" else "Pause",
                    tint = if (isPaused) pausedColor else labelColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (isPaused && !staticFullRange) {
                Text(
                    "PAUSED — drag to scroll, pinch to zoom",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    fontSize = 10.sp,
                    color = pausedColor
                )
            }
        }
    }
}
