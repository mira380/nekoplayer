package com.nekoplayer.app.mining

import android.text.Html
import android.os.Build
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class PronunciationAudioPreset(
    val id: String,
    val kind: String,
    val url: String,
)

internal object PronunciationAudioPresets {
    val defaults = listOf(
        PronunciationAudioPreset("NHK-2016", "ajatt-index", "https://github.com/Ajatt-Tools/nhk_2016_pronunciations_index/releases/latest/download/NHK-2016_main.zip"),
        PronunciationAudioPreset("NHK-1998", "ajatt-index", "https://github.com/Ajatt-Tools/nhk_1998_pronunciations_index/releases/latest/download/NHK-1998_main.zip"),
        PronunciationAudioPreset("Shinmeikai-8", "ajatt-index", "https://github.com/Ajatt-Tools/shinmeikai_8_pronunciations_index/releases/latest/download/Shinmeikai-8_main.zip"),
        PronunciationAudioPreset("Daijisen", "ajatt-index", "https://github.com/Ajatt-Tools/daijisen_pronunciations_index/releases/latest/download/Daijisen_main.zip"),
        PronunciationAudioPreset("TAAS", "ajatt-index", "https://github.com/Ajatt-Tools/taas_pronunciations_index/releases/latest/download/TAAS_main.zip"),
        PronunciationAudioPreset("JapanesePod101", "ajatt-index", "https://github.com/Ajatt-Tools/jpod_pronunciations_index/releases/latest/download/JapanesePod101_main.zip"),
        PronunciationAudioPreset("Obunsha-Kogo", "ajatt-index", "https://github.com/Ajatt-Tools/oubunsha_kogo_pronunciations_index/releases/latest/download/Oubunsha-Kogo_main.zip"),
    )
}

data class PronunciationAudio(
    val attachment: AnkiDroidAttachment,
    val sourceId: String,
)

