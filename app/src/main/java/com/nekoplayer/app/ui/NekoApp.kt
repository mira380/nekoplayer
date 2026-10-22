package com.nekoplayer.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Process
import android.util.Log
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.nekoplayer.app.MainActivity
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.extensions.ExtensionManager
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode
import com.nekoplayer.app.library.AnimeLibraryScanner
import com.nekoplayer.app.mining.AnkiDroidClient
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private enum class AppPage(val label: String) {
    Library("Library"),
    Dictionaries("Dictionaries"),
    Extensions("Extensions"),
    Settings("Settings"),
}

internal typealias NextEpisodeAction = ((Boolean) -> Unit) -> Unit

data class VideoTarget(
    val uri: Uri,
    val displayName: String,
    val seriesTitle: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val videoMimeType: String? = null,
    val audioUri: String? = null,
    val audioMimeType: String? = null,
    val subtitleEpisodeName: String? = null,
    val subtitleEpisodeNumber: Int? = null,
    val sourceProgressIdentity: AppDatabase.SourceProgressIdentity? = null,
    val alternateSeriesTitle: String? = null,
    val onPlayNextEpisode: NextEpisodeAction? = null,
    val sourceSkipTimestamps: List<TimeStamp> = emptyList(),
)

@Composable
fun NekoApp(
    activity: MainActivity,
    database: AppDatabase,
    anki: AnkiDroidClient,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dictionaryRepository = remember(database) {
        DictionaryRepository(database.sql, context.contentResolver, File(context.cacheDir, "dictionary-imports"))
    }
    val aniList = remember(context) { AniListClient(context) }
    val snackbar = remember { SnackbarHostState() }
    val extensionManager = remember(context, database) { ExtensionManager(context, database) }

    LaunchedEffect(database, dictionaryRepository) {
        withContext(Dispatchers.IO) {
            val threadId = Process.myTid()
            val previousPriority = Process.getThreadPriority(threadId)
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                if (!database.readBoolean(AppDatabase.DICTIONARY_PAYLOAD_COMPACTED_KEY)) {
                    dictionaryRepository.compactExistingTermPayloads()
                    database.writeSetting(AppDatabase.DICTIONARY_PAYLOAD_COMPACTED_KEY, true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("NekoPlayer", "Dictionary storage compaction will resume on the next launch", error)
            } finally {
                Process.setThreadPriority(previousPriority)
            }
        }
    }

    var page by remember { mutableStateOf(AppPage.Library) }
    var pendingSourceEntry by remember { mutableStateOf<AppDatabase.SourceLibraryEntry?>(null) }
    var detailScreen by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(database.readBoolean("appearance.dark", true)) }
    var palette by remember { mutableStateOf(database.readString("appearance.palette", "classic")) }
    var accent by remember { mutableStateOf(database.readString("appearance.accent", "cyan")) }
    var language by remember { mutableStateOf(database.readString("appearance.language", "English")) }
    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderUriLoaded by remember { mutableStateOf(false) }
    var libraryEpisodes by remember { mutableStateOf<List<AnimeLibraryEpisode>>(emptyList()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var libraryScanRequest by remember { mutableIntStateOf(0) }
    var libraryProgressRefreshKey by remember { mutableIntStateOf(0) }
    var hasPlayedVideo by remember { mutableStateOf(false) }
    var activeVideo by remember { mutableStateOf<VideoTarget?>(null) }
    var coverVersion by remember { mutableIntStateOf(0) }
    var dictionaryVersion by remember { mutableIntStateOf(0) }
    var coverTarget by remember { mutableStateOf<String?>(null) }
    var subtitlePickerCallback by remember { mutableStateOf<((Uri, String) -> Unit)?>(null) }
    var videoPermissionCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dictionaryImportStatus by remember { mutableStateOf<String?>(null) }
    var dictionaryImportBusy by remember { mutableStateOf(false) }
    val videoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = videoPermissionCallback
        videoPermissionCallback = null
        if (granted) callback?.invoke() else scope.launch { snackbar.showSnackbar(localizeUiText("Allow video access to open this older file path.", language)) }
    }

    LaunchedEffect(Unit) {
        folderUri = withContext(Dispatchers.IO) { database.readSetting("library.folderPath") as? String }
        libraryLoading = !folderUri.isNullOrBlank()
        folderUriLoaded = true
    }

    LaunchedEffect(folderUri, folderUriLoaded, libraryScanRequest) {
        if (!folderUriLoaded) return@LaunchedEffect
        if (folderUri.isNullOrBlank()) {
            libraryEpisodes = emptyList()
            libraryError = null
            libraryLoading = false
            return@LaunchedEffect
        }
        val selectedFolderUri = folderUri ?: return@LaunchedEffect
        libraryLoading = true
        libraryError = null
        try {
            libraryEpisodes = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(context, selectedFolderUri.toUri())
                    ?: error("The saved library folder is unavailable.")
                AnimeLibraryScanner().scan(root)
            }
        } catch (_: Exception) {
            libraryError = "Couldn’t scan this folder. Choose it again or pick another folder."
            libraryEpisodes = emptyList()
        } finally {
            libraryLoading = false
        }
    }

    fun grantPersistedAccess(uri: Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers grant one-shot access only; the picked document remains usable this session.
        }
    }

    fun displayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column)?.takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Video"
    }

    fun openVideo(
        sourceUri: Uri,
        name: String,
        seriesTitle: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        videoMimeType: String? = null,
        audioUri: String? = null,
        audioMimeType: String? = null,
        subtitleEpisodeName: String? = null,
        subtitleEpisodeNumber: Int? = null,
        sourceProgressIdentity: AppDatabase.SourceProgressIdentity? = null,
        alternateSeriesTitle: String? = null,
        onPlayNextEpisode: NextEpisodeAction? = null,
        sourceSkipTimestamps: List<TimeStamp> = emptyList(),
    ) {
        val uri = if (sourceUri.scheme.isNullOrBlank()) Uri.fromFile(File(sourceUri.toString())) else sourceUri
        if (uri.scheme == "file") {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(activity, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                videoPermissionCallback = {
                    activeVideo = VideoTarget(
                        uri,
                        name,
                        seriesTitle,
                        requestHeaders,
                        videoMimeType,
                        audioUri,
                        audioMimeType,
                        subtitleEpisodeName,
                        subtitleEpisodeNumber,
                        sourceProgressIdentity,
                        alternateSeriesTitle,
                        onPlayNextEpisode,
                        sourceSkipTimestamps,
                    )
                }
                videoPermissionLauncher.launch(permission)
                return
            }
        }
        activeVideo = VideoTarget(
            uri,
            name,
            seriesTitle,
            requestHeaders,
            videoMimeType,
            audioUri,
            audioMimeType,
            subtitleEpisodeName,
            subtitleEpisodeNumber,
            sourceProgressIdentity,
            alternateSeriesTitle,
            onPlayNextEpisode,
            sourceSkipTimestamps,
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            grantPersistedAccess(uri)
            folderUri = uri.toString()
            libraryLoading = true
            libraryScanRequest += 1
            scope.launch(Dispatchers.IO) { database.writeSetting("library.folderPath", uri.toString()) }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            grantPersistedAccess(uri)
            val name = displayName(uri)
            if (AnimeLibraryScanner.isVideoName(name)) openVideo(uri, name)
            else scope.launch { snackbar.showSnackbar(localizeUiText("Choose a supported local video file.", language)) }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selectedUris ->
        val uris = selectedUris.distinct()
        if (uris.isEmpty()) {
            dictionaryImportBusy = false
            dictionaryImportStatus = null
        } else {
            uris.forEach(::grantPersistedAccess)
            dictionaryImportStatus = null
            dictionaryImportBusy = true
            scope.launch {
                val failures = mutableListOf<String>()
                try {
                    uris.forEachIndexed { index, uri ->
                        val name = displayName(uri)
                        dictionaryImportStatus = "Importing ${index + 1} of ${uris.size}"
                        try {
                            withContext(Dispatchers.IO) { dictionaryRepository.importYomitanFile(uri) }
                            dictionaryVersion += 1
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                } finally {
                    dictionaryImportStatus = null
                    dictionaryImportBusy = false
                }
                if (failures.isNotEmpty()) {
                    snackbar.showSnackbar(
                        failures.joinToString("\n") { localizeUiText("$it could not be imported. Choose a valid Yomitan dictionary ZIP.", language) },
                    )
                }
            }
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            grantPersistedAccess(uri)
            val callback = subtitlePickerCallback
            subtitlePickerCallback = null
            callback?.invoke(uri, displayName(uri))
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            grantPersistedAccess(uri)
            val show = coverTarget
            coverTarget = null
            if (show != null) {
                val existing = database.readSetting("library.customCovers") as? JSONObject ?: JSONObject()
                val updated = JSONObject(existing.toString()).put(show, uri.toString())
                scope.launch {
                    withContext(Dispatchers.IO) { database.writeSetting("library.customCovers", updated) }
                    coverVersion += 1
                }
            }
        }
    }
    BackHandler(enabled = activeVideo != null || page != AppPage.Library) {
        if (activeVideo != null) {
            activeVideo = null
        } else {
            page = AppPage.Library
        }
    }
    LaunchedEffect(activeVideo != null) {
        activity.setPlayerImmersive(activeVideo != null)
        if (activeVideo != null) {
            hasPlayedVideo = true
        } else if (hasPlayedVideo) {
            libraryProgressRefreshKey++
            hasPlayedVideo = false
        }
    }

    NekoTheme(darkMode, palette, accent) {
      ProvideNekoLanguage(language) {
        val current = activeVideo
        SideEffect {
            activity.setSystemBarDarkIcons(enabled = !darkMode && current == null)
        }
        Box(Modifier.fillMaxSize()) {
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val safeSides = if (detailScreen) {
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            } else {
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(safeSides))
                    .windowInsetsPadding(WindowInsets.ime),
            ) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth(),
                    ) {
                        LibraryScreen(
                            database = database,
                            extensionManager = extensionManager,
                            aniList = aniList,
                            folderUri = folderUri,
                            episodes = libraryEpisodes,
                            loading = !folderUriLoaded || libraryLoading,
                            error = libraryError,
                            coverVersion = coverVersion,
                            progressRefreshKey = libraryProgressRefreshKey,
                            onPickFolder = { folderPicker.launch(null) },
                            onPickVideo = { videoPicker.launch(arrayOf("*/*")) },
                            onOpenVideo = { uri, name, title, onNextEpisode ->
                                openVideo(uri, name, title, onPlayNextEpisode = onNextEpisode)
                            },
                            onOpenSourceEntry = { entry ->
                                pendingSourceEntry = entry
                                page = AppPage.Extensions
                            },
                            onPickCover = { show -> coverTarget = show; coverPicker.launch(arrayOf("image/*")) },
                            onDetailScreenChanged = { detailScreen = it },
                            isActive = page == AppPage.Library,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (page == AppPage.Library) Modifier
                                    else Modifier.alpha(0f).clearAndSetSemantics {},
                                ),
                        )
                        if (page != AppPage.Library) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) {},
                            ) {
                                when (page) {
                                    AppPage.Library -> Unit
                                    AppPage.Dictionaries -> DictionaryScreen(
                                        repository = dictionaryRepository,
                                        refreshKey = dictionaryVersion,
                                        onImportDictionary = {
                                            dictionaryImportStatus = null
                                            importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                        },
                                        dictionaryImportStatus = dictionaryImportStatus,
                                        dictionaryImportBusy = dictionaryImportBusy,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    AppPage.Extensions -> {
                                        ExtensionsScreen(
                                            manager = extensionManager,
                                            database = database,
                                            aniList = aniList,
                                            sourceEntryToOpen = pendingSourceEntry,
                                            onSourceEntryOpened = { pendingSourceEntry = null },
                                            onReturnToLibrary = {
                                                page = AppPage.Library
                                                pendingSourceEntry = null
                                                detailScreen = false
                                            },
                                            onPlayVideo = { uri, name, title, headers, episodeName, episodeNumber, progressIdentity, alternateTitle, onNextEpisode, sourceSkipTimestamps ->
                                                openVideo(
                                                    sourceUri = uri,
                                                    name = name,
                                                    seriesTitle = title,
                                                    requestHeaders = headers,
                                                    subtitleEpisodeName = episodeName,
                                                    subtitleEpisodeNumber = episodeNumber,
                                                    sourceProgressIdentity = progressIdentity,
                                                    alternateSeriesTitle = alternateTitle,
                                                    onPlayNextEpisode = onNextEpisode,
                                                    sourceSkipTimestamps = sourceSkipTimestamps,
                                                )
                                            },
                                            onDetailScreenChanged = { detailScreen = it },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    AppPage.Settings -> SettingsScreen(
                                        database = database,
                                        dictionaries = dictionaryRepository,
                                        anki = anki,
                                        darkMode = darkMode,
                                        language = language,
                                        palette = palette,
                                        accent = accent,
                                        onDarkModeChanged = { value ->
                                            darkMode = value
                                            scope.launch(Dispatchers.IO) { database.writeSetting("appearance.dark", value) }
                                        },
                                        onLanguageChanged = { value ->
                                            language = value
                                            scope.launch(Dispatchers.IO) { database.writeSetting("appearance.language", value) }
                                        },
                                        onPaletteChanged = { value ->
                                            palette = value
                                            scope.launch(Dispatchers.IO) { database.writeSetting("appearance.palette", value) }
                                        },
                                        onAccentChanged = { value ->
                                            accent = value
                                            scope.launch(Dispatchers.IO) { database.writeSetting("appearance.accent", value) }
                                        },
                                        onImportDictionary = {
                                            dictionaryImportStatus = null
                                            importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                        },
                                        dictionaryImportStatus = dictionaryImportStatus,
                                        dictionaryImportBusy = dictionaryImportBusy,
                                        refreshKey = dictionaryVersion,
                                        onDetailScreenChanged = { detailScreen = it },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                }
                if (!imeVisible && !detailScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 0.dp,
                    ) {
                        AppPage.entries.forEach { destination ->
                            val icon = when (destination) {
                                AppPage.Library -> Icons.AutoMirrored.Filled.LibraryBooks
                                AppPage.Dictionaries -> Icons.AutoMirrored.Filled.MenuBook
                                AppPage.Extensions -> Icons.Default.VideoLibrary
                                AppPage.Settings -> Icons.Default.Settings
                            }
                            NavigationBarItem(
                                selected = page == destination,
                                onClick = { page = destination; detailScreen = false },
                                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                                label = { Text(destination.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
            if (current != null) {
                NekoPlayerTheme(darkMode, palette, accent) {
                    PlayerScreen(
                        activity = activity,
                        database = database,
                        dictionaries = dictionaryRepository,
                        anki = anki,
                        uri = current.uri,
                        displayName = current.displayName,
                        seriesTitle = current.seriesTitle,
                        videoMimeType = current.videoMimeType,
                        audioUri = current.audioUri,
                        audioMimeType = current.audioMimeType,
                        requestHeaders = current.requestHeaders,
                        subtitleEpisodeName = current.subtitleEpisodeName,
                        subtitleEpisodeNumber = current.subtitleEpisodeNumber,
                        sourceProgressIdentity = current.sourceProgressIdentity,
                        alternateSeriesTitle = current.alternateSeriesTitle,
                        sourceSkipTimestamps = current.sourceSkipTimestamps,
                        onClose = { activeVideo = null },
                        onPickSubtitle = { callback ->
                            subtitlePickerCallback = callback
                            subtitlePicker.launch(arrayOf("*/*"))
                        },
                        onNotify = { message -> Toast.makeText(activity, localizeUiText(message, language), Toast.LENGTH_SHORT).show() },
                        onPlayNextEpisode = current.onPlayNextEpisode,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Register after the underlying screens so Back closes the player first.
            BackHandler(enabled = current != null) {
                activeVideo = null
            }
        }
      }
    }
}
