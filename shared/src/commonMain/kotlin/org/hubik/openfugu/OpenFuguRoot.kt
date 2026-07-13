package org.hubik.openfugu

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.hubik.openfugu.session.Session
import org.hubik.openfugu.ui.MockDeviceOverlay
import org.hubik.openfugu.ui.theme.OpenFuguTheme

/**
 * The whole app under the theme: EFuguApp routing, the simulated-device
 * overlay, and the snackbar for one-shot user messages. Each platform shell
 * (MainActivity, MainViewController) mounts this and injects only what the
 * platform owns: permissions and scan start, session-file import, log saving,
 * and the session share flow.
 */
@Composable
fun OpenFuguRoot(
    store: EFuguStore,
    onRequestPermissionsAndScan: () -> Unit,
    importSession: (suspend () -> Session?)? = null,
    onImportSessionHandled: () -> Unit = {},
    onSaveLogs: (List<String>) -> String = { "" },
    onShareSession: (fileName: String, text: String) -> Unit = { _, _ -> },
) {
    val appSettings by store.appSettings.collectAsState()
    OpenFuguTheme(
        darkTheme = when (appSettings.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            store.userMessages.collect { snackbarHostState.showSnackbar(it) }
        }
        Box {
            EFuguApp(
                store = store,
                onRequestPermissionsAndScan = onRequestPermissionsAndScan,
                importSession = importSession,
                onImportSessionHandled = onImportSessionHandled,
                onSaveLogs = onSaveLogs,
                onShareSession = onShareSession
            )
            // Slider controls for simulated devices, drawn over every
            // screen (games included) while any mock is connected.
            MockDeviceOverlay(store = store)
            // Messages float over every screen, games included — the
            // same reach the old toasts had.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }
}
