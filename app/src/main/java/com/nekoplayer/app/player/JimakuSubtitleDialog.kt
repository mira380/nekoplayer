package com.nekoplayer.app.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekoplayer.app.library.AniListCandidate
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuEntry
import com.nekoplayer.app.library.JimakuFile
import com.nekoplayer.app.library.JimakuSubtitleClient
import com.nekoplayer.app.library.filterJimakuFilesByEpisode
import com.nekoplayer.app.library.parseJimakuEpisodeNumber
import com.nekoplayer.app.ui.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SubtitleDialogPage { Sources, Working, AnimeChoices, Collections, SubtitleFiles }

@Composable
fun JimakuSubtitleDialog(
    showName: String,
    episodeFileName: String,
    apiKey: String,
    aniListClient: AniListClient,
    jimakuSubtitleClient: JimakuSubtitleClient,
    onSubtitleFile: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val episode = remember(episodeFileName) {
        episodeFileName.takeIf(String::isNotBlank)?.let(::parseJimakuEpisodeNumber)
    }
    var page by remember { mutableStateOf(SubtitleDialogPage.Sources) }
    var searchText by remember(showName) { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf(emptyList<AniListCandidate>()) }
    var entries by remember { mutableStateOf(emptyList<JimakuEntry>()) }
    var files by remember { mutableStateOf(emptyList<JimakuFile>()) }
    var selectedEntryId by remember { mutableStateOf<Int?>(null) }
    var automaticFetch by remember { mutableStateOf(false) }

    fun showFailure(message: String) {
        page = SubtitleDialogPage.Sources
        status = null
        error = message
    }

    fun launchIo(onError: (Throwable) -> Unit, task: suspend () -> Unit) {
        scope.launch {
            try {
                task()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onError(failure)
            }
        }
    }

    lateinit var loadCollections: (AniListCandidate, Boolean) -> Unit
    lateinit var openCollection: (JimakuEntry, Boolean) -> Unit
    lateinit var download: (JimakuFile, Int) -> Unit

    download = { file, entryId ->
        page = SubtitleDialogPage.Working
        status = "Downloading ${file.name}…"
        error = null
        launchIo(
            onError = { showFailure(it.message ?: "The subtitle could not be downloaded. Check the connection and try again.") },
        ) {
            val cached = withContext(Dispatchers.IO) { jimakuSubtitleClient.downloadToCache(file, entryId) }
            onSubtitleFile(cached)
            onDismiss()
        }
    }

    openCollection = { entry, automatic ->
        selectedEntryId = entry.id
        page = SubtitleDialogPage.Working
        status = "Listing subtitle files…"
        error = null
        launchIo(
            onError = { showFailure(it.message ?: "Jimaku files could not be listed. Check the connection and try again.") },
        ) {
            val allFiles = withContext(Dispatchers.IO) { jimakuSubtitleClient.listFiles(apiKey, entry.id) }
            if (allFiles.isEmpty()) {
                showFailure("That collection has no downloadable files.")
                return@launchIo
            }
            val matching = episode?.let { filterJimakuFilesByEpisode(allFiles, it) } ?: allFiles
            if (automatic && matching.size == 1) {
                download(matching.single(), entry.id)
            } else {
                files = matching
                status = episode?.let { "Episode $it: pick a subtitle file." } ?: "Pick a subtitle file."
                page = SubtitleDialogPage.SubtitleFiles
            }
        }
    }

    loadCollections = { candidate, automatic ->
        page = SubtitleDialogPage.Working
        status = "Searching Jimaku for ${candidate.title}…"
        error = null
        launchIo(
            onError = { showFailure(it.message ?: "Jimaku search failed. Check the connection and try again.") },
        ) {
            val found = withContext(Dispatchers.IO) { jimakuSubtitleClient.searchEntries(apiKey, candidate.id) }
            if (found.isEmpty()) {
                showFailure("Jimaku has no subtitles for “${candidate.title}”.")
            } else if (automatic && found.size == 1) {
                openCollection(found.single(), true)
            } else {
                entries = found
                status = if (found.size > 1) "Several collections match “${candidate.title}”. Pick one." else "“${candidate.title}”: pick a collection."
                page = SubtitleDialogPage.Collections
            }
        }
    }

    fun searchAniList(query: String, automatic: Boolean) {
        val title = query.trim()
        if (apiKey.isBlank() || title.isEmpty()) return
        page = SubtitleDialogPage.Working
        status = if (automatic) "Finding $title…" else "Searching for “$title”…"
        error = null
        automaticFetch = automatic
        launchIo(
            onError = { showFailure(it.message ?: "AniList search failed. Check the connection and try again.") },
        ) {
            val found = withContext(Dispatchers.IO) {
                aniListClient.searchAnimeCandidates(
                    title,
                    if (automatic && episodeFileName.isNotBlank()) listOf(episodeFileName) else emptyList(),
                )
            }
            if (found.isEmpty()) {
                showFailure(if (automatic) "No AniList match for “$title”. Search below instead." else "No AniList match for “$title”. Try another title.")
            } else if (found.size == 1) {
                loadCollections(found.single(), automatic)
            } else {
                candidates = found
                status = if (automatic) "Several titles match. Pick the right anime." else "Pick the matching anime title."
                page = SubtitleDialogPage.AnimeChoices
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 520.dp).heightIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Load subtitles", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                when (page) {
                    SubtitleDialogPage.Working -> Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(status.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }

                    SubtitleDialogPage.Sources -> Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("From Jimaku", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (apiKey.isBlank()) {
                            Text("Add your Jimaku API key in Settings → Subtitles to fetch subtitles automatically.")
                        } else {
                            val detected = buildList {
                                if (showName.isNotBlank()) add(showName.trim())
                                episode?.let { add("episode $it") }
                            }.joinToString(" · ")
                            if (detected.isNotBlank()) Text(detected, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(
                                onClick = { searchAniList(showName, automatic = true) },
                                enabled = showName.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Fetch for this anime")
                            }
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Search another title") },
                                trailingIcon = {
                                    IconButton(onClick = { searchAniList(searchText, automatic = false) }, enabled = searchText.isNotBlank()) {
                                        Icon(Icons.Rounded.Search, contentDescription = "Search Jimaku")
                                    }
                                },
                            )
                            TextButton(
                                onClick = { searchAniList(searchText, automatic = false) },
                                enabled = searchText.isNotBlank(),
                            ) { Text("Search") }
                        }
                        error?.let { ErrorText(it) }
                    }

                    SubtitleDialogPage.AnimeChoices -> {
                        Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 390.dp)) {
                            items(candidates, key = AniListCandidate::id) { candidate ->
                                ChoiceRow(candidate.title, "AniList match", null) {
                                    loadCollections(candidate, automaticFetch)
                                }
                            }
                        }
                        BackToSources { page = SubtitleDialogPage.Sources; status = null; error = null }
                    }

                    SubtitleDialogPage.Collections -> {
                        Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 390.dp)) {
                            items(entries, key = JimakuEntry::id) { entry ->
                                ChoiceRow(entry.displayName, null, "›") { openCollection(entry, false) }
                            }
                        }
                        BackToSources { page = SubtitleDialogPage.Sources; status = null; error = null }
                    }

                    SubtitleDialogPage.SubtitleFiles -> {
                        Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 390.dp)) {
                            items(files, key = { "${selectedEntryId}:${it.name}" }) { file ->
                                ChoiceRow(
                                    title = file.name,
                                    subtitle = file.size?.let { "${(it + 1023) / 1024} KB" },
                                    trailing = null,
                                    leading = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                ) { selectedEntryId?.let { download(file, it) } }
                            }
                        }
                        BackToSources { page = SubtitleDialogPage.Sources; status = null; error = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    trailing: String?,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun BackToSources(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Back")
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
