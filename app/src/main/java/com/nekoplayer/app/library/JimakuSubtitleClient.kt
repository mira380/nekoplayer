package com.nekoplayer.app.library

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import com.nekoplayer.app.media.SubtitleParser
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class JimakuEntry(
    val id: Int,
    val englishName: String,
    val japaneseName: String,
    val name: String = "",
    val anilistId: Int? = null,
    val lastModifiedAtMs: Long? = null,
) {
    val titleNames: List<String>
        get() = listOf(englishName, japaneseName, name)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    val displayName: String
        get() {
            val english = englishName.trim().ifBlank { name.trim() }
            val japanese = japaneseName.trim()
            return when {
                english.isEmpty() -> japanese
                japanese.isEmpty() || japanese == english -> english
                else -> "$english | $japanese"
            }
        }
}

data class JimakuFile(val name: String, val url: String, val size: Long?)

data class JimakuSubtitleAvailability(
    val availableForEpisode: Boolean,
    val latestAvailableEpisode: Int? = null,
    val entryFound: Boolean = false,
    val entryId: Int? = null,
)

internal data class JimakuEntryTitleMatch(
    val entry: JimakuEntry,
    val score: Double,
)

internal fun rankJimakuEntriesByTitle(
    referenceTitles: Iterable<String>,
    entries: Iterable<JimakuEntry>,
): List<JimakuEntryTitleMatch> {
    val references = referenceTitles.map(String::trim).filter(String::isNotBlank)
    return entries.map { entry ->
        val score = references.asSequence()
            .flatMap { reference ->
                entry.titleNames.asSequence().map { title -> jimakuTitleSimilarity(reference, title) }
            }
            .maxOrNull() ?: 0.0
        JimakuEntryTitleMatch(entry, score)
    }.sortedWith(
        compareByDescending<JimakuEntryTitleMatch>(JimakuEntryTitleMatch::score)
            .thenBy { it.entry.displayName.lowercase(Locale.ROOT) },
    )
}

