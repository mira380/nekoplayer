package com.nekoplayer.app.mining

import com.nekoplayer.app.data.AppDatabase
import org.json.JSONObject

data class AnkiTemplateValue(
    val token: String,
    val label: String,
    val aliases: List<String>,
)

object AnkiNoteFieldTemplates {
    const val SETTING_KEY = "anki.noteTypeFieldTemplates"

    val values = listOf(
        AnkiTemplateValue("expression", "Expression", listOf("expression", "term", "word", "vocabulary", "vocab", "front", "headword", "japanese")),
        AnkiTemplateValue("furigana-plain", "Expression with reading", listOf("expressionfurigana", "furiganaplain")),
        AnkiTemplateValue("reading", "Reading", listOf("expressionreading", "reading", "kana", "furigana", "readingtext")),
        AnkiTemplateValue("single-glossary", "Selected entry definition", listOf("maindefinition", "singleglossary")),
        AnkiTemplateValue("definition", "Definition", listOf("definition", "meaning", "gloss", "translation", "back", "english")),
        AnkiTemplateValue("pitchAccent", "Pitch accent graph", listOf("pitchaccentgraph", "pitchaccent", "pitchgraph", "accentpattern", "pitch")),
        AnkiTemplateValue("glossary", "Full glossary", listOf("glossary", "definitions", "dictionary", "dictionaryentry", "yomitan", "termhtml")),
        AnkiTemplateValue("sentence", "Sentence", listOf("sentence", "context", "example", "examplesentence", "japanesesentence")),
        AnkiTemplateValue("sentenceFurigana", "Sentence Furigana", listOf("sentencefurigana", "sentencewithfurigana", "furiganasentence")),
        AnkiTemplateValue("sentenceAudio", "Sentence audio", listOf("sentenceaudio", "sentencesound", "audiosentence")),
        AnkiTemplateValue("audio", "Pronunciation audio", listOf("expressionaudio", "audio")),
        AnkiTemplateValue("pronunciationAudio", "Pronunciation audio (camelCase alias)", listOf("pronunciationaudio", "wordaudio", "vocabularyaudio", "sound")),
        AnkiTemplateValue("screenshot", "Screenshot", listOf("definitionpicture", "screenshot", "image", "picture", "capture")),
        AnkiTemplateValue("frequencies", "All frequency values", listOf("frequency", "frequencies", "allfrequencies")),
        AnkiTemplateValue("frequency", "First frequency rank", listOf("freq", "rank", "wordfrequency")),
        AnkiTemplateValue("frequencySort", "Frequency sort / harmonic rank", listOf("freqsort", "frequencysort")),
        AnkiTemplateValue("popup-selection-text", "Selected subtitle text", listOf("selectiontext", "selectedtext", "popupselectiontext")),
        AnkiTemplateValue("selectionText", "Selected subtitle text (camelCase alias)", emptyList()),
    )

    private val tokenPattern = Regex("\\{([^{}]+)\\}")
    private val exactLegacyFrequencyPattern = Regex("^\\s*\\{\\s*frequency\\s*\\}\\s*$", RegexOption.IGNORE_CASE)
    private val exactSentencePattern = Regex("^\\s*\\{\\s*sentence\\s*\\}\\s*$", RegexOption.IGNORE_CASE)

    fun load(database: AppDatabase): Map<String, Map<String, String>> = decode(database.readSetting(SETTING_KEY))

    fun decode(value: Any?): Map<String, Map<String, String>> {
        val root = value as? JSONObject ?: return emptyMap()
        val result = linkedMapOf<String, Map<String, String>>()
        val names = root.keys()
        while (names.hasNext()) {
            val name = names.next()
            val fields = root.optJSONObject(name) ?: continue
            val mapping = linkedMapOf<String, String>()
            val fieldNames = fields.keys()
            while (fieldNames.hasNext()) {
                val field = fieldNames.next()
                mapping[field] = fields.optString(field)
            }
            result[name] = mapping
        }
        return result
    }

    fun encode(mappings: Map<String, Map<String, String>>): JSONObject = JSONObject().apply {
        mappings.forEach { (noteType, fields) ->
            put(noteType, JSONObject().apply {
                fields.forEach { (field, template) -> put(field, template) }
            })
        }
    }

