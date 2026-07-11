package org.hubik.openfugu.session

import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.exercise.PeakMarker
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization for sessions and index entries — pure functions with
 * no file or Android dependencies, extracted from SessionRepository so the
 * round-trip behavior is unit-testable on the JVM.
 */
internal object SessionJson {

    // Pressure-derived values are rounded to 3 decimals when written: 0.001 hPa
    // = 0.1 Pa, ten times finer than the sensor's 1 Pa resolution, while keeping
    // files free of floating-point noise ("12.340000000000003").
    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    fun sessionToJson(session: Session): JSONObject = JSONObject().apply {
        put("id", session.id)
        put("type", session.type.name)
        put("timestamp", session.timestamp)
        put("durationMs", session.durationMs)
        put("deviceName", session.deviceName)
        put("userName", session.userName ?: JSONObject.NULL)
        put("pressureTrace", pressureTraceToJson(session.pressureTrace))
        when (session) {
            is Session.MinEqSession -> {
                put("peakMarkers", JSONArray().apply {
                    session.peakMarkers.forEach { m ->
                        put(JSONObject().apply {
                            put("t", m.timestamp)
                            put("v", round3(m.valueHPa))
                            put("s", m.successful)
                        })
                    }
                })
                put("mean", round3(session.mean))
                put("stddev", session.stddev?.let { round3(it) } ?: JSONObject.NULL)
                put("successCount", session.successCount)
                put("failCount", session.failCount)
            }
            is Session.ConstantEqSession -> {
                put("lowerBound", round3(session.lowerBound))
                put("upperBound", round3(session.upperBound))
                put("activationThreshold", round3(session.activationThreshold))
                put("scoringStartMs", session.scoringStartMs)
                put("percentInRange", round3(session.percentInRange.toDouble()))
                put("bestStreakMs", session.bestStreakMs)
                put("difficultyLabel", session.difficultyLabel)
                put("durationSetting", session.durationSetting)
            }
            is Session.GameSession -> {
                put("score", session.score)
                put("pressureRange", round3(session.pressureRange))
                put("negativeRange", round3(session.negativeRange))
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
                            put("pressureRange", round3(p.pressureRange))
                            put("negativeRange", round3(p.negativeRange))
                            put("expertMode", p.expertMode)
                            put("pressureTrace", pressureTraceToJson(p.pressureTrace))
                        })
                    }
                })
            }
        }
    }

    fun sessionFromJson(json: JSONObject): Session? {
        val type = try { SessionType.valueOf(json.getString("type")) } catch (e: Exception) { return null }
        val id = json.getString("id")
        val timestamp = json.getLong("timestamp")
        val durationMs = json.getLong("durationMs")
        val deviceName = json.getString("deviceName")
        val userName = if (json.isNull("userName")) null else json.getString("userName")
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
            SessionType.MULTIPLAYER_REEF_GAME, SessionType.MULTIPLAYER_FEAST_GAME -> {
                val playersArr = json.getJSONArray("players")
                val players = (0 until playersArr.length()).map { i ->
                    val p = playersArr.getJSONObject(i)
                    Session.PlayerResult(
                        deviceName = p.getString("deviceName"),
                        userName = if (p.isNull("userName")) null else p.getString("userName"),
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

    fun indexEntryFromSession(session: Session): SessionIndexEntry {
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

    fun indexEntryToJson(entry: SessionIndexEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("type", entry.type.name)
        put("timestamp", entry.timestamp)
        put("durationMs", entry.durationMs)
        put("deviceName", entry.deviceName)
        put("userName", entry.userName ?: JSONObject.NULL)
        put("summaryText", entry.summaryText)
    }

    fun indexEntryFromJson(json: JSONObject) = SessionIndexEntry(
        id = json.getString("id"),
        type = SessionType.valueOf(json.getString("type")),
        timestamp = json.getLong("timestamp"),
        durationMs = json.getLong("durationMs"),
        deviceName = json.getString("deviceName"),
        userName = if (json.isNull("userName")) null else json.getString("userName"),
        summaryText = json.getString("summaryText")
    )

    private fun pressureTraceToJson(trace: List<PressureReading>): JSONArray =
        JSONArray().apply {
            trace.forEach { r ->
                put(JSONObject().apply {
                    put("p", round3(r.pressureHPa))
                    put("r", round3(r.relativeHPa))
                    put("t", r.timestamp)
                })
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
}
