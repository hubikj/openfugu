package org.hubik.openfugu.storage

/**
 * Small string key-value persistence (device list, profiles, pairings,
 * app settings). Backed by SharedPreferences on Android; other platforms
 * plug in their own implementation when the code goes multiplatform.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Flat text-file storage inside one directory (session recordings).
 * Names are plain file names, no paths.
 */
interface FileStore {
    /** @return null if the file does not exist. */
    fun readText(name: String): String?

    /** Write via temp file + rename so a crash mid-write cannot truncate the target. */
    fun writeTextAtomic(name: String, text: String)

    fun delete(name: String)
    fun list(): List<String>
}
