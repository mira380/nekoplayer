package com.nekoplayer.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.animation.Crossfade
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.data.ImportedDictionary
import com.nekoplayer.app.mining.AnkiDroidClient
import com.nekoplayer.app.mining.AnkiDroidDeck
import com.nekoplayer.app.mining.AnkiDroidNoteType
import com.nekoplayer.app.mining.PronunciationAudioPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

private val defaultFieldMap = linkedMapOf(
    "expression" to "Expression",
    "reading" to "Reading",
    "definition" to "Definition",
    "glossary" to "Glossary",
    "sentence" to "Sentence",
    "sentenceAudio" to "Sentence Audio",
    "pronunciationAudio" to "Pronunciation Audio",
    "screenshot" to "Picture",
    "frequency" to "Frequency",
)

@Composable
fun SettingsScreen(
    database: AppDatabase,
    dictionaries: DictionaryRepository,
    anki: AnkiDroidClient,
    darkMode: Boolean,
    language: String,
    onDarkModeChanged: (Boolean) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onImportDictionary: () -> Unit,
    dictionaryImportStatus: String?,
    dictionaryImportBusy: Boolean,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
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
    var ankiRefreshSucceeded by remember { mutableStateOf(false) }
    var noteTypes by remember { mutableStateOf(emptyList<AnkiDroidNoteType>()) }
    var decks by remember { mutableStateOf(emptyList<AnkiDroidDeck>()) }
    var noteFields by remember { mutableStateOf(emptyList<String>()) }
    var noteTypeMenu by remember { mutableStateOf(false) }
    var deckMenu by remember { mutableStateOf(false) }
    var openFieldMenu by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val activeImportStep = remember(dictionaryImportStatus) {
        Regex("^Importing (\\d+) of (\\d+)$").find(dictionaryImportStatus.orEmpty())
    }
    val importStepNumber = activeImportStep?.groupValues?.getOrNull(1)?.toIntOrNull()
    val importStepCount = activeImportStep?.groupValues?.getOrNull(2)?.toIntOrNull()

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
    var fontSize by remember { mutableStateOf(database.readNumber("subtitles.fontSize", 32.0).toFloat().coerceIn(14f, 48f)) }
    var bottomInset by remember { mutableStateOf(database.readNumber("subtitles.bottomInsetPx", 112.0).toFloat().coerceIn(0f, 240f)) }
    var backgroundOpacity by remember { mutableStateOf(database.readNumber("subtitles.backgroundOpacity", 0.72).toFloat().coerceIn(0f, 1f)) }
    var shadowStyle by remember {
        val storedStyle = database.readString("subtitles.shadowStyle", "")
        mutableStateOf(
            storedStyle.takeIf { it in setOf("Off", "Soft", "Strong", "Hard") }
                ?: if (database.readBoolean("subtitles.shadowEnabled", true)) "Soft" else "Off",
        )
    }
    var vignetteIntensity by remember { mutableStateOf(database.readNumber("subtitles.vignette", 0.0).toFloat().coerceIn(0f, 1f)) }
    var prePadding by remember { mutableStateOf(snapToGrid(database.readNumber("capture.audioPrePadding", 900.0).toFloat(), 10f, 0f..5000f)) }
    var postPadding by remember { mutableStateOf(snapToGrid(database.readNumber("capture.audioPostPadding", 1000.0).toFloat(), 10f, 0f..5000f)) }
    var screenshotDelay by remember { mutableStateOf(snapToGrid(database.readNumber("capture.screenshotDelayMs", 1000.0).toFloat(), 10f, 0f..30000f)) }
    var maxWidth by remember { mutableStateOf(snapToGrid(database.readNumber("capture.maxWidth", 0.0).toFloat(), 10f, 0f..4096f)) }
    var maxHeight by remember { mutableStateOf(snapToGrid(database.readNumber("capture.maxHeight", 0.0).toFloat(), 10f, 0f..4096f)) }
    var trimBlackBars by remember { mutableStateOf(database.readBoolean("capture.trimBlackBars", false)) }
    var playAudioDuringCapture by remember { mutableStateOf(database.readBoolean("capture.playAudioWhileCapture", false)) }
    var screenshotFormat by remember { mutableStateOf(database.readString("capture.screenshotFormat", "PNG")) }
    var jimakuKey by remember { mutableStateOf(database.readString("jimaku.apiKey")) }
    var deck by remember { mutableStateOf(database.readString("anki.deck", "Japanese::Mining")) }
    var noteType by remember { mutableStateOf(database.readString("anki.noteType", "Basic")) }
    var ankiFields by remember { mutableStateOf(loadFieldMap(database)) }
    var audioSources by remember { mutableStateOf(loadAudioSources(database)) }

    fun save(key: String, value: Any?) {
        scope.launch(Dispatchers.IO) { database.writeSetting(key, value) }
    }

    fun loadAnkiOptions() {
        if (accessBusy) return
        message = null
        ankiRefreshSucceeded = false
        accessBusy = true
        anki.requestWritePermission { permissionResult ->
            scope.launch {
                val allowed = permissionResult.getOrElse { error ->
                    message = error.message ?: "Could not request AnkiDroid access."
                    false
                }
                if (!allowed) {
                    accessBusy = false
                    if (message == null) message = "AnkiDroid access denied."
                    return@launch
                }
                runCatching {
                    val loadedTypes = withContext(Dispatchers.IO) { anki.noteTypes() }
                    val loadedDecks = withContext(Dispatchers.IO) { anki.decks() }
                    val current = loadedTypes.firstOrNull { it.name.equals(noteType, ignoreCase = true) }
                    val loadedFields = current?.let { type -> withContext(Dispatchers.IO) { anki.fields(type.id) } }.orEmpty()
                    Triple(loadedTypes, loadedDecks, loadedFields)
                }.fold(
                    onSuccess = { (loadedTypes, loadedDecks, loadedFields) ->
                        noteTypes = loadedTypes
                        decks = loadedDecks
                        noteFields = loadedFields
                        if (loadedDecks.none { it.name == deck } && loadedDecks.isNotEmpty()) deck = loadedDecks.first().name
                        if (loadedFields.isNotEmpty()) {
                            ankiFields = suggestFieldMappings(defaultFieldMap, loadedFields, ankiFields)
                        }
                        message = if (loadedTypes.isEmpty()) "No AnkiDroid note types found." else null
                        ankiRefreshSucceeded = loadedTypes.isNotEmpty()
                    },
                    onFailure = { error -> message = error.message ?: "Could not load AnkiDroid note types." },
                )
                accessBusy = false
            }
        }
    }

    LaunchedEffect(refreshKey) {
        imports = withContext(Dispatchers.IO) { dictionaries.listDictionaries() }
    }
    LaunchedEffect(ankiRefreshSucceeded) {
        if (ankiRefreshSucceeded) {
            delay(900)
            ankiRefreshSucceeded = false
        }
    }
    LaunchedEffect(selectedSection) { listState.scrollToItem(0) }
    LaunchedEffect(jimakuKey) { delay(450); save("jimaku.apiKey", jimakuKey) }
    LaunchedEffect(deck) { delay(450); save("anki.deck", deck) }
    LaunchedEffect(noteType) { delay(450); save("anki.noteType", noteType) }
    LaunchedEffect(ankiFields) { save("anki.fieldMap", ankiFields) }
    LaunchedEffect(audioSources) {
        delay(400L)
        save("audio.pronunciationSources", audioSources.map { it.toJson() })
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (selectedSection == null) 24.dp else 8.dp),
    ) {
        if (selectedSection == null) {
            item {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            item { SettingsCategoryGroup(listOf("Anki", "Dictionaries", "Appearance", "Playback", "Subtitles"), selected = { selectedSection = it }) }
            item { SettingsCategoryGroup(listOf("Capture & Mining", "Pronunciation Audio"), selected = { selectedSection = it }, title = "More settings") }
        } else {
            item {
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    IconButton(
                        onClick = ::returnToSettings,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to settings") }
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
            SettingSection("Theme") {
                SettingSwitch("Dark theme", darkMode, onChange = onDarkModeChanged)
            }
        }
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
            SettingSection("Subtitle search") {
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

        if (selectedSection == "Dictionaries") {
        item {
            SettingSection("Import") {
                Text("Select a Yomitan dictionary ZIP archive", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Button(
                        enabled = !dictionaryImportBusy,
                        onClick = onImportDictionary,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.width(220.dp),
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
                if (imports.isEmpty()) Text("No dictionaries yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                imports.forEachIndexed { index, item ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text("${item.kind} · ${item.entryCount} entries · ${item.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(enabled = index > 0, onClick = {
                            val order = imports.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) }
                            scope.launch(Dispatchers.IO) {
                                dictionaries.reorderDictionaries(order)
                                imports = dictionaries.listDictionaries()
                            }
                        }) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move up") }
                        IconButton(enabled = index < imports.lastIndex, onClick = {
                            val order = imports.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) }
                            scope.launch(Dispatchers.IO) {
                                dictionaries.reorderDictionaries(order)
                                imports = dictionaries.listDictionaries()
                            }
                        }) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move down") }
                        IconButton(onClick = { removeTarget = item }) { Icon(Icons.Default.Delete, contentDescription = "Remove dictionary", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        }

        if (selectedSection == "Anki") {
        item {
            SettingSection("AnkiDroid") {
                Text("Create cards in AnkiDroid.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !accessBusy,
                        onClick = ::loadAnkiOptions,
                        modifier = Modifier.width(164.dp),
                    ) {
                        Crossfade(targetState = ankiRefreshSucceeded) { refreshed ->
                            Icon(
                                if (refreshed) Icons.Default.Check else Icons.Default.Refresh,
                                contentDescription = if (refreshed) "Fields refreshed" else "Refresh fields",
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (accessBusy) "Loading…" else if (noteTypes.isEmpty()) "Load fields" else "Refresh fields",
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
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = deck,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Default deck") },
                        trailingIcon = { IconButton(enabled = decks.isNotEmpty(), onClick = { deckMenu = true }) { Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose default deck") } },
                        readOnly = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                    )
                    DropdownMenu(expanded = deckMenu, onDismissRequest = { deckMenu = false }) {
                        decks.forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = { deck = option.name; deckMenu = false })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = noteType,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Note type") },
                        trailingIcon = {
                            IconButton(enabled = noteTypes.isNotEmpty(), onClick = { noteTypeMenu = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose note type")
                            }
                        },
                        readOnly = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                    )
                    DropdownMenu(expanded = noteTypeMenu, onDismissRequest = { noteTypeMenu = false }) {
                        noteTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    noteType = type.name
                                    noteTypeMenu = false
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { anki.fields(type.id) } }
                                            .onSuccess { fields ->
                                                noteFields = fields
                                                ankiFields = suggestFieldMappings(defaultFieldMap, fields, ankiFields)
                                            }
                                            .onFailure { message = it.message ?: "Could not load AnkiDroid fields." }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingSection("Field mapping") {
                defaultFieldMap.keys.forEach { key ->
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = ankiFields[key].orEmpty(),
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(ankiFieldLabel(key)) },
                            trailingIcon = {
                                IconButton(enabled = noteFields.isNotEmpty(), onClick = { openFieldMenu = key }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose ${ankiFieldLabel(key)} field")
                                }
                            },
                            readOnly = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            singleLine = true,
                        )
                        DropdownMenu(expanded = openFieldMenu == key, onDismissRequest = { openFieldMenu = null }) {
                            DropdownMenuItem(
                                text = { Text("Not mapped") },
                                onClick = { ankiFields = ankiFields + (key to ""); openFieldMenu = null },
                            )
                            noteFields.forEach { field ->
                                DropdownMenuItem(
                                    text = { Text(field) },
                                    onClick = { ankiFields = ankiFields + (key to field); openFieldMenu = null },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (selectedSection == "Capture & Mining") {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSection("Audio capture") {
                SettingSlider("Audio padding before subtitle", "${prePadding.roundToInt()} ms", prePadding, 0f..5000f, { prePadding = it }, snapStep = 10f) { save("capture.audioPrePadding", it.toDouble()) }
                SettingSlider("Audio padding after subtitle", "${postPadding.roundToInt()} ms", postPadding, 0f..5000f, { postPadding = it }, snapStep = 10f) { save("capture.audioPostPadding", it.toDouble()) }
            }
            SettingSection("Picture") {
                SettingSlider("Picture delay", "${screenshotDelay.roundToInt()} ms", screenshotDelay, 0f..30000f, { screenshotDelay = it }, snapStep = 10f) { save("capture.screenshotDelayMs", it.toDouble()) }
                SettingSwitch("Crop black bars", trimBlackBars) { trimBlackBars = it; save("capture.trimBlackBars", it) }
                SettingSlider("Maximum picture width", if (maxWidth == 0f) "Original size" else "${maxWidth.roundToInt()} px", maxWidth, 0f..4096f, { maxWidth = it }, snapStep = 10f) { save("capture.maxWidth", it.toDouble()) }
                SettingSlider("Maximum picture height", if (maxHeight == 0f) "Original size" else "${maxHeight.roundToInt()} px", maxHeight, 0f..4096f, { maxHeight = it }, snapStep = 10f) { save("capture.maxHeight", it.toDouble()) }
                Text("Picture format", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PNG", "WebP").forEach { format -> FilterChip(selected = screenshotFormat == format, onClick = { screenshotFormat = format; save("capture.screenshotFormat", format) }, label = { Text(format) }) }
                }
            }
            SettingSection("Mining") {
                SettingSwitch("Play audio during capture", playAudioDuringCapture) { playAudioDuringCapture = it; save("capture.playAudioWhileCapture", it) }
                }
            }
        }
        }

        if (selectedSection == "Pronunciation Audio") {
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
                            }) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move source up") }
                            IconButton(enabled = index < audioSources.lastIndex, onClick = {
                                audioSources = audioSources.toMutableList().apply { add(index + 1, removeAt(index)) }
                            }) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move source down") }
                            if (source.kind.startsWith("custom")) {
                                IconButton(onClick = { audioSources = audioSources.filterNot { it.name == source.name } }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove pronunciation source", tint = MaterialTheme.colorScheme.error)
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
                        val id = "custom-${System.currentTimeMillis()}"
                        audioSources = audioSources + AudioSourceSetting(id, "custom-url", true, "")
                    }) { Text("Add URL") }
                    OutlinedButton(onClick = {
                        val id = "custom-${System.currentTimeMillis()}"
                        audioSources = audioSources + AudioSourceSetting(id, "custom-json", true, "")
                    }) { Text("Add JSON") }
                }
            }
        }
        }
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

private fun loadFieldMap(database: AppDatabase): Map<String, String> {
    val saved = database.readSetting("anki.fieldMap") as? JSONObject ?: return defaultFieldMap
    return defaultFieldMap.mapValues { (key, value) -> saved.optString(key, value) }
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
            shape = RoundedCornerShape(18.dp),
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
                            Icons.Default.ArrowForward,
                            contentDescription = "Open $category",
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

private fun categoryIcon(category: String) = when (category) {
    "Anki" -> Icons.Default.ImportExport
    "Dictionaries" -> Icons.Default.Book
    "Appearance" -> Icons.Default.ColorLens
    "Playback" -> Icons.Default.PlayArrow
    "Subtitles" -> Icons.Default.Subtitles
    "Capture & Mining" -> Icons.Default.Tune
    else -> Icons.Default.GraphicEq
}

private val fieldAliases = mapOf(
    "expression" to listOf("expression", "term", "word", "vocabulary", "vocab", "front", "headword", "japanese"),
    "reading" to listOf("reading", "kana", "furigana", "readingtext"),
    "definition" to listOf("definition", "meaning", "gloss", "translation", "back", "english"),
    "glossary" to listOf("glossary", "definitions", "dictionary", "dictionaryentry", "yomitan", "termhtml"),
    "sentence" to listOf("sentence", "context", "example", "examplesentence", "japanesesentence"),
    "sentenceAudio" to listOf("sentenceaudio", "sentencesound", "audiosentence", "audio"),
    "pronunciationAudio" to listOf("pronunciationaudio", "wordaudio", "expressionaudio", "vocabularyaudio", "sound"),
    "screenshot" to listOf("screenshot", "image", "picture", "capture"),
    "frequency" to listOf("frequency", "freq", "rank", "wordfrequency"),
)

private fun normalizeFieldName(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

private fun suggestFieldMappings(
    mappingKeys: Map<String, String>,
    fields: List<String>,
    current: Map<String, String>,
): Map<String, String> {
    val available = fields.associateBy(::normalizeFieldName)
    val result = linkedMapOf<String, String>()
    val claimed = mutableSetOf<String>()

    mappingKeys.keys.forEach { key ->
        val selected = current[key].orEmpty()
        val actual = fields.firstOrNull { it.equals(selected, ignoreCase = true) }
        if (actual != null && claimed.add(normalizeFieldName(actual))) result[key] = actual
    }
    mappingKeys.keys.forEach { key ->
        if (key in result) return@forEach
        val aliases = fieldAliases[key].orEmpty().map(::normalizeFieldName)
        val candidate = aliases.asSequence().mapNotNull(available::get)
            .firstOrNull { normalizeFieldName(it) !in claimed }
            ?: fields.firstOrNull { field ->
                val normalized = normalizeFieldName(field)
                normalized.isNotBlank() && aliases.any { alias ->
                    alias.length >= 5 && alias != "audio" && alias != "sound" && alias != "front" && alias != "back" &&
                        (normalized.contains(alias) || alias.contains(normalized))
                } && normalized !in claimed
            }
        result[key] = candidate.orEmpty()
        candidate?.let { claimed += normalizeFieldName(it) }
    }
    return result
}

@Composable
private fun SettingCard(spacing: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
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
        )
    }
}

private fun snapToGrid(value: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float {
    val bounded = value.coerceIn(range.start, range.endInclusive)
    if (step <= 0f) return bounded
    return ((bounded / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
}

private val ankiFieldLabels = mapOf(
    "expression" to "Expression",
    "reading" to "Reading",
    "definition" to "Definition",
    "glossary" to "Glossary",
    "sentence" to "Sentence",
    "sentenceAudio" to "Sentence Audio",
    "pronunciationAudio" to "Pronunciation Audio",
    "screenshot" to "Picture",
    "frequency" to "Frequency",
)

private fun ankiFieldLabel(key: String): String = ankiFieldLabels[key] ?: key.replaceFirstChar(Char::uppercase)

private fun audioSourceLabel(source: AudioSourceSetting, index: Int): String = when (source.kind) {
    "jpod101" -> "JapanesePod101"
    "language-pod-101" -> "Language Pod 101"
    "jisho" -> "Jisho"
    "custom-url" -> "Custom URL ${index + 1}"
    "custom-json" -> "Custom JSON ${index + 1}"
    else -> source.name.replaceFirstChar(Char::uppercase)
}
