package org.hubik.openfugu.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.exercise.PeakMarker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Stores one JSON file per session plus a lightweight index file.
 *
 * All file access runs on Dispatchers.IO; writers are serialized by a mutex.
 * Files are written atomically (temp file + rename) so a process death
 * mid-write can never leave a truncated session or index behind. The parsed
 * index is cached in memory to avoid re-reading it on every save.
 */
class SessionRepository(private val context: Context) {

    companion object {
        private const val TAG = "SessionRepository"
        private const val DIR_NAME = "sessions"
        private const val INDEX_FILE = "sessions_index.json"
        private const val MAX_SESSIONS = 50
    }

    private val mutex = Mutex()
    private var cachedIndex: List<SessionIndexEntry>? = null

    private val sessionsDir: File
        get() = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /** @return false if the session could not be saved — surface this to the user. */
    suspend fun saveSession(session: Session): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val json = sessionToJson(session)
                File(sessionsDir, "session_${session.id}.json").writeAtomically(json.toString())
                updateIndexLocked(session)
                cleanupOldSessionsLocked()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session", e)
                false
            }
        }
    }

    suspend fun loadIndex(): List<SessionIndexEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndexLocked() }
    }

    suspend fun loadSession(id: String): Session? = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "session_$id.json")
        if (!file.exists()) return@withContext null
        try {
            sessionFromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session $id", e)
            null
        }
    }

    suspend fun deleteSession(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(sessionsDir, "session_$id.json").delete()
            writeIndexLocked(loadIndexLocked().filter { it.id != id })
        }
    }

    suspend fun exportSessionJson(id: String): String? = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "session_$id.json")
        if (file.exists()) file.readText() else null
    }

    // --- Index management (callers must hold [mutex]) ---

    private fun loadIndexLocked(): List<SessionIndexEntry> {
        cachedIndex?.let { return it }
        val file = File(sessionsDir, INDEX_FILE)
        val entries = if (!file.exists()) {
            rebuildIndexLocked()
        } else try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { indexEntryFromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load index, rebuilding", e)
            rebuildIndexLocked()
        }
        cachedIndex = entries
        return entries
    }

    private fun cleanupOldSessionsLocked() {
        val index = loadIndexLocked()
        if (index.size <= MAX_SESSIONS) return
        index.drop(MAX_SESSIONS).forEach { entry ->
            File(sessionsDir, "session_${entry.id}.json").delete()
        }
        writeIndexLocked(index.take(MAX_SESSIONS))
    }

    private fun updateIndexLocked(session: Session) {
        val entries = loadIndexLocked().toMutableList()
        entries.removeAll { it.id == session.id }
        entries.add(0, indexEntryFromSession(session))
        writeIndexLocked(entries)
    }

    private fun writeIndexLocked(entries: List<SessionIndexEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(indexEntryToJson(it)) }
        File(sessionsDir, INDEX_FILE).writeAtomically(arr.toString())
        cachedIndex = entries
    }

    private fun rebuildIndexLocked(): List<SessionIndexEntry> {
        val entries = sessionsDir.listFiles()
            ?.filter { it.name.startsWith("session_") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    val session = sessionFromJson(JSONObject(file.readText()))
                    session?.let { indexEntryFromSession(it) }
                } catch (e: Exception) { null }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
        writeIndexLocked(entries)
        return entries
    }

    /** Write via temp file + rename so a crash mid-write cannot truncate the target. */
    private fun File.writeAtomically(text: String) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(this)) {
            delete()
            if (!tmp.renameTo(this)) throw IOException("Atomic rename failed for $name")
        }
    }

    // --- JSON serialization ---

    private fun sessionToJson(session: Session): JSONObject = JSONObject().apply {
        put("id", session.id)
        put("type", session.type.name)
        put("timestamp", session.timestamp)
        put("durationMs", session.durationMs)
        put("deviceName", session.deviceName)
        put("userName", session.userName ?: JSONObject.NULL)
        put("pressureTrace", JSONArray().apply {
            session.pressureTrace.forEach { r ->
                put(JSONObject().apply {
                    put("p", r.pressureHPa)
                    put("r", r.relativeHPa)
                    put("t", r.timestamp)
                })
            }
        })
        when (session) {
            is Session.MinEqSession -> {
                put("peakMarkers", JSONArray().apply {
                    session.peakMarkers.forEach { m ->
                        put(JSONObject().apply {
                            put("t", m.timestamp)
                            put("v", m.valueHPa)
                            put("s", m.successful)
                        })
                    }
                })
                put("mean", session.mean)
                put("stddev", session.stddev ?: JSONObject.NULL)
                put("successCount", session.successCount)
                put("failCount", session.failCount)
            }
            is Session.ConstantEqSession -> {
                put("lowerBound", session.lowerBound)
                put("upperBound", session.upperBound)
                put("activationThreshold", session.activationThreshold)
                put("scoringStartMs", session.scoringStartMs)
                put("percentInRange", session.percentInRange.toDouble())
                put("bestStreakMs", session.bestStreakMs)
                put("difficultyLabel", session.difficultyLabel)
                put("durationSetting", session.durationSetting)
            }
            is Session.GameSession -> {
                put("score", session.score)
                put("pressureRange", session.pressureRange)
                put("negativeRange", session.negativeRange)
                put("expertMode", session.expertMode)
            }
            is Session.MultiplayerGameSession -> {
                put("players", JSONArray().apply {
                    session.players.forEach { p ->
                        put(JSONObject().apply {
                            put("deviceName", p.deviceName)
                            put("userName", p.userName ?: JSONObject.NULL)
                            put("colorArgb", p.colorArgb ?: JSONObject.NULL)
                            put("score", p.score)
                            put("rank", p.rank)
                            put("pressureRange", p.pressureRange)
                            put("negativeRange", p.negativeRange)
                            put("expertMode", p.expertMode)
                            put("pressureTrace", JSONArray().apply {
                                p.pressureTrace.forEach { r ->
                                    put(JSONObject().apply {
                                        put("p", r.pressureHPa)
                                        put("r", r.relativeHPa)
                                        put("t", r.timestamp)
                                    })
                                }
                            })
                        })
                    }
                })
            }
        }
    }

    private fun sessionFromJson(json: JSONObject): Session? {
        val type = try { SessionType.valueOf(json.getString("type")) } catch (e: Exception) { return null }
        val id = json.getString("id")
        val timestamp = json.getLong("timestamp")
        val durationMs = json.getLong("durationMs")
        val deviceName = json.getString("deviceName")
        val userName = json.optString("userName").takeIf { it.isNotEmpty() && it != "null" }
        val trace = parsePressureTrace(json.getJSONArray("pressureTrace"))

        return when (type) {
            SessionType.MIN_EQ -> Session.MinEqSession(
                id = id, timestamp = timestamp, durationMs = durationMs,
                deviceName = deviceName, userName = userName, pressureTrace = trace,
                peakMarkers = parsePeakMarkers(json.getJSONArray("peakMarkers")),
                mean = json.getDouble("mean"),
                stddev = if (json.isNull("stddev")) null else json.getDouble("stddev"),
                successCount = json.getInt("successCount"),
                failCount = json.getInt("failCount")
            )
            SessionType.CONSTANT_EQ -> Session.ConstantEqSession(
                id = id, timestamp = timestamp, durationMs = durationMs,
                deviceName = deviceName, userName = userName, pressureTrace = trace,
                lowerBound = json.getDouble("lowerBound"),
                upperBound = json.getDouble("upperBound"),
                activationThreshold = json.getDouble("activationThreshold"),
                scoringStartMs = json.getLong("scoringStartMs"),
                percentInRange = json.getDouble("percentInRange").toFloat(),
                bestStreakMs = json.getLong("bestStreakMs"),
                difficultyLabel = json.getString("difficultyLabel"),
                durationSetting = json.getString("durationSetting")
            )
            SessionType.REEF_GAME, SessionType.FEAST_GAME, SessionType.CAVE_GAME, SessionType.FLOW_GAME -> Session.GameSession(
                id = id, type = type, timestamp = timestamp, durationMs = durationMs,
                deviceName = deviceName, userName = userName, pressureTrace = trace,
                score = json.getInt("score"),
                pressureRange = json.optDouble("pressureRange", 40.0),
                negativeRange = json.optDouble("negativeRange", 0.0),
                expertMode = json.optBoolean("expertMode", false)
            )
            SessionType.MULTIPLAYER_REEF_GAME -> {
                val playersArr = json.getJSONArray("players")
                val players = (0 until playersArr.length()).map { i ->
                    val p = playersArr.getJSONObject(i)
                    Session.PlayerResult(
                        deviceName = p.getString("deviceName"),
                        userName = p.optString("userName").takeIf { it.isNotEmpty() && it != "null" },
                        colorArgb = if (p.isNull("colorArgb")) null else p.getLong("colorArgb"),
                        score = p.getInt("score"),
                        rank = p.getInt("rank"),
                        pressureTrace = parsePressureTrace(p.getJSONArray("pressureTrace")),
                        pressureRange = p.optDouble("pressureRange", 40.0),
                        negativeRange = p.optDouble("negativeRange", 0.0),
                        expertMode = p.optBoolean("expertMode", false)
                    )
                }
                Session.MultiplayerGameSession(
                    id = id, type = type, timestamp = timestamp, durationMs = durationMs,
                    pressureTrace = trace, players = players
                )
            }
        }
    }

    private fun parsePressureTrace(arr: JSONArray): List<PressureReading> =
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            PressureReading(obj.getDouble("p"), obj.getDouble("r"), obj.getLong("t"))
        }

    private fun parsePeakMarkers(arr: JSONArray): List<PeakMarker> =
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            PeakMarker(obj.getLong("t"), obj.getDouble("v"), obj.getBoolean("s"))
        }

    // --- Index entries ---

    private fun indexEntryFromSession(session: Session): SessionIndexEntry {
        val summary = when (session) {
            is Session.MinEqSession -> "${"%.1f".format(session.mean)} hPa (${session.successCount} peaks)"
            is Session.ConstantEqSession -> "${"%.0f".format(session.percentInRange * 100)}% in range"
            is Session.GameSession -> "Score: ${session.score}"
            is Session.MultiplayerGameSession -> {
                val winner = session.players.minByOrNull { it.rank }
                val name = winner?.userName ?: winner?.deviceName ?: "?"
                "${session.players.size} players · Winner: $name (${winner?.score ?: 0})"
            }
        }
        return SessionIndexEntry(
            id = session.id,
            type = session.type,
            timestamp = session.timestamp,
            durationMs = session.durationMs,
            deviceName = session.deviceName,
            userName = session.userName,
            summaryText = summary
        )
    }

    private fun indexEntryToJson(entry: SessionIndexEntry) = JSONObject().apply {
        put("id", entry.id)
        put("type", entry.type.name)
        put("timestamp", entry.timestamp)
        put("durationMs", entry.durationMs)
        put("deviceName", entry.deviceName)
        put("userName", entry.userName ?: JSONObject.NULL)
        put("summaryText", entry.summaryText)
    }

    private fun indexEntryFromJson(json: JSONObject) = SessionIndexEntry(
        id = json.getString("id"),
        type = SessionType.valueOf(json.getString("type")),
        timestamp = json.getLong("timestamp"),
        durationMs = json.getLong("durationMs"),
        deviceName = json.getString("deviceName"),
        userName = json.optString("userName").takeIf { it.isNotEmpty() && it != "null" },
        summaryText = json.getString("summaryText")
    )
}
