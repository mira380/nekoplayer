package com.nekoplayer.app.mining

import android.content.Context
import android.net.Uri
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.data.DictionaryTermMeta
import com.nekoplayer.app.ui.PitchAccentGraph
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

class AnkiDroidMiningWorkflow(
    private val context: Context,
    private val database: AppDatabase,
    private val client: AnkiDroidClient,
    private val capture: MediaCapture = MediaCapture(context),
    private val dictionaries: DictionaryRepository? = null,
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
        info: String = "",
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
        require(cueStartMs in (0L..cueEndMs)) { "The selected subtitle timing is invalid." }

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
        val matchingFrequencyRows = matchingFrequencyRows(metadata, mainEntry)
        val frequency = matchingFrequencyRows.asSequence()
            .flatMap { it.rows.asSequence() }
            .mapNotNull(::frequencyValue)
            .firstOrNull()
            .orEmpty()
        val frequencies = renderFrequenciesField(matchingFrequencyRows)
        val frequencySort = frequencySortValue(matchingFrequencyRows)
        val expressionWithFurigana = furiganaPlain(expression, mainEntry.reading)
        val sentenceWithFurigana = sentenceFurigana(
            sentence = cleanSentence,
            expression = expression,
            reading = mainEntry.reading,
            surface = surfaceText,
            dictionaries = dictionaries,
        )
        val templateValues = buildMap {
            putAll(
                mapOf(
                    "expression" to expression,
                    "reading" to mainEntry.reading.trim(),
                    "furiganaPlain" to expressionWithFurigana,
                    "definition" to if (separateGlossary) glossaryData.mainDefinition else allGlossary,
                    "singleGlossary" to singleGlossary,
                    "pitchAccent" to PitchAccentGraph.renderAnkiField(metadata, expression, mainEntry.reading),
                    "glossary" to if (separateGlossary || definitionFields.isEmpty()) allGlossary else "",
                    "sentence" to boldMinedWord(cleanSentence, expression, surfaceText),
                    "sentenceFurigana" to sentenceWithFurigana,
                    "info" to info,
                    "frequency" to frequency,
                    "frequencies" to frequencies,
                    "frequencySort" to frequencySort,
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
                                    preferredName = "nekoplayer_audio.${media.audioFile.extension}",
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


private data class MatchingFrequencyRows(
    val dictionaryName: String,
    val rows: List<DictionaryTermMeta>,
)

private fun matchingFrequencyRows(
    metadata: List<DictionaryTermMeta>,
    mainEntry: DictionaryEntry,
): List<MatchingFrequencyRows> {
    val expression = mainEntry.expression.trim()
    val reading = mainEntry.reading.trim()
    val rowsByDictionary = metadata.asSequence()
        .filter { it.mode.trim().equals("freq", ignoreCase = true) }
        .mapNotNull { row ->
            if (frequencyMatchScore(row, expression, reading) < 0 || row.dictionaryName.isBlank()) null
            else row
        }
        .groupBy { it.dictionaryName.trim().lowercase(Locale.ROOT) }

    return rowsByDictionary.values.map { rows ->
        MatchingFrequencyRows(
            dictionaryName = rows.first().dictionaryName.trim(),
            rows = rows,
        )
    }
}

private fun renderFrequenciesField(groups: List<MatchingFrequencyRows>): String {
    val items = groups.flatMap { group ->
        group.rows.mapNotNull { row ->
            frequencyValue(row)?.let { value ->
                "<li><b>${escapeHtml(group.dictionaryName)}</b>: ${escapeHtml(value)}</li>"
            }
        }.distinct()
    }
    return if (items.isEmpty()) "" else items.joinToString(separator = "", prefix = "<ul>", postfix = "</ul>")
}

private fun frequencySortValue(groups: List<MatchingFrequencyRows>): String {
    val ranks = groups.mapNotNull { group ->
        group.rows.firstNotNullOfOrNull { row ->
            row.sortValue.takeIf { it.isFinite() && it > 0.0 && it < Double.MAX_VALUE }
        }
    }
    if (ranks.isEmpty()) return "9999999"
    val harmonicMean = ranks.size / ranks.sumOf { 1.0 / it }
    return floor(harmonicMean).toLong().toString()
}

private fun frequencyValue(row: DictionaryTermMeta): String? =
    row.display?.trim()?.takeIf(String::isNotEmpty)
        ?: row.sortValue.toString().removeSuffix(".0").takeIf(String::isNotEmpty)

private fun frequencyMatchScore(row: DictionaryTermMeta, expression: String, reading: String): Int {
    val rowExpression = row.expression.trim()
    val rowReading = row.reading.trim()
    return when {
        rowExpression == expression && rowReading == reading -> 5
        rowExpression == expression && rowReading.isEmpty() -> 4
        rowExpression == reading && rowReading == reading -> 3
        rowExpression == reading && rowReading.isEmpty() -> 2
        else -> -1
    }
}
