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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.key
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

private enum class LibraryShowSort(val title: String) {
    TITLE("Title A–Z"),
    RECENT("Recently watched"),
    EPISODES("Most episodes");

    companion object {
        fun fromStored(value: String): LibraryShowSort = entries.firstOrNull { it.name == value } ?: TITLE
    }
}

private sealed interface LibraryShelfItem {
    val key: String
    val title: String
    val episodeCount: Int
    val lastWatchedAtMs: Long

    data class LocalShow(
        val showName: String,
        val episodes: List<AnimeLibraryEpisode>,
        override val lastWatchedAtMs: Long,
    ) : LibraryShelfItem {
        override val key: String get() = "show:$showName"
        override val title: String get() = showName
        override val episodeCount: Int get() = episodes.size
    }

    data class SourceTitle(
        val entry: AppDatabase.SourceLibraryEntry,
        override val lastWatchedAtMs: Long,
    ) : LibraryShelfItem {
        override val key: String get() = "source:${entry.key}"
        override val title: String get() = entry.title
        override val episodeCount: Int get() = entry.episodeCount ?: 0
    }
}

private data class LibraryShelf(
    val key: String,
    val title: String,
    val entries: List<LibraryShelfItem>,
)

private fun sourceTitleSortKey(entry: AppDatabase.SourceLibraryEntry): String =
    entry.title.lowercase()

private fun sourceEpisodeCount(entry: AppDatabase.SourceLibraryEntry): Int =
    entry.episodeCount ?: 0

private fun String.toLibraryTimestampMs(): Long = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .parse(this)?.time ?: 0L
}.getOrDefault(0L)

