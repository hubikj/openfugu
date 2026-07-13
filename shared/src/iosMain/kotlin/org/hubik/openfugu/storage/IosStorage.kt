package org.hubik.openfugu.storage

import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** [KeyValueStore] on NSUserDefaults — the SharedPreferences counterpart. */
class UserDefaultsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

/**
 * [FileStore] inside one directory under the app sandbox (session recordings
 * live in Documents/sessions). NSString.writeToFile(atomically = true) gives
 * the same temp-file-plus-rename guarantee AndroidFileStore implements by hand.
 */
class IosFileStore(private val dirPath: String) : FileStore {

    private fun path(name: String) = "$dirPath/$name"

    override fun readText(name: String): String? =
        NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)

    override fun writeTextAtomic(name: String, text: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            dirPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        @Suppress("CAST_NEVER_SUCCEEDS") // Kotlin String bridges to NSString at runtime
        (text as NSString).writeToFile(path(name), true, NSUTF8StringEncoding, null)
    }

    override fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(name), error = null)
    }

    override fun list(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dirPath, error = null)
            ?.map { it.toString() }
            ?: emptyList()
}
