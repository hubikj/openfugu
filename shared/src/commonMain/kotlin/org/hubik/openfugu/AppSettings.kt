package org.hubik.openfugu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.hubik.openfugu.util.boolean
import org.hubik.openfugu.util.stringOrNull

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/** App-level settings — everything user-specific stays on UserProfile instead. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showSimulatedDevices: Boolean = false
) {
    fun toJsonString(): String = buildJsonObject {
        put("themeMode", themeMode.name)
        put("showSimulatedDevices", showSimulatedDevices)
    }.toString()

    companion object {
        /** Unknown values from future versions fall back to defaults, never crash. */
        fun fromJsonString(text: String): AppSettings = try {
            val obj = Json.parseToJsonElement(text).jsonObject
            AppSettings(
                themeMode = obj.stringOrNull("themeMode")
                    ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: ThemeMode.SYSTEM,
                showSimulatedDevices = obj.boolean("showSimulatedDevices", false)
            )
        } catch (e: Exception) {
            AppSettings()
        }
    }
}
