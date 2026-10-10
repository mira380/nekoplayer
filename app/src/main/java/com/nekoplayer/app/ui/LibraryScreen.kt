package com.nekoplayer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun LibraryScreen(
    database: AppDatabase,
    aniList: AniListClient,
    folderUri: String?,
    episodes: List<AnimeLibraryEpisode>,
    loading: Boolean,
    error: String?,
    coverVersion: Int,
    onPickFolder: () -> Unit,
    onPickVideo: () -> Unit,
    onOpenVideo: (Uri, String, String?) -> Unit,
    onPickCover: (String) -> Unit,
    onDetailScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recent by remember { mutableStateOf(emptyList<AppDatabase.RecentMedia>()) }
    var covers by remember { mutableStateOf(database.readSetting("library.customCovers") as? org.json.JSONObject ?: org.json.JSONObject()) }
    var search by remember { mutableStateOf("") }
    var expandedShow by remember { mutableStateOf<String?>(null) }
    var coverUrlTarget by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf("") }
    val expandedMenus = remember { mutableStateMapOf<String, Boolean>() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { recent = withContext(Dispatchers.IO) { database.listRecent() } }
    LaunchedEffect(coverVersion) {
        covers = withContext(Dispatchers.IO) {
            database.readSetting("library.customCovers") as? org.json.JSONObject ?: org.json.JSONObject()
        }
    }

    val groups = episodes.groupBy { it.showName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val visibleGroups = groups.filterKeys { search.isBlank() || it.contains(search, ignoreCase = true) }
    val selectedEpisodes = expandedShow?.let { name -> groups[name] }.orEmpty()
    BackHandler(enabled = expandedShow != null) { expandedShow = null }
    LaunchedEffect(expandedShow) { onDetailScreenChanged(expandedShow != null) }

    if (expandedShow != null && selectedEpisodes.isNotEmpty()) {
        ShowDetailScreen(
            showName = expandedShow.orEmpty(),
            episodes = selectedEpisodes,
            recent = recent,
            onBack = { expandedShow = null },
            onOpenVideo = { episode ->
                onOpenVideo(episode.uri, episode.displayName, episode.showName)
                expandedShow = null
            },
            modifier = modifier,
        )
    } else Column(modifier.fillMaxSize()) {
        if (episodes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Search") },
                )
            }
        }
        if (episodes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onPickFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose folder", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onPickVideo) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open video", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (episodes.isEmpty() && !loading) {
            val noFolderSelected = folderUri.isNullOrBlank()
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyLibraryCard(
                    onPickFolder = onPickFolder,
                    onPickVideo = onPickVideo,
                    title = when {
                        error != null -> "Folder unavailable"
                        noFolderSelected -> "Build your library"
                        else -> "No videos found"
                    },
                    detail = error ?: if (noFolderSelected) {
                        "Scan a folder of local episodes or open one video directly."
                    } else {
                        "Choose a folder containing local anime episodes."
                    },
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = gridState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (episodes.isNotEmpty() && !loading && visibleGroups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLibraryCard(
                            onPickFolder = onPickFolder,
                            onPickVideo = onPickVideo,
                            title = "No matching shows",
                            detail = "Try a shorter show name.",
                        )
                    }
                }
                items(visibleGroups.toList(), key = { "show:${it.first}" }) { (showName, showEpisodes) ->
                    val customCover = covers.optString(showName).takeIf { it.isNotBlank() }
                    ShowCard(
                        context = context,
                        client = aniList,
                        showName = showName,
                        episodeCount = showEpisodes.size,
                        episodeNames = showEpisodes.take(8).map { it.displayName },
                        coverSource = customCover,
                        menuExpanded = expandedMenus[showName] == true,
                        onToggle = { expandedShow = showName },
                        onMenu = { expandedMenus[showName] = true },
                        onDismissMenu = { expandedMenus[showName] = false },
                        onSetUrl = {
                            coverUrlTarget = showName
                            coverUrl = customCover.orEmpty().takeIf { it.startsWith("http") }.orEmpty()
                        },
                        onPickLocal = { onPickCover(showName) },
                        onRemoveCover = {
                            covers = org.json.JSONObject(covers.toString()).apply { remove(showName) }
                            database.writeSetting("library.customCovers", covers)
                        },
                    )
                }
            }
        }
    }


    if (coverUrlTarget != null) {
        AlertDialog(
            onDismissRequest = { coverUrlTarget = null },
            title = { Text("Artwork URL") },
            text = {
                OutlinedTextField(
                    value = coverUrl,
                    onValueChange = { coverUrl = it },
                    label = { Text("HTTPS image URL") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = coverUrlTarget ?: return@TextButton
                    val value = coverUrl.trim()
                    if (value.startsWith("https://")) {
                        covers = org.json.JSONObject(covers.toString()).put(target, value)
                        database.writeSetting("library.customCovers", covers)
                    }
                    coverUrlTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { coverUrlTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun mediaUri(value: String): Uri {
    val parsed = Uri.parse(value)
    return if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(value)) else parsed
}

@Composable
private fun EmptyLibraryCard(
    onPickFolder: () -> Unit,
    onPickVideo: () -> Unit,
    title: String = "Build your library",
    detail: String = "Scan a folder of local episodes or open one video directly.",
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose folder")
                }
                TextButton(onClick = onPickVideo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open video")
                }
            }
        }
    }
}

@Composable
private fun ShowDetailScreen(
    showName: String,
    episodes: List<AnimeLibraryEpisode>,
    recent: List<AppDatabase.RecentMedia>,
    onBack: () -> Unit,
    onOpenVideo: (AnimeLibraryEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
            }
            Text(
                showName,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 56.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Text(
            "${episodes.size} episodes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            items(episodes, key = { it.uri.toString() }) { episode ->
                val saved = recent.firstOrNull { it.uri == episode.uri.toString() }
                EpisodeRow(episode, saved) { onOpenVideo(episode) }
            }
        }
    }
}

