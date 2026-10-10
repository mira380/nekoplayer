package com.nekoplayer.app.library

import androidx.core.net.toUri

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// The URI stays readable through the saved tree permission; open it with ContentResolver.
data class AnimeLibraryEpisode(
    val uri: Uri,
    val displayName: String,
    val showName: String,
    val showUri: Uri,
)

data class AniListMatch(val id: Int, val title: String)
data class AniListCandidate(val id: Int, val title: String, val score: Double)

data class JimakuEntry(
    val id: Int,
    val englishName: String,
    val japaneseName: String,
) {
    val displayName: String
        get() {
            val english = englishName.trim()
            val japanese = japaneseName.trim()
            return when {
                english.isEmpty() -> japanese
                japanese.isEmpty() || japanese == english -> english
                else -> "$english | $japanese"
            }
        }
}

data class JimakuFile(val name: String, val url: String, val size: Long?)

class LibraryNetworkException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

// Walks the SAF tree synchronously. Call this from Dispatchers.IO.
class AnimeLibraryScanner {
    fun scan(root: DocumentFile): List<AnimeLibraryEpisode> {
        if (!root.isDirectory || !root.canRead()) {
            throw IOException("The selected library folder is unavailable.")
        }
        val videos = mutableListOf<Pair<DocumentFile, List<String>>>()
        val stack = ArrayDeque<Pair<DocumentFile, List<String>>>()
        stack.add(root to emptyList())
        while (stack.isNotEmpty()) {
            val (directory, parts) = stack.removeLast()
            for (child in directory.listFiles()) {
                if (child.isDirectory) {
                    val name = child.name?.takeIf(String::isNotBlank) ?: continue
                    stack.add(child to (parts + name))
                } else if (child.isFile && isVideoName(child.name.orEmpty())) {
                    videos += child to parts
                }
            }
        }
        videos.sortBy { it.first.name.orEmpty().lowercase(Locale.ROOT) + "\u0000" + it.first.uri.toString().lowercase(Locale.ROOT) }
        val firstLevelFolders = videos.mapNotNull { it.second.firstOrNull() }.toSet()
        val rootIsOneShow = firstLevelFolders.isNotEmpty() && firstLevelFolders.all(::looksLikeSeasonFolder)
        val rootName = root.name?.takeIf(String::isNotBlank) ?: "Anime"
        return videos.map { (file, parts) ->
            val showAtRoot = rootIsOneShow || parts.isEmpty()
            val showName = if (showAtRoot) rootName else parts.first()
            val showUri = if (showAtRoot) root.uri else findTopLevelChild(root, parts.first())?.uri ?: root.uri
            AnimeLibraryEpisode(
                uri = file.uri,
                displayName = file.name.orEmpty().substringBeforeLast('.', file.name.orEmpty()),
                showName = showName,
                showUri = showUri,
            )
        }
    }

    private fun findTopLevelChild(root: DocumentFile, name: String): DocumentFile? =
        root.listFiles().firstOrNull { it.isDirectory && it.name == name }

    companion object {
        val videoExtensions = setOf("mp4", "mkv", "mov", "m4v", "avi", "webm", "mpg", "mpeg", "ts", "ogv")

        fun isVideoName(name: String): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return extension in videoExtensions
        }

        fun looksLikeSeasonFolder(name: String): Boolean =
            Regex("^(?:season|series|s)\\s*[-_ ]?\\d+$|^(?:ova|specials?)$", RegexOption.IGNORE_CASE)
                .matches(name.trim())
    }
}

