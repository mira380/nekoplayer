package com.nekoplayer.app.ui

import com.nekoplayer.app.data.AppDatabase
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import org.json.JSONObject

internal const val PREFERRED_VIDEO_STREAM_SETTING_KEY = "playback.preferredVideoStream"
internal const val AUTO_PLAY_PREFERRED_STREAM_SETTING_KEY = "playback.autoPlayPreferredStream"
internal const val ALL_SOURCES_VIDEO_STREAM_KEY = "*"

internal data class StreamIdentity(
    val hosterName: String,
    val videoTitle: String,
)

internal data class StreamChoice(
    val label: String,
    val video: Video,
    val identity: StreamIdentity,
)

internal data class PreferredVideoStream(
    val sourceId: String,
    val sourceName: String,
    val identity: StreamIdentity,
    val label: String,
)

internal fun readPreferredVideoStreams(database: AppDatabase): Map<String, PreferredVideoStream> {
    val stored = database.readSetting(PREFERRED_VIDEO_STREAM_SETTING_KEY) as? JSONObject ?: return emptyMap()
    val sources = stored.optJSONObject("sources")
    if (sources != null) {
        return buildMap {
            val keys = sources.keys()
            while (keys.hasNext()) {
                val sourceId = keys.next()
                val value = sources.optJSONObject(sourceId) ?: continue
                val hosterName = value.optString("hosterName")
                val videoTitle = value.optString("videoTitle")
                if (hosterName.isBlank() && videoTitle.isBlank()) continue
                put(
                    sourceId,
                    PreferredVideoStream(
                        sourceId = sourceId,
                        sourceName =
                            value.optString("sourceName").ifBlank {
                                if (sourceId == ALL_SOURCES_VIDEO_STREAM_KEY) "All sources" else sourceId
                            },
                        identity = StreamIdentity(hosterName, videoTitle),
                        label =
                            value.optString("label").ifBlank {
                                listOf(hosterName, videoTitle).filter(String::isNotBlank).joinToString(" · ")
                            },
                    ),
                )
            }
        }
    }

    // Preserve preferences written by earlier versions as a fallback for every source.
    val hosterName = stored.optString("hosterName")
    val videoTitle = stored.optString("videoTitle")
    if (hosterName.isBlank() && videoTitle.isBlank()) return emptyMap()
    return mapOf(
        ALL_SOURCES_VIDEO_STREAM_KEY to
            PreferredVideoStream(
                sourceId = ALL_SOURCES_VIDEO_STREAM_KEY,
                sourceName = "All sources",
                identity = StreamIdentity(hosterName, videoTitle),
                label =
                    stored.optString("label").ifBlank {
                        listOf(hosterName, videoTitle).filter(String::isNotBlank).joinToString(" · ")
                    },
            ),
    )
}

internal fun writePreferredVideoStreams(
    database: AppDatabase,
    preferred: Map<String, PreferredVideoStream>,
) {
    database.writeSetting(
        PREFERRED_VIDEO_STREAM_SETTING_KEY,
        preferred.takeIf { it.isNotEmpty() }?.let { streams ->
            JSONObject().put(
                "sources",
                JSONObject().apply {
                    streams.forEach { (sourceId, stream) ->
                        put(
                            sourceId,
                            JSONObject()
                                .put("sourceName", stream.sourceName)
                                .put("hosterName", stream.identity.hosterName)
                                .put("videoTitle", stream.identity.videoTitle)
                                .put("label", stream.label),
                        )
                    }
                },
            )
        },
    )
}

private val episodeNumberInStreamTitle =
    listOf(
        Regex("""(?i)\b(?:episode|ep\.?)\s*#?\s*\d+(?:\.\d+)?\b"""),
        Regex("""(?i)\bs\d+\s*e\d+\b"""),
        Regex("""(?i)\b\d+\s*x\s*\d+\b"""),
        Regex("""(?i)^\s*\d+(?:\.\d+)?\s*[-:|]\s*"""),
    )

internal fun normalizedStreamLabel(value: String): String =
    value
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

internal fun streamIdentity(
    hosterName: String,
    videoTitle: String,
): StreamIdentity {
    val normalizedTitle =
        episodeNumberInStreamTitle.fold(videoTitle.lowercase(java.util.Locale.ROOT)) { title, regex ->
            title.replace(regex, " ")
        }
    return StreamIdentity(
        hosterName = normalizedStreamLabel(hosterName),
        videoTitle = normalizedStreamLabel(normalizedTitle),
    )
}

internal enum class EpisodeSortOrder { NumberAscending, NumberDescending }

private val episodeLabelNumber = Regex("""(?i)\b(?:episode|ep\.?)\s*#?\s*(\d+(?:\.\d+)?)\b""")
private val leadingEpisodeNumber = Regex("""^\s*(\d+(?:\.\d+)?)\b""")

internal fun SEpisode.sortingNumber(): Float? {
    if (episode_number.isFinite() && (episode_number >= 0f)) return episode_number
    val labelMatch = episodeLabelNumber.find(name) ?: leadingEpisodeNumber.find(name)
    return labelMatch
        ?.groupValues
        ?.getOrNull(1)
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it >= 0f }
}

internal fun formatProgressTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun AppDatabase.SourceEpisodeProgress.isUnfinished(): Boolean =
    positionMs > 0L &&
        (durationMs <= 0L || positionMs < durationMs - AppDatabase.SOURCE_COMPLETION_TOLERANCE_MS)

internal fun AppDatabase.SourceEpisodeProgress.isCompletedForStats(): Boolean {
    if (positionMs <= 0L || durationMs <= 0L) return false
    val thresholdMs =
        completionPositionMs
            .takeIf { it > 0L && it <= durationMs }
            ?: episodeCompletionThresholdMs(durationMs)
            ?: return false
    return positionMs >= thresholdMs
}

internal fun LinkageError.toExtensionRuntimeMessage(apiVersion: Double): String {
    val detail =
        message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(160)
            ?.takeIf(String::isNotBlank)
            ?.let { " ($it)" }
            .orEmpty()
    return "This source failed to link with extension API ${apiVersion.toInt()}$detail. Update it or choose another source."
}
