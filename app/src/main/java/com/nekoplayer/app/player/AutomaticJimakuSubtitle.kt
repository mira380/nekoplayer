package com.nekoplayer.app.player

import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuEntry
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
    episodeNumber: Int? = null,
): File? = withContext(Dispatchers.IO) {
    if (showName.isBlank() || apiKey.isBlank()) return@withContext null
    val episode = episodeNumber?.takeIf { it in 1..300 }
        ?: parseJimakuEpisodeNumber(episodeFileName)
        ?: return@withContext null

    try {
        val candidates = aniListClient.searchAnimeCandidates(showName, listOf(episodeFileName))
        val candidate = candidates.firstOrNull()?.takeIf { it.score >= MINIMUM_TITLE_SCORE }
            ?: return@withContext null
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && candidate.score - runnerUp.score < MINIMUM_TITLE_MARGIN) {
            return@withContext null
        }

        val entries = jimakuClient.searchEntries(apiKey, candidate.id)
        val rankedEntries = entries
            .map { entry -> entry to jimakuEntryTitleScore(showName, candidate.title, entry) }
            .sortedByDescending { it.second }
        if (rankedEntries.isEmpty()) return@withContext null
        val matchingEntries = if (rankedEntries.size == 1) {
            rankedEntries
        } else {
            rankedEntries.filter { it.second >= MINIMUM_JIMAKU_ENTRY_SCORE }
        }.take(MAX_JIMAKU_ENTRY_CANDIDATES)
        if (matchingEntries.isEmpty()) return@withContext null

        for ((entry, _) in matchingEntries) {
            val files = jimakuClient.listFiles(apiKey, entry.id)
            val episodeFiles = files.filter { file ->
                val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                extension in SubtitleParser.SUPPORTED_EXTENSIONS &&
                    (file.size == null || file.size <= SubtitleParser.MAX_FILE_BYTES) &&
                    parseJimakuEpisodeNumber(file.name) == episode
            }
            if (episodeFiles.isEmpty()) continue

            // Prefer styled subtitles, but leave ties for the manual picker.
            val preferredRank = episodeFiles.maxOf(::formatRank)
            val selected = episodeFiles.filter { formatRank(it) == preferredRank }.singleOrNull()
                ?: return@withContext null
            return@withContext jimakuClient.downloadToCache(selected, entry.id)
        }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun jimakuEntryTitleScore(
    showName: String,
    aniListTitle: String,
    entry: JimakuEntry,
): Double = listOf(showName, aniListTitle)
    .filter(String::isNotBlank)
    .flatMap { reference -> listOf(entry.englishName, entry.japaneseName).map { title -> titleMatchScore(reference, title) } }
    .maxOrNull() ?: 0.0

private fun titleMatchScore(left: String, right: String): Double {
    val first = normalizeTitle(left)
    val second = normalizeTitle(right)
    if (first.isEmpty() || second.isEmpty()) return 0.0
    if (first == second) return 1.0

    val firstWords = first.split(' ').filter { it.length > 1 && it !in COMMON_TITLE_WORDS }.toSet()
    val secondWords = second.split(' ').filter { it.length > 1 && it !in COMMON_TITLE_WORDS }.toSet()
    val sharedWords = firstWords.intersect(secondWords).size
    val wordScore = if (firstWords.isEmpty() || secondWords.isEmpty()) 0.0
        else 2.0 * sharedWords / (firstWords.size + secondWords.size)
    val containsScore = if (first.contains(second) || second.contains(first)) {
        minOf(first.length, second.length).toDouble() / maxOf(first.length, second.length)
    } else {
        0.0
    }
    return maxOf(wordScore, containsScore)
}

private fun normalizeTitle(title: String): String = title.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun formatRank(file: JimakuFile): Int =
    when (file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "ass" -> 4
        "ssa" -> 3
        "vtt" -> 2
        "srt" -> 1
        else -> 0
    }

private val COMMON_TITLE_WORDS = setOf("a", "an", "and", "for", "of", "the", "to", "with")
private const val MINIMUM_TITLE_SCORE = 0.68
private const val MINIMUM_TITLE_MARGIN = 0.03
private const val MINIMUM_JIMAKU_ENTRY_SCORE = 0.45
private const val MAX_JIMAKU_ENTRY_CANDIDATES = 3
