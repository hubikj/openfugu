@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.hubik.openfugu

import org.hubik.openfugu.util.AppLog
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/**
 * Write [text] to a temporary file and present the system share sheet for it.
 * Serves both session sharing and log export on iOS.
 */
internal fun presentShareSheet(fileName: String, text: String) {
    val path = NSTemporaryDirectory() + fileName
    @Suppress("CAST_NEVER_SUCCEEDS") // Kotlin String bridges to NSString at runtime
    val written = (text as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
    if (!written) {
        AppLog.w("EFugu", "Could not write share file $fileName")
        return
    }
    val controller = UIActivityViewController(
        activityItems = listOf(NSURL.fileURLWithPath(path)),
        applicationActivities = null
    )
    UIApplication.sharedApplication.keyWindow?.rootViewController
        ?.presentViewController(controller, animated = true, completion = null)
}
