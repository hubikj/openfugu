package org.hubik.openfugu.storage

import android.content.Context
import java.io.File
import java.io.IOException

class SharedPrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

class AndroidFileStore(private val dir: File) : FileStore {

    override fun readText(name: String): String? =
        File(dir, name).takeIf { it.exists() }?.readText()

    override fun writeTextAtomic(name: String, text: String) {
        dir.mkdirs()
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Atomic rename failed for $name")
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun list(): List<String> =
        dir.listFiles()?.map { it.name } ?: emptyList()
}
