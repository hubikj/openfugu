package org.hubik.openfugu.util

import platform.Foundation.NSLog

/**
 * NSLog rather than println: NSLog reaches the system log, so remote testers'
 * devices can be watched live with idevicesyslog while sideloading (the
 * in-app log screen stays the primary diagnostic).
 */
actual object AppLog {
    actual fun i(tag: String, message: String) {
        NSLog("%@: %@", tag, message)
    }
    actual fun w(tag: String, message: String, e: Throwable?) {
        NSLog("W/%@: %@%@", tag, message, e?.let { " — $it" } ?: "")
    }
    actual fun e(tag: String, message: String, e: Throwable?) {
        NSLog("E/%@: %@%@", tag, message, e?.let { " — $it" } ?: "")
    }
}
