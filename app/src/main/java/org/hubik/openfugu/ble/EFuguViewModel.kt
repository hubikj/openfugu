package org.hubik.openfugu.ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hubik.openfugu.BuildConfig
import org.hubik.openfugu.EFuguStore
import org.hubik.openfugu.session.Session
import org.hubik.openfugu.storage.AndroidFileStore
import org.hubik.openfugu.storage.SharedPrefsStore
import org.hubik.openfugu.util.AppLog
import java.io.File

/**
 * Android shell around the multiplatform [EFuguStore]: supplies platform
 * storage and BLE, ties the store's lifetime to the ViewModel, and handles
 * the one Android-only flow — importing a session from a content URI.
 */
class EFuguViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "EFugu"
    }

    val store = EFuguStore(
        scope = viewModelScope,
        prefs = SharedPrefsStore(application, "efugu_prefs"),
        sessionFiles = AndroidFileStore(File(application.filesDir, "sessions")),
        ble = AndroidBlePlatform(application),
        appVersion = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    )

    /**
     * Import a shared session file (content URI from an ACTION_VIEW/ACTION_SEND
     * intent). Returns null if the file is not a readable OpenFugu session.
     */
    suspend fun importSession(uri: android.net.Uri): Session? {
        val text = withContext(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readNBytes(EFuguStore.MAX_IMPORT_BYTES + 1) }
                    ?: return@withContext null
                if (bytes.size > EFuguStore.MAX_IMPORT_BYTES) {
                    AppLog.w(TAG, "Rejecting session import: file exceeds ${EFuguStore.MAX_IMPORT_BYTES} bytes")
                    return@withContext null
                }
                bytes.decodeToString()
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to import session from $uri", e)
                null
            }
        } ?: return null
        return store.importSessionText(text)
    }

    override fun onCleared() {
        super.onCleared()
        store.shutdown()
    }
}
