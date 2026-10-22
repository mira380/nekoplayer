package com.nekoplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.data.ImportedDictionary
import com.nekoplayer.app.mining.AnkiDroidClient
import com.nekoplayer.app.mining.AnkiDroidDeck
import com.nekoplayer.app.mining.AnkiDroidNoteType
import com.nekoplayer.app.mining.AnkiNoteFieldTemplates
import com.nekoplayer.app.mining.PronunciationAudioPresets
import com.nekoplayer.app.update.AppUpdateClient
import com.nekoplayer.app.update.AppUpdateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import java.util.UUID

internal data class AnkiSourceField(val key: String, val label: String)

private val ankiSourceFields = listOf(
    AnkiSourceField("expression", "Expression"),
    AnkiSourceField( key = "expressionFurigana", label = "Expression Furigana"),
    AnkiSourceField("reading", "Reading"),
    AnkiSourceField("pronunciationAudio", "Pronunciation Audio"),
    AnkiSourceField("definition", "Definition"),
    AnkiSourceField("glossary", "Glossary"),
    AnkiSourceField("sentence", "Sentence"),
    AnkiSourceField("sentenceFurigana", "Sentence Furigana"),
    AnkiSourceField("sentenceAudio", "Sentence Audio"),
    AnkiSourceField("screenshot", "Picture"),
    AnkiSourceField("frequency", "Frequency (first rank)"),
    AnkiSourceField("frequencies", "Frequencies (all dictionaries)"),
    AnkiSourceField("frequencySort", "Frequency sort (harmonic rank)"),
    AnkiSourceField("pitchAccent", "Pitch Accent"),
)

