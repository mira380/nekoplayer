package com.nekoplayer.app.player

import android.content.Context
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuEntry
import com.nekoplayer.app.library.JimakuSubtitleClient
import com.nekoplayer.app.library.JimakuFile
import com.nekoplayer.app.library.parseJimakuEpisodeNumber
import com.nekoplayer.app.media.SubtitleParser
import com.nekoplayer.app.media.SubtitleTrack
import com.nekoplayer.app.media.SubtitleTrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

// Stores and reuses Jimaku subtitles for local and source videos.
class AutomaticJimakuSubtitleStore(
    context: Context,
    private val aniListClient: AniListClient,
    private val jimakuClient: JimakuSubtitleClient,
) {
    private val root = File(context.filesDir, "auto-jimaku-subtitles")

    suspend fun loadOrFetch(
        identity: String,
        showName: String,
        episodeFileName: String,
        apiKey: String,
        episodeNumber: Int? = null,
        alternateShowNames: List<String> = emptyList(),
    ): SubtitleTrack? = withContext(Dispatchers.IO) {
        if (identity.isBlank() || showName.isBlank()) return@withContext null

        val saved = episodeDirectory(identity).listFiles()
            ?.firstOrNull(::isUsableSubtitle)
        if (saved != null) {
            try {
                return@withContext parseTrack(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saved.delete()
            }
        }

        if (apiKey.isBlank()) return@withContext null
        val downloaded = findAutomaticJimakuSubtitle(
            showName = showName,
            episodeFileName = episodeFileName,
            apiKey = apiKey,
            aniListClient = aniListClient,
            jimakuClient = jimakuClient,
            episodeNumber = episodeNumber,
            alternateShowNames = alternateShowNames,
        ) ?: return@withContext null

        val track = try {
            parseTrack(downloaded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Remove invalid downloads so they can be fetched again.
            downloaded.delete()
            return@withContext null
        }

        persist(identity, downloaded)
        track
    }

    private fun parseTrack(file: File): SubtitleTrack {
        require(isUsableSubtitle(file)) { "Jimaku returned an invalid subtitle file." }
        val parsed = SubtitleParser.parse(file.readBytes(), file.name)
        val sourcePrefix = "jimaku-"
        return parsed.copy(
            id = sourcePrefix + parsed.id,
            cues = parsed.cues.map { cue ->
                cue.copy(id = sourcePrefix + cue.id, trackId = sourcePrefix + cue.trackId)
            },
            source = SubtitleTrackSource.JIMAKU,
        )
    }

    private fun persist(identity: String, source: File) {
        val directory = episodeDirectory(identity)
        if (!directory.exists() && !directory.mkdirs()) return
        val destination = File(directory, source.name)
        val temporary = File(directory, ".${source.name}.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!isUsableSubtitle(temporary)) {
                temporary.delete()
                return
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                return
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                return
            }
            directory.listFiles()
                ?.filter { it.isFile && (it != destination) }
                ?.forEach(File::delete)
            trimStorage(directory)
        } catch (_: Exception) {
            temporary.delete()
        }
    }

    private fun episodeDirectory(identity: String): File {
        val folderName = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
        return File(root, folderName)
    }

    private fun trimStorage(newestDirectory: File) {
        val olderDirectories = root.listFiles()
            ?.asSequence()
            ?.filter { (it.isDirectory) && (it != newestDirectory) }
            ?.sortedByDescending(File::lastModified)
            ?.toList()
            .orEmpty()
        var keptEpisodes = 1
        var keptBytes = newestDirectory.listFiles()?.asSequence()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
        for (directory in olderDirectories) {
            val bytes = directory.listFiles()?.asSequence()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
            if ((keptEpisodes < MAX_SAVED_EPISODES) && ((keptBytes + bytes) <= MAX_SAVED_BYTES)) {
                keptEpisodes++
                keptBytes += bytes
            } else {
                directory.deleteRecursively()
            }
        }
    }

    private fun isUsableSubtitle(file: File): Boolean {
        if (!file.isFile) return false
        val size = file.length()
        val extension = file.extension.lowercase(Locale.ROOT)
        return (size >= 1L) &&
            (size <= SubtitleParser.MAX_FILE_BYTES.toLong()) &&
            (extension in SubtitleParser.SUPPORTED_EXTENSIONS)
    }

    private companion object {
        const val MAX_SAVED_EPISODES = 200
        const val MAX_SAVED_BYTES = 128L * 1024L * 1024L
    }
}

// Finds the best matching Jimaku subtitle for an episode.
suspend fun findAutomaticJimakuSubtitle(
    showName: String,
    episodeFileName: String,
    apiKey: String,
    aniListClient: AniListClient,
    jimakuClient: JimakuSubtitleClient,
    episodeNumber: Int? = null,
    alternateShowNames: List<String> = emptyList(),
): File? = withContext(Dispatchers.IO) {
    val showNames = (listOf(showName) + alternateShowNames)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { normalizeTitle(it) }
        .toList()
    if (showNames.isEmpty() || apiKey.isBlank()) return@withContext null

    val episode = episodeNumber?.takeIf { (it >= 1) && (it <= 300) }
        ?: parseJimakuEpisodeNumber(episodeFileName)
        ?: return@withContext null

    try {
        val candidates = linkedMapOf<Int, CandidateWithScore>()
        for (title in showNames) {
            val matches = try {
                aniListClient.searchAnimeCandidates(
                    title,
                    listOf(episodeFileName),
                    minimumMatchScore = MINIMUM_ANI_LIST_SCORE,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            for ((id, title, candidateScore) in matches) {
                val score = maxOf(candidateScore, showNames.maxOf { titleMatchScore(it, title) })
                val current = candidates[id]
                if ((current == null) || (score > current.score)) {
                    candidates[id] = CandidateWithScore(id, title, score)
                }
            }
        }

        for ((candidateId, candidateTitle, candidateScore) in candidates.values.asSequence()
            .sortedByDescending(CandidateWithScore::score)
            .take(MAX_ANI_LIST_CANDIDATES)
        ) {
            if (candidateScore < MINIMUM_ANI_LIST_SCORE) continue
            val entries = try {
                jimakuClient.searchEntries(apiKey, candidateId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val referenceTitles = showNames + candidateTitle
            val scoredEntries = entries
                .map { entry -> entry to jimakuEntryTitleScore(referenceTitles, entry) }
            val rankedEntries = scoredEntries.asSequence()
                .filter { (_, score) -> score >= MINIMUM_JIMAKU_ENTRY_SCORE }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_JIMAKU_ENTRY_CANDIDATES)
                .toList()
                .ifEmpty {
                    if ((entries.size == 1) && (candidateScore >= STRONG_ANI_LIST_SCORE)) {
                        scoredEntries
                    } else {
                        emptyList()
                    }
                }

            val matchingFiles = mutableListOf<RankedJimakuFile>()
            for ((entryRank, rankedEntry) in rankedEntries.withIndex()) {
                val (entry, _) = rankedEntry
                val files = try {
                    jimakuClient.listFiles(apiKey, entry.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                files.asSequence()
                    .filter { isMatchingEpisodeFile(it, episode) }
                    .forEach { file -> matchingFiles += RankedJimakuFile(file, entry.id, entryRank) }
            }
            val preference = Comparator<RankedJimakuFile> { first, second ->
                val resolutionOrder = resolutionRank(first.file).compareTo(resolutionRank(second.file))
                if (resolutionOrder != 0) {
                    resolutionOrder
                } else {
                    val entryOrder = first.entryRank.compareTo(second.entryRank)
                    if (entryOrder != 0) {
                        entryOrder
                    } else {
                        val formatOrder = formatRank(second.file).compareTo(formatRank(first.file))
                        if (formatOrder != 0) formatOrder
                        else first.file.name.compareTo(second.file.name, ignoreCase = true)
                    }
                }
            }
            val selected = matchingFiles.minWithOrNull(preference) ?: continue

            return@withContext jimakuClient.downloadToCache(selected.file, selected.entryId)
        }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private data class CandidateWithScore(
    val id: Int,
    val title: String,
    val score: Double,
)

private data class RankedJimakuFile(
    val file: JimakuFile,
    val entryId: Int,
    val entryRank: Int,
)

private fun jimakuEntryTitleScore(
    referenceTitles: List<String>,
    entry: JimakuEntry,
): Double = referenceTitles.asSequence()
    .filter(String::isNotBlank)
    .flatMap { reference ->
        listOf(entry.englishName, entry.japaneseName).asSequence().map { title -> titleMatchScore(reference, title) }
    }
    .maxOrNull() ?: 0.0

private fun titleMatchScore(left: String, right: String): Double {
    val first = normalizeTitle(left)
    val second = normalizeTitle(right)
    if (first.isEmpty() || second.isEmpty()) return 0.0
    if (first == second) return 1.0

    val firstWords = first.split(' ').asSequence()
        .filter { (it.length > 1) && (it !in COMMON_TITLE_WORDS) }
        .toSet()
    val secondWords = second.split(' ').asSequence()
        .filter { (it.length > 1) && (it !in COMMON_TITLE_WORDS) }
        .toSet()
    val sharedWords = firstWords.intersect(secondWords).size
    val wordScore = if (firstWords.isEmpty() || secondWords.isEmpty()) 0.0
        else (2.0 * sharedWords) / (firstWords.size + secondWords.size)
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

private fun isMatchingEpisodeFile(file: JimakuFile, episode: Int): Boolean {
    val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val validSize = file.size?.let { size ->
        (size >= 1L) && (size <= SubtitleParser.MAX_FILE_BYTES.toLong())
    } ?: true
    return (extension in SubtitleParser.SUPPORTED_EXTENSIONS) &&
        validSize &&
        (parseJimakuEpisodeNumber(file.name) == episode)
}

private val UNPREFERRED_SUBTITLE_RESOLUTION =
    Regex("""1440\s*[x×]\s*1080""", RegexOption.IGNORE_CASE)

private fun resolutionRank(file: JimakuFile): Int =
    if (UNPREFERRED_SUBTITLE_RESOLUTION.containsMatchIn(file.name)) 1 else 0

private fun formatRank(file: JimakuFile): Int =
    when (file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "ass" -> 4
        "ssa" -> 3
        "vtt" -> 2
        "srt" -> 1
        else -> 0
    }

private val COMMON_TITLE_WORDS = setOf("a", "an", "and", "for", "of", "the", "to", "with")
private const val MINIMUM_ANI_LIST_SCORE = 0.55
private const val STRONG_ANI_LIST_SCORE = 0.72
private const val MINIMUM_JIMAKU_ENTRY_SCORE = 0.35
private const val MAX_ANI_LIST_CANDIDATES = 5
private const val MAX_JIMAKU_ENTRY_CANDIDATES = 3
