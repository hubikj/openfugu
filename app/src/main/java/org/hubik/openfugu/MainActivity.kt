package org.hubik.openfugu

import android.Manifest
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
                onSaveLogs = ::saveLogsToFile,
                onShareSession = ::shareSessionFile
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

    private fun saveLogsToFile(messages: List<String>): String = try {
        val file = File(getExternalFilesDir(null), "openfugu_log.txt")
        file.writeText(messages.joinToString("\n"))
        "Saved: ${file.absolutePath}"
    } catch (e: Exception) {
        "Could not save logs"
    }

    /** Write the session to a cache file and hand it to the system share sheet. */
    private fun shareSessionFile(fileName: String, text: String) {
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
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share session"))
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
            efuguViewModel.store.startScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
