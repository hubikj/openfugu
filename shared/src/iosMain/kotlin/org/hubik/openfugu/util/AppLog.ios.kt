package org.hubik.openfugu.util

import platform.Foundation.NSLog

/**
 * NSLog rather than println: NSLog reaches the system log, so remote testers'
 * devices can be watched live with idevicesyslog while sideloading (the
 * in-app log screen stays the primary diagnostic).
 *
 * NSLog is a C variadic function, and Kotlin/Native does not bridge Kotlin
 * strings to NSString in C varargs — an object specifier like %@ then makes
 * Foundation dereference raw string bytes (SIGSEGV on device the first time
 * this ran there). So: render the line in Kotlin, escape '%', pass no varargs.
 */
actual object AppLog {
    private fun write(line: String) = NSLog(line.replace("%", "%%"))

    actual fun i(tag: String, message: String) {
        write("$tag: $message")
    }
    actual fun w(tag: String, message: String, e: Throwable?) {
        write("W/$tag: $message${e?.let { " — $it" } ?: ""}")
    }
    actual fun e(tag: String, message: String, e: Throwable?) {
        write("E/$tag: $message${e?.let { " — $it" } ?: ""}")
    }
}
