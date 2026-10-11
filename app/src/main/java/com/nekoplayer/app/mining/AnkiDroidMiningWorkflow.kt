package com.nekoplayer.app.mining

import android.content.Context
import android.net.Uri
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryTermMeta
import com.nekoplayer.app.ui.PitchAccentGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnkiDroidMiningWorkflow(
    private val context: Context,
    private val database: AppDatabase,
    private val client: AnkiDroidClient,
    private val capture: MediaCapture = MediaCapture(context),
) {
    suspend fun mine(
        uri: Uri,
        sentence: String,
        cueStartMs: Long,
        cueEndMs: Long,
        mainEntry: DictionaryEntry,
        allEntries: List<DictionaryEntry>,
        metadata: List<DictionaryTermMeta>,
        surfaceText: String = "",
        pronunciationAttachment: AnkiDroidAttachment? = null,
        mediaDurationMs: Long? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        playbackAudioCapture: PlaybackAudioCapture? = null,
        renderedFrameProvider: (suspend () -> android.graphics.Bitmap?)? = null,
        preferPlaybackCapture: Boolean = false,
    ): Long = withContext(Dispatchers.IO) {
        val cleanSentence = sentence.trim()
        val expression = mainEntry.expression.trim()
        require(expression.isNotEmpty()) { "Look up a word before mining a card." }
        require(cleanSentence.isNotEmpty()) { "Select a subtitle cue before mining a card." }
        require(cueStartMs >= 0L && cueEndMs >= cueStartMs) { "The selected subtitle timing is invalid." }

        val status = client.status()
        if (!status.installed || !status.apiEnabled) {
            throw IllegalStateException(status.failureReason ?: "AnkiDroid is unavailable.")
        }
        if (!status.permissionGranted) {
            throw IllegalStateException("Allow nekoplayer to access AnkiDroid, then try again.")
        }

        val deckName = database.readString("anki.deck", "Japanese::Mining").trim()
        val noteTypeName = database.readString("anki.noteType", "Basic").trim()
        val deck = client.decks().firstOrNull { it.name == deckName }
            ?: throw IllegalArgumentException("AnkiDroid deck ‘$deckName’ was not found.")
        val noteType = client.noteTypes().firstOrNull { it.name == noteTypeName }
            ?: throw IllegalArgumentException("AnkiDroid note type ‘$noteTypeName’ was not found.")
        val fields = client.fields(noteType.id)
        val fieldSet = fields.toSet()
        val templates = AnkiNoteFieldTemplates.forNoteType(
            noteTypeName = noteType.name,
            fields = fields,
            mappings = AnkiNoteFieldTemplates.load(database),
            legacyFieldMap = database.readSetting("anki.fieldMap"),
        )
        val orderedEntries = (listOf(mainEntry) + allEntries).distinctBy { it.dictionaryId to it.id }
        val expressionFields = AnkiNoteFieldTemplates.destinations(templates, "expression").filter(fieldSet::contains)
        val definitionFields = AnkiNoteFieldTemplates.destinations(templates, "definition").filter(fieldSet::contains)
        val singleGlossaryFields = AnkiNoteFieldTemplates.destinations(templates, "singleGlossary").filter(fieldSet::contains)
        val glossaryFields = AnkiNoteFieldTemplates.destinations(templates, "glossary").filter(fieldSet::contains)
        if (expressionFields.isEmpty()) {
            throw IllegalArgumentException("Map an Anki field to {expression} in the selected note type's settings.")
        }
        if (definitionFields.isEmpty() && singleGlossaryFields.isEmpty() && glossaryFields.isEmpty()) {
            throw IllegalArgumentException("Map an Anki field to {definition}, {single-glossary}, or {glossary} in the selected note type's settings.")
        }

        val selectedDefinitionFields = definitionFields + singleGlossaryFields
        val separateGlossary = glossaryFields.any { it !in selectedDefinitionFields }
        val glossaryData = AnkiGlossaryHtml.buildFields(orderedEntries, mainEntry, separateGlossary)
        val allGlossary = glossaryData.glossary ?: glossaryData.mainDefinition
        val singleGlossary = AnkiGlossaryHtml.renderMainDefinition(mainEntry)
        val frequency = metadata.firstOrNull {
            it.mode == "freq" && (it.expression == mainEntry.expression || it.reading == mainEntry.reading)
        }?.display.orEmpty()
        val templateValues = buildMap {
            putAll(
                mapOf(
                    "expression" to expression,
                    "reading" to mainEntry.reading.trim(),
                    "furiganaPlain" to furiganaPlain(expression, mainEntry.reading),
                    "definition" to if (separateGlossary) glossaryData.mainDefinition else allGlossary,
                    "singleGlossary" to singleGlossary,
                    "pitchAccent" to PitchAccentGraph.renderAnkiField(metadata, expression, mainEntry.reading),
                    "glossary" to if (separateGlossary || definitionFields.isEmpty()) allGlossary else "",
                    "sentence" to boldMinedWord(cleanSentence, expression, surfaceText),
                    "frequency" to frequency,
                    "selectionText" to surfaceText,
                    "sentenceAudio" to "",
                    "pronunciationAudio" to "",
                    "screenshot" to "",
                ),
            )
            orderedEntries.map { it.dictionaryName.trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { dictionaryName ->
                    put(
                        "single-glossary-$dictionaryName",
                        AnkiGlossaryHtml.render(orderedEntries.filter { it.dictionaryName.trim() == dictionaryName }),
                    )
                }
        }
        val values = templates.mapNotNull { (field, template) ->
            if (field !in fieldSet || template.isBlank()) return@mapNotNull null
            val rendered = AnkiNoteFieldTemplates.render(template, templateValues)
            rendered.takeIf(String::isNotBlank)?.let { field to it }
        }.toMap(linkedMapOf())

        val sentenceAudioFields = AnkiNoteFieldTemplates.destinations(templates, "sentenceAudio").filter(fieldSet::contains)
        val imageFields = AnkiNoteFieldTemplates.destinations(templates, "screenshot").filter(fieldSet::contains)
        val pronunciationFields = AnkiNoteFieldTemplates.destinations(templates, "pronunciationAudio").filter(fieldSet::contains)

        val settings = CaptureSettings(
            prePaddingMs = database.readNumber("capture.audioPrePadding", 900.0).toLong().coerceIn(0L, 5_000L),
            postPaddingMs = database.readNumber("capture.audioPostPadding", 5_000.0).toLong().coerceIn(0L, 5_000L),
            screenshotDelayMs = database.readNumber("capture.screenshotDelayMs", 1_000.0).toLong().coerceIn(0L, 30_000L),
            screenshotFormat = database.readString("capture.screenshotFormat", "PNG").lowercase(),
            maxWidth = database.readNumber("capture.maxWidth", 0.0).toInt().coerceIn(0, 16_384),
            maxHeight = database.readNumber("capture.maxHeight", 0.0).toInt().coerceIn(0, 16_384),
            trimBlackBars = database.readBoolean("capture.trimBlackBars", false),
            mediaDurationMs = mediaDurationMs,
        )
        var captured: CapturedMedia? = null
        try {
            val media = if (sentenceAudioFields.isNotEmpty() || imageFields.isNotEmpty()) {
                capture.capture(
                    uri = uri,
                    positionMs = cueStartMs,
                    settings = settings,
                    cueEndMs = cueEndMs,
                    requestHeaders = requestHeaders,
                    playbackAudioCapture = playbackAudioCapture,
                    renderedFrameProvider = renderedFrameProvider,
                    preferPlaybackCapture = preferPlaybackCapture,
                ).also { captured = it }
            } else null
            val safeName = expression.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(48).ifBlank { "card" }
            client.createNote(
                AnkiDroidNote(
                    deckId = deck.id,
                    noteTypeId = noteType.id,
                    modelFields = fields,
                    fieldValues = values,
                    attachments = buildList {
                        if (media != null) {
                            sentenceAudioFields.forEach { field ->
                                add(AnkiDroidAttachment(
                                    file = media.audioFile,
                                    preferredName = "sentence_$safeName.${media.audioFile.extension}",
                                    mimeType = if (media.audioFile.extension.equals("wav", ignoreCase = true)) {
                                        "audio/wav"
                                    } else {
                                        "audio/mp4"
                                    },
                                    destinationField = field,
                                    isImage = false,
                                ))
                            }
                            imageFields.forEach { field ->
                                add(AnkiDroidAttachment(
                                    file = media.screenshotFile,
                                    preferredName = "screenshot_$safeName.${media.screenshotFile.extension}",
                                    mimeType = "image/${media.screenshotFile.extension}",
                                    destinationField = field,
                                    isImage = true,
                                ))
                            }
                        }
                        if (pronunciationFields.isNotEmpty() && pronunciationAttachment != null) {
                            pronunciationFields.forEach { field ->
                                add(pronunciationAttachment.copy(destinationField = field))
                            }
                        }
                    },
                    tags = listOf("nekoplayer"),
                ),
            )
        } finally {
            captured?.audioFile?.delete()
            captured?.screenshotFile?.delete()
            pronunciationAttachment?.file?.delete()
        }
    }
}


private fun boldMinedWord(sentence: String, expression: String, surface: String): String {
    if (sentence.isEmpty()) return sentence
    val candidates = listOf(expression.trim(), surface.trim()) +
        (expression.trim().length - 1 downTo 2).map { expression.trim().take(it) }
    val candidate = candidates.firstOrNull { it.isNotEmpty() && sentence.contains(it) } ?: return escapeHtml(sentence)
    val index = sentence.indexOf(candidate)
    return escapeHtml(sentence.substring(0, index)) + "<b>" + escapeHtml(candidate) + "</b>" +
        escapeHtml(sentence.substring(index + candidate.length))
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun furiganaPlain(expression: String, reading: String): String {
    val safeExpression = escapeHtml(expression)
    val safeReading = escapeHtml(reading.trim())
    if (safeReading.isEmpty() || safeReading == expression) return safeExpression
    return "<ruby>$safeExpression<rt>$safeReading</rt></ruby>"
}
