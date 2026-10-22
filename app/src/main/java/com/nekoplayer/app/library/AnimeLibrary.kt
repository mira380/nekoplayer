package com.nekoplayer.app.library

import androidx.core.net.toUri

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nekoplayer.app.media.SubtitleParser
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
data class AniListAiringEpisode(
    val episode: Int,
    val airingAtEpochSeconds: Long,
    val aniListId: Int? = null,
)

data class AniListLibraryMatch(
    val aniListId: Int,
    val nextAiringEpisode: AniListAiringEpisode?,
)

data class AniListAiringLookup(
    val key: String,
    val showName: String,
    val episodeFileNames: List<String> = emptyList(),
)

data class AniListCandidate(
    val id: Int,
    val title: String,
    val score: Double,
    val aliases: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val nextAiringEpisode: AniListAiringEpisode? = null,
)

class LibraryNetworkException(
    message: String,
    @Suppress("unused") val retryable: Boolean = false,
    cause: Throwable? = null,
    val retryAfterMs: Long? = null,
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
    private val cachePruneLock = Any()
    private var expiredCoverCachePruned = false
    private val airingScheduleCache = mutableMapOf<String, Pair<Long, AniListAiringEpisode?>>()
    @Volatile private var lastApiRequestAt = 0L

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    fun coverFor(showName: String, episodeFileNames: Iterable<String> = emptyList()): File? {
        val queries = searchQueries(showName, episodeFileNames)
        if (queries.isEmpty()) return null
        cacheRoot.mkdirs()
        pruneExpiredCoverCache()
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

    @Suppress("unused")
    fun findAnimeId(showName: String, episodeFileNames: Iterable<String> = emptyList()): AniListMatch? {
        val best = searchAnimeCandidates(showName, episodeFileNames).firstOrNull() ?: return null
        return AniListMatch(best.id, best.title)
    }

    fun searchAnimeCandidates(
        showName: String,
        episodeFileNames: Iterable<String> = emptyList(),
        minimumMatchScore: Double = MINIMUM_MATCH_SCORE,
        alternateTitles: Iterable<String> = emptyList(),
    ): List<AniListCandidate> {
        val queries = searchQueries(showName, episodeFileNames, alternateTitles)
        if (queries.isEmpty()) return emptyList()
        val candidates = linkedMapOf<Int, AniListCandidate>()
        for (query in queries) {
            val media = try { lookupAnime(query) } catch (_: Exception) { continue }
            parseAnimeCandidates(query, media, minimumMatchScore).forEach { candidate ->
                mergeAnimeCandidate(candidates, candidate)
            }
            if (candidates.values.any {
                    it.score >= STRONG_MATCH_SCORE && it.nextAiringEpisode != null
                }
            ) {
                break
            }
        }
        return candidates.values.sortedByDescending(AniListCandidate::score).take(MAX_CANDIDATES)
    }

    private fun parseAnimeCandidates(
        search: String,
        media: JSONArray,
        minimumMatchScore: Double,
    ): List<AniListCandidate> {
        val candidates = linkedMapOf<Int, AniListCandidate>()
        for (index in 0 until media.length()) {
            val item = media.optJSONObject(index) ?: continue
            val id = item.optInt("id", -1)
            val titles = item.optJSONObject("title") ?: continue
            val aliases = buildList {
                addAll(
                    listOf("english", "romaji", "native")
                        .map { titles.optString(it) }
                        .filter(String::isNotBlank),
                )
                val synonyms = item.optJSONArray("synonyms")
                if (synonyms != null) {
                    for (synonymIndex in 0 until synonyms.length()) {
                        synonyms.optString(synonymIndex)
                            .takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }
            }.distinct()
            if (id <= 0 || aliases.isEmpty()) continue
            val score = aliases.maxOf { aniListSearchTitleScore(search, it) }
            if (score < minimumMatchScore.coerceIn(0.0, 1.0)) continue
            val nextAiringEpisode = item.optJSONObject("nextAiringEpisode")?.let { airing ->
                val episode = airing.optInt("episode", -1)
                val airingAt = airing.optLong("airingAt", 0L)
                if (episode > 0 && airingAt > 0L) AniListAiringEpisode(episode, airingAt, id) else null
            }
            candidates[id] = AniListCandidate(
                id = id,
                title = aliases.first(),
                score = score,
                aliases = aliases,
                episodeCount = item.optInt("episodes").takeIf { it > 0 },
                nextAiringEpisode = nextAiringEpisode,
            )
        }
        return candidates.values.sortedByDescending(AniListCandidate::score).take(MAX_CANDIDATES)
    }

    private fun mergeAnimeCandidate(
        target: MutableMap<Int, AniListCandidate>,
        incoming: AniListCandidate,
    ) {
        val current = target[incoming.id]
        val aliases = (current?.aliases.orEmpty() + incoming.aliases).distinct()
        target[incoming.id] = if (current == null || incoming.score > current.score) {
            incoming.copy(
                aliases = aliases,
                nextAiringEpisode = incoming.nextAiringEpisode ?: current?.nextAiringEpisode,
            )
        } else {
            current.copy(
                aliases = aliases,
                nextAiringEpisode = current.nextAiringEpisode ?: incoming.nextAiringEpisode,
            )
        }
    }

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    fun findNextAiringEpisode(
        showName: String,
        episodeFileNames: Iterable<String> = emptyList(),
    ): AniListAiringEpisode? {
        val normalizedShowName = normalize(showName)
        if (normalizedShowName.isBlank()) return null
        val searchFileNames = episodeFileNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
        val cacheKey = buildString {
            append(normalizedShowName)
            searchFileNames.forEach { append('|').append(normalize(it)) }
        }
        val now = System.currentTimeMillis()
        val cached = synchronized(airingScheduleCache) { airingScheduleCache[cacheKey] }
        val cachedEpisode = cached?.second
        if (
            cached != null &&
            now - cached.first < AIRING_CACHE_LIFETIME_MS &&
            (cachedEpisode == null || cachedEpisode.airingAtEpochSeconds * 1_000L > now)
        ) {
            return cachedEpisode
        }

        val result = searchAnimeCandidates(
            showName = showName,
            episodeFileNames = searchFileNames,
            minimumMatchScore = STRONG_MATCH_SCORE,
        ).firstOrNull()?.nextAiringEpisode
        synchronized(airingScheduleCache) {
            airingScheduleCache[cacheKey] = now to result
        }
        return result
    }

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    fun findNextAiringEpisodes(
        lookups: Iterable<AniListAiringLookup>,
    ): Map<String, AniListAiringEpisode> {
        val now = System.currentTimeMillis()

        data class PendingLookup(
            val cacheKey: String,
            val queries: List<String>,
            val itemKeys: MutableList<String> = mutableListOf(),
            val candidates: MutableMap<Int, AniListCandidate> = linkedMapOf(),
        )

        val results = linkedMapOf<String, AniListAiringEpisode>()
        val pendingByCacheKey = linkedMapOf<String, PendingLookup>()
        for (lookup in lookups) {
            val normalizedShowName = normalize(lookup.showName)
            if (lookup.key.isBlank() || normalizedShowName.isBlank()) continue
            val episodeFileNames = lookup.episodeFileNames
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(2)
            val cacheKey = buildString {
                append(normalizedShowName)
                episodeFileNames.forEach { append('|').append(normalize(it)) }
            }
            val cached = synchronized(airingScheduleCache) { airingScheduleCache[cacheKey] }
            val cachedEpisode = cached?.second
            if (
                cached != null &&
                now - cached.first < AIRING_CACHE_LIFETIME_MS &&
                (cachedEpisode == null || cachedEpisode.airingAtEpochSeconds * 1_000L > now)
            ) {
                cachedEpisode?.let { results[lookup.key] = it }
                continue
            }

            val queries = searchQueries(lookup.showName, episodeFileNames)
            if (queries.isEmpty()) continue
            val pending = pendingByCacheKey.getOrPut(cacheKey) {
                PendingLookup(cacheKey = cacheKey, queries = queries)
            }
            pending.itemKeys += lookup.key
        }
        if (pendingByCacheKey.isEmpty()) return results

        val queryTexts = linkedMapOf<String, String>()
        val queryOwners = linkedMapOf<String, MutableSet<String>>()
        pendingByCacheKey.values.forEach { pending ->
            pending.queries.forEach { query ->
                val normalizedQuery = normalize(query)
                if (normalizedQuery !in queryTexts) queryTexts[normalizedQuery] = query
                queryOwners.getOrPut(normalizedQuery) { linkedSetOf() } += pending.cacheKey
            }
        }

        queryTexts.values.toList().chunked(MAX_AIRING_BATCH_QUERIES).forEach { batch ->
            val mediaByQuery = lookupAnimeBatch(batch)
            batch.forEach queryLoop@{ query ->
                val normalizedQuery = normalize(query)
                val media = mediaByQuery[normalizedQuery] ?: return@queryLoop
                val matches = parseAnimeCandidates(query, media, STRONG_MATCH_SCORE)
                queryOwners[normalizedQuery].orEmpty().forEach ownerLoop@{ cacheKey ->
                    val pending = pendingByCacheKey[cacheKey] ?: return@ownerLoop
                    matches.forEach { mergeAnimeCandidate(pending.candidates, it) }
                }
            }
        }

        pendingByCacheKey.values.forEach { pending ->
            val airingEpisode = pending.candidates.values
                .maxByOrNull(AniListCandidate::score)
                ?.nextAiringEpisode
            synchronized(airingScheduleCache) {
                airingScheduleCache[pending.cacheKey] = now to airingEpisode
            }
            airingEpisode?.let { episode ->
                pending.itemKeys.forEach { key -> results[key] = episode }
            }
        }
        return results
    }

    /** Resolves strong AniList matches in batches so the Library can reuse IDs for Jimaku lookups. */
    fun findLibraryMatches(
        lookups: Iterable<AniListAiringLookup>,
    ): Map<String, AniListLibraryMatch> {
        data class PendingLookup(
            val queries: List<String>,
            val itemKeys: MutableList<String> = mutableListOf(),
            val candidates: MutableMap<Int, AniListCandidate> = linkedMapOf(),
        )

        val pendingBySignature = linkedMapOf<String, PendingLookup>()
        lookups.forEach { lookup ->
            if (lookup.key.isBlank() || normalize(lookup.showName).isBlank()) return@forEach
            val fileNames = lookup.episodeFileNames.map(String::trim).filter(String::isNotBlank).take(2)
            val signature = buildString {
                append(normalize(lookup.showName))
                fileNames.forEach { append('|').append(normalize(it)) }
            }
            val queries = searchQueries(lookup.showName, fileNames)
            if (queries.isEmpty()) return@forEach
            pendingBySignature.getOrPut(signature) { PendingLookup(queries) }.itemKeys += lookup.key
        }
        if (pendingBySignature.isEmpty()) return emptyMap()

        val queryTexts = linkedMapOf<String, String>()
        val queryOwners = linkedMapOf<String, MutableSet<String>>()
        pendingBySignature.forEach { (signature, pending) ->
            pending.queries.forEach { query ->
                val normalizedQuery = normalize(query)
                queryTexts.putIfAbsent(normalizedQuery, query)
                queryOwners.getOrPut(normalizedQuery) { linkedSetOf() } += signature
            }
        }
        queryTexts.values.toList().chunked(MAX_AIRING_BATCH_QUERIES).forEach { batch ->
            val mediaByQuery = lookupAnimeBatch(batch)
            batch.forEach queryLoop@{ query ->
                val matches = mediaByQuery[normalize(query)]?.let {
                    parseAnimeCandidates(query, it, STRONG_MATCH_SCORE)
                }.orEmpty()
                queryOwners[normalize(query)].orEmpty().forEach ownerLoop@{ signature ->
                    val pending = pendingBySignature[signature] ?: return@ownerLoop
                    matches.forEach { mergeAnimeCandidate(pending.candidates, it) }
                }
            }
        }

        return buildMap {
            pendingBySignature.values.forEach { pending ->
                val best = pending.candidates.values.maxByOrNull(AniListCandidate::score) ?: return@forEach
                val match = AniListLibraryMatch(best.id, best.nextAiringEpisode)
                pending.itemKeys.forEach { key -> put(key, match) }
            }
        }
    }

    private fun contextCacheParent(): File = cacheRoot.parentFile ?: cacheRoot

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    private fun pruneExpiredCoverCache() {
        synchronized(cachePruneLock) {
            if (expiredCoverCachePruned) return
            val now = System.currentTimeMillis()
            val roots = listOf(cacheRoot, File(contextCacheParent(), "anilist-covers"))
            roots.forEach { directory ->
                directory.listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    when {
                        file.name.endsWith(".image", ignoreCase = true) &&
                            (file.length() == 0L || now - file.lastModified() >= IMAGE_LIFETIME_MS) -> {
                            file.delete()
                        }
                        file.name.endsWith(".miss", ignoreCase = true) -> {
                            val cachedAt = runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
                            if (cachedAt == null || now - cachedAt >= MISS_LIFETIME_MS) file.delete()
                        }
                    }
                }
            }
            expiredCoverCachePruned = true
        }
    }

    private data class CoverLookup(val succeeded: Boolean, val score: Double, val url: String?)

    private fun lookupCover(search: String): CoverLookup = apiRequest {
        val query = "query LibraryShowCover(\$search: String!) { Page(page: 1, perPage: 25) { media(search: \$search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) { title { romaji english native } coverImage { extraLarge large medium } } } }"
        val response = postGraphQl(query, search)
            ?: return@apiRequest CoverLookup(false, 0.0, null)
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
        val query = "query LibraryShowId(${'$'}search: String!) { Page(page: 1, perPage: 25) { media(search: ${'$'}search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) { id title { romaji english native } synonyms episodes nextAiringEpisode { episode airingAt } } } }"
        val response = postGraphQl(query, search) ?: return@apiRequest JSONArray()
        response.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
    }

    private fun lookupAnimeBatch(searches: List<String>): Map<String, JSONArray> = apiRequest {
        if (searches.isEmpty()) return@apiRequest emptyMap()
        val declarations = searches.indices.joinToString(", ") { index ->
            "\$search$index: String!"
        }
        val query = buildString {
            append("query LibraryAiringBatch(").append(declarations).append(") {")
            searches.indices.forEach { index ->
                append(" result$index: Page(page: 1, perPage: 25) { ")
                append("media(search: \$search$index, type: ANIME, isAdult: false, sort: SEARCH_MATCH) { ")
                append("id title { romaji english native } synonyms episodes ")
                append("nextAiringEpisode { episode airingAt } } }")
            }
            append(" }")
        }
        val variables = JSONObject().apply {
            searches.forEachIndexed { index, search -> put("search$index", search) }
        }
        val response = postGraphQl(query, variables) ?: return@apiRequest emptyMap()
        val data = response.optJSONObject("data") ?: return@apiRequest emptyMap()
        buildMap {
            searches.forEachIndexed { index, search ->
                data.optJSONObject("result$index")
                    ?.optJSONArray("media")
                    ?.let { put(normalize(search), it) }
            }
        }
    }

    private fun postGraphQl(query: String, search: String): JSONObject? =
        postGraphQl(query, JSONObject().put("search", search))

    private fun postGraphQl(query: String, variables: JSONObject): JSONObject? {
        val connection = URL(ANILIST_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "nekoplayer/1.0")
            val body = JSONObject().put("query", query).put("variables", variables).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) return null
            val bytes = stream.use { readBounded(it) }
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
            return connection.inputStream.use { readBounded(it) }
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

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val maxBytes = MAX_RESPONSE_BYTES
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

    private fun searchQueries(
        showName: String,
        episodeFileNames: Iterable<String>,
        alternateTitles: Iterable<String> = emptyList(),
    ): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun add(value: String) {
            val query = cleanSearchTitle(value)
            val key = normalize(query)
            if (key.length >= 3 && seen.add(key) && result.size < MAX_SEARCH_QUERIES) result += query
        }
        add(showName)
        alternateTitles.forEach(::add)
        episodeFileNames.forEach { filename -> episodeSeriesTitle(filename)?.let(::add) }
        return result
    }

    private fun episodeSeriesTitle(title: String): String? {
        val withoutGroup = title.replaceFirst(Regex("^\\s*\\[[^]]+]\\s*"), "")
        val cleaned = cleanSearchTitle(withoutGroup)
        val patterns = listOf(
            Regex("\\s+S\\d{1,2}E\\d{1,3}\\b.*$", RegexOption.IGNORE_CASE),
            Regex("\\s+[-–—_]\\s*(?:S\\d{1,2}\\s*E\\d{1,3}|\\d{1,3}(?:v\\d+)?)(?:\\s*[-–—_].*)?$", RegexOption.IGNORE_CASE),
            Regex("\\s+(?:episode|ep)\\s*\\d{1,3}\\b.*$", RegexOption.IGNORE_CASE),
            Regex("\\s+\\d{1,3}(?:v\\d+)?$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(cleaned)
            if (match != null && match.range.first >= 3) return cleaned.substring(0, match.range.first)
        }
        val parts = cleaned.split(Regex("\\s+[-–—]\\s+"))
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
        private const val MAX_SEARCH_QUERIES = 6
        private const val MAX_AIRING_BATCH_QUERIES = 20
        private const val MAX_CANDIDATES = 15
        private const val MINIMUM_MATCH_SCORE = 0.42
        private const val PREFIX_TITLE_SCORE = 0.75
        private const val STRONG_MATCH_SCORE = 0.88
        private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
        private const val IMAGE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
        private const val MISS_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        private const val AIRING_CACHE_LIFETIME_MS = 30L * 60 * 1000
        private const val MINIMUM_REQUEST_INTERVAL_MS = 2100L
        private val COMMON_WORDS = setOf("a", "an", "and", "are", "at", "by", "for", "from", "in", "is", "of", "on", "or", "the", "to", "with")
    }
}

@Suppress("unused")
fun jimakuShowTitleFromUriSegments(segments: List<String>, fallback: String): String {
    if (segments.size < 2) return fallback
    var folder = segments[segments.lastIndex - 1].trim()
    if (Regex("^(?:season|s|series|cour|part)\\s*\\d+$|^\\d+\\s*(?:st|nd|rd|th)?\\s*season$|^(?:ova|ovas|specials?|extras?|movies?)$", RegexOption.IGNORE_CASE).matches(folder) && segments.size >= 3) {
        folder = segments[segments.lastIndex - 2].trim()
    }
    if (folder.isEmpty() || AnimeLibraryScanner.looksLikeSeasonFolder(folder) || folder.lowercase(Locale.ROOT) in setOf("download", "downloads", "video", "videos", "movie", "movies", "anime", "tv", "shows", "media", "storage", "emulated", "sdcard")) return fallback
    return folder
}