@Composable
fun SettingsScreen(
    database: AppDatabase,
    dictionaries: DictionaryRepository,
    anki: AnkiDroidClient,
    darkMode: Boolean,
    language: String,
    palette: String,
    accent: String,
    onDarkModeChanged: (Boolean) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onPaletteChanged: (String) -> Unit,
    onAccentChanged: (String) -> Unit,
    onImportDictionary: () -> Unit,
    dictionaryImportStatus: String?,
    dictionaryImportBusy: Boolean,
    refreshKey: Int,
    onDetailScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imports by remember { mutableStateOf(emptyList<ImportedDictionary>()) }
    var popupExpansionMode by remember(database) {
        mutableStateOf(DictionaryPopupExpansion.load(database).mode)
    }
    var expandedPopupDictionaries by remember(database) {
        mutableStateOf(DictionaryPopupExpansion.load(database).customDictionaryNames)
    }
    var popupSize by remember(database) {
        mutableFloatStateOf(DictionaryPopupSize.load(database))
    }
    var removeTarget by remember { mutableStateOf<ImportedDictionary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var accessBusy by remember { mutableStateOf(value = false) }
    var noteTypes by remember { mutableStateOf(emptyList<AnkiDroidNoteType>()) }
    var decks by remember { mutableStateOf(emptyList<AnkiDroidDeck>()) }
    var noteFields by remember { mutableStateOf(emptyList<String>()) }
    var noteFieldsLoading by remember { mutableStateOf(false) }
    var noteTypeMenu by remember { mutableStateOf(false) }
    var deckMenu by remember { mutableStateOf(false) }
    var openFieldMenu by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var cacheScanning by remember { mutableStateOf(false) }
    var cacheClearing by remember { mutableStateOf(false) }
    var confirmCacheClear by remember { mutableStateOf(false) }
    var cacheError by remember { mutableStateOf<String?>(null) }
    var checkingRelease by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var latestRelease by remember { mutableStateOf<String?>(null) }
    var latestReleaseNotes by remember { mutableStateOf<String?>(null) }
    var showReleaseNotes by remember { mutableStateOf(false) }
    var latestReleaseApkUrl by remember { mutableStateOf<String?>(null) }
    var downloadedUpdateApk by remember { mutableStateOf<File?>(null) }
    var releaseError by remember { mutableStateOf<String?>(null) }
    val appUpdateClient = remember(context) { AppUpdateClient(context) }
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "Unknown"
    }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = downloadedUpdateApk
        if ((apk != null) && appUpdateClient.canInstallUnknownApps()) {
            runCatching { appUpdateClient.install(apk) }
                .onFailure { releaseError = (it as? AppUpdateException)?.message ?: "Couldn’t start the installer. Try again." }
        } else if (apk != null) {
            releaseError = "Allow NekoPlayer to install updates to continue."
        }
    }
    val settingsListState = rememberLazyListState()
    val detailListState = rememberLazyListState()

    fun scanTemporaryCache() {
        if (cacheScanning || cacheClearing) return
        scope.launch {
            cacheScanning = true
            cacheError = null
            try {
                cacheBytes = withContext(Dispatchers.IO) {
                    context.cacheDir.walkTopDown().filter(File::isFile).sumOf { it.length() }
                }
            } catch (_: Exception) {
                cacheError = "Couldn’t scan app storage. Try again."
            } finally {
                cacheScanning = false
            }
        }
    }

    fun clearTemporaryCache() {
        if (cacheClearing || dictionaryImportBusy) return
        scope.launch {
            cacheClearing = true
            cacheError = null
            try {
                val (sizeAfterCleanup, allFilesRemoved) = withContext(Dispatchers.IO) {
                    val children = context.cacheDir.listFiles().orEmpty()
                    val removed = children.all { child -> child.deleteRecursively() }
                    val remainingBytes = context.cacheDir.walkTopDown().filter(File::isFile).sumOf { it.length() }
                    remainingBytes to removed
                }
                cacheBytes = sizeAfterCleanup
                if (!allFilesRemoved) cacheError = "Some temporary files could not be removed."
                confirmCacheClear = false
            } catch (_: Exception) {
                cacheError = "Couldn’t clear app storage. Try again."
            } finally {
                cacheClearing = false
            }
        }
    }

    fun launchUpdateInstaller(apk: File) {
        if (!appUpdateClient.canInstallUnknownApps()) {
            runCatching {
                installPermissionLauncher.launch(appUpdateClient.installPermissionIntent())
            }.onFailure {
                releaseError = "Couldn’t start the installer. Try again."
            }
            return
        }
        runCatching { appUpdateClient.install(apk) }
            .onFailure { releaseError = (it as? AppUpdateException)?.message ?: "Couldn’t start the installer. Try again." }
    }

    fun downloadAndInstallUpdate() {
        val downloadUrl = latestReleaseApkUrl ?: return
        if (downloadingUpdate || checkingRelease) return
        scope.launch {
            downloadingUpdate = true
            downloadProgress = 0f
            releaseError = null
            try {
                val apk = appUpdateClient.downloadApk(downloadUrl) { progress ->
                    downloadProgress = progress
                }
                downloadedUpdateApk = apk
                launchUpdateInstaller(apk)
            } catch (error: AppUpdateException) {
                releaseError = error.message ?: "Couldn’t download the update. Check your connection and try again."
            } catch (_: Exception) {
                releaseError = "Couldn’t download the update. Check your connection and try again."
            } finally {
                downloadingUpdate = false
            }
        }
    }

    fun checkForUpdates() {
        if (checkingRelease || downloadingUpdate) return
        scope.launch {
            checkingRelease = true
            releaseError = null
            latestRelease = null
            latestReleaseNotes = null
            latestReleaseApkUrl = null
            downloadedUpdateApk = null
            try {
                val release = appUpdateClient.latestRelease()
                latestRelease = release.versionTag
                latestReleaseNotes = release.releaseNotes
                latestReleaseApkUrl = release.apkUrl
                if (
                    AppUpdateClient.isNewerVersion(release.versionTag, appVersion) &&
                    release.apkUrl == null
                ) {
                    releaseError = "No APK is attached to the latest release."
                }
            } catch (error: AppUpdateException) {
                releaseError = error.message ?: "Couldn’t check for updates. Check your connection and try again."
            } catch (_: Exception) {
                releaseError = "Couldn’t check for updates. Check your connection and try again."
            } finally {
                checkingRelease = false
            }
        }
    }

    LaunchedEffect(Unit) { scanTemporaryCache() }
    LaunchedEffect(selectedSection) { onDetailScreenChanged(selectedSection != null) }

    fun returnToSettings() {
        selectedSection = null
        message = null
        deckMenu = false
        noteTypeMenu = false
        openFieldMenu = null
    }

    BackHandler(enabled = selectedSection != null) {
        when {
            deckMenu -> deckMenu = false
            noteTypeMenu -> noteTypeMenu = false
            openFieldMenu != null -> openFieldMenu = null
            else -> returnToSettings()
        }
    }

    var autoPause by remember { mutableStateOf(database.readBoolean("playback.autoPause", false)) }
    var autoPlayNext by remember { mutableStateOf(database.readBoolean("playback.autoPlayNext", false)) }
    var skipPointsEnabled by remember { mutableStateOf(database.readBoolean("playback.skipPointsEnabled", true)) }
    var keepLookupOpenAfterMining by remember {
        mutableStateOf(database.readBoolean("playback.keepLookupOpenAfterMining", true))
    }
    var fontSize by remember { mutableFloatStateOf(database.readNumber("subtitles.fontSize", 32.0).toFloat().coerceIn(14f, 48f)) }
    var bottomInset by remember { mutableFloatStateOf(database.readNumber("subtitles.bottomInsetPx", 112.0).toFloat().coerceIn(0f, 240f)) }
    var backgroundOpacity by remember { mutableFloatStateOf(database.readNumber("subtitles.backgroundOpacity", 0.72).toFloat().coerceIn(0f, 1f)) }
    var shadowStyle by remember {
        val storedStyle = database.readString("subtitles.shadowStyle", "")
        mutableStateOf(
            storedStyle.takeIf { it in setOf("Off", "Soft", "Strong", "Hard") }
                ?: if (database.readBoolean("subtitles.shadowEnabled", true)) "Soft" else "Off",
        )
    }
    var vignetteIntensity by remember { mutableFloatStateOf(database.readNumber("subtitles.vignette", 0.0).toFloat().coerceIn(0f, 1f)) }
    var prePadding by remember { mutableFloatStateOf(snapToGrid(database.readNumber("capture.audioPrePadding", 900.0).toFloat(), 10f, 0f..5000f)) }
    var postPadding by remember { mutableFloatStateOf(snapToGrid(database.readNumber("capture.audioPostPadding", 5000.0).toFloat(), 10f, 0f..5000f)) }
    var screenshotDelay by remember { mutableFloatStateOf(snapToGrid(database.readNumber("capture.screenshotDelayMs", 1000.0).toFloat(), 10f, 0f..30000f)) }
    var maxWidth by remember { mutableFloatStateOf(snapToGrid(database.readNumber("capture.maxWidth", 0.0).toFloat(), 10f, 0f..4096f)) }
    var maxHeight by remember { mutableFloatStateOf(snapToGrid(database.readNumber("capture.maxHeight", 0.0).toFloat(), 10f, 0f..4096f)) }
    var trimBlackBars by remember { mutableStateOf(database.readBoolean("capture.trimBlackBars", false)) }
    var playAudioDuringCapture by remember { mutableStateOf(database.readBoolean("capture.playAudioWhileCapture", false)) }
    var screenshotFormat by remember { mutableStateOf(database.readString("capture.screenshotFormat", "PNG")) }
    var jimakuKey by remember { mutableStateOf(database.readString("jimaku.apiKey")) }
    var autoFetchJimaku by remember { mutableStateOf(database.readBoolean("jimaku.autoFetch", true)) }
    var deck by remember { mutableStateOf(database.readString("anki.deck", "Japanese::Mining")) }
    var noteType by remember { mutableStateOf(database.readString("anki.noteType", "Basic")) }
    var noteTypeFieldTemplates by remember { mutableStateOf(AnkiNoteFieldTemplates.load(database)) }
    var audioSources by remember { mutableStateOf(loadAudioSources(database)) }

    fun save(key: String, value: Any?) {
        scope.launch(Dispatchers.IO) { database.writeSetting(key, value) }
    }

    suspend fun seedNoteTypeTemplates(name: String, fields: List<String>) {
        if (fields.isEmpty()) return
        val legacyFieldMap = withContext(Dispatchers.IO) { database.readSetting("anki.fieldMap") }
        val seeded = AnkiNoteFieldTemplates.forNoteType(
            noteTypeName = name,
            fields = fields,
            mappings = noteTypeFieldTemplates,
            legacyFieldMap = legacyFieldMap,
        )
        noteTypeFieldTemplates += name to seeded
    }

    fun updateSourceFieldDestination(sourceToken: String, destinationField: String) {
        val mappings = noteTypeFieldTemplates[noteType].orEmpty().toMutableMap()
        mappings.toMap().forEach { (field, template) ->
            if (AnkiNoteFieldTemplates.containsToken(template, sourceToken)) {
                mappings[field] = removeAnkiTemplateToken(template, sourceToken)
            }
        }
        if (destinationField.isNotBlank()) {
            val current = mappings[destinationField].orEmpty().trim()
            val sourceTemplate = "{$sourceToken}"
            mappings[destinationField] = if (current.isBlank()) sourceTemplate else "$current $sourceTemplate"
        }
        noteTypeFieldTemplates = noteTypeFieldTemplates + (noteType to mappings)
    }

    fun loadAnkiOptions() {
        if (accessBusy) return
        message = null
        accessBusy = true
        noteFieldsLoading = false
        anki.requestWritePermission { permissionResult ->
            scope.launch {
                val allowed = permissionResult.getOrElse { _ ->
                    message = "Couldn’t request AnkiDroid access. Try again."
                    false
                }
                if (!allowed) {
                    accessBusy = false
                    if (message == null) message = "AnkiDroid access denied."
                    return@launch
                }
                runCatching {
                    val loadedTypes = withContext(Dispatchers.IO) { anki.noteTypes() }
                    val loadedDecks = runCatching { withContext(Dispatchers.IO) { anki.decks() } }
                    val current = loadedTypes.firstOrNull { it.name.equals(noteType, ignoreCase = true) }
                        ?: loadedTypes.firstOrNull()
                    val loadedFields = current?.let { type ->
                        runCatching { withContext(Dispatchers.IO) { anki.fields(type.id) } }
                    }
                    Triple(loadedTypes, loadedDecks, current to loadedFields)
                }.fold(
                    onSuccess = { (loadedTypes, loadedDecks, currentAndFields) ->
                        val (current, fieldsResult) = currentAndFields
                        val loadedFields = fieldsResult?.getOrDefault(emptyList()).orEmpty()
                        noteTypes = loadedTypes
                        decks = loadedDecks.getOrDefault(emptyList())
                        noteFields = loadedFields
                        current?.let {
                            noteType = it.name
                            seedNoteTypeTemplates(it.name, loadedFields)
                        }
                        if (decks.none { it.name == deck } && decks.isNotEmpty()) deck = decks.first().name
                        message = when {
                            loadedTypes.isEmpty() -> "No AnkiDroid note types found."
                            fieldsResult?.isFailure == true -> fieldsResult.exceptionOrNull()?.message ?: "Could not load AnkiDroid fields."
                            loadedDecks.isFailure -> loadedDecks.exceptionOrNull()?.message ?: "Could not load AnkiDroid decks."
                            fieldsResult?.isSuccess == true && loadedFields.isEmpty() -> "No fields found for ${current?.name ?: "the selected note type"}."
                            else -> null
                        }
                    },
                    onFailure = { message = "Couldn’t load AnkiDroid note types. Try refreshing." },
                )
                accessBusy = false
            }
        }
    }

    LaunchedEffect(refreshKey) {
        imports = withContext(Dispatchers.IO) { dictionaries.listDictionaries() }
    }
    LaunchedEffect(selectedSection) {
        if (selectedSection != null) detailListState.scrollToItem(0)
        if (selectedSection == "Anki" && noteTypes.isEmpty()) loadAnkiOptions()
    }
    LaunchedEffect(jimakuKey) { delay(450.milliseconds); save("jimaku.apiKey", jimakuKey) }
    LaunchedEffect(deck) { delay(450.milliseconds); save("anki.deck", deck) }
    LaunchedEffect(noteType) { delay(450.milliseconds); save("anki.noteType", noteType) }
    LaunchedEffect(noteTypeFieldTemplates) {
        save(AnkiNoteFieldTemplates.SETTING_KEY, AnkiNoteFieldTemplates.encode(noteTypeFieldTemplates))
    }
    LaunchedEffect(audioSources) {
        delay(400.milliseconds)
        save("audio.pronunciationSources", audioSources.map { it.toJson() })
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = if (selectedSection == null) settingsListState else detailListState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (selectedSection == null) 24.dp else 8.dp),
    ) {
        if (selectedSection == null) {
            item {
                Text(
                    "Settings",
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                SettingsCategoryGroup(
                    listOf(
                        "Appearance",
                        "Playback",
                        "Subtitles",
                        "Dictionary settings",
                        "Anki",
                        "Capture and audio",
                        "About and storage",
                    ),
                    selected = { selectedSection = it },
                )
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    IconButton(
                        onClick = ::returnToSettings,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localizeUiText("Back to settings")) }
                    Text(
                        selectedSection!!,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        if (selectedSection == "Appearance") {
        item {
            SettingSection("Language") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("English", "日本語").forEach { option ->
                        FilterChip(
                            selected = language == option,
                            onClick = { onLanguageChanged(option) },
                            label = { Text(option) },
                        )
                    }
                }
            }
        }
        item {
            SettingSection("Theme") {
                SettingSwitch("Dark theme", darkMode, onChange = onDarkModeChanged)
            }
        }
        item {
            SettingSection(if (darkMode) "Dark palette" else "Light palette") {
                val palettes = if (darkMode) {
                    listOf(
                        ThemePaletteOption("Default", "classic", Color(0xFF0C0F10), Color(0xFF171A1B), Color(0xFFA0CDE0)),
                        ThemePaletteOption("Warm dusk", "sepia", Color(0xFF13100E), Color(0xFF241E19), Color(0xFFB5A28E)),
                        ThemePaletteOption("Ink", "ink", Color(0xFF050607), Color(0xFF15191B), Color(0xFF92BDF5)),
                        ThemePaletteOption("OLED", "oled", Color.Black, Color(0xFF101214), Color(0xFF92BDF5)),
                    )
                } else {
                    listOf(
                        ThemePaletteOption("Default", "classic", Color(0xFFF4F7F8), Color(0xFFE8EEF0), Color(0xFF386A78)),
                        ThemePaletteOption("Warm paper", "sepia", Color(0xFFF5F0E7), Color(0xFFECE4D7), Color(0xFF8A5A00)),
                        ThemePaletteOption("Soft mist", "mist", Color(0xFFF1F5F4), Color(0xFFE6EEEC), Color(0xFF08766F)),
                    )
                }
                palettes.forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ThemePaletteRow(option, selected = palette == option.id) { onPaletteChanged(option.id) }
                }
            }
        }
        item {
            SettingSection("Accent") {
                val accents = listOf(
                    "cyan" to ("Cyan" to Color(0xFFA0CDE0)),
                    "violet" to ("Orchid" to Color(0xFFB9A3FF)),
                    "blue" to ("Ocean" to Color(0xFF92BDF5)),
                    "teal" to ("Jade" to Color(0xFF72D2C6)),
                    "green" to ("Fern" to Color(0xFF91D29C)),
                    "amber" to ("Amber" to Color(0xFFFFC66D)),
                    "coral" to ("Coral" to Color(0xFFFFA99F)),
                    "pink" to ("Rose" to Color(0xFFF0A3CB)),
                )
                accents.forEachIndexed { index, (id, option) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AccentColorRow(option.first, option.second, selected = accent == id) { onAccentChanged(id) }
                }
            }
        }
        }

        if (selectedSection == "Playback") {
        item {
            SettingSection("Playback") {
                SettingSwitch("Auto-play next", autoPlayNext) {
                    autoPlayNext = it
                    save("playback.autoPlayNext", it)
                }
                SettingSwitch("Show intro/outro skip buttons", skipPointsEnabled) {
                    skipPointsEnabled = it
                    save("playback.skipPointsEnabled", it)
                }
                SettingSwitch("Pause video for dictionary lookup", autoPause) {
                    autoPause = it
                    save("playback.autoPause", it)
                }
                SettingSwitch("Keep dictionary open after mining", keepLookupOpenAfterMining) {
                    keepLookupOpenAfterMining = it
                    save("playback.keepLookupOpenAfterMining", it)
                }
            }
        }
        }

        if (selectedSection == "Subtitles") {
        item {
            SettingSection("Subtitle appearance") {
                SettingSlider("Font size", "${fontSize.roundToInt()} sp", fontSize, 14f..48f, { fontSize = it }) {
                    save("subtitles.fontSize", it.toDouble())
                }
                SettingSlider("Bottom spacing", "${bottomInset.roundToInt()} px", bottomInset, 0f..240f, { bottomInset = it }) {
                    save("subtitles.bottomInsetPx", it.toDouble())
                }
                SettingSlider("Background opacity", "${(backgroundOpacity * 100).roundToInt()}%", backgroundOpacity, 0f..1f, { backgroundOpacity = it }) {
                    save("subtitles.backgroundOpacity", it.toDouble())
                }
                Text("Text shadow", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Off", "Soft", "Strong", "Hard").forEach { style ->
                        FilterChip(
                            selected = shadowStyle == style,
                            onClick = {
                                shadowStyle = style
                                save("subtitles.shadowStyle", style)
                                save("subtitles.shadowEnabled", style != "Off")
                            },
                            label = { Text(style) },
                        )
                    }
                }
                SettingSlider(
                    "Video vignette",
                    "${(vignetteIntensity * 100).roundToInt()}%",
                    vignetteIntensity,
                    0f..1f,
                    { vignetteIntensity = it },
                    snapStep = 0.1f,
                ) {
                    save("subtitles.vignette", it.toDouble())
                }
            }
        }
        item {
            SettingSection("Subtitle preview") {
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        "日本語字幕のプレビュー",
                        modifier = Modifier.fillMaxWidth()
                            .padding(bottom = (bottomInset / 4f).dp.coerceIn(8.dp, 40.dp))
                            .background(Color.Black.copy(alpha = backgroundOpacity))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color.White,
                        fontSize = fontSize.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        item {
            SettingSection("Subtitle search") {
                SettingSwitch(
                    "Auto-fetch subtitles",
                    autoFetchJimaku,
                    "Find and load matching Jimaku subtitles when playback starts."
                ) {
                    autoFetchJimaku = it
                    save("jimaku.autoFetch", it)
                }
                OutlinedTextField(
                    value = jimakuKey,
                    onValueChange = { jimakuKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    label = { Text("Jimaku API key") },
                    singleLine = true,
                )
            }
        }
        }

        if (selectedSection == "Dictionary settings") {
        item {
            SettingSection("Import") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    DictionaryImportButton(
                        onImportDictionary = onImportDictionary,
                        dictionaryImportStatus = dictionaryImportStatus,
                        dictionaryImportBusy = dictionaryImportBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                }
            }
        }
        item {
            SettingSection("Auto-expand dictionaries") {
                Text(
                    "Choose which dictionaries open automatically in subtitle lookups.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = popupExpansionMode == DictionaryPopupExpansion.Mode.FIRST,
                        onClick = {
                            popupExpansionMode = DictionaryPopupExpansion.Mode.FIRST
                            save(DictionaryPopupExpansion.MODE_SETTING, DictionaryPopupExpansion.Mode.FIRST.storedValue)
                        },
                        label = { Text("First dictionary") },
                    )
                    FilterChip(
                        selected = popupExpansionMode == DictionaryPopupExpansion.Mode.CUSTOM,
                        onClick = {
                            popupExpansionMode = DictionaryPopupExpansion.Mode.CUSTOM
                            save(DictionaryPopupExpansion.MODE_SETTING, DictionaryPopupExpansion.Mode.CUSTOM.storedValue)
                        },
                        label = { Text("Choose dictionaries") },
                    )
                }
                if (popupExpansionMode == DictionaryPopupExpansion.Mode.CUSTOM) {
                    val termDictionaries = imports.filter { it.kind != "meta" }
                    if (termDictionaries.isEmpty()) {
                        Text("Import a term dictionary to choose dictionaries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        termDictionaries.forEach { dictionary ->
                            SettingSwitch(
                                label = dictionary.name,
                                checked = dictionary.name in expandedPopupDictionaries,
                                onChange = { enabled ->
                                    val updated = if (enabled) expandedPopupDictionaries + dictionary.name
                                    else expandedPopupDictionaries - dictionary.name
                                    expandedPopupDictionaries = updated
                                    save(DictionaryPopupExpansion.DICTIONARIES_SETTING, updated.sorted())
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingSection("Popup size") {
                Text(
                    "Sets the lookup popup’s width and height on screen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSlider(
                    label = "Popup size",
                    valueLabel = "${(popupSize * 100f).roundToInt()}%",
                    value = popupSize,
                    range = DictionaryPopupSize.MIN..DictionaryPopupSize.MAX,
                    onChange = { popupSize = it },
                    snapStep = 0.05f,
                    onFinished = { save(DictionaryPopupSize.SETTING, it.toDouble()) },
                )
            }
        }
        item {
            SettingSection("Dictionaries") {
                if (imports.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Import some dictionaries", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Add a Yomitan dictionary to look up words and definitions.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                imports.forEachIndexed { index, item ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Material3Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text("${item.kind} · ${item.entryCount} entries · ${item.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(enabled = index > 0, onClick = {
                            val order = imports.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) }
                            scope.launch(Dispatchers.IO) {
                                dictionaries.reorderDictionaries(order)
                                imports = dictionaries.listDictionaries()
                            }
                        }) { Icon(Icons.Default.ArrowUpward, contentDescription = localizeUiText("Move up")) }
                        IconButton(enabled = index < imports.lastIndex, onClick = {
                            val order = imports.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) }
                            scope.launch(Dispatchers.IO) {
                                dictionaries.reorderDictionaries(order)
                                imports = dictionaries.listDictionaries()
                            }
                        }) { Icon(Icons.Default.ArrowDownward, contentDescription = localizeUiText("Move down")) }
                        IconButton(onClick = { removeTarget = item }) { Icon(Icons.Default.Delete, contentDescription = localizeUiText("Remove dictionary"), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        }

        if (selectedSection == "Anki") {
        item {
            SettingSection("AnkiDroid") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !accessBusy,
                        onClick = ::loadAnkiOptions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = localizeUiText("Refresh AnkiDroid"))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (accessBusy) "Loading…" else "Refresh",
                            maxLines = 1,
                        )
                    }
                }
                message?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            SettingSection("Card defaults") {
                AnkiChoiceRow(
                    label = "Deck",
                    value = deck,
                    enabled = decks.isNotEmpty(),
                    expanded = deckMenu,
                    onExpand = { deckMenu = true },
                    onDismiss = { deckMenu = false },
                    options = decks,
                    optionLabel = { it.name },
                    onSelect = { deck = it.name },
                )
                HorizontalDivider()
                AnkiChoiceRow(
                    label = "Model",
                    value = noteType,
                    enabled = noteTypes.isNotEmpty(),
                    expanded = noteTypeMenu,
                    onExpand = { noteTypeMenu = true },
                    onDismiss = { noteTypeMenu = false },
                    options = noteTypes,
                    optionLabel = { it.name },
                    onSelect = { type ->
                        noteType = type.name
                        noteFields = emptyList()
                        noteFieldsLoading = true
                        message = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { anki.fields(type.id) } }
                                .onSuccess { fields ->
                                    if (noteType == type.name) {
                                        noteFields = fields
                                        noteFieldsLoading = false
                                        message = if (fields.isEmpty()) "No fields found for ${type.name}." else null
                                        seedNoteTypeTemplates(type.name, fields)
                                    }
                                }
                                .onFailure {
                                    if (noteType == type.name) {
                                        noteFields = emptyList()
                                        noteFieldsLoading = false
                                        message = "Couldn’t load AnkiDroid fields. Try again."
                                    }
                                }
                        }
                    },
                )
            }
        }
        item {
            SettingSection("Field mapping") {
                if (noteFields.isEmpty()) {
                    Text(
                        if (accessBusy || noteFieldsLoading) "Loading fields…" else "Refresh fields to load the selected note type.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ankiSourceFields.forEachIndexed { index, source ->
                    if (index > 0) HorizontalDivider()
                    val destination = noteTypeFieldTemplates[noteType].orEmpty().entries
                        .firstOrNull { (field, template) ->
                            field in noteFields && AnkiNoteFieldTemplates.containsToken(template, source.key)
                        }
                        ?.key.orEmpty()
                    AnkiSourceFieldMappingRow(
                        source = source,
                        destination = destination,
                        options = noteFields,
                        enabled = noteFields.isNotEmpty(),
                        menuExpanded = openFieldMenu == source.key,
                        onMenuOpen = { openFieldMenu = source.key },
                        onMenuDismiss = { openFieldMenu = null },
                        onDestinationChange = { updateSourceFieldDestination(source.key, it) },
                    )
                }
            }
        }
        }

        if (selectedSection == "Capture and audio") {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSection("Audio capture") {
                SettingSlider("Before subtitle", "${prePadding.roundToInt()} ms", prePadding, 0f..5000f, { prePadding = it }, snapStep = 10f) { save("capture.audioPrePadding", it.toDouble()) }
                SettingSlider("After subtitle", "${postPadding.roundToInt()} ms", postPadding, 0f..5000f, { postPadding = it }, snapStep = 10f) { save("capture.audioPostPadding", it.toDouble()) }
            }
            SettingSection("Image capture") {
                SettingSlider("Capture delay", "${screenshotDelay.roundToInt()} ms", screenshotDelay, 0f..30000f, { screenshotDelay = it }, snapStep = 10f) { save("capture.screenshotDelayMs", it.toDouble()) }
                SettingSlider("Max image width", if (maxWidth == 0f) "Original size" else "${maxWidth.roundToInt()} px", maxWidth, 0f..4096f, { maxWidth = it }, snapStep = 10f) { save("capture.maxWidth", it.toDouble()) }
                SettingSlider("Max image height", if (maxHeight == 0f) "Original size" else "${maxHeight.roundToInt()} px", maxHeight, 0f..4096f, { maxHeight = it }, snapStep = 10f) { save("capture.maxHeight", it.toDouble()) }
                Text("Image format", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PNG", "WebP").forEach { format -> FilterChip(selected = screenshotFormat == format, onClick = { screenshotFormat = format; save("capture.screenshotFormat", format) }, label = { Text(format) }) }
                }
                SettingSwitch("Crop black bars", trimBlackBars) { trimBlackBars = it; save("capture.trimBlackBars", it) }
            }
            SettingSection("Mining") {
                SettingSwitch("Play audio while capturing", playAudioDuringCapture) { playAudioDuringCapture = it; save("capture.playAudioWhileCapture", it) }
                }
            }
        }
        }

        if (selectedSection == "Capture and audio") {
        item {
            SettingSection("Pronunciation audio") {
                audioSources.forEachIndexed { index, source ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                audioSourceLabel(source, index),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            IconButton(enabled = index > 0, onClick = {
                                audioSources = audioSources.toMutableList().apply { add(index - 1, removeAt(index)) }
                            }) { Icon(Icons.Default.ArrowUpward, contentDescription = localizeUiText("Move source up")) }
                            IconButton(enabled = index < audioSources.lastIndex, onClick = {
                                audioSources = audioSources.toMutableList().apply { add(index + 1, removeAt(index)) }
                            }) { Icon(Icons.Default.ArrowDownward, contentDescription = localizeUiText("Move source down")) }
                            if (source.kind.startsWith("custom")) {
                                IconButton(onClick = { audioSources = audioSources.filterNot { it.name == source.name } }) {
                                    Icon(Icons.Default.Delete, contentDescription = localizeUiText("Remove pronunciation source"), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { enabled ->
                                    audioSources = audioSources.map {
                                        if (it.name == source.name) it.copy(enabled = enabled) else it
                                    }
                                },
                            )
                        }
                        if (source.kind.startsWith("custom")) {
                            OutlinedTextField(
                                value = source.urlTemplate.orEmpty(),
                                onValueChange = { value ->
                                    audioSources = audioSources.map {
                                        if (it.name == source.name) it.copy(urlTemplate = value) else it
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                label = { Text("URL template") },
                                placeholder = { Text("https://…?term={term}&reading={reading}") },
                                supportingText = { Text("Tokens: {term}, {reading}, {language}.") },
                                isError = source.urlTemplate.orEmpty().isNotBlank() && !isValidAudioSourceTemplate(source.urlTemplate.orEmpty()),
                                singleLine = true,
                            )
                        }
                    }
                    if (index < audioSources.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 3.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val id = "custom-${UUID.randomUUID()}"
                        audioSources = audioSources + AudioSourceSetting(id, "custom-url", true, "")
                    }) { Text("Add URL") }
                    OutlinedButton(onClick = {
                        val id = "custom-${UUID.randomUUID()}"
                        audioSources = audioSources + AudioSourceSetting(id, "custom-json", true, "")
                    }) { Text("Add JSON") }
                }
            }
        }
        }

        if (selectedSection == "About and storage") {
            item {
                SettingSection("Storage") {
                    Text(
                        "Temporary files and downloaded artwork. Dictionaries and settings are stored separately.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("App cache", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    cacheScanning -> "Scanning…"
                                    cacheBytes != null -> formatBytes(cacheBytes!!)
                                    else -> "Not scanned"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = ::scanTemporaryCache, enabled = !cacheScanning && !cacheClearing) {
                            Text("Scan")
                        }
                    }
                    cacheError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { confirmCacheClear = true },
                        enabled = !cacheClearing && !dictionaryImportBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(if (cacheClearing) "Clearing…" else "Clear temporary cache")
                    }
                }
            }
            item {
                SettingSection("Updates") {
                    val versionIsNewer = latestRelease?.let {
                        AppUpdateClient.isNewerVersion(it, appVersion)
                    } == true
                    val cachedApk = downloadedUpdateApk?.takeIf { it.isFile }

                    latestRelease?.let { version ->
                        val isCurrent = version.removePrefix("v").equals(appVersion.removePrefix("v"), ignoreCase = true)
                        Text(
                            when {
                                isCurrent -> "Installed version $appVersion is current."
                                versionIsNewer -> "Update available: $version · Installed: $appVersion"
                                else -> "Latest release: $version · Installed: $appVersion"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    releaseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            when {
                                cachedApk != null -> launchUpdateInstaller(cachedApk)
                                versionIsNewer && latestReleaseApkUrl != null -> downloadAndInstallUpdate()
                                else -> checkForUpdates()
                            }
                        },
                        enabled = !checkingRelease && !downloadingUpdate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        if (downloadingUpdate) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Downloading update…")
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(CircleShape),
                                )
                            }
                        } else {
                            Text(
                                when {
                                    checkingRelease -> "Checking…"
                                    cachedApk != null -> "Install update"
                                    versionIsNewer && latestReleaseApkUrl != null -> "Download and install"
                                    else -> "Check for updates"
                                },
                            )
                        }
                    }
                    if (latestRelease != null) {
                        TextButton(
                            onClick = { showReleaseNotes = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(localizeUiText("Release notes")) }
                    }
                }
            }
            item {
                SettingSection("nekoplayer") {
                    Text(
                        "Version $appVersion",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    if (showReleaseNotes) {
        val formattedNotes = latestReleaseNotes.orEmpty()
            .lines()
            .joinToString("\n") { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("#") -> line.dropWhile { it == '#' }.trim()
                    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ->
                        "• " + line.drop(2)
                    else -> line
                }
            }
            .trim()

        AlertDialog(
            onDismissRequest = { showReleaseNotes = false },
            title = {
                Text(
                    buildString {
                        append(localizeUiText("Release notes"))
                        latestRelease?.let { append(" · $it") }
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        formattedNotes.ifBlank {
                            localizeUiText("No release notes are available.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showReleaseNotes = false }) {
                    Text(localizeUiText("Close"))
                }
            },
        )
    }

    if (confirmCacheClear) {
        AlertDialog(
            onDismissRequest = { confirmCacheClear = false },
            title = { Text("Clear temporary cache?") },
            text = {
                Text("This removes temporary files and downloaded artwork. Imported dictionaries and library settings stay on this device.")
            },
            confirmButton = {
                TextButton(onClick = ::clearTemporaryCache) { Text("Clear cache") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCacheClear = false }) { Text("Cancel") }
            },
        )
    }

    removeTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove dictionary?") },
            text = { Text("Remove ${item.name} and its imported entries from this device? The source file will remain.") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch(Dispatchers.IO) {
                        dictionaries.deleteDictionary(item.id)
                        imports = dictionaries.listDictionaries()
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}
