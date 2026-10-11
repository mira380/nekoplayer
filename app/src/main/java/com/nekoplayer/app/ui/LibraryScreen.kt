package com.nekoplayer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import android.util.LruCache
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    onOpenSourceEntry: (AppDatabase.SourceLibraryEntry) -> Unit,
    onPickCover: (String) -> Unit,
    onDetailScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artworkCache = remember { createLibraryArtworkCache() }
    var recent by remember { mutableStateOf(emptyList<AppDatabase.RecentMedia>()) }
    var sourceEntries by remember(database) { mutableStateOf(database.listSourceLibraryEntries()) }
    var covers by remember { mutableStateOf(database.readSetting("library.customCovers") as? org.json.JSONObject ?: org.json.JSONObject()) }
    var search by remember { mutableStateOf("") }
    var expandedShow by remember { mutableStateOf<String?>(null) }
    var coverUrlTarget by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf("") }
    var selectingSources by remember { mutableStateOf(false) }
    var selectedSourceKeys by remember { mutableStateOf(emptySet<String>()) }
    var removingSelected by remember { mutableStateOf(false) }
    var confirmRemoveSources by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val expandedMenus = remember { mutableStateMapOf<String, Boolean>() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(database) {
        recent = withContext(Dispatchers.IO) { database.listRecent() }
        sourceEntries = withContext(Dispatchers.IO) { database.listSourceLibraryEntries() }
    }
    LaunchedEffect(coverVersion) {
        covers = withContext(Dispatchers.IO) {
            database.readSetting("library.customCovers") as? org.json.JSONObject ?: org.json.JSONObject()
        }
    }

    val groups = remember(episodes) {
        episodes.groupBy { it.showName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val visibleGroups = remember(groups, search) {
        groups.filterKeys { search.isBlank() || it.contains(search, ignoreCase = true) }
    }
    val visibleGroupEntries = remember(visibleGroups) { visibleGroups.toList() }
    val visibleSourceEntries = remember(sourceEntries, search) {
        sourceEntries.filter {
            search.isBlank() ||
                it.title.contains(search, ignoreCase = true) ||
                it.sourceName.contains(search, ignoreCase = true) ||
                it.extensionName.contains(search, ignoreCase = true)
        }
    }
    val selectedEpisodes = remember(expandedShow, groups) {
        expandedShow?.let { name -> groups[name] }.orEmpty()
    }

    fun clearSourceSelection() {
        selectingSources = false
        selectedSourceKeys = emptySet()
        selectionError = null
    }

    fun removeSelectedSourceEntries() {
        val keys = selectedSourceKeys
        if (keys.isEmpty() || removingSelected) return
        removingSelected = true
        selectionError = null
        scope.launch {
            try {
                val updatedEntries = withContext(Dispatchers.IO) {
                    database.removeSourceLibraryEntries(keys)
                    database.listSourceLibraryEntries()
                }
                sourceEntries = updatedEntries
                clearSourceSelection()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                selectionError = "Couldn't remove the selected titles. Try again."
            } finally {
                removingSelected = false
            }
        }
    }

    BackHandler(enabled = expandedShow != null || selectingSources) {
        when {
            expandedShow != null -> expandedShow = null
            confirmRemoveSources -> confirmRemoveSources = false
            else -> clearSourceSelection()
        }
    }
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
        if (episodes.isNotEmpty() || sourceEntries.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Search") },
                )
            }
        }
        if (episodes.isNotEmpty() || sourceEntries.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (selectingSources) {
                    Text(
                        "${selectedSourceKeys.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = { confirmRemoveSources = true },
                        enabled = selectedSourceKeys.isNotEmpty() && !removingSelected,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (removingSelected) "Removing…" else "Remove")
                    }
                    IconButton(
                        onClick = ::clearSourceSelection,
                        enabled = !removingSelected,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = localizeUiText("Cancel selection"))
                    }
                } else {
                    TextButton(onClick = onPickFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Choose folder", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onPickVideo, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open video", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.weight(1f))
                    if (sourceEntries.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                selectingSources = true
                                selectedSourceKeys = emptySet()
                                selectionError = null
                            },
                            modifier = Modifier.size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(14.dp),
                                ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAddCheck,
                                contentDescription = localizeUiText("Select saved titles"),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            selectionError?.let { message ->
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (episodes.isEmpty() && sourceEntries.isEmpty() && !loading) {
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
                columns = GridCells.Adaptive(minSize = 126.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = gridState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sourceEntries.isNotEmpty() && visibleSourceEntries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "section_header") {
                        Text(
                            "Sources",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                items(visibleSourceEntries, key = { "source:${it.key}" }, contentType = { "source_card" }) { entry ->
                    SourceLibraryCard(
                        context = context,
                        client = aniList,
                        artworkCache = artworkCache,
                        entry = entry,
                        selectionMode = selectingSources,
                        selected = entry.key in selectedSourceKeys,
                        onOpen = { onOpenSourceEntry(entry) },
                        onToggleSelected = {
                            selectionError = null
                            selectedSourceKeys =
                                if (entry.key in selectedSourceKeys) selectedSourceKeys - entry.key
                                else selectedSourceKeys + entry.key
                        },
                    )
                }
                if (sourceEntries.isNotEmpty() && visibleGroups.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "section_header") {
                        Text(
                            "Local episodes",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if ((episodes.isNotEmpty() || sourceEntries.isNotEmpty()) &&
                    !loading && visibleGroups.isEmpty() && visibleSourceEntries.isEmpty()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "empty_state") {
                        EmptyLibraryCard(
                            onPickFolder = onPickFolder,
                            onPickVideo = onPickVideo,
                            title = "No matching shows",
                            detail = "Try a shorter show name.",
                        )
                    }
                }
                items(visibleGroupEntries, key = { "show:${it.first}" }, contentType = { "show_card" }) { (showName, showEpisodes) ->
                    val customCover = covers.optString(showName).takeIf { it.isNotBlank() }
                    ShowCard(
                        context = context,
                        client = aniList,
                        artworkCache = artworkCache,
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

    if (confirmRemoveSources && selectedSourceKeys.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSources = false },
            title = { Text("Remove selected titles?") },
            text = {
                val titleWord = if (selectedSourceKeys.size == 1) "title" else "titles"
                Text(
                    "Remove ${selectedSourceKeys.size} saved $titleWord from your library? " +
                        "You can add them again from the video source."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveSources = false
                        removeSelectedSourceEntries()
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveSources = false }) { Text("Cancel") }
            },
        )
    }
}

private fun mediaUri(value: String): Uri {
    val parsed = value.toUri()
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localizeUiText("Back to library"))
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
    artworkCache: LruCache<String, Bitmap>,
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
    val coverKey = remember(showName, coverSource, episodeNames) {
        coverCacheKey(coverSource, showName, episodeNames)
    }
    var loadedBitmap by remember(coverKey) { mutableStateOf(artworkCache.get(coverKey)) }
    LaunchedEffect(coverKey) {
        if (loadedBitmap == null) {
            loadedBitmap = withContext(Dispatchers.IO) {
                loadCachedCover(context, coverSource, client, showName, episodeNames, artworkCache)
            }
        }
    }
    val imageBitmap = remember(loadedBitmap) { loadedBitmap?.asImageBitmap() }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onToggle)) {
            if (imageBitmap != null) {
                Image(imageBitmap, contentDescription = localizeUiText("$showName artwork"), modifier = Modifier.fillMaxWidth().aspectRatio(0.78f), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.78f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = localizeUiText("Artwork options"), tint = androidx.compose.ui.graphics.Color.White) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("Set image URL") }, onClick = { onDismissMenu(); onSetUrl() })
                    DropdownMenuItem(text = { Text("Choose local image") }, onClick = { onDismissMenu(); onPickLocal() })
                    if (coverSource != null) DropdownMenuItem(text = { Text("Use AniList artwork") }, onClick = { onDismissMenu(); onRemoveCover() })
                }
            }
        }
        Material3Text(showName, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("$episodeCount episodes", Modifier.padding(start = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceLibraryCard(
    context: Context,
    client: AniListClient,
    artworkCache: LruCache<String, Bitmap>,
    entry: AppDatabase.SourceLibraryEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    val coverKey = remember(entry.key, entry.thumbnailUrl) {
        coverCacheKey(entry.thumbnailUrl, entry.title, emptyList())
    }
    var loadedBitmap by remember(coverKey) { mutableStateOf(artworkCache.get(coverKey)) }
    LaunchedEffect(coverKey) {
        if (loadedBitmap == null) {
            loadedBitmap = withContext(Dispatchers.IO) {
                loadCachedCover(context, entry.thumbnailUrl, client, entry.title, emptyList(), artworkCache)
            }
        }
    }
    val imageBitmap = remember(loadedBitmap) { loadedBitmap?.asImageBitmap() }

    val cardShape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(cardShape)
            .then(
                if (selected) Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    cardShape,
                ) else Modifier,
            )
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, cardShape)
                else Modifier,
            )
            .clickable(onClick = if (selectionMode) onToggleSelected else onOpen),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    imageBitmap,
                    contentDescription = localizeUiText("${entry.title} artwork"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }
            if (selectionMode) {
                IconButton(
                    onClick = onToggleSelected,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
                ) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = localizeUiText(if (selected) "Deselect ${entry.title}" else "Select ${entry.title}"),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Material3Text(
            entry.title,
            Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Material3Text(
                entry.sourceName,
                Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.episodeCount?.let { if (it == 1) "1 ep" else "$it eps" } ?: "— eps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun createLibraryArtworkCache(): LruCache<String, Bitmap> =
    object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

private fun coverCacheKey(source: String?, showName: String, episodeNames: List<String>): String =
    source?.takeIf(String::isNotBlank)?.let { "source:$it" }
        ?: buildString {
            append("anilist:")
            append(showName)
            episodeNames.forEach { append('\u0000').append(it) }
        }

private suspend fun loadCachedCover(
    context: Context,
    source: String?,
    client: AniListClient,
    showName: String,
    episodeNames: List<String>,
    cache: LruCache<String, Bitmap>,
): Bitmap? {
    val key = coverCacheKey(source, showName, episodeNames)
    cache.get(key)?.let { return it }
    return loadCover(context, source, client, showName, episodeNames)?.also { cache.put(key, it) }
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
        source.startsWith("content://") -> context.contentResolver.openInputStream(source.toUri())?.use { it.readBytesBounded(12 * 1024 * 1024) }
        source.startsWith("file://") -> File(source.toUri().path.orEmpty()).inputStream().use { it.readBytesBounded(12 * 1024 * 1024) }
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
            Material3Text(episode.displayName, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (saved != null && saved.positionMs > 0) Text(formatTime(saved.positionMs), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