    fun forNoteType(
        noteTypeName: String,
        fields: List<String>,
        mappings: Map<String, Map<String, String>>,
        legacyFieldMap: Any? = null,
    ): Map<String, String> {
        val saved = mappings[noteTypeName].orEmpty()
        val legacy = legacyFieldMap as? JSONObject
        val legacyTemplates = linkedMapOf<String, String>()
        values.forEach { value ->
            val field = legacy?.optString(value.token)?.trim().orEmpty()
            if (field.isNotEmpty()) {
                val previous = legacyTemplates[field]
                legacyTemplates[field] = when (previous to value.token) {
                    "{definition}" to "glossary",
                    "{glossary}" to "definition" -> "{definition}"
                    else -> previous?.let { "$it {${value.token}}" } ?: "{${value.token}}"
                }
            }
        }
        val result = linkedMapOf<String, String>()

        fields.forEach { field ->
            val migrated = legacyTemplates[field]
            val normalizedField = normalize(field)
            val isSentenceFuriganaField = normalizedField in setOf(
                "sentencefurigana",
                "sentencewithfurigana",
                "furiganasentence",
            )
            val isPronunciationAudioField = normalizedField in setOf(
                "expressionaudio",
                "pronunciationaudio",
                "wordaudio",
                "vocabularyaudio",
                "sound",
            )
            val current = saved[field]?.takeUnless {
                it.isBlank() && (isSentenceFuriganaField || isPronunciationAudioField)
            }
            val template = when {
                current != null -> if (isSentenceFuriganaField && exactSentencePattern.matches(current)) {
                    "{sentenceFurigana}"
                } else current
                migrated != null -> if (isSentenceFuriganaField && exactSentencePattern.matches(migrated)) {
                    "{sentenceFurigana}"
                } else migrated
                else -> suggestedTemplate(field)
            }
            result[field] = if (exactLegacyFrequencyPattern.matches(template)) {
                when (normalize(field)) {
                    "freqsort" -> "{frequencySort}"
                    "frequency" -> "{frequencies}"
                    else -> template
                }
            } else {
                template
            }
        }

        // Keep a manually assigned template even if Anki temporarily hides that field.
        saved.forEach { (field, template) ->
            if (field !in result && template.isNotBlank()) result[field] = template
        }
        return result
    }

    fun destinations(mappings: Map<String, String>, token: String): List<String> =
        mappings.filterValues { containsToken(it, token) }.keys.toList()

    fun containsToken(template: String, token: String): Boolean {
        val expected = canonicalToken(token)
        return tokenPattern.findAll(template).any { match ->
            val actual = canonicalToken(match.groupValues[1])
            actual == expected || (expected == "singleglossary" && actual.startsWith("singleglossary-"))
        }
    }

    fun render(template: String, valuesByToken: Map<String, String>): String {
        val normalizedValues = valuesByToken.entries.associate { canonicalToken(it.key) to it.value }
        return tokenPattern.replace(template) { match ->
            normalizedValues[canonicalToken(match.groupValues[1].trim())] ?: match.value
        }
    }

    private fun canonicalToken(token: String): String {
        val cleaned = token.trim()
        val dictionaryGlossary = Regex("^single-glossary-(.+)$", RegexOption.IGNORE_CASE).matchEntire(cleaned)
        if (dictionaryGlossary != null) return "singleglossary-${normalize(dictionaryGlossary.groupValues[1])}"
        return when (normalize(cleaned)) {
            "expressionfurigana", "furiganaplain" -> "furiganaplain"
            "audio" -> "pronunciationaudio"
            "popupselectiontext" -> "selectiontext"
            "singleglossary" -> "singleglossary"
            else -> cleaned.lowercase().filter(Char::isLetterOrDigit)
        }
    }

    private fun suggestedTemplate(field: String): String {
        val normalized = normalize(field)
        if (normalized.isBlank()) return ""
        val best = values.flatMapIndexed { valueIndex, value ->
            value.aliases.mapNotNull { alias ->
                val normalizedAlias = normalize(alias)
                val matches = normalized == normalizedAlias ||
                    (normalizedAlias.length >= 5 && normalized.contains(normalizedAlias))
                if (matches) Triple(value, normalizedAlias.length, valueIndex) else null
            }
        }.maxWithOrNull(compareBy<Triple<AnkiTemplateValue, Int, Int>> { it.second }.thenBy { -it.third })
        return best?.first?.let { "{${it.token}}" }.orEmpty()
    }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
}
