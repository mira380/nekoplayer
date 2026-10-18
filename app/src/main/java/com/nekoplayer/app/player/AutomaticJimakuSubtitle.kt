package com.nekoplayer.app.player

import android.content.Context
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuEntry
import com.nekoplayer.app.library.JimakuSubtitleClient
import com.nekoplayer.app.library.JimakuFile
import com.nekoplayer.app.library.isUsableJimakuSubtitleFile
import com.nekoplayer.app.library.parseJimakuSingleEpisodeNumber
import com.nekoplayer.app.library.rankJimakuEntriesByTitle
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
    private val root = File(context.filesDir, "auto-jimaku-subtitles-v2")

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

// Finds a subtitle only when the anime title and episode both have a strong match.
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
        .distinctBy(::normalizeTitle)
        .toList()
    if (showNames.isEmpty() || apiKey.isBlank()) return@withContext null

    val episode = episodeNumber?.takeIf { it in 1..300 }
        ?: parseJimakuSingleEpisodeNumber(episodeFileName)
        ?: return@withContext null

    try {
        val matchedEntries = linkedMapOf<Int, ScoredJimakuEntry>()
        val listedFiles = mutableMapOf<Int, List<JimakuFile>>()

        fun addMatches(
            entries: List<JimakuEntry>,
            referenceTitles: List<String>,
            linkedAniListId: Int? = null,
        ) {
            val ranked = rankJimakuEntriesByTitle(referenceTitles, entries)
                .map { match ->
                    if (linkedAniListId != null && match.entry.anilistId == linkedAniListId) {
                        match.copy(score = 1.0)
                    } else {
                        match
                    }
                }
                .filter { it.score >= MINIMUM_JIMAKU_ENTRY_SCORE }
                .sortedByDescending { it.score }
                .take(MAX_ENTRIES_PER_QUERY)
            for (match in ranked) {
                val previous = matchedEntries[match.entry.id]
                if (previous == null || match.score > previous.score) {
                    matchedEntries[match.entry.id] = ScoredJimakuEntry(match.entry, match.score)
                }
            }
        }

        suspend fun tryDownloadBestMatch(): File? {
            val availableEntries = mutableListOf<Pair<ScoredJimakuEntry, List<JimakuFile>>>()
            for (scoredEntry in matchedEntries.values
                .sortedByDescending(ScoredJimakuEntry::score)
                .take(MAX_ENTRIES_TO_CHECK)
            ) {
                val files = listedFiles[scoredEntry.entry.id] ?: try {
                    jimakuClient.listFiles(apiKey, scoredEntry.entry.id)
                        .also { listedFiles[scoredEntry.entry.id] = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                val episodeFiles = files.filter { isMatchingEpisodeFile(it, episode) }
                if (episodeFiles.isNotEmpty()) availableEntries += scoredEntry to episodeFiles
            }

            val best = availableEntries.firstOrNull() ?: return null
            if (best.first.score < MINIMUM_JIMAKU_ENTRY_SCORE) return null
            val runnerUp = availableEntries.getOrNull(1)
            if (runnerUp != null && best.first.score - runnerUp.first.score < MINIMUM_MATCH_SCORE_GAP) {
                return null
            }

            val preferredFile = best.second.minWithOrNull(JIMAKU_FILE_PREFERENCE_ORDER) ?: return null
            return jimakuClient.downloadToCache(preferredFile, best.first.entry.id)
        }

        for (title in showNames) {
            val entries = try {
                jimakuClient.searchEntriesByTitle(apiKey, title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            addMatches(entries, showNames)
        }

        tryDownloadBestMatch()?.let { return@withContext it }

        val animeCandidates = try {
            aniListClient.searchAnimeCandidates(
                showNames.first(),
                listOf(episodeFileName),
                minimumMatchScore = MINIMUM_ANI_LIST_SCORE,
                alternateTitles = showNames.drop(1),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

        for (candidate in animeCandidates.asSequence()
            .filter { it.score >= MINIMUM_ANI_LIST_SCORE }
            .take(MAX_ANI_LIST_CANDIDATES)
        ) {
            val entries = try {
                jimakuClient.searchEntries(apiKey, candidate.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            addMatches(
                entries = entries,
                referenceTitles = showNames + candidate.title + candidate.aliases,
                linkedAniListId = candidate.id,
            )
        }

        tryDownloadBestMatch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private data class ScoredJimakuEntry(
    val entry: JimakuEntry,
    val score: Double,
)

private fun normalizeTitle(title: String): String = title.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun isMatchingEpisodeFile(file: JimakuFile, episode: Int): Boolean =
    isUsableJimakuSubtitleFile(file) && parseJimakuSingleEpisodeNumber(file.name) == episode

private fun chsSubtitleRank(file: JimakuFile): Int =
    if (file.name.contains("CHS", ignoreCase = true)) 1 else 0

private fun preferredSourceRank(file: JimakuFile): Int =
    if (PREFERRED_SUBTITLE_SOURCE_PATTERNS.any { it.containsMatchIn(file.name) }) 0 else 1

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

internal val JIMAKU_FILE_PRIORITY_ORDER: Comparator<JimakuFile> =
    compareBy<JimakuFile>(::chsSubtitleRank)
        .thenBy(::preferredSourceRank)
        .thenBy(::resolutionRank)
        .thenByDescending(::formatRank)

internal val JIMAKU_FILE_PREFERENCE_ORDER: Comparator<JimakuFile> =
    JIMAKU_FILE_PRIORITY_ORDER.thenBy { it.name.lowercase(Locale.ROOT) }

private val PREFERRED_SUBTITLE_SOURCE_PATTERNS = listOf(
    Regex("""(?i)NETFLIX"""),
    Regex("""(?i)DMM[\s._-]*TV"""),
    Regex("""(?i)ABEMA"""),
    Regex("""(?i)U[\s._-]*NEXT"""),
    Regex("""(?i)WEB[\s._-]*DL"""),
    Regex("""(?i)WEB[\s._-]*RIP"""),
)

private val UNPREFERRED_SUBTITLE_RESOLUTION =
    Regex("""1440\s*[x×]\s*1080""", RegexOption.IGNORE_CASE)

private const val MINIMUM_ANI_LIST_SCORE = 0.72
private const val MINIMUM_JIMAKU_ENTRY_SCORE = 0.82
private const val MINIMUM_MATCH_SCORE_GAP = 0.10
private const val MAX_ANI_LIST_CANDIDATES = 5
private const val MAX_ENTRIES_PER_QUERY = 8
private const val MAX_ENTRIES_TO_CHECK = 6
