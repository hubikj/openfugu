package com.efugu.open

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.efugu.open.ble.DeviceConnection
import com.efugu.open.ble.DeviceConnectionState
import com.efugu.open.ble.EFuguViewModel
import com.efugu.open.game.FuguReefScreen
import com.efugu.open.game.drawFugu
import com.efugu.open.ble.PressureReading
import com.efugu.open.ble.SavedDevice
import com.efugu.open.ble.ScanState
import com.efugu.open.ble.ScannedDevice
import com.efugu.open.ble.formatHPa
import com.efugu.open.ui.theme.OpenFuguTheme

class MainActivity : ComponentActivity() {
    private lateinit var efuguViewModel: EFuguViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            efuguViewModel.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenFuguTheme {
                efuguViewModel = viewModel()
                EFuguApp(
                    viewModel = efuguViewModel,
                    onRequestPermissionsAndScan = { requestPermissionsAndScan() }
                )
            }
        }
    }

    private fun requestPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            efuguViewModel.startScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}

// =============================================================================
// App root — always shows bottom navigation
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EFuguApp(viewModel: EFuguViewModel, onRequestPermissionsAndScan: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var isGameActive by remember { mutableStateOf(false) }

    val connections by viewModel.connections.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val logMessages by viewModel.logMessages.collectAsState()

    // Auto-start scan on first composition
    LaunchedEffect(Unit) {
        onRequestPermissionsAndScan()
    }

    // Switch to Live tab when first device connects
    LaunchedEffect(connections.size) {
        if (connections.isNotEmpty() && selectedTab == 3) {
            selectedTab = 0
        }
    }

    // When a game is active, render it full-screen (no top/bottom bars)
    if (isGameActive && selectedTab == 1) {
        val connection = connections.values.firstOrNull()
        if (connection != null) {
            BackHandler { isGameActive = false }
            FuguReefScreen(
                connection = connection,
                onBack = { isGameActive = false }
            )
            return
        } else {
            isGameActive = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("OpenFugu") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = "Live") },
                    label = { Text("Live") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.FitnessCenter, contentDescription = "Exercises") },
                    label = { Text("Exercises") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (connections.isNotEmpty()) {
                                    Badge { Text("${connections.size}") }
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Sensors, contentDescription = "Devices")
                        }
                    },
                    label = { Text("Devices") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> LiveTab(
                connections = connections,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            1 -> ExercisesTab(
                connection = connections.values.firstOrNull(),
                onGameStart = { isGameActive = true },
                modifier = Modifier.padding(padding)
            )
            2 -> DevicesTab(
                scanState = scanState,
                savedDevices = savedDevices,
                scannedDevices = scannedDevices,
                connections = connections,
                onScan = onRequestPermissionsAndScan,
                onStopScan = { viewModel.stopScan() },
                onConnect = { viewModel.connectToDevice(it) },
                onDisconnect = { viewModel.disconnectDevice(it) },
                onForget = { viewModel.forgetDevice(it) },
                onNicknameSet = { addr, name -> viewModel.setNickname(addr, name) },
                modifier = Modifier.padding(padding)
            )
            3 -> LogsTab(
                logMessages = logMessages,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

// =============================================================================
// Live tab — shows all connected devices
// =============================================================================

@Composable
fun LiveTab(
    connections: Map<String, DeviceConnection>,
    viewModel: EFuguViewModel,
    modifier: Modifier = Modifier
) {
    if (connections.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No devices connected.\nGo to the Devices tab to connect.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        return
    }

    val deviceList = connections.values.toList()

    if (deviceList.size == 1) {
        // Single device: full-screen layout
        SingleDevicePanel(
            connection = deviceList.first(),
            viewModel = viewModel,
            modifier = modifier
        )
    } else {
        // Multiple devices: scrollable list of compact panels
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
        ) {
            items(deviceList, key = { it.address }) { connection ->
                CompactDevicePanel(
                    connection = connection,
                    viewModel = viewModel
                )
            }
        }
    }
}

/** Full-screen panel for single device (same layout as before) */
@Composable
fun SingleDevicePanel(
    connection: DeviceConnection,
    viewModel: EFuguViewModel,
    modifier: Modifier = Modifier
) {
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()
    val chartMin by connection.chartMin.collectAsState()
    val chartMax by connection.chartMax.collectAsState()
    val batteryLevel by connection.batteryLevel.collectAsState()
    val deviceInfo by connection.deviceInfo.collectAsState()
    val isCalibrated by connection.isCalibrated.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val currentSaved = savedDevices.find { it.address == connection.address } ?: connection.savedDevice

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        DeviceCard(
            savedDevice = currentSaved,
            batteryLevel = batteryLevel,
            deviceInfo = deviceInfo,
            onDisconnect = { viewModel.disconnectDevice(connection.address) },
            onNicknameSet = { viewModel.setNickname(connection.address, it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PressureDisplay(
            reading = latestPressure,
            chartMin = chartMin,
            chartMax = chartMax,
            isCalibrated = isCalibrated,
            onRecalibrate = { viewModel.resetCalibration(connection.address) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (chartData.size >= 2) {
            PressureChart(
                data = chartData,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else if (isCalibrated) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Waiting for chart data...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Calibrating...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Compact panel for multi-device view */
@Composable
fun CompactDevicePanel(
    connection: DeviceConnection,
    viewModel: EFuguViewModel
) {
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()
    val chartMin by connection.chartMin.collectAsState()
    val chartMax by connection.chartMax.collectAsState()
    val batteryLevel by connection.batteryLevel.collectAsState()
    val isCalibrated by connection.isCalibrated.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val currentSaved = savedDevices.find { it.address == connection.address } ?: connection.savedDevice

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: name + battery + disconnect
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    currentSaved.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                batteryLevel?.let {
                    Text("$it%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(
                    onClick = { viewModel.disconnectDevice(connection.address) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Disconnect", fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pressure value + min/max
            if (latestPressure != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        "${formatHPa(latestPressure!!.relativeHPa)} hPa",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (chartMin != null && chartMax != null) {
                        Text(
                            "min ${formatHPa(chartMin!!)}  max ${formatHPa(chartMax!!)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            } else if (!isCalibrated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Calibrating...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Mini chart
            if (chartData.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                PressureChart(
                    data = chartData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            }
        }
    }
}

// =============================================================================
// Device card (single-device view)
// =============================================================================

@Composable
fun DeviceCard(
    savedDevice: SavedDevice,
    batteryLevel: Int?,
    deviceInfo: Map<String, String>,
    onDisconnect: () -> Unit,
    onNicknameSet: (String?) -> Unit
) {
    var showNicknameDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        savedDevice.displayName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(
                        onClick = { showNicknameDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Rename",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                val infoItems = buildList {
                    batteryLevel?.let { add("Battery: $it%") }
                    deviceInfo["Firmware"]?.let { add("FW $it") }
                    deviceInfo["Hardware"]?.let { add("HW $it") }
                }
                if (infoItems.isNotEmpty()) {
                    Text(
                        infoItems.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect", fontSize = 12.sp)
            }
        }
    }

    if (showNicknameDialog) {
        NicknameDialog(
            currentNickname = savedDevice.nickname,
            deviceName = savedDevice.name,
            onDismiss = { showNicknameDialog = false },
            onConfirm = { nickname ->
                onNicknameSet(nickname)
                showNicknameDialog = false
            }
        )
    }
}

// =============================================================================
// Pressure display
// =============================================================================

@Composable
fun PressureDisplay(
    reading: PressureReading?,
    chartMin: Double?,
    chartMax: Double?,
    isCalibrated: Boolean,
    onRecalibrate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (reading != null) {
                Text(
                    "${formatHPa(reading.relativeHPa)} hPa",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        if (chartMin != null && chartMax != null) {
                            Text(
                                "min ${formatHPa(chartMin)}  max ${formatHPa(chartMax)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            "abs ${formatHPa(reading.pressureHPa)} hPa",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                        )
                    }
                    OutlinedButton(
                        onClick = onRecalibrate,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Recalibrate", fontSize = 12.sp)
                    }
                }
            } else if (!isCalibrated) {
                Text("Calibrating...", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            } else {
                Text("Waiting for data...", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            }
        }
    }
}

// =============================================================================
// Pressure chart
// =============================================================================

@Composable
fun PressureChart(data: List<PressureReading>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val density = LocalDensity.current

    Card(modifier = modifier) {
        if (data.size < 2) return@Card

        val values = data.map { it.relativeHPa.toFloat() }
        val rawMin = values.min()
        val rawMax = values.max()
        val minVal = (rawMin - 1f).coerceAtMost(-1f)
        val maxVal = (rawMax + 1f).coerceAtLeast(1f)
        val range = maxVal - minVal

        // Fixed 10-second window
        val windowSec = 10f
        val nowTs = data.last().timestamp

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 40.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)
        ) {
            val w = size.width
            val h = size.height
            val labelTextSize = with(density) { 10.sp.toPx() }

            val textPaint = android.graphics.Paint().apply {
                color = labelColor.hashCode()
                textSize = labelTextSize
                isAntiAlias = true
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
                val y = h * (1f - (gridVal - minVal) / range)
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

            // X-axis: fixed labels for the 10-second window
            val xLabelY = h + with(density) { 16.dp.toPx() }
            for (sec in listOf(2, 4, 6, 8, 10)) {
                val x = w * (1f - sec.toFloat() / windowSec)
                drawContext.canvas.nativeCanvas.drawText(
                    "-${sec}s", x, xLabelY, textPaint
                )
            }
            drawContext.canvas.nativeCanvas.drawText(
                "now", w - with(density) { 20.dp.toPx() }, xLabelY, textPaint
            )

            // Pressure line — position each point by timestamp within fixed window
            val path = Path()
            var started = false
            data.forEach { reading ->
                val secAgo = (nowTs - reading.timestamp) / 1000f
                val x = w * (1f - secAgo / windowSec)
                if (x >= 0f) {
                    val y = h * (1f - (reading.relativeHPa.toFloat() - minVal) / range)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
            }
            if (started) drawPath(path, lineColor, style = Stroke(width = 2.5f))
        }
    }
}

// =============================================================================
// Devices tab
// =============================================================================

@Composable
fun DevicesTab(
    scanState: ScanState,
    savedDevices: List<SavedDevice>,
    scannedDevices: List<ScannedDevice>,
    connections: Map<String, DeviceConnection>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onForget: (String) -> Unit,
    onNicknameSet: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Scan controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (scanState) {
                is ScanState.Idle -> {
                    Button(onClick = onScan) { Text("Scan") }
                }
                is ScanState.Scanning -> {
                    Button(onClick = onStopScan) { Text("Stop") }
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Scanning...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ScanState.Error -> {
                    Button(onClick = onScan) { Text("Scan") }
                    Text(scanState.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }

        // Saved devices
        if (savedDevices.isNotEmpty()) {
            Text(
                "Saved devices",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            savedDevices.forEach { device ->
                val isConnected = connections.containsKey(device.address)
                SavedDeviceRow(
                    device = device,
                    isConnected = isConnected,
                    onConnect = { onConnect(device.address) },
                    onDisconnect = { onDisconnect(device.address) },
                    onForget = { onForget(device.address) },
                    onNicknameSet = { onNicknameSet(device.address, it) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Scanned devices (new ones not in saved list)
        if (scanState is ScanState.Scanning && scannedDevices.isNotEmpty()) {
            val savedAddresses = savedDevices.map { it.address }.toSet()
            val newDevices = scannedDevices.filter { it.address !in savedAddresses }
            if (newDevices.isNotEmpty()) {
                Text(
                    "Nearby devices",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                newDevices.forEach { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onConnect(device.address) }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(device.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Hint when empty
        if (savedDevices.isEmpty() && scanState is ScanState.Idle) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "Tap Scan to find your eFugu device",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun SavedDeviceRow(
    device: SavedDevice,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onNicknameSet: (String?) -> Unit
) {
    var showNicknameDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (!isConnected) Modifier.clickable { onConnect() } else Modifier),
        colors = if (isConnected) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (isConnected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Connected",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    if (device.nickname != null) "${device.name} — ${device.address}"
                    else device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Disconnect", fontSize = 11.sp)
                }
            } else {
                IconButton(onClick = { showNicknameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onForget) {
                    Icon(Icons.Filled.Close, contentDescription = "Forget", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showNicknameDialog) {
        NicknameDialog(
            currentNickname = device.nickname,
            deviceName = device.name,
            onDismiss = { showNicknameDialog = false },
            onConfirm = { nickname ->
                onNicknameSet(nickname)
                showNicknameDialog = false
            }
        )
    }
}

@Composable
fun NicknameDialog(
    currentNickname: String?,
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var text by remember { mutableStateOf(currentNickname ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column {
                Text("Device: $deviceName", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Nickname") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.takeIf { it.isNotBlank() }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// =============================================================================
// Exercises tab (placeholder)
// =============================================================================

@Composable
fun ExercisesTab(
    connection: DeviceConnection?,
    onGameStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        if (connection == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Connect a device to play",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            Text("Games", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGameStart() }
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
                            "Navigate your fugu through the reef using equalization pressure",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// Logs tab
// =============================================================================

@Composable
fun LogsTab(logMessages: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        LogHeader(logMessages)
        Spacer(modifier = Modifier.height(4.dp))
        LogDisplay(messages = logMessages, modifier = Modifier.weight(1f))
    }
}

@Composable
fun LogHeader(messages: List<String>) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Log:", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenFugu Logs", messages.joinToString("\n")))
                Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
            }) {
                Text("Copy", fontSize = 12.sp)
            }
            TextButton(onClick = {
                val dir = context.getExternalFilesDir(null)
                val file = File(dir, "openfugu_log.txt")
                file.writeText(messages.joinToString("\n"))
                Toast.makeText(context, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }) {
                Text("Save", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun LogDisplay(messages: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(8.dp)
        ) {
            items(messages) { msg ->
                Text(
                    msg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
