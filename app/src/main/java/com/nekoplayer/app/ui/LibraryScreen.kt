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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.RadioButton
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
import com.nekoplayer.app.extensions.ExtensionManager
import com.nekoplayer.app.library.AniListAiringEpisode
import com.nekoplayer.app.library.AniListAiringLookup
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode
import com.nekoplayer.app.library.JimakuSubtitleClient
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

private const val LIBRARY_EPISODE_BADGE_BASELINE_RESET_KEY = "library.episodeBadgeBaselineReset.v1"

@android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
private fun libraryCurrentEpochSeconds(): Long = System.currentTimeMillis() / 1_000L

@Composable
fun LibraryScreen(
    database: AppDatabase,
    extensionManager: ExtensionManager,
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
    onOpenSourceEntry: (AppDatabase.SourceLibraryEntry, AniListAiringEpisode?) -> Unit,
    onPickCover: (String) -> Unit,
    onDetailScreenChanged: (Boolean) -> Unit,
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artworkCache = remember { sharedLibraryArtworkCache() }
    val jimakuSubtitleClient = remember(context) { JimakuSubtitleClient(context.cacheDir) }
    var recent by remember { mutableStateOf(emptyList<AppDatabase.RecentMedia>()) }
    var sourceEntries by remember(database) { mutableStateOf(database.listSourceLibraryEntries()) }
    var sourceRecency by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var covers by remember {
        mutableStateOf((database.readSetting("library.customCovers") as? org.json.JSONObject) ?: org.json.JSONObject())
    }
    var libraryGroups by remember(database) { mutableStateOf(readLibraryGroups(database)) }
    val storedGroupAssignments = remember(database) { readLibraryGroupAssignments(database) }
    var groupAssignments by remember(database) {
        mutableStateOf(normalizeLibraryGroupAssignments(storedGroupAssignments, libraryGroups))
    }
    var collapsedGroups by remember(database) {
        mutableStateOf(readCollapsedLibraryGroups(database))
    }
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
    var seenEpisodeCounts by remember(database) {
        mutableStateOf(readLibrarySeenEpisodeCounts(database))
    }
    var jimakuApiKey by remember(database) { mutableStateOf(database.readString("jimaku.apiKey")) }
    var subtitleStatuses by remember(database) { mutableStateOf(emptyMap<String, LibrarySubtitleStatus>()) }
    var aniListSubtitleMatches by remember(database) {
        mutableStateOf(emptyMap<String, CachedAniListLibraryMatch>())
    }
    var manualJimakuMatches by remember(database) { mutableStateOf(emptyMap<String, Int>()) }
    var subtitleCacheLoaded by remember(database) { mutableStateOf(false) }
    var subtitleRefreshStarted by remember(database) { mutableStateOf(false) }
    var subtitleRefreshing by remember(database) { mutableStateOf(false) }
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
        jimakuApiKey = withContext(Dispatchers.IO) { database.readString("jimaku.apiKey") }
    }
    LaunchedEffect(database) {
        val (loadedStatuses, loadedMatches, loadedManualMatches) = withContext(Dispatchers.IO) {
            Triple(
                readLibrarySubtitleStatusCache(database),
                readAniListMatchCache(database),
                readManualJimakuMatchCache(database),
            )
        }
        subtitleStatuses = loadedStatuses
        aniListSubtitleMatches = loadedMatches
        manualJimakuMatches = loadedManualMatches
        subtitleCacheLoaded = true
    }
    LaunchedEffect(database, extensionManager) {
        sourceEntries = refreshLibrarySourceEpisodeCounts(database, extensionManager)
    }
    LaunchedEffect(database) {
        val resetBaseline = withContext(Dispatchers.IO) {
            if (database.readBoolean(LIBRARY_EPISODE_BADGE_BASELINE_RESET_KEY)) {
                false
            } else {
                writeLibrarySeenEpisodeCounts(database, emptyMap())
                database.writeSetting(LIBRARY_EPISODE_BADGE_BASELINE_RESET_KEY, true)
                true
            }
        }
        if (resetBaseline) seenEpisodeCounts = emptyMap()
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
    val libraryItems = remember(libraryShelves) {
        libraryShelves.flatMap(LibraryShelf::entries).distinctBy(LibraryShelfItem::key)
    }
    LaunchedEffect(libraryItems, aniListSubtitleMatches, subtitleCacheLoaded) {
        if (!subtitleCacheLoaded) return@LaunchedEffect

        val currentItemKeys = libraryItems.mapTo(mutableSetOf(), LibraryShelfItem::key)
        val resolvedCacheKeys = mutableSetOf<String>()
        val cachedSchedules = buildMap {
            val nowEpochSeconds = libraryCurrentEpochSeconds()
            libraryItems.forEach { item ->
                val lookup = item.toAiringLookup()
                val cached = aniListSubtitleMatches[item.key]
                    ?.takeIf { it.signature == aniListLookupSignature(lookup) }
                    ?: return@forEach
                resolvedCacheKeys += item.key
                cached.match.nextAiringEpisode
                    ?.takeIf { it.airingAtEpochSeconds > nowEpochSeconds }
                    ?.let { put(item.key, it) }
            }
        }
        airingEpisodesByItemKey = airingEpisodesByItemKey
            .filterKeys { it in currentItemKeys && it !in resolvedCacheKeys } + cachedSchedules
    }

    LaunchedEffect(database, subtitleCacheLoaded, jimakuApiKey, libraryShelves) {
        if (
            !subtitleCacheLoaded ||
            subtitleRefreshStarted ||
            jimakuApiKey.isBlank() ||
            libraryShelves.isEmpty()
        ) return@LaunchedEffect
        subtitleRefreshStarted = true
        subtitleRefreshing = true
        try {
            val result = withContext(Dispatchers.IO) {
                refreshLibrarySubtitleAvailability(
                    aniList = aniList,
                    jimaku = jimakuSubtitleClient,
                    apiKey = jimakuApiKey,
                    items = libraryShelves.flatMap(LibraryShelf::entries),
                    cachedStatuses = subtitleStatuses,
                    cachedAniListMatches = aniListSubtitleMatches,
                    manualEntryIds = manualJimakuMatches,
                    lastBatchCheckMs = database.readNumber(LIBRARY_JIMAKU_BATCH_CHECK_KEY).toLong(),
                ).also { refreshed ->
                    writeLibrarySubtitleStatusCache(database, refreshed.statuses)
                    writeAniListMatchCache(database, refreshed.aniListMatches)
                    refreshed.batchCheckedAtMs?.let { database.writeSetting(LIBRARY_JIMAKU_BATCH_CHECK_KEY, it) }
                }
            }
            subtitleStatuses = result.statuses
            aniListSubtitleMatches = result.aniListMatches
        } finally {
            subtitleRefreshing = false
        }
    }

    fun subtitleStatusFor(item: LibraryShelfItem): LibrarySubtitleStatus? {
        val currentEpisode = item.episodeCount.takeIf { it in 1..300 } ?: return null
        val cached = subtitleStatuses[item.key]
        if (cached == null) {
            val state = when {
                jimakuApiKey.isBlank() -> LibrarySubtitleState.KEY_REQUIRED
                subtitleRefreshing -> LibrarySubtitleState.CHECKING
                else -> LibrarySubtitleState.MATCHING
            }
            return LibrarySubtitleStatus(currentEpisode, state, checkedAtMs = 0L)
        }
        if (cached.state !in setOf(LibrarySubtitleState.AVAILABLE, LibrarySubtitleState.MISSING)) {
            return cached.copy(episodeNumber = currentEpisode)
        }
        val latestAvailable = cached.latestAvailableEpisode
            ?: cached.episodeNumber.takeIf { cached.state == LibrarySubtitleState.AVAILABLE }
        return cached.copy(
            episodeNumber = currentEpisode,
            state = if (latestAvailable != null && latestAvailable >= currentEpisode) {
                LibrarySubtitleState.AVAILABLE
            } else {
                LibrarySubtitleState.MISSING
            },
            latestAvailableEpisode = latestAvailable,
        )
    }
    fun hasUnseenEpisode(item: LibraryShelfItem): Boolean {
        val count = item.episodeCount
        val seenCount = seenEpisodeCounts[item.key] ?: 0
        return count > seenCount
    }

    fun shouldShowNewEpisodeBell(item: LibraryShelfItem): Boolean =
        hasUnseenEpisode(item) && airingEpisodesByItemKey[item.key] != null

    fun markEpisodesSeen(item: LibraryShelfItem) {
        val episodeCount = item.episodeCount
        if (episodeCount <= 0 || episodeCount <= (seenEpisodeCounts[item.key] ?: 0)) return
        val updated = seenEpisodeCounts + (item.key to episodeCount)
        seenEpisodeCounts = updated
        scope.launch(Dispatchers.IO) { writeLibrarySeenEpisodeCounts(database, updated) }
    }

    LaunchedEffect(aniList, libraryShelves, collapsedGroups, subtitleCacheLoaded) {
        if (!subtitleCacheLoaded) return@LaunchedEffect
        val lookups = libraryShelves
            .asSequence()
            .filterNot { it.key in collapsedGroups }
            .flatMap { it.entries.asSequence() }
            .distinctBy(LibraryShelfItem::key)
            .map(LibraryShelfItem::toAiringLookup)
            .toList()
        if (lookups.isEmpty()) return@LaunchedEffect

        val itemKeys = lookups.mapTo(mutableSetOf(), AniListAiringLookup::key)
        val schedules = withContext(Dispatchers.IO) {
            aniList.findNextAiringEpisodes(lookups)
        }
        airingEpisodesByItemKey =
            airingEpisodesByItemKey.filterKeys { it !in itemKeys } + schedules
    }

    val selectedEpisodes = remember(expandedShow, groups) {
        expandedShow?.let { name -> groups[name] }.orEmpty()
    }

    fun saveLibraryGroups(updatedGroups: List<String>) {
        libraryGroups = updatedGroups
        database.writeSetting("library.groups", updatedGroups)
    }

    fun saveGroupAssignments(updatedAssignments: Map<String, Set<String>>) {
        val normalizedAssignments = normalizeLibraryGroupAssignments(updatedAssignments, libraryGroups)
        groupAssignments = normalizedAssignments
        val encoded = org.json.JSONObject()
        normalizedAssignments.forEach { (itemKey, assignedGroups) ->
            if (assignedGroups.isNotEmpty()) {
                encoded.put(itemKey, org.json.JSONArray(assignedGroups.sorted()))
            }
        }
        database.writeSetting("library.groupAssignments", encoded)
    }

    LaunchedEffect(database, storedGroupAssignments) {
        val normalizedAssignments = normalizeLibraryGroupAssignments(storedGroupAssignments, libraryGroups)
        if (normalizedAssignments != storedGroupAssignments) {
            saveGroupAssignments(normalizedAssignments)
        }
    }

    fun saveCollapsedGroups(updatedCollapsedGroups: Set<String>) {
        collapsedGroups = updatedCollapsedGroups
        writeCollapsedLibraryGroups(database, updatedCollapsedGroups)
    }

    fun deleteLibraryGroup(groupName: String) {
        saveLibraryGroups(libraryGroups.filterNot { it.equals(groupName, ignoreCase = true) })
        saveGroupAssignments(
            groupAssignments.mapValues { (_, assigned) -> assigned - groupName }
                .filterValues { it.isNotEmpty() },
        )
        saveCollapsedGroups(collapsedGroups - "group:$groupName")
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
        val groupName = batchGroupDraft.singleOrNull() ?: return
        if (selectedLibraryItemKeys.isEmpty()) return
        val updated = groupAssignments.toMutableMap()
        selectedLibraryItemKeys.forEach { itemKey ->
            updated[itemKey] = setOf(groupName)
        }
        saveGroupAssignments(updated)
        showBatchGroupDialog = false
        clearLibrarySelection()
    }

    fun toggleGroupAssignment(itemKey: String, groupName: String) {
        val updated = groupAssignments.toMutableMap()
        val current = updated[itemKey].orEmpty()
        val alreadyAssigned = current.any { it.equals(groupName, ignoreCase = true) }
        val next = if (alreadyAssigned) emptySet() else setOf(groupName)
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

    BackHandler(enabled = isActive && ((expandedShow != null) || selectingLibraryItems)) {
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
    LaunchedEffect(expandedShow, isActive) {
        if (isActive) onDetailScreenChanged(expandedShow != null)
    }

    if ((expandedShow != null) && selectedEpisodes.isNotEmpty()) {
        ShowDetailScreen(
            showName = expandedShow.orEmpty(),
            episodes = selectedEpisodes,
            nextAiringEpisode = airingEpisodesByItemKey["show:${expandedShow.orEmpty()}"],
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
                                if (libraryGroups.isEmpty()) "Create" else "Manage groups",
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
                                val updatedCollapsedGroups = if (expanded) {
                                    collapsedGroups + shelfKey
                                } else {
                                    collapsedGroups - shelfKey
                                }
                                saveCollapsedGroups(updatedCollapsedGroups)
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
                                val columnCount = 2
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
                                                    newEpisodeAvailable = shouldShowNewEpisodeBell(shelfItem),
                                                    subtitleStatus = subtitleStatusFor(shelfItem),
                                                    selectionMode = selectingLibraryItems,
                                                    selected = sourceLibraryGroupKey(entry) in selectedLibraryItemKeys,
                                                    menuExpanded = expandedMenus["source:${entry.key}"] == true,
                                                    onOpen = {
                                                        markEpisodesSeen(shelfItem)
                                                        onOpenSourceEntry(entry, airingEpisodesByItemKey[shelfItem.key])
                                                    },
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
                                                    newEpisodeAvailable = shouldShowNewEpisodeBell(shelfItem),
                                                    subtitleStatus = subtitleStatusFor(shelfItem),
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
                                                    onToggle = {
                                                        markEpisodesSeen(shelfItem)
                                                        expandedShow = showName
                                                    },
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
            title = { Text("Add selected to a group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose one group for $selectedCount selected items.")
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(libraryGroups, key = { "batch-group:$it" }) { groupName ->
                            val checked = batchGroupDraft.any { it.equals(groupName, ignoreCase = true) }
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        role = Role.RadioButton,
                                        onClick = { batchGroupDraft = setOf(groupName) },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = checked, onClick = null)
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
                    Text("Add to group")
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
                        shape = RoundedCornerShape(16.dp),
                        label = { Text("New group name") },
                        singleLine = true,
                    )
                    if (libraryGroups.isEmpty()) {
                        Text("Add titles to a group from their options menus.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            itemsIndexed(
                                libraryGroups,
                                key = { _, groupName -> "library-group:$groupName" },
                            ) { index, groupName ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(groupName, Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            saveLibraryGroups(moveLibraryGroup(libraryGroups, groupName, index - 1))
                                        },
                                        enabled = index > 0,
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = localizeUiText("Move $groupName up"),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            saveLibraryGroups(moveLibraryGroup(libraryGroups, groupName, index + 1))
                                        },
                                        enabled = index < libraryGroups.lastIndex,
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = localizeUiText("Move $groupName down"),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            groupToDelete = groupName
                                            showGroupManager = false
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = localizeUiText("Delete $groupName"),
                                            tint = MaterialTheme.colorScheme.error,
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
                            saveLibraryGroups(libraryGroups + name)
                            groupNameDraft = ""
                        }
                    },
                    enabled = groupNameDraft.isNotBlank() &&
                        libraryGroups.none { it.equals(groupNameDraft.trim(), ignoreCase = true) },
                ) {
                    Text("Create")
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
            text = {
                Text("Delete “$deletingGroup”? Titles in this group will stay in your library and become ungrouped.")
            },
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