private fun jimakuTitleSimilarity(left: String, right: String): Double {
    val first = normalizeJimakuTitle(left)
    val second = normalizeJimakuTitle(right)
    if (first.isEmpty() || second.isEmpty()) return 0.0
    if (first == second) return 1.0

    val firstWords = first.split(' ').filter { it.length > 1 && it !in JIMAKU_COMMON_TITLE_WORDS }.toSet()
    val secondWords = second.split(' ').filter { it.length > 1 && it !in JIMAKU_COMMON_TITLE_WORDS }.toSet()
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

private val JIMAKU_CENSORSHIP_RELEASE_TAG = Regex(
    """(?i)[\[(\{]\s*(?:uncensored|censored|uncut)(?:\s+(?:version|cut|release))?\s*[\])}]""",
)

internal fun stripJimakuReleaseTags(title: String): String = title
    .replace(JIMAKU_CENSORSHIP_RELEASE_TAG, " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun jimakuTitleKey(title: String): String = stripJimakuReleaseTags(title).lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun normalizeJimakuTitle(title: String): String = jimakuTitleKey(title)

private val JIMAKU_COMMON_TITLE_WORDS = setOf("a", "an", "and", "for", "of", "the", "to", "with")
internal const val MIN_LIBRARY_TITLE_MATCH_SCORE = 0.45

internal fun jimakuReferenceTitles(showTitle: String, sourceUrl: String? = null): List<String> {
    val sourceSlug = sourceUrl?.let(::titleFromSourceUrl)
    return listOfNotNull(showTitle.trim(), sourceSlug)
        .filter(String::isNotBlank)
        .flatMap { title -> listOfNotNull(title, primaryJimakuTitle(title)) }
        .distinctBy(::normalizeJimakuTitle)
}

internal fun jimakuHasPrimaryTitleAlias(showTitle: String, sourceUrl: String? = null): Boolean =
    listOfNotNull(showTitle.trim(), sourceUrl?.let(::titleFromSourceUrl))
        .any { primaryJimakuTitle(it) != null }

private fun primaryJimakuTitle(title: String): String? {
    val separator = title.indexOfAny(charArrayOf(':', '：'))
    if (separator <= 0) return null
    val primaryTitle = title.substring(0, separator).trim()
    val significantWords = normalizeJimakuTitle(primaryTitle)
        .split(' ')
        .filter { it.length > 1 && it !in JIMAKU_COMMON_TITLE_WORDS }
    return primaryTitle.takeIf { significantWords.size >= 3 }
}

private fun titleFromSourceUrl(sourceUrl: String): String? {
    val segment = runCatching { Uri.parse(sourceUrl).pathSegments.lastOrNull() }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: return null
    val title = Uri.decode(segment)
        .substringBefore('?')
        .replace(Regex("(?i)\\.(?:html?|php)$"), " ")
        .replace(Regex("[-_]+"), " ")
        .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return title.takeIf { it.length >= 4 && it.any(Char::isLetter) }
}

private fun parseJimakuTimestampMs(value: String): Long? {
    if (value.isBlank()) return null
    val normalized = value.replace(Regex("\\.(\\d+)(?=Z|[+-]\\d{2}:?\\d{2}$)")) { match ->
        ".${match.groupValues[1].padEnd(3, '0').take(3)}"
    }.replace(Regex("Z$"), "+0000")
        .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ")
        .firstNotNullOfOrNull { format ->
            runCatching {
                SimpleDateFormat(format, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
        }
}

class JimakuSubtitleClient(
    private val cacheDirectory: File,
    baseUrl: String = "https://jimaku.cc",
) {
    private val baseUrl = baseUrl.trimEnd('/')

    @Throws(LibraryNetworkException::class)
    fun searchEntries(apiKey: String, anilistId: Int): List<JimakuEntry> {
        requireKey(apiKey)
        val body = getJson("$baseUrl/api/entries/search?anilist_id=$anilistId", apiKey, "Searching Jimaku")
        return parseEntries(body)
    }

    @Throws(LibraryNetworkException::class)
    fun searchRecentlyModifiedEntries(apiKey: String, afterUnixSeconds: Long): List<JimakuEntry> {
        requireKey(apiKey)
        require(afterUnixSeconds >= 0L) { "A valid Jimaku timestamp is required." }
        val body = getJson(
            "$baseUrl/api/entries/search?anime=true&after=$afterUnixSeconds",
            apiKey,
            "Checking recently updated Jimaku entries",
        )
        return parseEntries(body)
    }

    @Throws(LibraryNetworkException::class)
    fun searchEntriesByTitle(apiKey: String, title: String): List<JimakuEntry> {
        requireKey(apiKey)
        val query = stripJimakuReleaseTags(title)
        if (query.isBlank()) return emptyList()
        val encodedQuery = Uri.encode(query)
        val body = getJson(
            "$baseUrl/api/entries/search?query=$encodedQuery&anime=true",
            apiKey,
            "Searching Jimaku by title",
        )
        return parseEntries(body)
    }

    private fun parseEntries(body: Any): List<JimakuEntry> {
        if (body !is JSONArray) throw LibraryNetworkException("Jimaku answered in an unexpected format.")
        return (0 until body.length()).mapNotNull { index ->
            val item = body.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", -1L)
            if (id < 0 || id > Int.MAX_VALUE) return@mapNotNull null
            JimakuEntry(
                id = id.toInt(),
                englishName = item.optString("english_name"),
                japaneseName = item.optString("japanese_name"),
                name = item.optString("name"),
                anilistId = item.optInt("anilist_id", 0).takeIf { it > 0 },
                lastModifiedAtMs = parseJimakuTimestampMs(item.optString("last_modified")),
            )
        }
    }

    @Throws(LibraryNetworkException::class)
    fun listFiles(apiKey: String, entryId: Int, episode: Int? = null): List<JimakuFile> {
        requireKey(apiKey)
        require(episode == null || episode in 1..300) { "A valid episode number is required." }
        val episodeQuery = episode?.let { "?episode=$it" }.orEmpty()
        val body = getJson("$baseUrl/api/entries/$entryId/files$episodeQuery", apiKey, "Listing Jimaku files")
        if (body !is JSONArray) throw LibraryNetworkException("Jimaku answered in an unexpected format.")
        return (0 until body.length()).mapNotNull { index ->
            val item = body.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val url = item.optString("url")
            if (name.isBlank() || url.isBlank()) return@mapNotNull null
            JimakuFile(name, url, if (item.has("size") && !item.isNull("size")) item.optLong("size").takeIf { it >= 0 } else null)
        }
    }

    fun findSubtitleAvailability(
        apiKey: String,
        showTitle: String,
        episode: Int,
        aniListId: Int? = null,
        sourceUrl: String? = null,
    ): JimakuSubtitleAvailability {
        require(episode in 1..300) { "A valid episode number is required." }
        val title = showTitle.trim()
        require(title.isNotEmpty()) { "A show title is required." }
        val referenceTitles = jimakuReferenceTitles(title, sourceUrl)

        // AniList IDs are a stronger link than provider display titles and URL slugs.
        if (aniListId != null && aniListId > 0) {
            val entries = searchEntries(apiKey, aniListId)
            if (entries.isNotEmpty()) {
                val bestEntry = rankJimakuEntriesByTitle(referenceTitles, entries).first().entry
                return inspectEntryForEpisode(apiKey, bestEntry.id, episode)
            }
        }

        for (query in referenceTitles) {
            val searchResults = searchEntriesByTitle(apiKey, query)
            val matches = rankJimakuEntriesByTitle(
                referenceTitles = referenceTitles,
                entries = searchResults,
            ).filter { it.score >= MIN_LIBRARY_TITLE_MATCH_SCORE }
            if (matches.isEmpty()) continue

            // Nearby sequel titles often score similarly. Combining their files can report
            // subtitles from the wrong season, so only inspect the single best match.
            return inspectEntryForEpisode(apiKey, matches.first().entry.id, episode)
        }
        return JimakuSubtitleAvailability(availableForEpisode = false)
    }

    fun findSubtitleAvailabilityInEntry(
        apiKey: String,
        entryId: Int,
        episode: Int,
    ): JimakuSubtitleAvailability {
        requireKey(apiKey)
        require(episode in 1..300) { "A valid episode number is required." }
        return inspectEntryForEpisode(apiKey, entryId, episode)
    }

    private fun inspectEntryForEpisode(
        apiKey: String,
        entryId: Int,
        episode: Int,
    ): JimakuSubtitleAvailability {
        val episodeFiles = listFiles(apiKey, entryId, episode)
        if (episodeFiles.any(JimakuFile::isAvailabilityCandidate)) {
            return JimakuSubtitleAvailability(
                availableForEpisode = true,
                latestAvailableEpisode = episode,
                entryFound = true,
                entryId = entryId,
            )
        }

        // The episode filter is best effort. On an empty result, inspect the complete entry once;
        // this also identifies the newest numbered episode available so the Library can be precise.
        val allFiles = listFiles(apiKey, entryId).filter(JimakuFile::isAvailabilityCandidate)
        val latestEpisode = allFiles.mapNotNull(JimakuFile::latestEpisodeNumber).maxOrNull()
        val hasRequestedEpisode = allFiles.any { it.containsEpisode(episode) }
        return JimakuSubtitleAvailability(
            availableForEpisode = hasRequestedEpisode,
            latestAvailableEpisode = if (hasRequestedEpisode) maxOf(episode, latestEpisode ?: episode) else latestEpisode,
            entryFound = true,
            entryId = entryId,
        )
    }

    // Reuse cached downloads for both automatic lookup and the manual picker.
    @Throws(LibraryNetworkException::class)
    @Synchronized
    fun downloadToCache(file: JimakuFile, entryId: Int): File {
        val safeName = file.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "subtitle.srt" }
        val directory = File(cacheDirectory, "jimaku-v2")
        val destination = File(directory, "${entryId}_$safeName")
        if (
            destination.isFile &&
            destination.length() in 1L..MAX_DOWNLOAD_BYTES
        ) {
            return destination
        }
        destination.delete()

        val uri = try { file.url.toUri() } catch (error: Exception) {
            throw LibraryNetworkException("Jimaku returned an unusable download link.", cause = error)
        }
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
            throw LibraryNetworkException("Jimaku returned an unusable download link.")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw LibraryNetworkException("The subtitle could not be saved to the cache.")
        }

        val temporary = File(directory, ".${entryId}_$safeName.part")
        temporary.delete()
        val connection = URL(file.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw LibraryNetworkException("Jimaku refused the download (HTTP ${connection.responseCode}).", retryable = true)
            }
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw LibraryNetworkException("Jimaku returned a suspiciously large response.")
            }

            var total = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw LibraryNetworkException("Jimaku returned a suspiciously large response.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (total == 0L) throw LibraryNetworkException("Jimaku returned an empty subtitle file.")
            if (!temporary.renameTo(destination)) {
                throw LibraryNetworkException("The downloaded subtitle could not be saved.")
            }
            return destination
        } catch (error: LibraryNetworkException) {
            throw error
        } catch (error: Exception) {
            throw LibraryNetworkException(
                "The subtitle could not be downloaded. Check the connection and try again.",
                retryable = true,
                cause = error,
            )
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }

    private fun getJson(url: String, apiKey: String, operation: String): Any {
        awaitApiRequestSlot()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", apiKey.trim())
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "nekoplayer/Android")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw LibraryNetworkException("Jimaku rejected the API key. Check it in Settings.")
            }
            if (status == HTTP_TOO_MANY_REQUESTS) {
                val retryAfterMs = readRateLimitResetMs(connection) ?: DEFAULT_RATE_LIMIT_RETRY_MS
                holdApiRequestsFor(retryAfterMs)
                throw LibraryNetworkException(
                    "$operation rate limited (HTTP $status).",
                    retryable = true,
                    retryAfterMs = retryAfterMs,
                )
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw LibraryNetworkException("$operation failed (HTTP $status).", retryable = true)
            }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (length > MAX_JSON_BYTES) throw LibraryNetworkException("Jimaku answered with a suspiciously large response.")
            val bytes = connection.inputStream.use { readBounded(it) }
            return org.json.JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()
        } catch (error: LibraryNetworkException) {
            throw error
        } catch (error: Exception) {
            throw LibraryNetworkException("$operation failed. Check the connection and try again.", retryable = true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    private fun readRateLimitResetMs(connection: HttpURLConnection): Long? {
        val resetAfterMs = connection.getHeaderField("X-RateLimit-Reset-After")
            ?.toDoubleOrNull()
            ?.times(1_000.0)
            ?.toLong()
        val resetAtMs = connection.getHeaderField("X-RateLimit-Reset")
            ?.toDoubleOrNull()
            ?.times(1_000.0)
            ?.minus(System.currentTimeMillis())
            ?.toLong()
        val retryAfterMs = connection.getHeaderField("Retry-After")
            ?.toDoubleOrNull()
            ?.times(1_000.0)
            ?.toLong()
        return sequenceOf(resetAfterMs, resetAtMs, retryAfterMs)
            .filterNotNull()
            .firstOrNull { it > 0L }
            ?.coerceIn(MIN_RATE_LIMIT_RETRY_MS, MAX_RATE_LIMIT_RETRY_MS)
    }

    private fun requireKey(apiKey: String) {
        if (apiKey.isBlank()) throw LibraryNetworkException("Add your Jimaku API key in Settings → Subtitles first.")
    }

    private fun awaitApiRequestSlot() {
        synchronized(API_REQUEST_LOCK) {
            val now = SystemClock.elapsedRealtime()
            val waitMs = (nextApiRequestAtMs - now).coerceAtLeast(0L)
            if (waitMs > 0L) Thread.sleep(waitMs)
            nextApiRequestAtMs = SystemClock.elapsedRealtime() + MIN_API_REQUEST_INTERVAL_MS
        }
    }

    private fun holdApiRequestsFor(durationMs: Long) {
        synchronized(API_REQUEST_LOCK) {
            nextApiRequestAtMs = maxOf(
                nextApiRequestAtMs,
                SystemClock.elapsedRealtime() + durationMs,
            )
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val maxBytes = MAX_JSON_BYTES
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw LibraryNetworkException("Jimaku answered with a suspiciously large response.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private val API_REQUEST_LOCK = Any()
        private var nextApiRequestAtMs = 0L
        private const val MIN_API_REQUEST_INTERVAL_MS = 1_200L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_RATE_LIMIT_RETRY_MS = 60_000L
        private const val MIN_RATE_LIMIT_RETRY_MS = 15_000L
        private const val MAX_RATE_LIMIT_RETRY_MS = 60 * 60 * 1_000L
        private const val TIMEOUT_MS = 15_000
        private const val MAX_JSON_BYTES = 8 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
    }
}

fun parseJimakuEpisodeNumber(fileName: String): Int? {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    val explicitPatterns = listOf(
        Regex("""(?i)\bS\d{1,2}\s*[-._ ]?\s*EP?[\s._-]*(\d{1,3})\b"""),
        Regex("""(?i)\b(?:episode|ep)[\s._-]*(\d{1,3})\b"""),
        Regex("""(?i)\b\d{1,2}\s*[xX]\s*(\d{1,3})\b"""),
        Regex("""第\s*(\d{1,3})\s*[話回]"""),
        Regex("""(?i)(?<![\p{L}\p{N}])#\s*(\d{1,3})(?!\d)"""),
        Regex("""(?i)(?<![\p{L}\p{N}])E\s*(\d{1,3})(?!\d)"""),
        Regex("""(?i)\b(?:season|s)[\s._-]*\d{1,2}[\s._-]+(?:(?:episode|ep)[\s._-]*)?(\d{1,3})\b"""),
    )
    val genericPatterns = listOf(
        // Prefer separated episode numbers so numeric words in a title are not mistaken for episodes.
        Regex("""(?<=[\s_\-\[])(\d{1,3})(?:v\d+)?(?=$|[\s._\-)\]])"""),
        // Some releases are named only by their number, such as 01.ass.
        Regex("""^\s*(\d{1,3})(?:v\d+)?(?=$|[._\-\s])"""),
    )

    for (pattern in explicitPatterns) {
        for (match in pattern.findAll(base)) {
            val number = match.groupValues[1]
            val episode = number.toIntOrNull() ?: continue
            val numberStart = match.groups[1]?.range?.first ?: match.range.first
            if (episode !in 1..300 || isYearOrResolution(base, numberStart, number)) continue
            return episode
        }
    }
    return genericPatterns.asSequence()
        .flatMap { pattern -> pattern.findAll(base).asSequence() }
        .mapNotNull { match ->
            val number = match.groupValues[1]
            val episode = number.toIntOrNull() ?: return@mapNotNull null
            val numberStart = match.groups[1]?.range?.first ?: match.range.first
            if (episode !in 1..300 || isYearOrResolution(base, numberStart, number)) null
            else numberStart to episode
        }
        .maxByOrNull { it.first }
        ?.second
}

fun filterJimakuFilesByEpisode(files: List<JimakuFile>, episode: Int): List<JimakuFile> =
    files.filter { isUsableJimakuSubtitleFile(it) && parseJimakuSingleEpisodeNumber(it.name) == episode }

private fun JimakuFile.containsEpisode(episode: Int): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val availabilityExtensions = SubtitleParser.SUPPORTED_EXTENSIONS + setOf("zip", "rar", "7z")
    if (extension !in availabilityExtensions || size?.let { it <= 0L } == true) return false
    val range = parseJimakuEpisodeRange(name)
    return if (range != null) episode in range else parseJimakuEpisodeNumber(name) == episode
}

private fun JimakuFile.isAvailabilityCandidate(): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val extensions = SubtitleParser.SUPPORTED_EXTENSIONS + setOf("zip", "rar", "7z")
    return extension in extensions && size?.let { it > 0L } != false
}

private fun JimakuFile.latestEpisodeNumber(): Int? =
    parseJimakuEpisodeRange(name)?.last ?: parseJimakuEpisodeNumber(name)

private fun parseJimakuEpisodeRange(fileName: String): IntRange? {
    val datePattern = Regex("""(?<!\d)(?:19|20)\d{2}[-._]\d{1,2}[-._]\d{1,2}(?!\d)""")
    val normalized = datePattern.replace(fileName, " ")
        .replace(Regex("""(?i)\bE(?=\d)"""), " ")
    val rangePattern = Regex(
        """(?i)(?<![\p{L}\p{N}])0*(\d{1,3})\s*(?:[-~–—]|\bto\b|\bthrough\b|[&,+])\s*0*(\d{1,3})(?!\d)""",
    )
    return rangePattern.findAll(normalized).firstNotNullOfOrNull { match ->
        val start = match.groupValues[1].toIntOrNull() ?: return@firstNotNullOfOrNull null
        val end = match.groupValues[2].toIntOrNull() ?: return@firstNotNullOfOrNull null
        if (start in 1..300 && end in start..300) start..end else null
    }
}

internal fun isUsableJimakuSubtitleFile(file: JimakuFile): Boolean {
    val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val validSize = file.size?.let { it in 1L..SubtitleParser.MAX_FILE_BYTES.toLong() } ?: true
    return extension in SubtitleParser.SUPPORTED_EXTENSIONS && validSize
}

fun parseJimakuSingleEpisodeNumber(fileName: String): Int? {
    if (isMultiEpisodeRange(fileName)) return null
    return parseJimakuEpisodeNumber(fileName)
}

private fun isMultiEpisodeRange(fileName: String): Boolean {
    val datePattern = Regex("""(?<!\d)(?:19|20)\d{2}[-._]\d{1,2}[-._]\d{1,2}(?!\d)""")
    val withoutDates = datePattern.replace(fileName, " ")
        .replace(Regex("""(?i)\bE(?=\d)"""), " ")

    val rangePattern =
        Regex("""(?i)(?<![\p{L}\p{N}])0*(\d{1,3})\s*(?:[-~–—]|\bto\b|\bthrough\b|[&,+])\s*0*(\d{1,3})(?!\d)""")
    return rangePattern.findAll(withoutDates).any { range ->
        val first = range.groupValues[1].toIntOrNull()
        val last = range.groupValues[2].toIntOrNull()
        first != null && last != null && first in 1..300 && last in 1..300 && first < last
    }
}

private fun isYearOrResolution(base: String, numberStart: Int, number: String): Boolean {
    if (number.length == 4 || number in setOf("1080", "720", "480")) return true
    val before = base.getOrNull(numberStart - 1)?.toString().orEmpty()
    val after = base.getOrNull(numberStart + number.length)?.toString().orEmpty()
    if (before == "(" && after == ")") return true
    if (before.any(Char::isDigit) || after.any(Char::isDigit)) return true
    return after.equals("p", ignoreCase = true)
}
