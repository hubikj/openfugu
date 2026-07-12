package org.hubik.openfugu.util

import android.util.Log

/**
 * Tiny logging façade so nothing outside this file imports android.util.Log —
 * the implementation becomes platform-specific when the code goes multiplatform.
 */
object AppLog {
    fun i(tag: String, message: String) { Log.i(tag, message) }
    fun w(tag: String, message: String, e: Throwable? = null) { Log.w(tag, message, e) }
    fun e(tag: String, message: String, e: Throwable? = null) { Log.e(tag, message, e) }
}
