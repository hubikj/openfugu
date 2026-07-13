package org.hubik.openfugu

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import org.hubik.openfugu.ble.KableOnlyBlePlatform
import org.hubik.openfugu.storage.IosFileStore
import org.hubik.openfugu.storage.UserDefaultsStore
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
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

/** Entry point for the SwiftUI shell (iosApp/OpenFugu/iOSApp.swift). */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    OpenFuguRoot(
        store = store,
        // CoreBluetooth raises its own permission prompt on first use, so
        // scanning can start straight away.
        onRequestPermissionsAndScan = { store.startScan() },
        onSaveLogs = { messages ->
            presentShareSheet("openfugu_log.txt", messages.joinToString("\n"))
            "" // the share sheet is the feedback
        },
        onShareSession = { fileName, text -> presentShareSheet(fileName, text) }
    )
}
