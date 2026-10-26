package com.nekoplayer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.library.AniListAiringEpisode
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode

import java.net.URL

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EmptyLibraryCard(
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
internal fun ShowDetailScreen(
    showName: String,
    episodes: List<AnimeLibraryEpisode>,
    nextAiringEpisode: AniListAiringEpisode?,
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
internal fun GroupAssignmentMenuItems(
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
internal fun ShowCard(
    context: Context,
    client: AniListClient,
    artworkCache: LruCache<String, Bitmap>,
    showName: String,
    episodeCount: Int,
    newEpisodeAvailable: Boolean = false,
    subtitleStatus: LibrarySubtitleStatus? = null,
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
            if (newEpisodeAvailable) {
                NewEpisodeIndicator(
                    title = showName,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
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
        subtitleStatus?.let { status ->
            SubtitleAvailabilityBadge(status, Modifier.padding(top = 4.dp, start = 2.dp))
        }
    }
}

@Composable
internal fun SourceLibraryCard(
    context: Context,
    client: AniListClient,
    artworkCache: LruCache<String, Bitmap>,
    entry: AppDatabase.SourceLibraryEntry,
    newEpisodeAvailable: Boolean = false,
    subtitleStatus: LibrarySubtitleStatus? = null,
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
            if (newEpisodeAvailable) {
                NewEpisodeIndicator(
                    title = entry.title,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
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
        subtitleStatus?.let { status ->
            SubtitleAvailabilityBadge(status, Modifier.padding(top = 4.dp, start = 2.dp))
        }
    }
}

@Composable
private fun NewEpisodeIndicator(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(36.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.NotificationsActive,
            contentDescription = localizeUiText("New episode available for $title. Open to dismiss."),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(2.dp)
                .size(9.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

@Composable
private fun SubtitleAvailabilityBadge(status: LibrarySubtitleStatus, modifier: Modifier = Modifier) {
    val label = when (status.state) {
        LibrarySubtitleState.AVAILABLE -> "Subs ${status.episodeNumber}/${status.episodeNumber}"
        LibrarySubtitleState.MISSING -> status.latestAvailableEpisode
            ?.takeIf { it > 0 }
            ?.let { "Subs ${it.coerceAtMost(status.episodeNumber)}/${status.episodeNumber}" }
            ?: "No subs"
        LibrarySubtitleState.NO_MATCH -> "No subs"
        LibrarySubtitleState.CHECKING -> "Checking EP${status.episodeNumber}"
        LibrarySubtitleState.RATE_LIMITED -> "Rate limited"
        LibrarySubtitleState.ERROR -> Regex("""HTTP\s+\d{3}""", RegexOption.IGNORE_CASE)
            .find(status.errorMessage.orEmpty())?.value?.uppercase()
            ?: if (status.errorMessage.orEmpty().contains("connection", ignoreCase = true)) "Offline" else status.state.label
        else -> status.state.label
    }
    val earlierSubsFound = status.state == LibrarySubtitleState.MISSING &&
        status.latestAvailableEpisode != null && status.latestAvailableEpisode < status.episodeNumber
    val hasAvailableSubtitles = status.state == LibrarySubtitleState.AVAILABLE
    val noSubtitles = status.state == LibrarySubtitleState.NO_MATCH ||
        (status.state == LibrarySubtitleState.MISSING && !earlierSubsFound)
    val icon = when (status.state) {
        LibrarySubtitleState.AVAILABLE -> Icons.Default.CheckCircle
        LibrarySubtitleState.MISSING,
        LibrarySubtitleState.NO_MATCH -> Icons.Default.Close
        LibrarySubtitleState.MATCHING,
        LibrarySubtitleState.CHECKING -> Icons.Default.Schedule
        LibrarySubtitleState.ANI_LIST_ERROR,
        LibrarySubtitleState.KEY_REQUIRED,
        LibrarySubtitleState.KEY_REJECTED,
        LibrarySubtitleState.RATE_LIMITED,
        LibrarySubtitleState.ERROR -> Icons.Default.Info
    }
    val tint = when (status.state) {
        LibrarySubtitleState.MATCHING,
        LibrarySubtitleState.AVAILABLE,
        LibrarySubtitleState.CHECKING -> MaterialTheme.colorScheme.primary
        LibrarySubtitleState.MISSING -> if (earlierSubsFound) Color(0xFFFFC857)
        else MaterialTheme.colorScheme.error
        LibrarySubtitleState.NO_MATCH -> MaterialTheme.colorScheme.error
        LibrarySubtitleState.ANI_LIST_ERROR,
        LibrarySubtitleState.KEY_REJECTED,
        LibrarySubtitleState.RATE_LIMITED,
        LibrarySubtitleState.ERROR -> MaterialTheme.colorScheme.error
        LibrarySubtitleState.KEY_REQUIRED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .offset(y = (-0.5).dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (hasAvailableSubtitles || earlierSubsFound || noSubtitles) {
            Box(
                Modifier.size(14.dp).background(tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (hasAvailableSubtitles) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(9.dp).offset(y = (-0.25).dp),
                        tint = MaterialTheme.colorScheme.surface,
                    )
                } else if (earlierSubsFound) {
                    Material3Text(
                        "−",
                        color = Color(0xFF302400),
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (noSubtitles) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(9.dp).offset(y = (-0.5).dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        } else {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
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