// Tries configured Yomitan-style sources in order. Network and file work is bounded and runs on Dispatchers.IO.
class PronunciationAudioClient(
    private val database: AppDatabase,
    private val cacheDirectory: File,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
    private val maxPageBytes: Int = 2 * 1024 * 1024,
    private val maxAudioBytes: Int = 12 * 1024 * 1024,
) {
    private val indexLock = Any()
    private var cachedIndexSourceId: String? = null
    private var cachedIndex: AjattIndex? = null

    suspend fun resolve(
        entry: DictionaryEntry,
        language: String = "ja",
        destinationField: String,
    ): PronunciationAudio? = withContext(Dispatchers.IO) {
        val term = entry.expression.trim()
        if (term.isEmpty() || destinationField.isBlank()) return@withContext null
        val reading = entry.reading.trim()
        val sources = loadSources()
        for (source in sources) {
            if (!source.enabled) continue
            try {
                val bytes = resolveSource(source, term, reading, language) ?: continue
                val mime = sniffMime(bytes) ?: continue
                val extension = when (mime) {
                    "audio/mpeg" -> "mp3"
                    "audio/ogg" -> "ogg"
                    "audio/wav" -> "wav"
                    "audio/mp4" -> "m4a"
                    else -> continue
                }
                val directory = File(cacheDirectory, "mining")
                if (!directory.exists() && !directory.mkdirs()) continue
                val safeStem = "pron-${sha256("${source.id}\u0000$term\u0000$reading").take(20)}"
                val file = File.createTempFile("$safeStem-", ".$extension", directory)
                try {
                    file.outputStream().use { it.write(bytes) }
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
                return@withContext PronunciationAudio(
                    attachment = AnkiDroidAttachment(
                        file = file,
                        preferredName = "${safeStem}.$extension",
                        mimeType = "audio",
                        destinationField = destinationField,
                    ),
                    sourceId = source.id,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Keep trying the next source if this one fails.
            }
        }
        null
    }

    private fun loadSources(): List<Source> {
        val saved = database.readSetting("audio.pronunciationSources") as? JSONArray
            ?: return defaultSources
        val sources = (0 until saved.length()).mapNotNull { index ->
            val item = saved.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val kind = item.optString("kind").trim()
            if (id.isEmpty() || kind.isEmpty()) null
            else Source(
                id = id,
                kind = kind,
                enabled = item.optBoolean("enabled", false),
                urlTemplate = item.optString("urlTemplate").takeIf(String::isNotBlank),
            )
        }
        val legacyDefault = sources.size == 3 &&
            sources.all { it.enabled && it.urlTemplate.isNullOrBlank() } &&
            sources.map { it.id to it.kind }.toSet() == setOf(
                "jpod101" to "jpod101",
                "language-pod-101" to "language-pod-101",
                "jisho" to "jisho",
            )
        return if (legacyDefault) defaultSources else sources
    }

    private fun resolveSource(source: Source, term: String, reading: String, language: String): ByteArray? {
        return when (source.kind) {
            "jpod101" -> {
                val endpoint = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php"
                val query = ArrayList<String>(2)
                if (!(isKana(term) && term == reading)) query += "kanji=${encode(term)}"
                if (reading.isNotEmpty()) query += "kana=${encode(reading)}"
                if (query.isEmpty()) null else download("$endpoint?${query.joinToString("&")}")
            }
            "language-pod-101" -> {
                if (!language.equals("ja", ignoreCase = true)) return null
                val endpoint = "https://www.japanesepod101.com/learningcenter/reference/dictionary_post"
                val body = listOf(
                    "post=dictionary_reference",
                    "match_type=exact",
                    "search_query=${encode(term)}",
                    "vulgar=true",
                ).joinToString("&")
                val page = request(endpoint, "POST", body, "application/x-www-form-urlencoded") ?: return null
                if (page.status !in 200..299) return null
                val html = page.bytes.toString(Charsets.UTF_8)
                val rowStarts = Regex("(?is)<[^>]+class=[\"'][^\"']*\\bdc-result-row\\b[^\"']*[\"'][^>]*>")
                    .findAll(html).toList()
                for ((index, start) in rowStarts.withIndex()) {
                    val end = rowStarts.getOrNull(index + 1)?.range?.first ?: html.length
                    val row = html.substring(start.range.last + 1, end)
                    if (reading != term) {
                        val kana = Regex("(?is)class=[\"'][^\"']*\\bdc-vocab_kana\\b[^\"']*[\"'][^>]*>(.*?)</")
                            .find(row)?.groupValues?.get(1)?.let(::plainText)?.trim()
                        if (kana != reading) continue
                    }
                    val src = Regex("(?is)<source\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']")
                        .find(row)?.groupValues?.get(1) ?: continue
                    val url = resolveUrl(endpoint, decodeEntities(src)) ?: continue
                    download(url)?.let { return it }
                }
                null
            }
            "jisho" -> {
                val endpoint = "https://jisho.org/search/${encode(term)}"
                val page = request(endpoint) ?: return null
                if (page.status !in 200..299) return null
                val html = page.bytes.toString(Charsets.UTF_8)
                val audioId = "audio_${term}:$reading"
                val tag = Regex("(?is)<audio\\b[^>]*\\bid=[\"']${Regex.escape(audioId)}[\"'][^>]*>(.*?)</audio>")
                    .find(html)?.groupValues?.get(1) ?: return null
                val src = Regex("(?is)<source\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']")
                    .find(tag)?.groupValues?.get(1) ?: return null
                download(resolveUrl(endpoint, decodeEntities(src)) ?: return null)
            }
            "ajatt-index" -> resolveAjattIndex(source, term, reading)
            "custom-url" -> {
                val template = source.urlTemplate?.takeIf(String::isNotBlank) ?: return null
                download(customUri(template, term, reading, language))
            }
            "custom-json" -> {
                val template = source.urlTemplate?.takeIf(String::isNotBlank) ?: return null
                val endpoint = customUri(template, term, reading, language)
                val response = request(endpoint, accept = "application/json") ?: return null
                if (response.status !in 200..299) return null
                val sources = try {
                    JSONObject(response.bytes.toString(Charsets.UTF_8)).optJSONArray("audioSources")
                } catch (_: Exception) {
                    null
                } ?: return null
                for (index in 0 until sources.length()) {
                    val raw = sources.optJSONObject(index) ?: continue
                    val candidate = raw.opt("url") as? String ?: continue
                    val resolved = resolveUrl(endpoint, candidate.trim()) ?: continue
                    download(resolved)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun resolveAjattIndex(source: Source, term: String, reading: String): ByteArray? {
        val index = loadAjattIndex(source) ?: return null
        val keys = listOf(term, reading).filter(String::isNotBlank).distinct()
        for (key in keys) {
            val files = index.headwords.optJSONArray(key) ?: continue
            for (position in 0 until files.length()) {
                val fileName = when (val value = files.opt(position)) {
                    is String -> value
                    is JSONObject -> value.optString("url").ifBlank { value.optString("file") }
                    else -> ""
                }.trim()
                if (fileName.isBlank()) continue
                val fileInfo = index.files.optJSONObject(fileName)
                val fileReading = fileInfo?.optString("kana_reading").orEmpty().trim()
                if (reading.isNotBlank() && fileReading.isNotBlank() && fileReading != reading) continue
                val path = fileName.trimStart('/').split('/').joinToString("/") {
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                val mediaUrl = resolveUrl(index.mediaDirectory, path) ?: continue
                download(mediaUrl)?.let { return it }
            }
        }
        return null
    }

    @android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
    private fun loadAjattIndex(source: Source): AjattIndex? {
        val archiveUrl = source.urlTemplate?.takeIf(String::isNotBlank) ?: return null
        synchronized(indexLock) {
            if (cachedIndexSourceId == source.id) return cachedIndex
            val cacheDirectory = File(cacheDirectory, "pronunciation-indices")
            if (!cacheDirectory.exists()) cacheDirectory.mkdirs()
            val cacheFile = File(cacheDirectory, sha256(archiveUrl) + ".json")
            val cacheIsFresh = cacheFile.isFile &&
                System.currentTimeMillis() - cacheFile.lastModified() < INDEX_CACHE_TTL_MS
            if (!cacheIsFresh) {
                val response = request(archiveUrl, maxBytes = MAX_INDEX_ARCHIVE_BYTES)
                val indexBytes = if (response != null && response.status in 200..299) {
                    unzipIndex(response.bytes)
                } else {
                    null
                }
                if (indexBytes != null) {
                    runCatching {
                        val temporary = File(cacheDirectory, cacheFile.name + ".tmp")
                        temporary.writeBytes(indexBytes)
                        temporary.copyTo(cacheFile, overwrite = true)
                        temporary.delete()
                        cacheFile.setLastModified(System.currentTimeMillis())
                    }
                }
            }
            if (!cacheFile.isFile) return null
            val root = runCatching { JSONObject(cacheFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
            val meta = root.optJSONObject("meta") ?: JSONObject()
            val headwords = root.optJSONObject("headwords") ?: return null
            val files = root.optJSONObject("files") ?: return null
            val mediaDirectoryValue = meta.optString("media_dir_abs").ifBlank {
                meta.optString("media_dir")
            }
            if (mediaDirectoryValue.isBlank()) return null
            val mediaDirectory = if (isHttpUrl(mediaDirectoryValue)) {
                mediaDirectoryValue
            } else {
                resolveUrl(archiveUrl, mediaDirectoryValue) ?: return null
            }.let { if (it.endsWith('/')) it else "$it/" }
            return AjattIndex(headwords, files, mediaDirectory).also {
                cachedIndexSourceId = source.id
                cachedIndex = it
            }
        }
    }

    private fun unzipIndex(archive: ByteArray): ByteArray? =
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return@use null
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "index.json") {
                    return@use readBounded(zip, MAX_INDEX_JSON_BYTES).takeIf { it.isNotEmpty() }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        accept: String = "audio/*, application/octet-stream, text/html, */*",
        maxBytes: Int = maxPageBytes,
    ): Response? {
        var current = url
        for (redirect in 0..4) {
            if (!isHttpUrl(current)) return null
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", accept)
                setRequestProperty("Cache-Control", "no-cache")
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (body != null) doOutput = true
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    if (redirect == 4) return null
                    current = URL(URL(current), location).toString()
                    if (!isHttpUrl(current)) return null
                    continue
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = stream?.use { readBounded(it, maxBytes) } ?: ByteArray(0)
                return Response(status, connection.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT), bytes)
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun download(url: String): ByteArray? {
        val response = request(url, maxBytes = maxAudioBytes) ?: return null
        if (response.status !in 200..299 || response.bytes.isEmpty() || response.bytes.size > maxAudioBytes) return null
        val mime = response.mimeType
        if (!mime.startsWith("audio/") && mime != "application/octet-stream") return null
        return response.bytes.takeIf { sniffMime(it) != null }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalStateException("Pronunciation response exceeded the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte() -> "audio/mpeg"
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())) -> "audio/ogg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) -> "audio/wav"
        bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0 -> "audio/mpeg"
        bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> "audio/mp4"
        else -> null
    }

    private fun isKana(value: String): Boolean = value.isNotEmpty() && value.all { c ->
        c in '\u3040'..'\u30ff' || c in '\u31f0'..'\u31ff' || c in '\uff66'..'\uff9f'
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun customUri(template: String, term: String, reading: String, language: String): String {
        val interpolated = Regex("\\{(term|reading|language)\\}").replace(template) { match ->
            val value = when (match.groupValues[1]) {
                "term" -> term
                "reading" -> reading
                else -> language
            }
            encode(value).replace("+", "%20")
        }
        val uri = try { URI(interpolated) } catch (_: Exception) { null }
        if (uri == null || !isHttpUrl(uri)) {
            throw IllegalArgumentException("Pronunciation source URL must be HTTP or HTTPS.")
        }
        return uri.toASCIIString()
    }

    private fun isHttpUrl(value: String): Boolean = try {
        isHttpUrl(URI(value))
    } catch (_: Exception) { false }

    private fun isHttpUrl(uri: URI): Boolean =
        uri.isAbsolute && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank()

    private fun resolveUrl(base: String, relative: String): String? = try {
        URI(base).resolve(relative).takeIf(::isHttpUrl)?.toString()
    } catch (_: Exception) { null }
    @Suppress("DEPRECATION")
    private fun decodeEntities(value: String): String = if (Build.VERSION.SDK_INT >= 24) {
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        Html.fromHtml(value).toString()
    }

    @Suppress("DEPRECATION")
    private fun plainText(value: String): String = if (Build.VERSION.SDK_INT >= 24) {
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        Html.fromHtml(value).toString()
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class Source(
        val id: String,
        val kind: String,
        val enabled: Boolean,
        val urlTemplate: String? = null,
    )
    private data class AjattIndex(
        val headwords: JSONObject,
        val files: JSONObject,
        val mediaDirectory: String,
    )
    private data class Response(val status: Int, val mimeType: String, val bytes: ByteArray)

    private companion object {
        const val MAX_INDEX_ARCHIVE_BYTES = 64 * 1024 * 1024
        const val MAX_INDEX_JSON_BYTES = 64 * 1024 * 1024
        const val INDEX_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        val defaultSources = PronunciationAudioPresets.defaults.map {
            Source(it.id, it.kind, true, it.url)
        }
    }
}
