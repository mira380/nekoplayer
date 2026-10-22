package com.nekoplayer.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import okhttp3.Request

@Composable
internal fun SourceAnimeCard(
    anime: SAnime,
    source: AnimeHttpSource?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            SourceArtwork(
                imageUrl = anime.thumbnail_url,
                contentDescription = localizeUiText("${anime.title} cover art"),
                source = source,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Material3Text(
                    anime.title,
                    style = MaterialTheme.typography.titleSmall,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!anime.description.isNullOrBlank()) {
                    Material3Text(
                        anime.description.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SourceArtwork(
    imageUrl: String?,
    contentDescription: String?,
    source: AnimeHttpSource?,
    modifier: Modifier = Modifier,
) {
    val resolvedUrl = remember(imageUrl, source?.id, source?.baseUrl) {
        resolveArtworkUrl(imageUrl, source?.baseUrl)
    }
    var bitmap by remember(resolvedUrl, source?.id) {
        mutableStateOf(resolvedUrl?.let(sourceArtworkCache::get))
    }
    LaunchedEffect(resolvedUrl, source?.id) {
        if (resolvedUrl == null) {
            bitmap = null
        } else if (bitmap == null) {
            bitmap = withArtworkLoadSlot {
                withContext(Dispatchers.IO) {
                    sourceArtworkCache[resolvedUrl]
                        ?: loadSourceArtwork(resolvedUrl, source)?.also {
                            sourceArtworkCache.put(resolvedUrl, it)
                        }
                }
            }
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private val sourceArtworkCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

internal fun trimSourceArtworkCache(aggressive: Boolean) {
    sourceArtworkCache.trimToSize(if (aggressive) 0 else 3 * 1024 * 1024)
}

internal fun resolveArtworkUrl(imageUrl: String?, baseUrl: String?): String? = try {
    val rawUrl = imageUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = if (rawUrl.startsWith("//")) URI("https:$rawUrl") else URI(rawUrl)
    val resolved = if (uri.isAbsolute) {
        uri
    } else {
        val base = baseUrl?.trim()?.takeIf(String::isNotEmpty)?.let {
            URI(if (it.endsWith('/')) it else "$it/")
        } ?: return null
        base.resolve(uri)
    }
    resolved.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
} catch (_: Exception) {
    null
}

private fun loadSourceArtwork(
    imageUrl: String,
    source: AnimeHttpSource?,
): Bitmap? = try {
    fun decodeArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 720) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    val sourceBitmap = if (source != null) {
        try {
            val request = Request.Builder().url(imageUrl).headers(source.headers).build()
            source.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body.byteStream().use { stream ->
                        decodeArtwork(stream.readBytesBounded(MAX_ARTWORK_BYTES))
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    if (sourceBitmap != null) return sourceBitmap

    val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }
    val bytes = try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { it.readBytesBounded(MAX_ARTWORK_BYTES) }
    } finally {
        connection.disconnect()
    }
    decodeArtwork(bytes)
} catch (_: LinkageError) {
    null
} catch (_: Exception) {
    null
}

private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024

@Composable
internal fun FilterDialog(
    filterList: AnimeFilterList,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search filters") },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filterList.size) { index ->
                    key(revision, index) {
                        FilterControl(filterList[index]) { revision += 1 }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterControl(filter: AnimeFilter<*>, changed: () -> Unit) {
    when (filter) {
        is AnimeFilter.Header -> Material3Text(filter.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        is AnimeFilter.Separator -> HorizontalDivider()
        is AnimeFilter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = { setFilterState(filter, it); changed() },
            modifier = Modifier.fillMaxWidth(),
            label = { Material3Text(filter.name) },
            singleLine = true,
        )
        is AnimeFilter.CheckBox -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Material3Text(filter.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = filter.state, onCheckedChange = { setFilterState(filter, it); changed() })
        }
        is AnimeFilter.TriState -> {
            Material3Text(filter.name, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Any" to 0, "Include" to 1, "Exclude" to 2).forEach { (label, value) ->
                    FilterChip(
                        selected = filter.state == value,
                        onClick = { setFilterState(filter, value); changed() },
                        label = { Material3Text(label) },
                    )
                }
            }
        }
        is AnimeFilter.Select<*> -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) {
                    Material3Text("${filter.name}: ${filter.values.getOrNull(filter.state)?.toString() ?: localizeUiText("Any")}")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    filter.values.forEachIndexed { index, value ->
                        DropdownMenuItem(
                            text = { Material3Text(value?.toString().orEmpty()) },
                            onClick = { setFilterState(filter, index); changed(); expanded = false },
                        )
                    }
                }
            }
        }
        is AnimeFilter.Sort -> {
            var expanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Material3Text(filter.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { expanded = true }) {
                    Material3Text(filter.state?.let { "${filter.values.getOrNull(it.index)} · ${if (it.ascending) "↑" else "↓"}" } ?: localizeUiText("Choose"))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    filter.values.forEachIndexed { index, value ->
                        DropdownMenuItem(
                            text = { Material3Text(value) },
                            onClick = { setFilterState(filter, AnimeFilter.Sort.Selection(index, true)); changed(); expanded = false },
                        )
                    }
                }
            }
        }
        is AnimeFilter.Group<*> -> {
            Material3Text(filter.name, style = MaterialTheme.typography.titleSmall)
            filter.state.filterIsInstance<AnimeFilter<*>>().forEach { nested ->
                FilterControl(nested, changed)
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun setFilterState(filter: AnimeFilter<*>, value: Any?) {
    (filter as AnimeFilter<Any?>).state = value
}
