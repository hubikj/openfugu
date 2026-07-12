package org.hubik.openfugu.util

/**
 * Tiny logging façade so shared code never touches a platform logger
 * directly; each platform maps it onto its native one.
 */
expect object AppLog {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, e: Throwable? = null)
    fun e(tag: String, message: String, e: Throwable? = null)
}
