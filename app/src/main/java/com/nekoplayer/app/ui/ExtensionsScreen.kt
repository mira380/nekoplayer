package com.nekoplayer.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.extensions.ExtensionManager
import com.nekoplayer.app.library.AniListClient
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import com.nekoplayer.app.extensions.model.Extension
import com.nekoplayer.app.extensions.model.ExtensionApiVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val extensionIconCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Suppress("DEPRECATION")
@Composable
fun ExtensionsScreen(
    manager: ExtensionManager,
    database: AppDatabase,
    aniList: AniListClient,
    sourceEntryToOpen: AppDatabase.SourceLibraryEntry?,
    onSourceEntryOpened: () -> Unit,
    onReturnToLibrary: () -> Unit,
    onPlayVideo: (
        android.net.Uri,
        String,
        String?,
        Map<String, String>,
        String?,
        Int?,
        AppDatabase.SourceProgressIdentity?,
        String?,
        NextEpisodeAction?,
        List<TimeStamp>,
    ) -> Unit,
    onDetailScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val installed by manager.installed.collectAsState()
    val available by manager.available.collectAsState()
    val repoUrls by manager.repoUrls.collectAsState()
    val loadErrors by manager.loadErrors.collectAsState()
    val error by manager.error.collectAsState()
    val loading by manager.loading.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedPage by remember { mutableIntStateOf(0) }
    var extensionMode by remember { mutableIntStateOf(0) }
    var browseQuery by remember { mutableStateOf("") }
    var selectedLanguages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var languageDraft by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf<ExtensionManager.InstalledVideoSource?>(null) }
    var selectedSourceSettings by remember { mutableStateOf<ExtensionManager.InstalledVideoSource?>(null) }
    var showRepositories by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Extension.Installed?>(null) }
    var busyPackage by remember { mutableStateOf<String?>(null) }
    var updatingAll by remember { mutableStateOf(false) }
    var selectedAnimeToOpen by remember { mutableStateOf<SAnime?>(null) }
    var sourceEntryError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { manager.start() }
    LaunchedEffect(sourceEntryToOpen, installed, loading) {
        val entry = sourceEntryToOpen ?: return@LaunchedEffect
        val installedSource = manager.catalogueSources().firstOrNull {
            it.source.id == entry.sourceId
        }
        if (installedSource != null) {
            selectedPage = 0
            selectedSourceSettings = null
            selectedSource = installedSource
            selectedAnimeToOpen = SAnime.create().apply {
                url = entry.animeUrl
                title = entry.title
                thumbnail_url = entry.thumbnailUrl
                description = entry.description
                initialized = false
            }
            sourceEntryError = null
            onSourceEntryOpened()
        } else if (!loading) {
            sourceEntryError = "${entry.extensionName} is not installed. Install it to open this saved title."
            onSourceEntryOpened()
        }
    }
    LaunchedEffect(selectedSource, selectedSourceSettings) {
        onDetailScreenChanged(selectedSource != null || selectedSourceSettings != null)
    }
    BackHandler(enabled = selectedSource != null || selectedSourceSettings != null) {
        if (selectedSourceSettings != null) {
            selectedSourceSettings = null
        } else {
            val openedFromLibrary = selectedAnimeToOpen != null
            selectedSource = null
            selectedAnimeToOpen = null
            if (openedFromLibrary) {
                onDetailScreenChanged(false)
                onReturnToLibrary()
            }
        }
    }

    val settingsSource = selectedSourceSettings
    if (settingsSource != null) {
        SourceSettingsScreen(
            item = settingsSource,
            onBack = { selectedSourceSettings = null },
            modifier = modifier,
        )
        return
    }

    val source = selectedSource
    if (source != null) {
        VideoSourceBrowser(
            item = source,
            database = database,
            aniList = aniList,
            initialAnime = selectedAnimeToOpen,
            onBack = {
                val openedFromLibrary = selectedAnimeToOpen != null
                selectedSource = null
                selectedAnimeToOpen = null
                if (openedFromLibrary) {
                    onDetailScreenChanged(false)
                    onReturnToLibrary()
                }
            },
            onPlayVideo = onPlayVideo,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selectedPage == 0) "Video sources" else "Extensions",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = { showRepositories = true }) {
                Icon(Icons.Default.Add, contentDescription = localizeUiText("Add video source repository"))
            }
            IconButton(
                enabled = !loading,
                onClick = { scope.launch { manager.refresh() } },
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = localizeUiText("Refresh extensions"))
                }
            }
        }

        TabRow(selectedTabIndex = selectedPage) {
            Tab(selected = selectedPage == 0, onClick = { selectedPage = 0 }, text = { Text("Video sources") })
            Tab(selected = selectedPage == 1, onClick = { selectedPage = 1 }, text = { Text("Extensions") })
        }

        val visibleError = sourceEntryError ?: error
        if (visibleError != null) {
            Text(
                visibleError,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (selectedPage == 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = extensionMode == 0, onClick = { extensionMode = 0 }, label = { Text("Installed") })
                FilterChip(selected = extensionMode == 1, onClick = { extensionMode = 1 }, label = { Text("Browse") })
            }

            if (extensionMode == 0) {
                val updates = installed.filter { it.hasUpdate }
                if (updates.isNotEmpty()) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Updates pending", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${updates.size} video source${if (updates.size == 1) "" else "s"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                enabled = !updatingAll,
                                onClick = {
                                    scope.launch {
                                        updatingAll = true
                                        withContext(Dispatchers.IO) { manager.updateAll() }
                                        updatingAll = false
                                    }
                                },
                            ) { Text(if (updatingAll) "Updating…" else "Update all") }
                        }
                        if (updatingAll) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading && installed.isEmpty()) {
                        item { LoadingCard("Loading installed video sources…") }
                    } else if (installed.isEmpty()) {
                        item {
                            EmptyCard(
                                title = "No video extensions installed",
                                body = "Browse the video-source repository to add a catalogue.",
                            )
                        }
                    }
                    if (loadErrors.isNotEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Couldn’t load some extensions", style = MaterialTheme.typography.titleSmall)
                                    loadErrors.forEach { (name, reason) ->
                                        Material3Text(
                                            "${name}: ${reason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(installed, key = { it.pkgName }) { extension ->
                        InstalledExtensionCard(
                            extension = extension,
                            update = available.firstOrNull { it.pkgName == extension.pkgName && it.isSupported },
                            busy = busyPackage == extension.pkgName,
                            onUpdate = { remote ->
                                scope.launch {
                                    busyPackage = extension.pkgName
                                    withContext(Dispatchers.IO) { manager.install(remote) }
                                    busyPackage = null
                                }
                            },
                            onRemove = { removeTarget = extension },
                        )
                    }
                }
            } else {
                val languages = remember(available) {
                    available.flatMap(::extensionLanguageTags)
                        .distinct()
                        .sortedBy { extensionLanguageLabel(it).lowercase() }
                }
                LaunchedEffect(languages) {
                    selectedLanguages = selectedLanguages.intersect(languages.toSet())
                    languageDraft = languageDraft.intersect(languages.toSet())
                }
                val results = available.filter { extension ->
                    val matchesLanguage = selectedLanguages.isEmpty() ||
                        extensionLanguageTags(extension).any(selectedLanguages::contains)
                    val matchesQuery = browseQuery.isBlank() || extension.name.contains(browseQuery, true) ||
                        extension.lang.contains(browseQuery, true) ||
                        extension.sources.any { source -> source.name.contains(browseQuery, true) }
                    matchesLanguage && matchesQuery
                }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = browseQuery,
                            onValueChange = { browseQuery = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            label = { Text("Search video sources") },
                            singleLine = true,
                        )
                        OutlinedButton(
                            onClick = {
                                languageDraft = selectedLanguages
                                showLanguageDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (selectedLanguages.isEmpty()) "Languages" else "Languages (" + selectedLanguages.size + ")")
                        }
                    }
                    if (showLanguageDialog) {
                        AlertDialog(
                            onDismissRequest = { showLanguageDialog = false },
                            title = { Text("Languages") },
                            text = {
                                Column {
                                    Text(
                                        "Choose one or more languages. Leave all unselected to show every language.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                        items(languages, key = { it }) { language ->
                                            val nativeLabel = extensionNativeLanguageLabel(language)
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .heightIn(min = 60.dp)
                                                    .padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(nativeLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                                    val englishLabel = extensionLanguageLabel(language)
                                                    if (!nativeLabel.equals(englishLabel, ignoreCase = true)) {
                                                        Text(
                                                            englishLabel,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                Switch(
                                                    checked = language in languageDraft,
                                                    onCheckedChange = { checked ->
                                                        languageDraft =
                                                            if (checked) languageDraft + language
                                                            else languageDraft - language
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        selectedLanguages = languageDraft
                                        showLanguageDialog = false
                                    },
                                ) { Text("Apply") }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = { languageDraft = emptySet() }) { Text("Clear") }
                                    TextButton(onClick = { showLanguageDialog = false }) { Text("Cancel") }
                                }
                            },
                        )
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (loading && available.isEmpty()) item { LoadingCard("Loading the extension catalogue…") }
                        if (!loading && available.isEmpty()) {
                            item {
                                EmptyCard(
                                    title = "No extensions available",
                                    body = if (repoUrls.isEmpty()) {
                                        "Add a video-source repository to browse and install extensions."
                                    } else {
                                        "Check the repository connection or add another video-source repository."
                                    }
                                )
                            }
                        }
                        if (available.isNotEmpty() && results.isEmpty()) {
                            item {
                                EmptyCard(
                                    title = "No matching extensions",
                                    body = "Try a different language or search term.",
                                )
                            }
                        }
                        items(results, key = { it.pkgName }) { extension ->
                            AvailableExtensionCard(
                                extension = extension,
                                installed = installed.firstOrNull { it.pkgName == extension.pkgName },
                                busy = busyPackage == extension.pkgName,
                                onInstall = {
                                    scope.launch {
                                        busyPackage = extension.pkgName
                                        withContext(Dispatchers.IO) { manager.install(extension) }
                                        busyPackage = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            val sources = manager.catalogueSources()
            val sourcesByLanguage = sources.groupBy {
                it.source.lang.trim().lowercase().ifBlank { "unknown" }
            }
            val orderedLanguages = sourcesByLanguage.keys.sortedBy {
                if (it == "unknown") "zzzz" else extensionLanguageLabel(it).lowercase()
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sources.isEmpty()) {
                    item {
                        EmptyCard(
                            title = "No video sources yet",
                            body = "Add a repository, then install a video extension to browse its catalogue and episodes.",
                        )
                    }
                }
                orderedLanguages.forEach { language ->
                    item(key = "language:$language", contentType = "source_language") {
                        Text(
                            if (language == "unknown") "Other" else extensionLanguageLabel(language),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(
                        items = sourcesByLanguage.getValue(language),
                        key = { it.source.id },
                        contentType = { "source" },
                    ) { item ->
                        Card(
                            onClick = { selectedSource = item },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SourceIcon(
                                    drawable = item.extension.icon,
                                    cacheKey = "installed:" + item.extension.pkgName + ":" + item.extension.versionCode,
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        item.source.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        extensionLanguageLabel(language),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.source is ConfigurableAnimeSource) {
                                    IconButton(
                                        onClick = { selectedSourceSettings = item },
                                    ) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = localizeUiText("Settings for ${item.source.name}"),
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

    if (showRepositories) {
        RepositoryDialog(
            repositories = repoUrls,
            onDismiss = { showRepositories = false },
            onAdd = manager::addRepository,
            onRemove = { url -> scope.launch { manager.removeRepository(url) } },
        )
    }
    if (removeTarget != null) {
        val target = removeTarget!!
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${target.name}?") },
            text = { Text("Its video sources will disappear from the catalogue list. You can install it again later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        scope.launch {
                            busyPackage = target.pkgName
                            withContext(Dispatchers.IO) { manager.remove(target.pkgName) }
                            busyPackage = null
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun LoadingCard(label: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun EmptyCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RepositoryDialog(
    repositories: List<String>,
    onDismiss: () -> Unit,
    onAdd: suspend (String) -> Result<Unit>,
    onRemove: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video-source repositories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Repositories provide extensions that run inside nekoplayer. Add repositories you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (repositories.isEmpty()) {
                    Text(
                        "No repositories added yet. Add one below to browse video-source extensions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                repositories.forEach { repo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            repo,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onRemove(repo) }) {
                            Icon(Icons.Default.Delete, contentDescription = localizeUiText("Remove repository"))
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS repository URL") },
                    placeholder = { Text("https://…/repo") },
                    singleLine = true,
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = url.isNotBlank() && !adding,
                        onClick = {
                            adding = true
                            scope.launch {
                                val result = onAdd(url)
                                result.fold(
                                    onSuccess = { url = ""; error = null },
                                    onFailure = { error = it.message ?: "Couldn’t add this repository." },
                                )
                                adding = false
                            }
                        },
                    ) { Text(if (adding) "Checking…" else "Add repository") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

