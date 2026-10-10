package com.nekoplayer.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.content.Intent
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import java.util.UUID

private data class AnkiSourceField(val key: String, val label: String)

private val ankiSourceFields = listOf(
    AnkiSourceField("expression", "Expression"),
    AnkiSourceField( key = "expressionFurigana", label = "Expression Furigana"),
    AnkiSourceField("reading", "Reading"),
    AnkiSourceField("pronunciationAudio", "Pronunciation Audio"),
    AnkiSourceField("definition", "Definition"),
    AnkiSourceField("glossary", "Glossary"),
    AnkiSourceField("sentence", "Sentence"),
    AnkiSourceField("sentenceAudio", "Sentence Audio"),
    AnkiSourceField("screenshot", "Picture"),
    AnkiSourceField("frequency", "Frequency"),
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
    var removeTarget by remember { mutableStateOf<ImportedDictionary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var accessBusy by remember { mutableStateOf(false) }
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
    var latestRelease by remember { mutableStateOf<String?>(null) }
    var latestReleaseUrl by remember { mutableStateOf<String?>(null) }
    var releaseError by remember { mutableStateOf<String?>(null) }
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "Unknown"
    }
    val settingsListState = rememberLazyListState()
    val detailListState = rememberLazyListState()
    val activeImportStep = remember(dictionaryImportStatus) {
        Regex("^Importing (\\d+) of (\\d+)$").find(dictionaryImportStatus.orEmpty())
    }
    val importStepNumber = activeImportStep?.groupValues?.getOrNull(1)?.toIntOrNull()
    val importStepCount = activeImportStep?.groupValues?.getOrNull(2)?.toIntOrNull()

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

    fun checkForUpdates() {
        if (checkingRelease) return
        scope.launch {
            checkingRelease = true
            releaseError = null
            latestRelease = null
            latestReleaseUrl = null
            try {
                val release = withContext(Dispatchers.IO) {
                    val connection = (URL("https://api.github.com/repos/mira380/nekoplayer/releases/latest")
                        .openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 10000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "nekoplayer-android")
                    }
                    try {
                        if (connection.responseCode !in 200..299) error("Release check failed")
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        connection.disconnect()
                    }
                }
                latestRelease = release.optString("tag_name").takeIf(String::isNotBlank)
                latestReleaseUrl = release.optString("html_url").takeIf(String::isNotBlank)
                if (latestRelease == null) releaseError = "Couldn’t read the latest release information."
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
    var postPadding by remember { mutableFloatStateOf(snapToGrid(database.readNumber("capture.audioPostPadding", 1000.0).toFloat(), 10f, 0f..5000f)) }
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
        noteTypeFieldTemplates = noteTypeFieldTemplates + (name to seeded)
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
                val allowed = permissionResult.getOrElse { error ->
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
    LaunchedEffect(jimakuKey) { delay(450); save("jimaku.apiKey", jimakuKey) }
    LaunchedEffect(deck) { delay(450); save("anki.deck", deck) }
    LaunchedEffect(noteType) { delay(450); save("anki.noteType", noteType) }
    LaunchedEffect(noteTypeFieldTemplates) {
        save(AnkiNoteFieldTemplates.SETTING_KEY, AnkiNoteFieldTemplates.encode(noteTypeFieldTemplates))
    }
    LaunchedEffect(audioSources) {
        delay(400L)
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
            SettingSection("Playback behavior") {
                SettingSwitch("Pause video when opening dictionary lookup", autoPause) {
                    autoPause = it; save("playback.autoPause", it)
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
                SettingSlider("Bottom inset", "${bottomInset.roundToInt()} px", bottomInset, 0f..240f, { bottomInset = it }) {
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
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = backgroundOpacity))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = fontSize.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        item {
            SettingSection("Subtitle search") {
                SettingSwitch(
                    "Automatically fetch Jimaku subtitles",
                    autoFetchJimaku,
                    "Find and load a matching subtitle when playback starts.",
                ) {
                    autoFetchJimaku = it
                    save("jimaku.autoFetch", it)
                }
                OutlinedTextField(
                    value = jimakuKey,
                    onValueChange = { jimakuKey = it },
                    modifier = Modifier.fillMaxWidth(),
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
                    Button(
                        enabled = !dictionaryImportBusy,
                        onClick = onImportDictionary,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ImportExport, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (dictionaryImportBusy && importStepNumber != null && importStepCount != null) {
                                        "Importing $importStepNumber of $importStepCount"
                                    } else if (dictionaryImportBusy) {
                                        "Importing…"
                                    } else {
                                        "Import dictionaries"
                                    },
                                    maxLines = 1,
                                )
                            }
                            if (dictionaryImportBusy) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingSection("Popup auto-expand") {
                Text(
                    "Choose which dictionary groups open automatically in subtitle lookups.",
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
                        label = { Text("Custom selection") },
                    )
                }
                if (popupExpansionMode == DictionaryPopupExpansion.Mode.CUSTOM) {
                    val termDictionaries = imports.filter { it.kind != "meta" }
                    if (termDictionaries.isEmpty()) {
                        Text("Import a term dictionary to choose groups.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SettingSection("Installed dictionaries") {
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
                SettingSlider("Audio padding before subtitle", "${prePadding.roundToInt()} ms", prePadding, 0f..5000f, { prePadding = it }, snapStep = 10f) { save("capture.audioPrePadding", it.toDouble()) }
                SettingSlider("Audio padding after subtitle", "${postPadding.roundToInt()} ms", postPadding, 0f..5000f, { postPadding = it }, snapStep = 10f) { save("capture.audioPostPadding", it.toDouble()) }
            }
            SettingSection("Picture") {
                SettingSlider("Picture delay", "${screenshotDelay.roundToInt()} ms", screenshotDelay, 0f..30000f, { screenshotDelay = it }, snapStep = 10f) { save("capture.screenshotDelayMs", it.toDouble()) }
                SettingSlider("Maximum picture width", if (maxWidth == 0f) "Original size" else "${maxWidth.roundToInt()} px", maxWidth, 0f..4096f, { maxWidth = it }, snapStep = 10f) { save("capture.maxWidth", it.toDouble()) }
                SettingSlider("Maximum picture height", if (maxHeight == 0f) "Original size" else "${maxHeight.roundToInt()} px", maxHeight, 0f..4096f, { maxHeight = it }, snapStep = 10f) { save("capture.maxHeight", it.toDouble()) }
                Text("Picture format", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PNG", "WebP").forEach { format -> FilterChip(selected = screenshotFormat == format, onClick = { screenshotFormat = format; save("capture.screenshotFormat", format) }, label = { Text(format) }) }
                }
                SettingSwitch("Crop black bars", trimBlackBars) { trimBlackBars = it; save("capture.trimBlackBars", it) }
            }
            SettingSection("Mining") {
                SettingSwitch("Play audio during capture", playAudioDuringCapture) { playAudioDuringCapture = it; save("capture.playAudioWhileCapture", it) }
                }
            }
        }
        }

        if (selectedSection == "Capture and audio") {
        item {
            SettingSection("Pronunciation sources") {
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
                        "Temporary files and downloaded artwork. Imported dictionaries and saved settings are stored separately.",
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
                    latestRelease?.let { version ->
                        val isCurrent = version.removePrefix("v").equals(appVersion.removePrefix("v"), ignoreCase = true)
                        Text(
                            if (isCurrent) "Installed version $appVersion is current."
                            else "Latest release: $version · Installed: $appVersion",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    releaseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = ::checkForUpdates,
                        enabled = !checkingRelease,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(if (checkingRelease) "Checking…" else "Check for updates")
                    }
                    latestReleaseUrl?.let { url ->
                        TextButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open release notes") }
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

private data class AudioSourceSetting(val name: String, val kind: String, val enabled: Boolean, val urlTemplate: String? = null) {
    fun toJson() = JSONObject().apply {
        put("id", name)
        put("kind", kind)
        put("enabled", enabled)
        if (urlTemplate != null) put("urlTemplate", urlTemplate)
    }
}

private fun loadAudioSources(database: AppDatabase): List<AudioSourceSetting> {
    val saved = database.readSetting("audio.pronunciationSources") as? org.json.JSONArray
    if (saved == null) return defaultAudioSources()
    val sources = (0 until saved.length()).mapNotNull { index ->
        val item = saved.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        val kind = item.optString("kind")
        if (id.isBlank() || kind.isBlank()) null else AudioSourceSetting(id, kind, item.optBoolean("enabled"), item.optString("urlTemplate").takeIf { it.isNotBlank() })
    }
    val legacyDefault = sources.size == 3 &&
        sources.all { it.enabled && it.urlTemplate.isNullOrBlank() } &&
        sources.map { it.name to it.kind }.toSet() == setOf(
            "jpod101" to "jpod101",
            "language-pod-101" to "language-pod-101",
            "jisho" to "jisho",
        )
    return if (legacyDefault) defaultAudioSources() else sources
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "${bytes} B"
    bytes < 1024L * 1024L -> "${(bytes / 1024.0).roundToInt()} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun defaultAudioSources() = PronunciationAudioPresets.defaults.map {
    AudioSourceSetting(it.id, it.kind, true, it.url)
}

private fun isValidAudioSourceTemplate(template: String): Boolean = try {
    val expanded = template.replace("{term}", "term").replace("{reading}", "reading").replace("{language}", "ja")
    val uri = java.net.URI(expanded)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
} catch (_: Exception) {
    false
}

@Composable
private fun SettingsCategoryGroup(
    categories: List<String>,
    selected: (String) -> Unit,
    title: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                categories.forEachIndexed { index, category ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).clickable { selected(category) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            categoryIcon(category),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            category,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = localizeUiText("Open ${localizeUiText(category)}"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (index != categories.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> AnkiChoiceRow(
    label: String,
    value: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Material3Text(value.ifBlank { localizeUiText("None") }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = onExpand, enabled = enabled) {
                Text("Choose")
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Material3Text(optionLabel(option)) },
                        onClick = {
                            onDismiss()
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AnkiSourceFieldMappingRow(
    source: AnkiSourceField,
    destination: String,
    options: List<String>,
    enabled: Boolean,
    menuExpanded: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onDestinationChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(enabled = enabled, onClick = onMenuOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Material3Text(
                destination.ifBlank { localizeUiText("Not mapped") },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = onMenuOpen, enabled = enabled) {
                Text("Choose")
                Icon(Icons.Default.ArrowDropDown, contentDescription = localizeUiText("Choose ${localizeUiText(source.label)} field"))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text("Not mapped") },
                    onClick = {
                        onMenuDismiss()
                        onDestinationChange("")
                    },
                )
                options.forEach { field ->
                    DropdownMenuItem(
                        text = { Material3Text(field) },
                        onClick = {
                            onMenuDismiss()
                            onDestinationChange(field)
                        },
                    )
                }
            }
        }
    }
}

private fun removeAnkiTemplateToken(template: String, token: String): String {
    val remaining = Regex("\\{([^{}]+)\\}").replace(template) { match ->
        if (AnkiNoteFieldTemplates.containsToken(match.value, token)) "" else match.value
    }
    return remaining.replace(Regex("\\s+"), " ").trim()
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingCard(spacing = 10.dp, content = content)
    }
}

private data class ThemePaletteOption(
    val label: String,
    val id: String,
    val background: Color,
    val surface: Color,
    val accent: Color,
)

@Composable
private fun ThemePaletteRow(option: ThemePaletteOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.size(width = 48.dp, height = 30.dp).clip(RoundedCornerShape(7.dp)).background(option.background),
        ) {
            Box(Modifier.weight(1f).fillMaxSize().padding(4.dp).clip(RoundedCornerShape(3.dp)).background(option.surface))
            Box(Modifier.weight(1f).fillMaxSize().padding(vertical = 4.dp, horizontal = 2.dp).clip(RoundedCornerShape(3.dp)).background(option.accent))
            Box(Modifier.weight(1f).fillMaxSize().padding(4.dp).clip(RoundedCornerShape(3.dp)).background(option.surface.copy(alpha = 0.72f)))
        }
        Text(option.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun AccentColorRow(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp)
                .then(if (selected) Modifier.border(2.dp, color, CircleShape).padding(3.dp) else Modifier)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        RadioButton(selected = selected, onClick = null)
    }
}

private fun categoryIcon(category: String) = when (category) {
    "Anki" -> Icons.Default.Style
    "Dictionary settings" -> Icons.AutoMirrored.Filled.MenuBook
    "Appearance" -> Icons.Default.ColorLens
    "Playback" -> Icons.Default.PlayArrow
    "Subtitles" -> Icons.Default.Subtitles
    "Capture and audio" -> Icons.Default.GraphicEq
    "About and storage" -> Icons.Default.Info
    else -> Icons.Default.GraphicEq
}

@Composable
private fun SettingCard(spacing: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            detail?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onChange)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    snapStep: Float = 0f,
    onFinished: (Float) -> Unit,
) {
    fun snap(input: Float): Float = if (snapStep > 0f) snapToGrid(input, snapStep, range) else input
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { onChange(snap(it)) },
            valueRange = range,
            onValueChangeFinished = { onFinished(snap(value)) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            track = { sliderState ->
                val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
                val inactiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                val stopColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                    val trackHeight = size.height
                    val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                    val progressWidth = size.width * sliderState.coercedValueAsFraction
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, trackHeight),
                        cornerRadius = cornerRadius,
                    )
                    if (progressWidth > 0f) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset.Zero,
                            size = Size(progressWidth, trackHeight),
                            cornerRadius = cornerRadius,
                        )
                    }
                    drawCircle(
                        color = stopColor,
                        radius = 2.dp.toPx(),
                        center = Offset(size.width - 8.dp.toPx(), trackHeight / 2f),
                    )
                }
            },
        )
    }
}

private fun snapToGrid(value: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float {
    val bounded = value.coerceIn(range.start, range.endInclusive)
    if (step <= 0f) return bounded
    return ((bounded / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
}

private fun audioSourceLabel(source: AudioSourceSetting, index: Int): String = when (source.kind) {
    "jpod101" -> "JapanesePod101"
    "language-pod-101" -> "Language Pod 101"
    "jisho" -> "Jisho"
    "custom-url" -> "Custom URL ${index + 1}"
    "custom-json" -> "Custom JSON ${index + 1}"
    else -> source.name.replaceFirstChar(Char::uppercase)
}