@Composable
fun LibraryScreen(
    database: AppDatabase,
    aniList: AniListClient,
    folderUri: String?,
    episodes: List<AnimeLibraryEpisode>,
    loading: Boolean,
    error: String?,
    coverVersion: Int,
    progressRefreshKey: Int,
    onPickFolder: () -> Unit,
    onPickVideo: () -> Unit,
    onOpenVideo: (Uri, String, String?, NextEpisodeAction?) -> Unit,
    onOpenSourceEntry: (AppDatabase.SourceLibraryEntry) -> Unit,
    onPickCover: (String) -> Unit,
    onDetailScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artworkCache = remember { createLibraryArtworkCache() }
    var recent by remember { mutableStateOf(emptyList<AppDatabase.RecentMedia>()) }
    var sourceEntries by remember(database) { mutableStateOf(database.listSourceLibraryEntries()) }
    var sourceRecency by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var covers by remember {
        mutableStateOf((database.readSetting("library.customCovers") as? org.json.JSONObject) ?: org.json.JSONObject())
    }
    var libraryGroups by remember(database) { mutableStateOf(readLibraryGroups(database)) }
    var groupAssignments by remember(database) { mutableStateOf(readLibraryGroupAssignments(database)) }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    var showGroupManager by remember { mutableStateOf(value = false) }
    var groupNameDraft by remember { mutableStateOf("") }
    var groupToDelete by remember { mutableStateOf<String?>(null) }
    var groupAssignmentTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var assignmentDraft by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    var sortMenuExpanded by remember { mutableStateOf(value = false) }
    var librarySort by remember(database) {
        mutableStateOf(LibraryShowSort.fromStored(database.readString("library.sortOrder", "TITLE")))
    }
    var expandedShow by remember { mutableStateOf<String?>(null) }
    var coverUrlTarget by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf("") }
    var selectingSources by remember { mutableStateOf(value = false) }
    var selectedSourceKeys by remember { mutableStateOf(emptySet<String>()) }
    var removingSelected by remember { mutableStateOf(value = false) }
    var confirmRemoveSources by remember { mutableStateOf(value = false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val expandedMenus = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(database, progressRefreshKey) {
        val (loadedRecent, loadedSourceEntries, loadedSourceRecency) = withContext(Dispatchers.IO) {
            val entries = database.listSourceLibraryEntries()
            val recency = entries.associateBy(
                keySelector = { it.key },
            ) { (sourceId, _, _, animeUrl) ->
                database.latestSourceProgress(sourceId, animeUrl)?.updatedAtMs ?: 0L
            }
            Triple(database.listRecent(), entries, recency)
        }
        recent = loadedRecent
        sourceEntries = loadedSourceEntries
        sourceRecency = loadedSourceRecency
    }
    LaunchedEffect(coverVersion) {
        covers = withContext(Dispatchers.IO) {
            (database.readSetting("library.customCovers") as? org.json.JSONObject) ?: org.json.JSONObject()
        }
    }

    val groups = remember(episodes) {
        episodes.groupBy(AnimeLibraryEpisode::showName).toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val recentByUri = remember(recent) { recent.associateBy(AppDatabase.RecentMedia::uri) }
    val visibleGroups = remember(groups, search, librarySort, recentByUri) {
        val filtered = groups.filter { (showName, _) ->
            search.isBlank() || showName.contains(search, ignoreCase = true)
        }
        fun latestWatch(episodesForShow: List<AnimeLibraryEpisode>): Long =
            episodesForShow.asSequence()
                .mapNotNull { (uri, _, _, _) -> recentByUri[uri.toString()]?.lastOpenedAt }
                .maxOfOrNull(String::toLibraryTimestampMs) ?: 0L
        val comparator = when (librarySort) {
            LibraryShowSort.TITLE -> compareBy { (key, _) -> key.lowercase() }
            LibraryShowSort.RECENT -> compareByDescending<Map.Entry<String, List<AnimeLibraryEpisode>>> { (_, showEpisodes) ->
                latestWatch(showEpisodes)
            }.thenBy { (key, _) -> key.lowercase() }
            LibraryShowSort.EPISODES -> compareByDescending<Map.Entry<String, List<AnimeLibraryEpisode>>> { (_, showEpisodes) ->
                showEpisodes.size
            }.thenBy { (key, _) -> key.lowercase() }
        }
        filtered.entries.asSequence()
            .sortedWith(comparator)
            .associateBy({ (key, _) -> key }) { (_, value) -> value }
    }
    val visibleSourceEntries = remember(sourceEntries, search, librarySort, sourceRecency) {
        val filtered = sourceEntries.filter { (_, sourceName, extensionName, _, title, _, _, _) ->
            search.isBlank() ||
                title.contains(search, ignoreCase = true) ||
                sourceName.contains(search, ignoreCase = true) ||
                extensionName.contains(search, ignoreCase = true)
        }
        val comparator = when (librarySort) {
            LibraryShowSort.TITLE -> compareBy(::sourceTitleSortKey)
            LibraryShowSort.RECENT -> compareByDescending<AppDatabase.SourceLibraryEntry> { sourceRecency[it.key] ?: 0L }
                .thenBy(::sourceTitleSortKey)
            LibraryShowSort.EPISODES -> compareByDescending(::sourceEpisodeCount)
                .thenBy(::sourceTitleSortKey)
        }
        filtered.sortedWith(comparator)
    }
    val libraryShelves = remember(
        libraryGroups,
        visibleGroups,
        visibleSourceEntries,
        groupAssignments,
        sourceRecency,
        recentByUri,
        librarySort,
        search,
    ) {
        fun entriesForGroup(groupName: String?): List<LibraryShelfItem> {
            fun isAssignedToShelf(key: String): Boolean {
                val assignedGroups = groupAssignments[key].orEmpty()
                    .filter { assigned -> libraryGroups.any { it.equals(assigned, ignoreCase = true) } }
                return if (groupName == null) {
                    assignedGroups.isEmpty()
                } else {
                    assignedGroups.any { it.equals(groupName, ignoreCase = true) }
                }
            }

            val entries = buildList {
                visibleGroups.forEach { (showName, showEpisodes) ->
                    val key = localLibraryGroupKey(showName)
                    if (isAssignedToShelf(key)) {
                        val lastWatchedAt = showEpisodes.asSequence()
                            .mapNotNull { (uri, _, _, _) -> recentByUri[uri.toString()]?.lastOpenedAt }
                            .maxOfOrNull(String::toLibraryTimestampMs) ?: 0L
                        add(LibraryShelfItem.LocalShow(showName, showEpisodes, lastWatchedAt))
                    }
                }
                visibleSourceEntries.forEach { entry ->
                    if (isAssignedToShelf(sourceLibraryGroupKey(entry))) {
                        add(LibraryShelfItem.SourceTitle(entry, sourceRecency[entry.key] ?: 0L))
                    }
                }
            }
            val comparator = when (librarySort) {
                LibraryShowSort.TITLE -> compareBy { it.title.lowercase() }
                LibraryShowSort.RECENT -> compareByDescending<LibraryShelfItem> { it.lastWatchedAtMs }
                    .thenBy { it.title.lowercase() }
                LibraryShowSort.EPISODES -> compareByDescending<LibraryShelfItem> { it.episodeCount }
                    .thenBy { it.title.lowercase() }
            }
            return entries.sortedWith(comparator)
        }

        buildList {
            libraryGroups.forEach { groupName ->
                val entries = entriesForGroup(groupName)
                if (entries.isNotEmpty()) {
                    add(LibraryShelf("group:$groupName", groupName, entries))
                }
            }
            val ungroupedEntries = entriesForGroup(null)
            if (ungroupedEntries.isNotEmpty()) {
                add(LibraryShelf("ungrouped", "Ungrouped", ungroupedEntries))
            }
        }
    }
    val selectedEpisodes = remember(expandedShow, groups) {
        expandedShow?.let { name -> groups[name] }.orEmpty()
    }

    fun saveLibraryGroups(updatedGroups: List<String>) {
        libraryGroups = updatedGroups
        database.writeSetting("library.groups", updatedGroups)
    }

    fun saveGroupAssignments(updatedAssignments: Map<String, Set<String>>) {
        groupAssignments = updatedAssignments
        val encoded = org.json.JSONObject()
        updatedAssignments.forEach { (itemKey, assignedGroups) ->
            if (assignedGroups.isNotEmpty()) {
                encoded.put(itemKey, org.json.JSONArray(assignedGroups.sorted()))
            }
        }
        database.writeSetting("library.groupAssignments", encoded)
    }

    fun deleteLibraryGroup(groupName: String) {
        saveLibraryGroups(libraryGroups.filterNot { it.equals(groupName, ignoreCase = true) })
        saveGroupAssignments(
            groupAssignments.mapValues { (_, assigned) -> assigned - groupName }
                .filterValues { it.isNotEmpty() },
        )
        collapsedGroups -= "group:$groupName"
    }

    fun openGroupAssignment(itemKey: String, title: String) {
        assignmentDraft = groupAssignments[itemKey].orEmpty()
        groupAssignmentTarget = itemKey to title
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
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                selectionError = "Couldn't remove the selected titles. Try again."
            } finally {
                removingSelected = false
            }
        }
    }

    BackHandler(enabled = (expandedShow != null) || selectingSources) {
        when {
            expandedShow != null -> expandedShow = null
            confirmRemoveSources -> confirmRemoveSources = false
            else -> clearSourceSelection()
        }
    }
    LaunchedEffect(expandedShow) { onDetailScreenChanged(expandedShow != null) }

    if ((expandedShow != null) && selectedEpisodes.isNotEmpty()) {
        ShowDetailScreen(
            showName = expandedShow.orEmpty(),
            episodes = selectedEpisodes,
            recent = recent,
            onBack = { expandedShow = null },
            onOpenVideo = { (uri, displayName, showName, _), onNextEpisode ->
                onOpenVideo(uri, displayName, showName, onNextEpisode)
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
                    Box {
                        IconButton(
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = localizeUiText("Sort library: ${librarySort.title}"),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            LibraryShowSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.title) },
                                    onClick = {
                                        librarySort = option
                                        sortMenuExpanded = false
                                        scope.launch(Dispatchers.IO) {
                                            database.writeSetting("library.sortOrder", option.name)
                                        }
                                    },
                                )
                            }
                        }
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

        if (!selectingSources && (episodes.isNotEmpty() || sourceEntries.isNotEmpty())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Collections",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = { showGroupManager = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (libraryGroups.isEmpty()) "Create group" else "Manage groups")
                }
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
        } else if (libraryShelves.isEmpty() && !loading) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyLibraryCard(
                    onPickFolder = onPickFolder,
                    onPickVideo = onPickVideo,
                    title = "No matching titles",
                    detail = "Try a shorter show name or clear the search.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(libraryShelves, key = LibraryShelf::key, contentType = { "library_shelf" }) { shelf ->
                    val (shelfKey, shelfTitle, shelfEntries) = shelf
                    val expanded = shelfKey !in collapsedGroups
                    Column(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                collapsedGroups = if (expanded) {
                                    collapsedGroups + shelfKey
                                } else {
                                    collapsedGroups - shelfKey
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 20.dp,
                                vertical = 10.dp,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    shelfTitle,
                                    modifier = Modifier.widthIn(max = 260.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    shelfEntries.size.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                    contentDescription = localizeUiText(
                                        if (expanded) "Collapse $shelfTitle" else "Expand $shelfTitle",
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                            }
                        }

                        if (expanded) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val horizontalInset = 20.dp
                                val cardSpacing = 8.dp
                                val availableCardWidth = maxWidth - (horizontalInset * 2)
                                val columnCount = ((availableCardWidth + cardSpacing) / (108.dp + cardSpacing))
                                    .toInt()
                                    .coerceIn(1, 5)
                                val cardWidth =
                                    (availableCardWidth - cardSpacing * (columnCount - 1)) / columnCount - 1.dp

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(
                                        start = horizontalInset,
                                        top = 8.dp,
                                        end = horizontalInset,
                                        bottom = 14.dp,
                                    ),
                                    maxItemsInEachRow = columnCount,
                                    horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                shelfEntries.forEach { shelfItem ->
                                    key(shelfItem.key) {
                                        when (shelfItem) {
                                            is LibraryShelfItem.SourceTitle -> {
                                                val entry = shelfItem.entry
                                                SourceLibraryCard(
                                                    context = context,
                                                    client = aniList,
                                                    artworkCache = artworkCache,
                                                    entry = entry,
                                                    selectionMode = selectingSources,
                                                    selected = entry.key in selectedSourceKeys,
                                                    menuExpanded = expandedMenus["source:${entry.key}"] == true,
                                                    onOpen = { onOpenSourceEntry(entry) },
                                                    onMenu = { expandedMenus["source:${entry.key}"] = true },
                                                    onDismissMenu = { expandedMenus["source:${entry.key}"] = false },
                                                    onManageGroups = {
                                                        openGroupAssignment(sourceLibraryGroupKey(entry), entry.title)
                                                    },
                                                    onToggleSelected = {
                                                        selectionError = null
                                                        selectedSourceKeys =
                                                            if (entry.key in selectedSourceKeys) selectedSourceKeys - entry.key
                                                            else selectedSourceKeys + entry.key
                                                    },
                                                    modifier = Modifier.width(cardWidth),
                                                )
                                            }

                                            is LibraryShelfItem.LocalShow -> {
                                                val showName = shelfItem.showName
                                                val showEpisodes = shelfItem.episodes
                                                val customCover = covers.optString(showName).takeIf { it.isNotBlank() }
                                                ShowCard(
                                                    context = context,
                                                    client = aniList,
                                                    artworkCache = artworkCache,
                                                    showName = showName,
                                                    episodeCount = showEpisodes.size,
                                                    episodeNames = showEpisodes.asSequence()
                                                        .take(8)
                                                        .map { it.displayName }
                                                        .toList(),
                                                    coverSource = customCover,
                                                    menuExpanded = expandedMenus[showName] == true,
                                                    onToggle = { expandedShow = showName },
                                                    onMenu = { expandedMenus[showName] = true },
                                                    onDismissMenu = { expandedMenus[showName] = false },
                                                    onManageGroups = {
                                                        openGroupAssignment(localLibraryGroupKey(showName), showName)
                                                    },
                                                    onSetUrl = {
                                                        coverUrlTarget = showName
                                                        coverUrl = customCover.orEmpty()
                                                            .takeIf { it.startsWith("http") }.orEmpty()
                                                    },
                                                    onPickLocal = { onPickCover(showName) },
                                                    onRemoveCover = {
                                                        covers = org.json.JSONObject(covers.toString())
                                                            .apply { remove(showName) }
                                                        database.writeSetting("library.customCovers", covers)
                                                    },
                                                    modifier = Modifier.width(cardWidth),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }


    if (showGroupManager) {
        AlertDialog(
            onDismissRequest = { showGroupManager = false },
            title = { Text("Library groups") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = groupNameDraft,
                        onValueChange = { groupNameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("New group name") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            val name = groupNameDraft.trim()
                            if (name.isNotEmpty() && libraryGroups.none { it.equals(name, ignoreCase = true) }) {
                                saveLibraryGroups((libraryGroups + name).sortedBy { it.lowercase() })
                                groupNameDraft = ""
                            }
                        },
                        enabled = groupNameDraft.isNotBlank() &&
                            libraryGroups.none { it.equals(groupNameDraft.trim(), ignoreCase = true) },
                    ) { Text("Create group") }
                    if (libraryGroups.isEmpty()) {
                        Text("Create a group, then assign it from a title’s menu.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(libraryGroups, key = { "library-group:$it" }) { groupName ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(groupName, Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            groupToDelete = groupName
                                            showGroupManager = false
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = localizeUiText("Delete $groupName"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupManager = false }) { Text("Done") }
            },
        )
    }

    if (groupToDelete != null) {
        val deletingGroup = groupToDelete.orEmpty()
        AlertDialog(
            onDismissRequest = {
                groupToDelete = null
                showGroupManager = true
            },
            title = { Text("Delete group?") },
            text = { Text("Remove “$deletingGroup” from the library and all assigned titles?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteLibraryGroup(deletingGroup)
                        groupToDelete = null
                        showGroupManager = true
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        groupToDelete = null
                        showGroupManager = true
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (groupAssignmentTarget != null) {
        val target = groupAssignmentTarget!!
        AlertDialog(
            onDismissRequest = { groupAssignmentTarget = null },
            title = { Text("Organize ${target.second}") },
            text = {
                if (libraryGroups.isEmpty()) {
                    Text("Create a library group first, then assign titles to it.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(libraryGroups, key = { "assign-group:$it" }) { groupName ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    assignmentDraft = if (groupName in assignmentDraft) {
                                        assignmentDraft - groupName
                                    } else {
                                        assignmentDraft + groupName
                                    }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = groupName in assignmentDraft,
                                    onCheckedChange = { checked ->
                                        assignmentDraft = if (checked) assignmentDraft + groupName
                                        else assignmentDraft - groupName
                                    },
                                )
                                Text(groupName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = groupAssignments.toMutableMap()
                        if (assignmentDraft.isEmpty()) updated.remove(target.first)
                        else updated[target.first] = assignmentDraft
                        saveGroupAssignments(updated)
                        groupAssignmentTarget = null
                    },
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupAssignmentTarget = null }) { Text("Cancel") }
            },
        )
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
                TextButton(
                    onClick = {
                        val target = coverUrlTarget ?: return@TextButton
                        val value = coverUrl.trim()
                        if (value.startsWith("https://")) {
                            covers = org.json.JSONObject(covers.toString()).put(target, value)
                            database.writeSetting("library.customCovers", covers)
                        }
                        coverUrlTarget = null
                    },
                ) {
                    Text("Save")
                }
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
                        "You can add them again from the video source.",
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
    onOpenVideo: (AnimeLibraryEpisode, NextEpisodeAction?) -> Unit,
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
        fun openEpisodeAt(index: Int) {
            val episode = episodes.getOrNull(index) ?: return
            val nextEpisode = if (index < episodes.lastIndex) {
                { onFinished: (Boolean) -> Unit ->
                    openEpisodeAt(index + 1)
                    onFinished(true)
                }
            } else {
                null
            }
            onOpenVideo(episode, nextEpisode)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            items(episodes, key = { it.uri.toString() }) { episode ->
                val saved = recent.firstOrNull { it.uri == episode.uri.toString() }
                EpisodeRow(episode, saved) { openEpisodeAt(episodes.indexOf(episode)) }
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
    onManageGroups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverKey = remember(showName, coverSource, episodeNames) {
        coverCacheKey(coverSource, showName, episodeNames)
    }
    var loadedBitmap by remember(coverKey) { mutableStateOf(artworkCache[coverKey]) }
    LaunchedEffect(coverKey) {
        if (loadedBitmap == null) {
            loadedBitmap = withContext(Dispatchers.IO) {
                loadCachedCover(context, coverSource, client, showName, episodeNames, artworkCache)
            }
        }
    }
    val imageBitmap = remember(loadedBitmap) { loadedBitmap?.asImageBitmap() }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onToggle)) {
            if (imageBitmap != null) {
                Image(imageBitmap, contentDescription = localizeUiText("$showName artwork"), modifier = Modifier.fillMaxWidth().aspectRatio(0.78f), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.78f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = onMenu) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = localizeUiText("Show options"),
                        modifier = Modifier.size(18.dp),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("Organize groups") }, onClick = { onDismissMenu(); onManageGroups() })
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
    menuExpanded: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onManageGroups: () -> Unit,
    onToggleSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverKey = remember(entry.key, entry.thumbnailUrl) {
        coverCacheKey(entry.thumbnailUrl, entry.title, emptyList())
    }
    var loadedBitmap by remember(coverKey) { mutableStateOf(artworkCache[coverKey]) }
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
        modifier.fillMaxWidth()
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
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Box(
                        Modifier.size(32.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (selected) Icons.Default.CheckCircle else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = localizeUiText(if (selected) "Deselect ${entry.title}" else "Select ${entry.title}"),
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = onMenu,
                    ) {
                        Box(
                            Modifier.size(32.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = localizeUiText("Title options"),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                        DropdownMenuItem(
                            text = { Text("Organize groups") },
                            onClick = { onDismissMenu(); onManageGroups() },
                        )
                    }
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

private fun readLibraryGroups(database: AppDatabase): List<String> {
    val stored = (database.readSetting("library.groups") as? org.json.JSONArray) ?: return emptyList()
    return (0 until stored.length()).asSequence()
        .mapNotNull { index -> stored.optString(index).trim().takeIf(String::isNotEmpty) }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .sortedBy { it.lowercase(java.util.Locale.ROOT) }
        .toList()
}

private fun readLibraryGroupAssignments(database: AppDatabase): Map<String, Set<String>> {
    val stored = (database.readSetting("library.groupAssignments") as? org.json.JSONObject) ?: return emptyMap()
    return buildMap {
        val keys = stored.keys()
        while (keys.hasNext()) {
            val itemKey = keys.next()
            val assigned = stored.optJSONArray(itemKey) ?: continue
            val names = (0 until assigned.length()).asSequence()
                .mapNotNull { index ->
                    assigned.optString(index).trim().takeIf(String::isNotEmpty)
                }
                .toSet()
            if (names.isNotEmpty()) put(itemKey, names)
        }
    }
}

private fun localLibraryGroupKey(showName: String): String =
    "local:${showName.trim().lowercase(java.util.Locale.ROOT)}"

private fun sourceLibraryGroupKey(entry: AppDatabase.SourceLibraryEntry): String =
    "source:${entry.key}"

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
    cache[key]?.let { return it }
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
        if ((saved != null) && (saved.positionMs > 0)) Text(formatTime(saved.positionMs), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
