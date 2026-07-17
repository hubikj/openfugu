@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.hubik.openfugu

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import org.hubik.openfugu.ble.KableOnlyBlePlatform
import org.hubik.openfugu.storage.IosFileStore
import org.hubik.openfugu.storage.UserDefaultsStore
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.UIKit.UIViewController

/**
 * App-lifetime store. iOS has no ViewModel equivalent — the process itself is
 * the scope, so nothing ever calls shutdown().
 */
private val store: EFuguStore by lazy {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).first() as String
    EFuguStore(
        scope = MainScope(),
        prefs = UserDefaultsStore(),
        sessionFiles = IosFileStore("$documents/sessions"),
        ble = KableOnlyBlePlatform(),
        appVersion = bundleVersionString()
    )
}

private fun bundleVersionString(): String {
    val info = NSBundle.mainBundle.infoDictionary
    val version = info?.get("CFBundleShortVersionString") as? String ?: "?"
    val build = info?.get("CFBundleVersion") as? String ?: "?"
    return "$version (build $build)"
}

// Path of a .fugu file the system asked us to open; EFuguApp picks it up
// whether the app was already running or launched by the open. The iOS
// counterpart of MainActivity.importIntent.
private val pendingImportFile = mutableStateOf<String?>(null)

/** Called from the SwiftUI shell when the app is asked to open a file. */
@Suppress("unused")
fun handleIncomingFile(path: String) {
    pendingImportFile.value = path
}

/** Entry point for the SwiftUI shell (iosApp/OpenFugu/iOSApp.swift). */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val importPath = pendingImportFile.value
    OpenFuguRoot(
        store = store,
        // CoreBluetooth raises its own permission prompt on first use, so
        // scanning can start straight away.
        onRequestPermissionsAndScan = { store.startScan() },
        onShareLogs = { messages ->
            presentShareSheet("openfugu_log.txt", messages.joinToString("\n"))
        },
        onShareSession = { fileName, text -> presentShareSheet(fileName, text) },
        importSession = importPath?.let { path ->
            suspend {
                NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
                    ?.let { store.importSessionText(it) }
            }
        },
        onImportSessionHandled = {
            // The system copied the file into our sandbox (Documents/Inbox);
            // it is ours to clean up.
            pendingImportFile.value?.let {
                NSFileManager.defaultManager.removeItemAtPath(it, error = null)
            }
            pendingImportFile.value = null
        }
    )
}
