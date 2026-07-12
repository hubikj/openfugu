package org.hubik.openfugu.util

import android.util.Log

actual object AppLog {
    actual fun i(tag: String, message: String) { Log.i(tag, message) }
    actual fun w(tag: String, message: String, e: Throwable?) { Log.w(tag, message, e) }
    actual fun e(tag: String, message: String, e: Throwable?) { Log.e(tag, message, e) }
}
