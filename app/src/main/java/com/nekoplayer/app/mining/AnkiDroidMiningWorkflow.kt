package com.nekoplayer.app.mining

import android.content.Context
import android.net.Uri
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryTermMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
        val configured = database.readSetting("anki.fieldMap") as? JSONObject ?: JSONObject()
        fun mapped(key: String, fallback: String): String = configured.optString(key, fallback).trim()
        val values = linkedMapOf<String, String>()
        fun put(key: String, fallback: String, value: String, required: Boolean = false) {
            if (value.isBlank()) return
            val field = mapped(key, fallback)
            if (field.isBlank()) {
                if (required) throw IllegalArgumentException("Choose an Anki field for $key in Settings.")
                return
            }
            if (field !in fieldSet) {
                if (required) throw IllegalArgumentException("Anki note type is missing the mapped field ‘$field’.")
                return
            }
            values[field] = value
        }

        val orderedEntries = (listOf(mainEntry) + allEntries).distinctBy { it.dictionaryId to it.id }
        val definitionField = mapped("definition", "Definition")
        val glossaryField = mapped("glossary", "Glossary")
        val separateGlossary = glossaryField.isNotBlank() && glossaryField != definitionField && glossaryField in fieldSet
        val glossaryFields = AnkiGlossaryHtml.buildFields(orderedEntries, mainEntry, separateGlossary)
        put("expression", "Expression", expression, required = true)
        put("reading", "Reading", mainEntry.reading.trim())
        put("definition", "Definition", glossaryFields.mainDefinition, required = true)
        if (separateGlossary && !glossaryFields.glossary.isNullOrBlank()) values[glossaryField] = glossaryFields.glossary
        put("sentence", "Sentence", boldMinedWord(cleanSentence, expression, surfaceText))
        val frequency = metadata.firstOrNull {
            it.mode == "freq" && (it.expression == mainEntry.expression || it.reading == mainEntry.reading)
        }?.display.orEmpty()
        put("frequency", "Frequency", frequency)

        val audioField = mapped("sentenceAudio", "Sentence Audio")
        val imageField = mapped("screenshot", "Picture")
        if (audioField.isBlank() || audioField !in fieldSet) {
            throw IllegalArgumentException("Anki note type is missing the mapped sentence audio field ‘$audioField’.")
        }
        if (imageField.isBlank() || imageField !in fieldSet) {
            throw IllegalArgumentException("Anki note type is missing the mapped picture field ‘$imageField’.")
        }

        val settings = CaptureSettings(
            prePaddingMs = database.readNumber("capture.audioPrePadding", 900.0).toLong().coerceIn(0L, 5_000L),
            postPaddingMs = database.readNumber("capture.audioPostPadding", 1_000.0).toLong().coerceIn(0L, 5_000L),
            screenshotDelayMs = database.readNumber("capture.screenshotDelayMs", 1_000.0).toLong().coerceIn(0L, 30_000L),
            screenshotFormat = database.readString("capture.screenshotFormat", "PNG").lowercase(),
            maxWidth = database.readNumber("capture.maxWidth", 0.0).toInt().coerceIn(0, 16_384),
            maxHeight = database.readNumber("capture.maxHeight", 0.0).toInt().coerceIn(0, 16_384),
            trimBlackBars = database.readBoolean("capture.trimBlackBars", false),
        )
        var captured: CapturedMedia? = null
        try {
            val media = capture.capture(uri, cueStartMs, settings, cueEndMs = cueEndMs)
            captured = media
            val safeName = expression.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(48).ifBlank { "card" }
            val audioPreferred = "sentence_$safeName.m4a"
            val imagePreferred = "screenshot_$safeName.${media.screenshotFile.extension}"
            client.createNote(
                AnkiDroidNote(
                    deckId = deck.id,
                    noteTypeId = noteType.id,
                    modelFields = fields,
                    fieldValues = values,
                    attachments = buildList {
                        add(AnkiDroidAttachment(
                            file = media.audioFile,
                            preferredName = audioPreferred,
                            mimeType = "audio/mp4",
                            destinationField = audioField,
                            isImage = false,
                        ))
                        add(AnkiDroidAttachment(
                            file = media.screenshotFile,
                            preferredName = imagePreferred,
                            mimeType = "image/${media.screenshotFile.extension}",
                            destinationField = imageField,
                            isImage = true,
                        ))
                        pronunciationAttachment?.let { add(it) }
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
