package org.hubik.openfugu.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.hubik.openfugu.storage.FileStore
import org.hubik.openfugu.util.AppLog

/**
 * Stores one JSON file per session plus a lightweight index file.
 *
 * All file access runs on Dispatchers.IO; writers are serialized by a mutex.
 * Files are written atomically (temp file + rename via [FileStore]) so a
 * process death mid-write can never leave a truncated session or index
 * behind. The parsed index is cached in memory to avoid re-reading it on
 * every save.
 */
class SessionRepository(private val files: FileStore) {

    companion object {
        private const val TAG = "SessionRepository"
        private const val INDEX_FILE = "sessions_index.json"
        private const val MAX_SESSIONS = 50

        private fun fileName(id: String) = "session_$id.json"
    }

    private val mutex = Mutex()
    private var cachedIndex: List<SessionIndexEntry>? = null

    /** @return false if the session could not be saved — surface this to the user. */
    suspend fun saveSession(session: Session): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val json = SessionJson.sessionToJson(session)
                files.writeTextAtomic(fileName(session.id), json.toString())
                updateIndexLocked(session)
                cleanupOldSessionsLocked()
                true
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to save session", e)
                false
            }
        }
    }

    suspend fun loadIndex(): List<SessionIndexEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndexLocked() }
    }

    suspend fun loadSession(id: String): Session? = withContext(Dispatchers.IO) {
        val text = files.readText(fileName(id)) ?: return@withContext null
        try {
            SessionJson.sessionFromJson(Json.parseToJsonElement(text).jsonObject)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to load session $id", e)
            null
        }
    }

    suspend fun deleteSession(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            files.delete(fileName(id))
            writeIndexLocked(loadIndexLocked().filter { it.id != id })
        }
    }

    suspend fun exportSessionJson(id: String): String? = withContext(Dispatchers.IO) {
        files.readText(fileName(id))
    }

    // --- Index management (callers must hold [mutex]) ---

    private fun loadIndexLocked(): List<SessionIndexEntry> {
        cachedIndex?.let { return it }
        val text = files.readText(INDEX_FILE)
        val entries = if (text == null) {
            rebuildIndexLocked()
        } else try {
            Json.parseToJsonElement(text).jsonArray
                .map { SessionJson.indexEntryFromJson(it.jsonObject) }
                .sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to load index, rebuilding", e)
            rebuildIndexLocked()
        }
        cachedIndex = entries
        return entries
    }

    private fun cleanupOldSessionsLocked() {
        val index = loadIndexLocked()
        // Delete orphans: files that fell out of the index (corrupt, unknown
        // future type, or a crash between write and indexing) would otherwise
        // never be reclaimed by the MAX_SESSIONS cap.
        val known = index.mapTo(HashSet()) { fileName(it.id) }
        files.list().forEach { name ->
            if (name.startsWith("session_") && name.endsWith(".json") && name !in known) {
                files.delete(name)
            }
        }
        if (index.size <= MAX_SESSIONS) return
        index.drop(MAX_SESSIONS).forEach { entry ->
            files.delete(fileName(entry.id))
        }
        writeIndexLocked(index.take(MAX_SESSIONS))
    }

    private fun updateIndexLocked(session: Session) {
        val entries = loadIndexLocked().toMutableList()
        entries.removeAll { it.id == session.id }
        entries.add(0, SessionJson.indexEntryFromSession(session))
        writeIndexLocked(entries)
    }

    private fun writeIndexLocked(entries: List<SessionIndexEntry>) {
        val arr = buildJsonArray { entries.forEach { add(SessionJson.indexEntryToJson(it)) } }
        files.writeTextAtomic(INDEX_FILE, arr.toString())
        cachedIndex = entries
    }

    private fun rebuildIndexLocked(): List<SessionIndexEntry> {
        val entries = files.list()
            .filter { it.startsWith("session_") && it.endsWith(".json") }
            .mapNotNull { name ->
                try {
                    val text = files.readText(name) ?: return@mapNotNull null
                    val session = SessionJson.sessionFromJson(Json.parseToJsonElement(text).jsonObject)
                    session?.let { SessionJson.indexEntryFromSession(it) }
                } catch (e: Exception) { null }
            }
            .sortedByDescending { it.timestamp }
        writeIndexLocked(entries)
        return entries
    }
}
