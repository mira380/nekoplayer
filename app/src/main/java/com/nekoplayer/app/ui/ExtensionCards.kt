package com.nekoplayer.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nekoplayer.app.extensions.model.Extension
import com.nekoplayer.app.extensions.model.ExtensionApiVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val extensionIconCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

internal fun trimExtensionIconCache(aggressive: Boolean) {
    extensionIconCache.trimToSize(if (aggressive) 0 else 1 * 1024 * 1024)
}

@Composable
internal fun InstalledExtensionCard(
    extension: Extension.Installed,
    update: Extension.Available?,
    busy: Boolean,
    onUpdate: (Extension.Available) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(
                drawable = extension.icon,
                cacheKey = "installed:" + extension.pkgName + ":" + extension.versionCode,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Material3Text(extension.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (extension.lang.isBlank()) "Video" else extensionLanguageLabel(extension.lang)} · API ${extension.libVersion.toInt()} · ${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (extension.hasUpdate && update != null) {
                    TextButton(enabled = !busy, onClick = { onUpdate(update) }) {
                        Text(if (busy) "Updating…" else "Update")
                    }
                } else if (busy) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                }
                TextButton(enabled = !busy, onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = localizeUiText("Remove ${extension.name}"))
                }
            }
        }
    }
}

@Composable
internal fun AvailableExtensionCard(
    extension: Extension.Available,
    installed: Extension.Installed?,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            val label = when {
                !extension.isSupported -> "Unsupported"
                installed?.hasUpdate == true -> "Update"
                installed != null -> "Installed"
                busy -> "Installing…"
                else -> "Install"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceIcon(
                            iconUrl = extension.iconUrl,
                            cacheKey = "available:" + extension.pkgName + ":" + extension.versionCode,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Material3Text(extension.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${if (extension.lang.isBlank()) "Video" else extensionLanguageLabel(extension.lang)} · API ${extension.libVersion.toInt()} · ${extension.versionName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!extension.isSupported) {
                        Text(
                            "Extension API ${extension.libVersion.toInt()} is unsupported. This runtime supports APIs ${ExtensionApiVersion.MIN_SUPPORTED.toInt()}–${ExtensionApiVersion.MAX_SUPPORTED.toInt()}.",
                            modifier = Modifier.padding(start = 52.dp, top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (extension.sources.isNotEmpty()) {
                        Material3Text(
                            extension.sources.take(3).joinToString(" · ") { it.name },
                            modifier = Modifier.padding(start = 52.dp, top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    modifier = Modifier.width(124.dp),
                    enabled = extension.isSupported && installed?.hasUpdate != false && !busy,
                    onClick = onInstall,
                ) {
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal fun extensionLanguageTags(extension: Extension.Available): List<String> {
    val declared = extension.lang.trim().lowercase()
    val sourceLanguages = extension.sources.map { it.lang.trim().lowercase() }.filter(String::isNotBlank)
    val isMultiLanguage = declared == "all" || declared == "multi"
    return buildSet {
        when {
            isMultiLanguage -> {
                add("multi")
                addAll(sourceLanguages)
            }
            declared.isNotBlank() -> add(declared)
            sourceLanguages.isNotEmpty() -> addAll(sourceLanguages)
            else -> add("unknown")
        }
    }.toList()
}

internal fun extensionNativeLanguageLabel(language: String): String = when (language.lowercase()) {
    "all", "multi" -> "Multi"
    "unknown" -> "Unknown"
    else -> {
        val locale = Locale.forLanguageTag(language.replace('_', '-'))
        locale.getDisplayLanguage(locale).takeIf(String::isNotBlank) ?: extensionLanguageLabel(language)
    }
}

internal fun extensionLanguageLabel(language: String): String = when (language.lowercase()) {
    "all", "multi" -> "Multi-language"
    "unknown" -> "Unknown"
    else -> {
        val locale = Locale.forLanguageTag(language.replace('_', '-'))
        val label = locale.getDisplayLanguage(Locale.ENGLISH)
        if (locale.language.isBlank() || locale.language == "und") language
        else label.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

@Composable
internal fun SourceIcon(
    drawable: Drawable? = null,
    iconUrl: String? = null,
    cacheKey: String? = null,
) {
    val iconSizePx = with(LocalDensity.current) { 42.dp.roundToPx() }
    val resolvedCacheKey = cacheKey
        ?: iconUrl?.takeIf(String::isNotBlank)
        ?: drawable?.let { "drawable:" + System.identityHashCode(it) }
    var bitmap by remember(resolvedCacheKey, iconSizePx) {
        mutableStateOf(resolvedCacheKey?.let(extensionIconCache::get))
    }
    LaunchedEffect(resolvedCacheKey, drawable, iconUrl, iconSizePx) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = when {
            drawable != null -> withContext(Dispatchers.IO) {
                runCatching { drawable.toBitmap(iconSizePx, iconSizePx) }.getOrNull()
            }
            !iconUrl.isNullOrBlank() -> withArtworkLoadSlot {
                withContext(Dispatchers.IO) {
                    loadRemoteExtensionIcon(iconUrl, iconSizePx)
                }
            }
            else -> null
        }
        if (loaded != null) {
            resolvedCacheKey?.let { extensionIconCache.put(it, loaded) }
            bitmap = loaded
        }
    }
    val iconBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun loadRemoteExtensionIcon(iconUrl: String, targetSizePx: Int): Bitmap? {
    val parsedUrl = try {
        URL(iconUrl)
    } catch (_: Exception) {
        return null
    }
    if (parsedUrl.protocol != "https" || parsedUrl.host.isBlank()) return null
    val connection = try {
        parsedUrl.openConnection() as? HttpURLConnection
    } catch (_: Exception) {
        null
    } ?: return null

    connection.connectTimeout = 6_000
    connection.readTimeout = 6_000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "nekoplayer-android")
    return try {
        val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        if (connection.responseCode !in 200..299 || contentLength > 1_000_000L) return null
        val bytes = connection.inputStream.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 1_000_000) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetSizePx * 2 ||
            bounds.outHeight / sampleSize > targetSizePx * 2
        ) {
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

