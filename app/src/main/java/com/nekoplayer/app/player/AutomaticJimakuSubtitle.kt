package com.nekoplayer.app.player

import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuSubtitleClient
import com.nekoplayer.app.library.JimakuFile
import com.nekoplayer.app.library.parseJimakuEpisodeNumber
import com.nekoplayer.app.media.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// Returns a file only when the title, episode, and subtitle choice are clear.
// Ambiguous matches return null so the user can search manually.
suspend fun findAutomaticJimakuSubtitle(
    showName: String,
    episodeFileName: String,
    apiKey: String,
    aniListClient: AniListClient,
    jimakuClient: JimakuSubtitleClient,
): File? = withContext(Dispatchers.IO) {
    if (showName.isBlank() || apiKey.isBlank()) return@withContext null
    val episode = parseJimakuEpisodeNumber(episodeFileName) ?: return@withContext null

    try {
        val candidates = aniListClient.searchAnimeCandidates(showName, listOf(episodeFileName))
        val candidate = candidates.firstOrNull()?.takeIf { it.score >= MINIMUM_TITLE_SCORE }
            ?: return@withContext null
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && candidate.score - runnerUp.score < MINIMUM_TITLE_MARGIN) {
            return@withContext null
        }

        val entries = jimakuClient.searchEntries(apiKey, candidate.id)
        val entry = entries.singleOrNull() ?: return@withContext null
        val files = jimakuClient.listFiles(apiKey, entry.id)
        val episodeFiles = files.filter { file ->
            val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            extension in SubtitleParser.SUPPORTED_EXTENSIONS &&
                (file.size == null || file.size <= SubtitleParser.MAX_FILE_BYTES) &&
                parseJimakuEpisodeNumber(file.name) == episode
        }
        if (episodeFiles.isEmpty()) return@withContext null

        // Prefer styled subtitles, but leave ties for the manual picker.
        val preferredRank = episodeFiles.maxOf(::formatRank)
        val selected = episodeFiles.filter { formatRank(it) == preferredRank }.singleOrNull()
            ?: return@withContext null
        jimakuClient.downloadToCache(selected, entry.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun formatRank(file: JimakuFile): Int =
    when (file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "ass" -> 4
        "ssa" -> 3
        "vtt" -> 2
        "srt" -> 1
        else -> 0
    }

private const val MINIMUM_TITLE_SCORE = 0.88
private const val MINIMUM_TITLE_MARGIN = 0.1
