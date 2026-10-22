package com.nekoplayer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.core.net.toUri

import com.nekoplayer.app.library.AniListClient

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun createLibraryArtworkCache(): LruCache<String, Bitmap> =
    object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

private val libraryArtworkCache = createLibraryArtworkCache()

internal fun sharedLibraryArtworkCache(): LruCache<String, Bitmap> = libraryArtworkCache

internal fun trimLibraryArtworkCache(aggressive: Boolean) {
    libraryArtworkCache.trimToSize(if (aggressive) 0 else 2 * 1024 * 1024)
}

internal fun coverCacheKey(source: String?, showName: String, episodeNames: List<String>): String =
    source?.takeIf(String::isNotBlank)?.let { "source:$it" }
        ?: buildString {
            append("anilist:")
            append(showName)
            episodeNames.forEach { append('\u0000').append(it) }
        }

internal suspend fun loadCachedCover(
    context: Context,
    source: String?,
    client: AniListClient,
    showName: String,
    episodeNames: List<String>,
    cache: LruCache<String, Bitmap>,
): Bitmap? {
    val key = coverCacheKey(source, showName, episodeNames)
    cache[key]?.let { return it }
    return withArtworkLoadSlot {
        cache[key]?.let { return@withArtworkLoadSlot it }
        withContext(Dispatchers.IO) {
            loadCover(context, source, client, showName, episodeNames)
        }?.also { cache.put(key, it) }
    }
}

private suspend fun loadCover(
    context: Context,
    source: String?,
    client: AniListClient,
    showName: String,
    episodeNames: List<String>,
): Bitmap? = try {
    val bytes = when {
        source.isNullOrBlank() -> {
            val file = client.coverFor(showName, episodeNames) ?: return null
            file.inputStream().use { it.readBytesBounded(12 * 1024 * 1024) }
        }
        source.startsWith("https://") -> withContext(Dispatchers.IO) {
            val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode !in (200..299)) {
                    null
                } else if (!connection.contentType.orEmpty().startsWith("image/")) {
                    null
                } else {
                    connection.inputStream.use { it.readBytesBounded(12 * 1024 * 1024) }
                }
            } finally {
                connection.disconnect()
            }
        }
        source.startsWith("content://") -> context.contentResolver.openInputStream(source.toUri())?.use { it.readBytesBounded(12 * 1024 * 1024) }
        source.startsWith("file://") -> File(source.toUri().path.orEmpty()).inputStream().use { it.readBytesBounded(12 * 1024 * 1024) }
        else -> null
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while ((bounds.outWidth / sample) > 512) {
        sample *= 2
    }
    while ((bounds.outHeight / sample) > 720) {
        sample *= 2
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (_: Exception) { null }