// Network calls are synchronous, so run this client off the main thread.
class AniListClient(context: Context) {
    private val cacheRoot = File(context.cacheDir, "anilist-covers-v2")
    private val apiLock = Any()
    @Volatile private var lastApiRequestAt = 0L

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    fun coverFor(showName: String, episodeFileNames: Iterable<String> = emptyList()): File? {
        val queries = searchQueries(showName, episodeFileNames)
        if (queries.isEmpty()) return null
        cacheRoot.mkdirs()
        val key = stableHash(queries.joinToString("|") { normalize(it) })
        val imageFile = File(cacheRoot, "$key.image")
        if (imageFile.isFile && imageFile.length() > 0L) {
            if (System.currentTimeMillis() - imageFile.lastModified() < IMAGE_LIFETIME_MS) return imageFile
            imageFile.delete()
        }
        // Reuse covers saved under the earlier cache key when they're still fresh.
        val old = File(File(contextCacheParent(), "anilist-covers"), "${stableHash(queries.first().lowercase(Locale.ROOT))}.image")
        if (old.isFile && old.length() > 0L && System.currentTimeMillis() - old.lastModified() < IMAGE_LIFETIME_MS) return old
        val miss = File(cacheRoot, "$key.miss")
        val missAt = miss.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()
        if (missAt != null && System.currentTimeMillis() - missAt < MISS_LIFETIME_MS) return null
        if (miss.exists()) miss.delete()

        var allSucceeded = true
        var bestScore = 0.0
        var bestUrl: String? = null
        for (query in queries) {
            val result = try {
                lookupCover(query)
            } catch (_: Exception) {
                allSucceeded = false
                continue
            }
            if (!result.succeeded) allSucceeded = false
            if (result.score > bestScore && result.url != null) {
                bestScore = result.score
                bestUrl = result.url
            }
            if (bestScore >= STRONG_MATCH_SCORE) break
        }
        if (bestUrl == null || bestScore < MINIMUM_MATCH_SCORE) {
            if (allSucceeded) miss.writeText(System.currentTimeMillis().toString())
            return null
        }
        val bytes = try { downloadImage(bestUrl) } catch (_: Exception) { null } ?: return null
        if (bytes.isEmpty()) return null
        imageFile.writeBytes(bytes)
        return imageFile
    }

    fun findAnimeId(showName: String, episodeFileNames: Iterable<String> = emptyList()): AniListMatch? {
        val best = searchAnimeCandidates(showName, episodeFileNames).firstOrNull() ?: return null
        return AniListMatch(best.id, best.title)
    }

