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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.library.AniListAiringEpisode
import com.nekoplayer.app.library.AniListAiringLookup
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private fun LibraryShelfItem.toAiringLookup(): AniListAiringLookup = when (this) {
    is LibraryShelfItem.LocalShow -> AniListAiringLookup(
        key = key,
        showName = showName,
        episodeFileNames = episodes.take(2).map(AnimeLibraryEpisode::displayName),
    )
    is LibraryShelfItem.SourceTitle -> AniListAiringLookup(
        key = key,
        showName = entry.title,
    )
}

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
    var search by remember { mutableStateOf("") }
    var libraryActionMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(value = false) }
    var librarySort by remember(database) {
        mutableStateOf(LibraryShowSort.fromStored(database.readString("library.sortOrder", "TITLE")))
    }
    var expandedShow by remember { mutableStateOf<String?>(null) }
    var coverUrlTarget by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf("") }
    var selectingLibraryItems by remember { mutableStateOf(false) }
    var selectedLibraryItemKeys by remember { mutableStateOf(emptySet<String>()) }
    val selectedSourceKeys = remember(selectedLibraryItemKeys) {
        selectedLibraryItemKeys.asSequence()
            .filter { it.startsWith("source:") }
            .map { it.removePrefix("source:") }
            .toSet()
    }
    var showBatchGroupDialog by remember { mutableStateOf(false) }
    var batchGroupDraft by remember { mutableStateOf(emptySet<String>()) }
    var removingSelected by remember { mutableStateOf(value = false) }
    var confirmRemoveSources by remember { mutableStateOf(value = false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val expandedMenus = remember { mutableStateMapOf<String, Boolean>() }
    var airingEpisodesByItemKey by remember(aniList) {
        mutableStateOf<Map<String, AniListAiringEpisode>>(emptyMap())
    }
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
            return entries.distinctBy(LibraryShelfItem::key).sortedWith(comparator)
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
    LaunchedEffect(aniList, libraryShelves, collapsedGroups) {
        val expandedShelves = libraryShelves.filterNot { it.key in collapsedGroups }
        expandedShelves.forEach { shelf ->
            val lookups = shelf.entries.map(LibraryShelfItem::toAiringLookup)
            if (lookups.isEmpty()) return@forEach
            val itemKeys = lookups.map(AniListAiringLookup::key).toSet()
            val schedules = withContext(Dispatchers.IO) {
                aniList.findNextAiringEpisodes(lookups)
            }
            airingEpisodesByItemKey =
                airingEpisodesByItemKey.filterKeys { it !in itemKeys } + schedules
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
        val canonicalGroups = libraryGroups.associateBy { it.lowercase(java.util.Locale.ROOT) }
        val normalizedAssignments = buildMap {
            updatedAssignments.forEach { (itemKey, assignedGroups) ->
                val normalizedGroups = assignedGroups.asSequence()
                    .mapNotNull { name ->
                        name.trim().takeIf(String::isNotEmpty)?.let { trimmed ->
                            canonicalGroups[trimmed.lowercase(java.util.Locale.ROOT)] ?: trimmed
                        }
                    }
                    .distinctBy { it.lowercase(java.util.Locale.ROOT) }
                    .toSet()
                if (normalizedGroups.isNotEmpty()) put(itemKey, normalizedGroups)
            }
        }
        groupAssignments = normalizedAssignments
        val encoded = org.json.JSONObject()
        normalizedAssignments.forEach { (itemKey, assignedGroups) ->
            encoded.put(itemKey, org.json.JSONArray(assignedGroups.sorted()))
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

    fun clearLibrarySelection() {
        selectingLibraryItems = false
        selectedLibraryItemKeys = emptySet()
        selectionError = null
    }

    fun startLibrarySelection(itemKey: String) {
        selectingLibraryItems = true
        selectedLibraryItemKeys = selectedLibraryItemKeys + itemKey
        selectionError = null
    }

    fun toggleLibraryItemSelection(itemKey: String) {
        val next = if (itemKey in selectedLibraryItemKeys) {
            selectedLibraryItemKeys - itemKey
        } else {
            selectedLibraryItemKeys + itemKey
        }
        selectedLibraryItemKeys = next
        if (next.isEmpty()) selectingLibraryItems = false
        selectionError = null
    }

    fun addSelectedItemsToGroups() {
        if (selectedLibraryItemKeys.isEmpty() || batchGroupDraft.isEmpty()) return
        val updated = groupAssignments.toMutableMap()
        selectedLibraryItemKeys.forEach { itemKey ->
            val current = updated[itemKey].orEmpty()
            val newGroups = batchGroupDraft.filterNot { candidate ->
                current.any { it.equals(candidate, ignoreCase = true) }
            }
            if (newGroups.isNotEmpty()) updated[itemKey] = current + newGroups
        }
        saveGroupAssignments(updated)
        showBatchGroupDialog = false
        clearLibrarySelection()
    }

    fun toggleGroupAssignment(itemKey: String, groupName: String) {
        val canonicalGroupName = libraryGroups.firstOrNull { it.equals(groupName, ignoreCase = true) }
            ?: return
        val updated = groupAssignments.toMutableMap()
        val current = updated[itemKey].orEmpty()
        val alreadyAssigned = current.any { it.equals(canonicalGroupName, ignoreCase = true) }
        val next = if (alreadyAssigned) {
            current.filterNot { it.equals(canonicalGroupName, ignoreCase = true) }.toSet()
        } else {
            current + canonicalGroupName
        }
        if (next.isEmpty()) updated.remove(itemKey) else updated[itemKey] = next
        saveGroupAssignments(updated)
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
                clearLibrarySelection()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                selectionError = "Couldn't remove the selected titles. Try again."
            } finally {
                removingSelected = false
            }
        }
    }

    BackHandler(enabled = (expandedShow != null) || selectingLibraryItems) {
        when {
            expandedShow != null -> expandedShow = null
            showBatchGroupDialog -> showBatchGroupDialog = false
            confirmRemoveSources -> confirmRemoveSources = false
            groupToDelete != null -> {
                groupToDelete = null
                showGroupManager = true
            }
            showGroupManager -> showGroupManager = false
            else -> clearLibrarySelection()
        }
    }
    LaunchedEffect(expandedShow) { onDetailScreenChanged(expandedShow != null) }

    if ((expandedShow != null) && selectedEpisodes.isNotEmpty()) {
        ShowDetailScreen(
            aniList = aniList,
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
                if (selectingLibraryItems) {
                    Text(
                        "${selectedLibraryItemKeys.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = {
                            batchGroupDraft = emptySet()
                            showBatchGroupDialog = true
                        },
                        enabled = selectedLibraryItemKeys.isNotEmpty() &&
                            libraryGroups.isNotEmpty() && !removingSelected,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Add to group")
                    }
                    if (selectedSourceKeys.isNotEmpty()) {
                        IconButton(
                            onClick = { confirmRemoveSources = true },
                            enabled = !removingSelected,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = localizeUiText("Remove saved titles"),
                            )
                        }
                    }
                    IconButton(
                        onClick = ::clearLibrarySelection,
                        enabled = !removingSelected,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = localizeUiText("Cancel selection"))
                    }
                } else {
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
                    IconButton(
                        onClick = { showGroupManager = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = localizeUiText(
                                if (libraryGroups.isEmpty()) "Create group" else "Manage groups",
                            ),
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { libraryActionMenuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = localizeUiText("Add to library"),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = libraryActionMenuExpanded,
                            onDismissRequest = { libraryActionMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                                        Spacer(Modifier.width(12.dp))
                                        Text("Choose folder")
                                    }
                                },
                                onClick = {
                                    libraryActionMenuExpanded = false
                                    onPickFolder()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VideoLibrary, contentDescription = null)
                                        Spacer(Modifier.width(12.dp))
                                        Text("Open video")
                                    }
                                },
                                onClick = {
                                    libraryActionMenuExpanded = false
                                    onPickVideo()
                                },
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
                                                    nextAiringEpisode = airingEpisodesByItemKey[shelfItem.key],
                                                    selectionMode = selectingLibraryItems,
                                                    selected = sourceLibraryGroupKey(entry) in selectedLibraryItemKeys,
                                                    menuExpanded = expandedMenus["source:${entry.key}"] == true,
                                                    onOpen = { onOpenSourceEntry(entry) },
                                                    onMenu = { expandedMenus["source:${entry.key}"] = true },
                                                    onDismissMenu = { expandedMenus["source:${entry.key}"] = false },
                                                    availableGroups = libraryGroups,
                                                    assignedGroups = groupAssignments[sourceLibraryGroupKey(entry)].orEmpty(),
                                                    onToggleGroup = {
                                                        toggleGroupAssignment(sourceLibraryGroupKey(entry), it)
                                                    },
                                                    onLongPress = {
                                                        startLibrarySelection(sourceLibraryGroupKey(entry))
                                                    },
                                                    onToggleSelected = {
                                                        toggleLibraryItemSelection(sourceLibraryGroupKey(entry))
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
                                                    nextAiringEpisode = airingEpisodesByItemKey[shelfItem.key],
                                                    episodeNames = showEpisodes.asSequence()
                                                        .take(8)
                                                        .map { it.displayName }
                                                        .toList(),
                                                    coverSource = customCover,
                                                    menuExpanded = expandedMenus[showName] == true,
                                                    selectionMode = selectingLibraryItems,
                                                    selected = localLibraryGroupKey(showName) in selectedLibraryItemKeys,
                                                    onLongPress = {
                                                        startLibrarySelection(localLibraryGroupKey(showName))
                                                    },
                                                    onToggleSelected = {
                                                        toggleLibraryItemSelection(localLibraryGroupKey(showName))
                                                    },
                                                    onToggle = { expandedShow = showName },
                                                    onMenu = { expandedMenus[showName] = true },
                                                    onDismissMenu = { expandedMenus[showName] = false },
                                                    availableGroups = libraryGroups,
                                                    assignedGroups = groupAssignments[localLibraryGroupKey(showName)].orEmpty(),
                                                    onToggleGroup = {
                                                        toggleGroupAssignment(localLibraryGroupKey(showName), it)
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


    if (showBatchGroupDialog) {
        val selectedCount = selectedLibraryItemKeys.size
        AlertDialog(
            onDismissRequest = { showBatchGroupDialog = false },
            title = { Text("Add selected to groups") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose groups for $selectedCount selected items.")
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(libraryGroups, key = { "batch-group:$it" }) { groupName ->
                            val checked = groupName in batchGroupDraft
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = { isChecked ->
                                            batchGroupDraft = if (isChecked) {
                                                batchGroupDraft + groupName
                                            } else {
                                                batchGroupDraft - groupName
                                            }
                                        },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(groupName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = ::addSelectedItemsToGroups,
                    enabled = batchGroupDraft.isNotEmpty(),
                ) {
                    Text(if (batchGroupDraft.size == 1) "Add to group" else "Add to groups")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchGroupDialog = false }) { Text("Cancel") }
            },
        )
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
                    if (libraryGroups.isEmpty()) {
                        Text("Add titles to a group from their options menus.")
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
            dismissButton = {
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
                ) {
                    Text("Create group")
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

@android.annotation.SuppressLint("DirectDateInstantiation")
private fun formatAiringDate(airingAtEpochSeconds: Long): String =
    SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
        .format(Date(airingAtEpochSeconds * 1_000L))

@Composable
private fun ShowDetailScreen(
    aniList: AniListClient,
    showName: String,
    episodes: List<AnimeLibraryEpisode>,
    recent: List<AppDatabase.RecentMedia>,
    onBack: () -> Unit,
    onOpenVideo: (AnimeLibraryEpisode, NextEpisodeAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var nextAiringEpisode by remember(showName) {
        mutableStateOf<AniListAiringEpisode?>(null)
    }
    LaunchedEffect(aniList, showName, episodes) {
        nextAiringEpisode = withContext(Dispatchers.IO) {
            aniList.findNextAiringEpisode(showName, episodes.map(AnimeLibraryEpisode::displayName))
        }
    }

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
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${episodes.size} episodes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            nextAiringEpisode?.let { airing ->
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Next ep. ${airing.episode} · ${formatAiringDate(airing.airingAtEpochSeconds)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
        }
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
private fun GroupAssignmentMenuItems(
    availableGroups: List<String>,
    assignedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onDismissMenu: () -> Unit,
) {
    if (availableGroups.isEmpty()) {
        DropdownMenuItem(
            text = { Text("No groups yet") },
            enabled = false,
            onClick = {},
        )
    } else {
        availableGroups.forEach { groupName ->
            val assigned = assignedGroups.any { it.equals(groupName, ignoreCase = true) }
            DropdownMenuItem(
                text = {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            groupName,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            if (assigned) Icons.Default.CheckCircle else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = localizeUiText(
                                if (assigned) "Remove $groupName from this title"
                                else "Add this title to $groupName",
                            ),
                            tint = if (assigned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                onClick = {
                    onDismissMenu()
                    onToggleGroup(groupName)
                },
            )
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
    nextAiringEpisode: AniListAiringEpisode?,
    episodeNames: List<String>,
    coverSource: String?,
    menuExpanded: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onLongPress: () -> Unit,
    onToggleSelected: () -> Unit,
    availableGroups: List<String>,
    assignedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onSetUrl: () -> Unit,
    onPickLocal: () -> Unit,
    onRemoveCover: () -> Unit,
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
            .combinedClickable(
                onClick = if (selectionMode) onToggleSelected else onToggle,
                onLongClick = onLongPress,
            ),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (imageBitmap != null) {
                Image(imageBitmap, contentDescription = localizeUiText("$showName artwork"), modifier = Modifier.fillMaxWidth().aspectRatio(0.78f), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.78f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                }
            }
            nextAiringEpisode?.let { airing ->
                Box(
                    Modifier.align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = localizeUiText(
                            "Upcoming episode ${airing.episode} · ${formatAiringDate(airing.airingAtEpochSeconds)}",
                        ),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
                            contentDescription = localizeUiText(
                                if (selected) "Deselect $showName" else "Select $showName",
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
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
                        GroupAssignmentMenuItems(
                            availableGroups = availableGroups,
                            assignedGroups = assignedGroups,
                            onToggleGroup = onToggleGroup,
                            onDismissMenu = onDismissMenu,
                        )
                        DropdownMenuItem(text = { Text("Set image URL") }, onClick = { onDismissMenu(); onSetUrl() })
                        DropdownMenuItem(text = { Text("Choose local image") }, onClick = { onDismissMenu(); onPickLocal() })
                        if (coverSource != null) DropdownMenuItem(text = { Text("Use AniList artwork") }, onClick = { onDismissMenu(); onRemoveCover() })
                    }
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
    nextAiringEpisode: AniListAiringEpisode?,
    selectionMode: Boolean,
    selected: Boolean,
    menuExpanded: Boolean,
    availableGroups: List<String>,
    assignedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onLongPress: () -> Unit,
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
            .combinedClickable(
                onClick = if (selectionMode) onToggleSelected else onOpen,
                onLongClick = onLongPress,
            ),
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
            nextAiringEpisode?.let { airing ->
                Box(
                    Modifier.align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = localizeUiText(
                            "Upcoming episode ${airing.episode} · ${formatAiringDate(airing.airingAtEpochSeconds)}",
                        ),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
                        GroupAssignmentMenuItems(
                            availableGroups = availableGroups,
                            assignedGroups = assignedGroups,
                            onToggleGroup = onToggleGroup,
                            onDismissMenu = onDismissMenu,
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
    val canonicalGroups = readLibraryGroups(database).associateBy { it.lowercase(java.util.Locale.ROOT) }
    return buildMap {
        val keys = stored.keys()
        while (keys.hasNext()) {
            val itemKey = keys.next()
            val assigned = stored.optJSONArray(itemKey) ?: continue
            val names = (0 until assigned.length()).asSequence()
                .mapNotNull { index ->
                    assigned.optString(index).trim().takeIf(String::isNotEmpty)?.let { name ->
                        canonicalGroups[name.lowercase(java.util.Locale.ROOT)] ?: name
                    }
                }
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }
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