@Composable
private fun ShowCard(
    context: Context,
    client: AniListClient,
    showName: String,
    episodeCount: Int,
    episodeNames: List<String>,
    coverSource: String?,
    menuExpanded: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onSetUrl: () -> Unit,
    onPickLocal: () -> Unit,
    onRemoveCover: () -> Unit,
) {
    var loadedBitmap by remember(showName, coverSource) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(showName, coverSource) {
        loadedBitmap = withContext(Dispatchers.IO) {
            loadCover(context, coverSource, client, showName, episodeNames)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onToggle)) {
            if (loadedBitmap != null) {
                Image(loadedBitmap!!.asImageBitmap(), contentDescription = "$showName artwork", modifier = Modifier.fillMaxWidth().aspectRatio(0.68f), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.68f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Artwork options", tint = androidx.compose.ui.graphics.Color.White) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("Set image URL") }, onClick = { onDismissMenu(); onSetUrl() })
                    DropdownMenuItem(text = { Text("Choose local image") }, onClick = { onDismissMenu(); onPickLocal() })
                    if (coverSource != null) DropdownMenuItem(text = { Text("Use AniList artwork") }, onClick = { onDismissMenu(); onRemoveCover() })
                }
            }
        }
        Text(showName, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("$episodeCount episodes", Modifier.padding(start = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        source.startsWith("https://") -> {
            val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
            }
            try {
                if (connection.responseCode !in 200..299 || !connection.contentType.orEmpty().startsWith("image/")) return null
                connection.inputStream.use { it.readBytesBounded(12 * 1024 * 1024) }
            } finally { connection.disconnect() }
        }
        source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytesBounded(12 * 1024 * 1024) }
        source.startsWith("file://") -> File(Uri.parse(source).path.orEmpty()).inputStream().use { it.readBytesBounded(12 * 1024 * 1024) }
        else -> null
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 720) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (_: Exception) { null }

private fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var size = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        size += count
        require(size <= maxBytes) { "Image exceeds the size limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable
private fun EpisodeRow(episode: AnimeLibraryEpisode, saved: AppDatabase.RecentMedia?, onClick: () -> Unit) {
    val progress = saved?.durationMs?.takeIf { it > 0 }?.let { (saved.positionMs.toFloat() / it).coerceIn(0f, 1f) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(episode.displayName, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (saved != null && saved.positionMs > 0) Text(formatTime(saved.positionMs), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
