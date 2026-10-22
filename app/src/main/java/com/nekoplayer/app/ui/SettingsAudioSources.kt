package com.nekoplayer.app.ui

import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.mining.PronunciationAudioPresets

import kotlin.math.roundToInt

import org.json.JSONObject

internal data class AudioSourceSetting(val name: String, val kind: String, val enabled: Boolean, val urlTemplate: String? = null) {
    fun toJson() = JSONObject().apply {
        put("id", name)
        put("kind", kind)
        put("enabled", enabled)
        urlTemplate?.let { put("urlTemplate", it) }
    }
}

internal fun loadAudioSources(database: AppDatabase): List<AudioSourceSetting> {
    val saved = database.readSetting("audio.pronunciationSources") as? org.json.JSONArray
        ?: return defaultAudioSources()
    if (saved.length() == 0) return emptyList()

    val defaultByKey = PronunciationAudioPresets.defaults.associateBy { it.id to it.kind }
    val sources = (0 until saved.length()).mapNotNull { index ->
        val item = saved.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim()
        val kind = item.optString("kind").trim()
        if (id.isBlank() || kind.isBlank()) {
            null
        } else {
            val preset = defaultByKey[id to kind]
            AudioSourceSetting(
                name = id,
                kind = kind,
                enabled = item.optBoolean("enabled", preset != null),
                urlTemplate = item.optString("urlTemplate").takeIf(String::isNotBlank)
                    ?: preset?.url,
            )
        }
    }
    val legacyDefault = sources.size == 3 &&
        sources.all { it.enabled && it.urlTemplate.isNullOrBlank() } &&
        sources.map { it.name to it.kind }.toSet() == setOf(
            "jpod101" to "jpod101",
            "language-pod-101" to "language-pod-101",
            "jisho" to "jisho",
        )
    return when {
        legacyDefault -> defaultAudioSources()
        else -> sources
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${(bytes / 1024.0).roundToInt()} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun defaultAudioSources() = PronunciationAudioPresets.defaults.map {
    AudioSourceSetting(it.id, it.kind, true, it.url)
}

internal fun isValidAudioSourceTemplate(template: String): Boolean = try {
    val expanded = template.replace("{term}", "term").replace("{reading}", "reading").replace("{language}", "ja")
    val uri = java.net.URI(expanded)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
} catch (_: Exception) {
    false
}
