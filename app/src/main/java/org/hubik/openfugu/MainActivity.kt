package org.hubik.openfugu

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.session.Session
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var efuguViewModel: EFuguViewModel

    // Session file handed to us via ACTION_VIEW/ACTION_SEND. Compose state so
    // EFuguApp picks it up whether it arrives in onCreate or onNewIntent.
    private var importIntent by mutableStateOf<Intent?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            ensureBluetoothOnAndScan()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            efuguViewModel.store.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        importIntent = intent
        setContent {
            efuguViewModel = viewModel()
            // Resolve the Android intent to a plain suspend loader here so
            // the shared UI stays free of Intent/Uri types.
            val importLoader: (suspend () -> Session?)? = remember(importIntent) {
                importIntent?.let(::sessionUriOf)?.let { uri ->
                    suspend { efuguViewModel.importSession(uri) }
                }
            }
            OpenFuguRoot(
                store = efuguViewModel.store,
                onRequestPermissionsAndScan = { requestPermissionsAndScan() },
                importSession = importLoader,
                onImportSessionHandled = { importIntent = null },
                onShareLogs = { messages ->
                    // text/plain rather than the session MIME type: log shares
                    // should offer text targets, not reimport into OpenFugu.
                    shareTextFile("openfugu_log.txt", messages.joinToString("\n"), "text/plain")
                },
                onShareSession = { fileName, text ->
                    shareTextFile(fileName, text, "application/octet-stream")
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importIntent = intent
    }

    private fun sessionUriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    /** Write [text] to a cache file and hand it to the system share sheet. */
    private fun shareTextFile(fileName: String, text: String, mimeType: String) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "shared_sessions")
                    dir.mkdirs()
                    // Old share copies accumulate otherwise; ones from previous
                    // shares are no longer referenced by any in-flight intent
                    // we control.
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, fileName).apply { writeText(text) }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                efuguViewModel.store.postUserMessage("Failed to share: ${e.message}")
            }
        }
    }

    private fun requestPermissionsAndScan() {
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            ensureBluetoothOnAndScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    /**
     * Offer the system enable-Bluetooth dialog when the radio is off instead
     * of failing straight into the "Bluetooth is disabled" state. Requires
     * BLUETOOTH_CONNECT, so this only runs after permissions are granted.
     */
    private fun ensureBluetoothOnAndScan() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            efuguViewModel.store.startScan()
        }
    }
}
