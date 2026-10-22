package com.nekoplayer.app.ui

import com.nekoplayer.app.data.AppDatabase

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

internal data class StreamIdentity(
    val hosterName: String,
    val videoTitle: String,
)

internal data class StreamChoice(
    val label: String,
    val video: Video,
    val identity: StreamIdentity,
)

private val episodeNumberInStreamTitle = listOf(
    Regex("""(?i)\b(?:episode|ep\.?)\s*#?\s*\d+(?:\.\d+)?\b"""),
    Regex("""(?i)\bs\d+\s*e\d+\b"""),
    Regex("""(?i)\b\d+\s*x\s*\d+\b"""),
    Regex("""(?i)^\s*\d+(?:\.\d+)?\s*[-:|]\s*"""),
)

internal fun normalizedStreamLabel(value: String): String =
    value.lowercase(java.util.Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

internal fun streamIdentity(hosterName: String, videoTitle: String): StreamIdentity {
    val normalizedTitle = episodeNumberInStreamTitle.fold(videoTitle.lowercase(java.util.Locale.ROOT)) { title, regex ->
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
    return labelMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it >= 0f }
}

internal fun formatProgressTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun AppDatabase.SourceEpisodeProgress.isUnfinished(): Boolean =
    positionMs > 0L &&
        (durationMs <= 0L || positionMs < durationMs - AppDatabase.SOURCE_COMPLETION_TOLERANCE_MS)

internal fun LinkageError.toExtensionRuntimeMessage(apiVersion: Double): String {
    val detail = message?.lineSequence()?.firstOrNull()?.trim()?.take(160)
        ?.takeIf(String::isNotBlank)
        ?.let { " ($it)" }
        .orEmpty()
    return "This source failed to link with extension API ${apiVersion.toInt()}$detail. Update it or choose another source."
}
