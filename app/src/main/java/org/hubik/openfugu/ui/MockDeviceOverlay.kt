package org.hubik.openfugu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.ble.MockDeviceConnection
import org.hubik.openfugu.ble.formatHPa

/**
 * Floating controls for simulated devices, drawn over every screen while any
 * mock is connected: one vertical pressure slider per simulated device
 * (drag or tap to set pressure, double-tap to zero), a wave toggle per device
 * for hands-free sine patterns, and a shared auto-zero toggle that releases
 * the pressure when the finger lifts. Collapsible to a slim handle at the
 * right edge so it never has to block a game.
 */
@Composable
fun MockDeviceOverlay(viewModel: EFuguViewModel) {
    val connections by viewModel.connections.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val mocks = connections.values.filterIsInstance<MockDeviceConnection>()
        .sortedBy { it.address }
    if (mocks.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    var autoZero by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Collapse handle
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                modifier = Modifier.clickable { expanded = !expanded }
            ) {
                Icon(
                    if (expanded) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                    contentDescription = if (expanded) "Hide simulated device controls"
                        else "Show simulated device controls",
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 2.dp).size(20.dp)
                )
            }
            if (expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            mocks.forEach { mock ->
                                key(mock.address) {
                                    val color = savedDevices
                                        .find { it.address == mock.address }?.colorArgb
                                        ?.let { Color(it.toInt()) }
                                        ?: MaterialTheme.colorScheme.tertiary
                                    MockPressureSlider(mock = mock, color = color, autoZero = autoZero)
                                }
                            }
                        }
                        FilterChip(
                            selected = autoZero,
                            onClick = { autoZero = !autoZero },
                            label = { Text("Auto zero", fontSize = 10.sp) },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One vertical pressure fader: zero at the center, positive pressure up,
 * negative down, spanning ±[MockDeviceConnection.CONTROL_RANGE_HPA].
 */
@Composable
private fun MockPressureSlider(
    mock: MockDeviceConnection,
    color: Color,
    autoZero: Boolean
) {
    val control by mock.controlHPa.collectAsState()
    val pattern by mock.pattern.collectAsState()
    val range = MockDeviceConnection.CONTROL_RANGE_HPA
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Text(
                " " + mock.address.removePrefix(MockDeviceConnection.ADDRESS_PREFIX),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatHPa(control),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(44.dp)
        )
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(220.dp)
                .pointerInput(autoZero) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            mock.controlHPa.value =
                                sliderValueForY(change.position.y, size.height, range)
                        },
                        onDragEnd = { if (autoZero) mock.controlHPa.value = 0.0 },
                        onDragCancel = { if (autoZero) mock.controlHPa.value = 0.0 }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { mock.controlHPa.value = 0.0 },
                        onTap = { offset ->
                            mock.controlHPa.value =
                                sliderValueForY(offset.y, size.height, range)
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackWidth = 8.dp.toPx()
                val trackLeft = (size.width - trackWidth) / 2f
                val centerY = size.height / 2f
                val thumbY = (centerY - (control / range) * centerY).toFloat()

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(trackLeft, 0f),
                    size = androidx.compose.ui.geometry.Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2f)
                )
                // Zero marker
                drawLine(
                    color = trackColor,
                    start = Offset(trackLeft - 6.dp.toPx(), centerY),
                    end = Offset(trackLeft + trackWidth + 6.dp.toPx(), centerY),
                    strokeWidth = 1.dp.toPx()
                )
                // Fill from zero to the current value
                drawRoundRect(
                    color = color.copy(alpha = 0.7f),
                    topLeft = Offset(trackLeft, minOf(centerY, thumbY)),
                    size = androidx.compose.ui.geometry.Size(
                        trackWidth,
                        kotlin.math.abs(centerY - thumbY)
                    ),
                    cornerRadius = CornerRadius(trackWidth / 2f)
                )
                drawCircle(color = color, radius = 10.dp.toPx(), center = Offset(size.width / 2f, thumbY))
            }
        }
        FilterChip(
            selected = pattern == MockDeviceConnection.Pattern.SineWave,
            onClick = {
                mock.pattern.value =
                    if (pattern == MockDeviceConnection.Pattern.SineWave)
                        MockDeviceConnection.Pattern.Manual
                    else MockDeviceConnection.Pattern.SineWave
            },
            label = { Text("Wave", fontSize = 10.sp) },
            modifier = Modifier.height(26.dp)
        )
    }
}

/** Map a touch Y inside the track to a pressure value: center = 0, top = +range, bottom = -range. */
private fun sliderValueForY(y: Float, heightPx: Int, range: Double): Double {
    val centerY = heightPx / 2.0
    return ((centerY - y) / centerY * range).coerceIn(-range, range)
}