    fun searchAnimeCandidates(
        showName: String,
        episodeFileNames: Iterable<String> = emptyList(),
        minimumMatchScore: Double = MINIMUM_MATCH_SCORE,
    ): List<AniListCandidate> {
        val queries = searchQueries(showName, episodeFileNames)
        if (queries.isEmpty()) return emptyList()
        val candidates = linkedMapOf<Int, AniListCandidate>()
        for (query in queries) {
            val media = try { lookupAnime(query) } catch (_: Exception) { continue }
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index) ?: continue
                val id = item.optInt("id", -1)
                val titles = item.optJSONObject("title") ?: continue
                val names = listOf("english", "romaji", "native")
                    .map { titles.optString(it) }
                    .filter { it.isNotBlank() }
                if (id <= 0 || names.isEmpty()) continue
                val score = names.maxOf { aniListSearchTitleScore(query, it) }
                if (score < minimumMatchScore.coerceIn(0.0, 1.0)) continue
                val current = candidates[id]
                if (current == null || score > current.score) {
                    candidates[id] = AniListCandidate(id, names.first(), score)
                }
            }
        }
        return candidates.values.sortedByDescending { it.score }.take(MAX_CANDIDATES)
    }

    private fun contextCacheParent(): File = cacheRoot.parentFile ?: cacheRoot

    private data class CoverLookup(val succeeded: Boolean, val score: Double, val url: String?)

    private fun lookupCover(search: String): CoverLookup = apiRequest {
        val query = "query LibraryShowCover(${'$'}search: String!) { Page(page: 1, perPage: 25) { media(search: ${'$'}search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) { title { romaji english native } coverImage { extraLarge large medium } } } }"
        val response = postGraphQl(query, search)
        if (response == null) return@apiRequest CoverLookup(false, 0.0, null)
        val media = response.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return@apiRequest CoverLookup(false, 0.0, null)
        var bestScore = 0.0
        var bestUrl: String? = null
        for (index in 0 until media.length()) {
            val item = media.optJSONObject(index) ?: continue
            val titles = item.optJSONObject("title") ?: continue
            val image = item.optJSONObject("coverImage") ?: continue
            val score = listOf("english", "romaji", "native")
                .map { titles.optString(it) }
                .filter(String::isNotBlank)
                .maxOfOrNull { titleMatchScore(search, it) } ?: 0.0
            val url = listOf("extraLarge", "large", "medium").map { image.optString(it) }.firstOrNull { it.isNotBlank() }
            if (score > bestScore && url != null) {
                bestScore = score
                bestUrl = url
            }
        }
        CoverLookup(true, bestScore, bestUrl)
    }

    private fun lookupAnime(search: String): JSONArray = apiRequest {
        val query = "query LibraryShowId(${'$'}search: String!) { Page(page: 1, perPage: 10) { media(search: ${'$'}search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) { id title { romaji english native } } } }"
        val response = postGraphQl(query, search) ?: return@apiRequest JSONArray()
        response.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
    }

    private fun postGraphQl(query: String, search: String): JSONObject? {
        val connection = URL(ANILIST_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "nekoplayer/1.0")
            val body = JSONObject().put("query", query).put("variables", JSONObject().put("search", search)).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) return null
            val bytes = stream.use { readBounded(it, MAX_RESPONSE_BYTES) }
            if (status != HttpURLConnection.HTTP_OK) return null
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadImage(value: String): ByteArray? {
        val url = URL(value)
        if (url.protocol != "https") return null
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode != HttpURLConnection.HTTP_OK || !connection.contentType.orEmpty().substringBefore(';').startsWith("image/")) return null
            return connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> apiRequest(block: () -> T): T = synchronized(apiLock) {
        val now = android.os.SystemClock.elapsedRealtime()
        val wait = MINIMUM_REQUEST_INTERVAL_MS - (now - lastApiRequestAt)
        if (lastApiRequestAt != 0L && wait > 0) Thread.sleep(wait)
        lastApiRequestAt = android.os.SystemClock.elapsedRealtime()
        block()
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        BufferedInputStream(input).use { stream ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw LibraryNetworkException("AniList answered with a suspiciously large response.")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun searchQueries(showName: String, episodeFileNames: Iterable<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun add(value: String) {
            val query = cleanSearchTitle(value)
            val key = normalize(query)
            if (key.length >= 3 && seen.add(key) && result.size < MAX_SEARCH_QUERIES) result += query
        }
        add(showName)
        for (filename in episodeFileNames) {
            if (result.size >= MAX_SEARCH_QUERIES) break
            episodeSeriesTitle(filename)?.let(::add)
        }
        return result
    }

    private fun episodeSeriesTitle(title: String): String? {
        val withoutGroup = title.replaceFirst(Regex("^\\s*\\[[^]]+]\\s*"), "")
        val cleaned = cleanSearchTitle(withoutGroup)
        val patterns = listOf(
            Regex("\\s+S\\d{1,2}E\\d{1,3}\\b.*$", RegexOption.IGNORE_CASE),
            Regex("\\s+(?:-|–|—|_)\\s*(?:S\\d{1,2}\\s*E\\d{1,3}|\\d{1,3}(?:v\\d+)?)(?:\\s*(?:-|–|—|_).*)?$", RegexOption.IGNORE_CASE),
            Regex("\\s+(?:episode|ep)\\s*\\d{1,3}\\b.*$", RegexOption.IGNORE_CASE),
            Regex("\\s+\\d{1,3}(?:v\\d+)?$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(cleaned)
            if (match != null && match.range.first >= 3) return cleaned.substring(0, match.range.first)
        }
        val parts = cleaned.split(Regex("\\s+(?:-|–|—)\\s+"))
        return parts.firstOrNull()?.takeIf { parts.size > 1 && it.length >= 3 }
    }

    private fun cleanSearchTitle(title: String): String = title
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
        .replace(Regex("_+"), " ")
        .replace(Regex("\\b(?:480|720|1080|2160)p\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\b(?:webrip|web-dl|bluray|bdrip|h264|h265|x264|x265)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun aniListSearchTitleScore(search: String, candidate: String): Double {
        val query = normalize(search)
        val result = normalize(candidate)
        val baseScore = titleMatchScore(search, candidate)
        if (query.isNotEmpty() && result.startsWith("$query ")) return maxOf(baseScore, PREFIX_TITLE_SCORE)
        if (result.isNotEmpty() && query.startsWith("$result ")) return maxOf(baseScore, PREFIX_TITLE_SCORE)
        return baseScore
    }

    private fun titleMatchScore(search: String, candidate: String): Double {
        val a = normalize(search)
        val b = normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (b.startsWith("$a ") && words(a).size >= 2) return 0.75
        if (b.contains(a) || a.contains(b)) return minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        val aw = words(a)
        val bw = words(b)
        if (aw.isEmpty() || bw.isEmpty()) return 0.0
        val shared = aw.intersect(bw).size
        return 2.0 * shared / (aw.size + bw.size)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\w\\u0080-\\uFFFF]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun words(value: String): Set<String> = normalize(value).split(' ').filter { it.length > 1 && it !in COMMON_WORDS }.toSet()

    private fun stableHash(value: String): String {
        var hash = 0x811c9dc5L
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = ((hash xor (byte.toLong() and 0xff)) * 0x01000193L) and 0xffffffffL
        }
        return hash.toString(16).padStart(8, '0')
    }

    companion object {
        private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
        private const val MAX_SEARCH_QUERIES = 4
        private const val MAX_CANDIDATES = 10
        private const val MINIMUM_MATCH_SCORE = 0.42
        private const val PREFIX_TITLE_SCORE = 0.75
        private const val STRONG_MATCH_SCORE = 0.88
        private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
        private const val IMAGE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
        private const val MISS_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        private const val MINIMUM_REQUEST_INTERVAL_MS = 2100L
        private val COMMON_WORDS = setOf("a", "an", "and", "are", "at", "by", "for", "from", "in", "is", "of", "on", "or", "the", "to", "with")
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
        if (body !is JSONArray) throw LibraryNetworkException("Jimaku answered in an unexpected format.")
        return (0 until body.length()).mapNotNull { index ->
            val item = body.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", -1L)
            if (id < 0 || id > Int.MAX_VALUE) return@mapNotNull null
            JimakuEntry(id.toInt(), item.optString("english_name"), item.optString("japanese_name"))
        }
    }

    @Throws(LibraryNetworkException::class)
    fun listFiles(apiKey: String, entryId: Int): List<JimakuFile> {
        requireKey(apiKey)
        val body = getJson("$baseUrl/api/entries/$entryId/files", apiKey, "Listing Jimaku files")
        if (body !is JSONArray) throw LibraryNetworkException("Jimaku answered in an unexpected format.")
        return (0 until body.length()).mapNotNull { index ->
            val item = body.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val url = item.optString("url")
            if (name.isBlank() || url.isBlank()) return@mapNotNull null
            JimakuFile(name, url, if (item.has("size") && !item.isNull("size")) item.optLong("size").takeIf { it >= 0 } else null)
        }
    }

    // Reuse cached downloads for both automatic lookup and the manual picker.
    @Throws(LibraryNetworkException::class)
    fun downloadToCache(file: JimakuFile, entryId: Int): File {
        val safeName = file.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "subtitle.srt" }
        val destination = File(File(cacheDirectory, "jimaku"), "${entryId}_$safeName")
        if (destination.isFile && destination.length() > 0L) return destination
        val uri = try { file.url.toUri() } catch (error: Exception) {
            throw LibraryNetworkException("Jimaku returned an unusable download link.", cause = error)
        }
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
            throw LibraryNetworkException("Jimaku returned an unusable download link.")
        }
        destination.parentFile?.mkdirs()
        val connection = URL(file.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw LibraryNetworkException("Jimaku refused the download (HTTP ${connection.responseCode}).", retryable = true)
            }
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > MAX_DOWNLOAD_BYTES) throw LibraryNetworkException("Jimaku returned a suspiciously large response.")
            var total = 0L
            try {
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES) throw LibraryNetworkException("Jimaku returned a suspiciously large response.")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: Exception) {
                destination.delete()
                if (error is LibraryNetworkException) throw error
                throw LibraryNetworkException("The subtitle could not be downloaded. Check the connection and try again.", retryable = true, cause = error)
            }
            if (total == 0L) {
                destination.delete()
                throw LibraryNetworkException("Jimaku returned an empty subtitle file.")
            }
            return destination
        } catch (error: LibraryNetworkException) {
            if (destination.exists() && destination.length() == 0L) destination.delete()
            throw error
        } catch (error: Exception) {
            destination.delete()
            throw LibraryNetworkException("The subtitle could not be downloaded. Check the connection and try again.", retryable = true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, apiKey: String, operation: String): Any {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", apiKey.trim())
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw LibraryNetworkException("Jimaku rejected the API key. Check it in Settings.")
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw LibraryNetworkException("$operation failed (HTTP $status).", retryable = true)
            }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (length > MAX_JSON_BYTES) throw LibraryNetworkException("Jimaku answered with a suspiciously large response.")
            val bytes = connection.inputStream.use { readBounded(it, MAX_JSON_BYTES) }
            return org.json.JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()
        } catch (error: LibraryNetworkException) {
            throw error
        } catch (error: Exception) {
            throw LibraryNetworkException("$operation failed. Check the connection and try again.", retryable = true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireKey(apiKey: String) {
        if (apiKey.isBlank()) throw LibraryNetworkException("Add your Jimaku API key in Settings → Subtitles first.")
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
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
        private const val TIMEOUT_MS = 15_000
        private const val MAX_JSON_BYTES = 8 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
    }
}

fun parseJimakuEpisodeNumber(fileName: String): Int? {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    val patterns = listOf(
        Regex("[Ss]\\d{1,2}[Ee](\\d{1,3})\\b"),
        Regex("\\b[Ee](\\d{1,3})\\b"),
        Regex("[\\s_\\-\\[]([0-9]{1,3})[vV]\\d+\\b"),
        Regex("\\b[Ee]p(?:isode)?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
        Regex("第(\\d{1,3})話"),
        Regex("[\\s_\\-\\[]([0-9]{1,3})\\s*(?:[\\s_\\-\\].]|${'$'})"),
    )
    for (pattern in patterns) {
        val match = pattern.find(base) ?: continue
        val episode = match.groupValues[1].toIntOrNull() ?: continue
        if (episode !in 1..300 || isYearOrResolution(base, match, match.groupValues[1])) continue
        return episode
    }
    return null
}

fun filterJimakuFilesByEpisode(files: List<JimakuFile>, episode: Int): List<JimakuFile> {
    val exactPattern = Regex("(?:^|[^0-9])(?:E$episode\\b|EP?${episode.toString().padStart(2, '0')}\\b|${episode.toString().padStart(2, '0')}(?![0-9])|${episode.toString().padStart(3, '0')}(?![0-9])|第${episode}話|#${episode}\\b)", RegexOption.IGNORE_CASE)
    val exact = files.filter { exactPattern.containsMatchIn(it.name) }
    if (exact.isNotEmpty()) return exact
    val loose = files.filter { it.name.contains(episode.toString()) }
    return loose.ifEmpty { files }
}

fun jimakuShowTitleFromUriSegments(segments: List<String>, fallback: String): String {
    if (segments.size < 2) return fallback
    var folder = segments[segments.lastIndex - 1].trim()
    if (Regex("^(?:season|s|series|cour|part)\\s*\\d+$|^\\d+\\s*(?:st|nd|rd|th)?\\s*season$|^(?:ova|ovas|specials?|extras?|movies?)$", RegexOption.IGNORE_CASE).matches(folder) && segments.size >= 3) {
        folder = segments[segments.lastIndex - 2].trim()
    }
    if (folder.isEmpty() || AnimeLibraryScanner.looksLikeSeasonFolder(folder) || folder.lowercase(Locale.ROOT) in setOf("download", "downloads", "video", "videos", "movie", "movies", "anime", "tv", "shows", "media", "storage", "emulated", "sdcard")) return fallback
    return folder
}

private fun isYearOrResolution(base: String, match: MatchResult, number: String): Boolean {
    if (number.length == 4) return true
    val before = base.getOrNull(match.range.first - 1)?.toString().orEmpty()
    val after = base.getOrNull(match.range.last + 1)?.toString().orEmpty()
    if (before == "(" && after == ")") return true
    if (before.any(Char::isDigit) || after.any(Char::isDigit)) return true
    if ((before.equals("p", true)) && (after.isEmpty() || after == ".")) return true
    return number in setOf("1080", "720", "480")
}
