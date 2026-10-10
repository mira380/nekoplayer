package com.nekoplayer.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.nekoplayer.app.MainActivity
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.AnimeLibraryEpisode
import com.nekoplayer.app.library.AnimeLibraryScanner
import com.nekoplayer.app.mining.AnkiDroidClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private enum class AppPage(val label: String) {
    Library("Library"),
    Dictionaries("Dictionaries"),
    Settings("Settings"),
}

data class VideoTarget(val uri: Uri, val displayName: String, val seriesTitle: String? = null)

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

    var page by remember { mutableStateOf(AppPage.Library) }
    var detailScreen by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(database.readBoolean("appearance.dark", true)) }
    var language by remember { mutableStateOf(database.readString("appearance.language", "English")) }
    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderUriLoaded by remember { mutableStateOf(false) }
    var libraryEpisodes by remember { mutableStateOf<List<AnimeLibraryEpisode>>(emptyList()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var libraryScanRequest by remember { mutableIntStateOf(0) }
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
        if (granted) callback?.invoke() else scope.launch { snackbar.showSnackbar("Allow video access to open this older file path.") }
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
        libraryLoading = true
        libraryError = null
        try {
            libraryEpisodes = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
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

    fun openVideo(sourceUri: Uri, name: String, seriesTitle: String? = null) {
        val uri = if (sourceUri.scheme.isNullOrBlank()) Uri.fromFile(File(sourceUri.toString())) else sourceUri
        if (uri.scheme == "file" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(activity, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                videoPermissionCallback = { activeVideo = VideoTarget(uri, name, seriesTitle) }
                videoPermissionLauncher.launch(permission)
                return
            }
        }
        activeVideo = VideoTarget(uri, name, seriesTitle)
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
            else scope.launch { snackbar.showSnackbar("Choose a supported local video file.") }
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
                        failures.joinToString("\n") { "$it could not be imported. Choose a valid Yomitan dictionary ZIP." },
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
    LaunchedEffect(activeVideo != null) { activity.setPlayerImmersive(activeVideo != null) }

    NekoTheme(darkMode) {
      provideNekoLanguage(language) {
        val current = activeVideo
        SideEffect { activity.setSystemBarDarkIcons(enabled = !darkMode && current == null) }
        if (current != null) {
            NekoPlayerTheme(darkMode) {
                PlayerScreen(
                    activity = activity,
                    database = database,
                    dictionaries = dictionaryRepository,
                    anki = anki,
                    uri = current.uri,
                    displayName = current.displayName,
                    seriesTitle = current.seriesTitle,
                    onClose = { activeVideo = null },
                    onPickSubtitle = { callback ->
                        subtitlePickerCallback = callback
                        subtitlePicker.launch(arrayOf("*/*"))
                    },
                    onNotify = { message -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
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
                        when (page) {
                        AppPage.Library -> LibraryScreen(
                            database = database,
                            aniList = aniList,
                            folderUri = folderUri,
                            episodes = libraryEpisodes,
                            loading = !folderUriLoaded || libraryLoading,
                            error = libraryError,
                            coverVersion = coverVersion,
                            onPickFolder = { folderPicker.launch(null) },
                            onPickVideo = { videoPicker.launch(arrayOf("*/*")) },
                            onOpenVideo = { uri, name, title -> openVideo(uri, name, title) },
                            onPickCover = { show -> coverTarget = show; coverPicker.launch(arrayOf("image/*")) },
                            onDetailScreenChanged = { detailScreen = it },
                            modifier = Modifier.fillMaxSize(),
                        )
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
                        AppPage.Settings -> SettingsScreen(
                            database = database,
                            dictionaries = dictionaryRepository,
                            anki = anki,
                            darkMode = darkMode,
                            language = language,
                            onDarkModeChanged = { value ->
                                darkMode = value
                                scope.launch(Dispatchers.IO) { database.writeSetting("appearance.dark", value) }
                            },
                            onLanguageChanged = { value ->
                                language = value
                                scope.launch(Dispatchers.IO) { database.writeSetting("appearance.language", value) }
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
                                AppPage.Library -> Icons.Default.LibraryBooks
                                AppPage.Dictionaries -> Icons.Default.MenuBook
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
        }
      }
    }
}
