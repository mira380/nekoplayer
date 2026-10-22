package com.nekoplayer.app.mining

import com.nekoplayer.app.data.DictionaryRepository
import java.text.Normalizer

internal fun boldMinedWord(sentence: String, expression: String, surface: String): String {
    if (sentence.isEmpty()) return sentence
    val candidate = minedWordCandidate(sentence, expression, surface) ?: return escapeHtml(sentence)
    val index = sentence.indexOf(candidate)
    return escapeHtml(sentence.substring(0, index)) + "<b>" + escapeHtml(candidate) + "</b>" +
        escapeHtml(sentence.substring(index + candidate.length))
}

private const val MAX_SENTENCE_WORD_CODE_POINTS = 16

internal fun sentenceFurigana(
    sentence: String,
    expression: String,
    reading: String,
    surface: String,
    dictionaries: DictionaryRepository?,
): String {
    val candidateTerms = linkedSetOf<String>()
    val spansByStart = linkedMapOf<Int, MutableList<String>>()
    var start = 0
    while (start < sentence.length) {
        val firstCodePoint = sentence.codePointAt(start)
        if (!isJapaneseWordCodePoint(firstCodePoint)) {
            start += Character.charCount(firstCodePoint)
            continue
        }

        val spans = mutableListOf<String>()
        var end = start
        var codePointCount = 0
        while (end < sentence.length && codePointCount < MAX_SENTENCE_WORD_CODE_POINTS) {
            val codePoint = sentence.codePointAt(end)
            if (!isJapaneseWordCodePoint(codePoint)) break
            end += Character.charCount(codePoint)
            codePointCount += 1
            val word = sentence.substring(start, end)
            if (containsKanji(word)) {
                spans += word
                candidateTerms += word
                candidateTerms += deinflectedDictionaryForms(word)
            }
        }
        if (spans.isNotEmpty()) spansByStart[start] = spans.sortedByDescending(String::length).toMutableList()
        start += Character.charCount(firstCodePoint)
    }

    val entriesByExpression = dictionaries
        ?.lookupExactTerms(candidateTerms, limit = 1_000)
        .orEmpty()
        .filter { it.reading.isNotBlank() && containsKanji(it.expression) }
        .groupBy { it.expression }
    val selectedCandidate = minedWordCandidate(sentence, expression, surface)
    val selectedStart = selectedCandidate?.let { sentence.indexOf(it) } ?: -1
    val rendered = StringBuilder(sentence.length + 48)
    var cursor = 0
    while (cursor < sentence.length) {
        val match = spansByStart[cursor].orEmpty().firstNotNullOfOrNull { word ->
            fun annotationFor(entryExpression: String, entryReading: String): String? =
                furiganaForSurface(word, entryExpression, entryReading)
                    .takeIf { it != escapeHtml(word) }

            val isSelectedWord = cursor == selectedStart && word == selectedCandidate
            val selectedAnnotation = if (isSelectedWord) {
                annotationFor(expression.trim(), reading)
            } else null
            val directAnnotation = entriesByExpression[word]
                ?.firstNotNullOfOrNull { entry -> annotationFor(entry.expression, entry.reading) }
            var inflectedAnnotation: String? = null
            if (selectedAnnotation == null && directAnnotation == null) {
                for (baseForm in deinflectedDictionaryForms(word)) {
                    inflectedAnnotation = entriesByExpression[baseForm]
                        ?.firstNotNullOfOrNull { entry -> annotationFor(entry.expression, entry.reading) }
                    if (inflectedAnnotation != null) break
                }
            }
            (selectedAnnotation ?: directAnnotation ?: inflectedAnnotation)?.let { word to it }
        }

        if (match != null) {
            val previousCodePoint = if (cursor > 0) sentence.codePointBefore(cursor) else -1
            if (previousCodePoint >= 0 && Character.isLetter(previousCodePoint) &&
                rendered.lastOrNull()?.isWhitespace() != true
            ) {
                rendered.append(' ')
            }
            rendered.append(match.second)
            cursor += match.first.length
        } else {
            val codePoint = sentence.codePointAt(cursor)
            rendered.append(escapeHtml(String(Character.toChars(codePoint))))
            cursor += Character.charCount(codePoint)
        }
    }
    return rendered.toString()
}

private fun isJapaneseWordCodePoint(codePoint: Int): Boolean =
    isHanCodePoint(codePoint) || isKanaCodePoint(codePoint)

private fun isHanCodePoint(codePoint: Int): Boolean =
    codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0x20000..0x2A6DF ||
        codePoint in 0x2A700..0x2B73F ||
        codePoint in 0x2B740..0x2B81F ||
        codePoint in 0x2B820..0x2CEAF ||
        codePoint in 0x2CEB0..0x2EBEF ||
        codePoint in 0x2EBF0..0x2EE5F ||
        codePoint in 0x2F800..0x2FA1F ||
        codePoint in 0x30000..0x323AF

private fun isKanaCodePoint(codePoint: Int): Boolean =
    codePoint in 0x3041..0x3096 ||
        codePoint in 0x309D..0x309F ||
        codePoint in 0x30A1..0x30FA ||
        codePoint == 0x30FC ||
        codePoint in 0x30FD..0x30FF ||
        codePoint in 0x31F0..0x31FF ||
        codePoint in 0xFF66..0xFF9D

private fun containsKanji(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (isHanCodePoint(codePoint)) return true
        index += Character.charCount(codePoint)
    }
    return false
}

private fun deinflectedDictionaryForms(surface: String): List<String> {
    val endingsBySuffix = listOf(
        "って" to listOf("る", "う", "つ", "く"),
        "った" to listOf("る", "う", "つ", "く"),
        "いて" to listOf("く"),
        "いた" to listOf("く"),
        "いで" to listOf("ぐ"),
        "いだ" to listOf("ぐ"),
        "んで" to listOf("む", "ぶ", "ぬ"),
        "んだ" to listOf("む", "ぶ", "ぬ"),
        "して" to listOf("す", "する"),
        "した" to listOf("す", "する"),
        "て" to listOf("る", "う", "つ", "く", "ぐ", "む", "ぶ", "ぬ", "す"),
        "た" to listOf("る", "う", "つ", "く", "ぐ", "む", "ぶ", "ぬ", "す"),
    )
    return buildList {
        endingsBySuffix.forEach { (suffix, endings) ->
            if (surface.endsWith(suffix) && surface.length > suffix.length) {
                val stem = surface.dropLast(suffix.length)
                endings.forEach { ending ->
                    val candidate = stem + ending
                    if (containsKanji(candidate)) add(candidate)
                }
            }
        }
    }.distinct()
}

private fun minedWordCandidate(sentence: String, expression: String, surface: String): String? {
    val cleanExpression = expression.trim()
    val candidates = listOf(cleanExpression, surface.trim()) +
        (cleanExpression.length - 1 downTo 2).map(cleanExpression::take)
    return candidates.firstOrNull { it.isNotEmpty() && sentence.contains(it) }
}

private fun furiganaForSurface(surface: String, expression: String, reading: String): String {
    if (surface == expression) return furiganaNotation(expression, reading).html

    val sourceParts = furiganaNotation(expression, reading).parts
    if (sourceParts.isEmpty()) return escapeHtml(surface)
    val usedParts = mutableSetOf<Int>()
    return buildString {
        japaneseTextRuns(surface).forEach { run ->
            append(escapeHtml(run.text))
            if (run.kind == JapaneseTextKind.KANJI) {
                val partIndex = sourceParts.indices.firstOrNull { index ->
                    index !in usedParts && sourceParts[index].expression == run.text
                } ?: return@forEach
                usedParts += partIndex
                append("[")
                append(escapeHtml(sourceParts[partIndex].reading))
                append("]")
            }
        }
    }
}

internal fun furiganaPlain(expression: String, reading: String): String =
    furiganaNotation(expression, reading).html

private data class FuriganaPart(val expression: String, val reading: String)
private data class FuriganaNotation(val html: String, val parts: List<FuriganaPart>)
private data class JapaneseTextRun(val text: String, val kind: JapaneseTextKind)
private enum class JapaneseTextKind { KANJI, KANA, OTHER }

private fun furiganaNotation(expression: String, reading: String): FuriganaNotation {
    val runs = japaneseTextRuns(expression)
    val readingText = normalizeKana(reading)
    val kanjiRunIndices = runs.indices.filter { runs[it].kind == JapaneseTextKind.KANJI }
    if (readingText.isEmpty() || kanjiRunIndices.isEmpty()) {
        return FuriganaNotation(escapeHtml(expression), emptyList())
    }

    val kanaRunIndices = runs.indices.filter { runs[it].kind == JapaneseTextKind.KANA }
    val kanaRanges = if (kanaRunIndices.isEmpty()) {
        emptyList()
    } else {
        alignKanaRuns(kanaRunIndices.map { normalizeKana(runs[it].text) }, readingText)
            ?: run {
                val fallback = mutableMapOf<Int, String>()
                fallback[kanjiRunIndices.first()] = readingText
                return renderFuriganaRuns(runs, fallback)
            }
    }
    val rangeByRunIndex = kanaRunIndices.zip(kanaRanges).toMap()
    val readingsByRunIndex = mutableMapOf<Int, String>()
    var readingCursor = 0

    runs.forEachIndexed { index, run ->
        when (run.kind) {
            JapaneseTextKind.KANJI -> {
                val nextKanaRun = rangeByRunIndex.keys.filter { it > index }.minOrNull()
                val readingEnd = nextKanaRun?.let { rangeByRunIndex.getValue(it).first }
                    ?: readingText.length
                if (readingEnd >= readingCursor) {
                    val part = readingText.substring(readingCursor, readingEnd)
                    if (part.isNotEmpty()) readingsByRunIndex[index] = part
                    readingCursor = readingEnd
                }
            }
            JapaneseTextKind.KANA -> {
                val range = rangeByRunIndex[index] ?: return@forEachIndexed
                readingCursor = maxOf(readingCursor, range.last + 1)
            }
            JapaneseTextKind.OTHER -> Unit
        }
    }
    return renderFuriganaRuns(runs, readingsByRunIndex)
}

private fun renderFuriganaRuns(
    runs: List<JapaneseTextRun>,
    readingsByRunIndex: Map<Int, String>,
): FuriganaNotation {
    val parts = mutableListOf<FuriganaPart>()
    val html = buildString {
        runs.forEachIndexed { index, run ->
            append(escapeHtml(run.text))
            val part = readingsByRunIndex[index]?.takeIf(String::isNotEmpty)
            if (run.kind == JapaneseTextKind.KANJI && part != null) {
                append("[")
                append(escapeHtml(part))
                append("]")
                parts += FuriganaPart(run.text, part)
            }
        }
    }
    return FuriganaNotation(html, parts)
}

private fun alignKanaRuns(kanaRuns: List<String>, reading: String): List<IntRange>? {
    val failedStates = mutableSetOf<Pair<Int, Int>>()
    fun align(index: Int, cursor: Int): List<IntRange>? {
        if (index == kanaRuns.size) return emptyList()
        val state = index to cursor
        if (state in failedStates) return null

        val anchor = kanaRuns[index]
        var start = reading.indexOf(anchor, cursor)
        while (start >= 0) {
            val range = start until (start + anchor.length)
            val following = align(index + 1, range.last + 1)
            if (following != null) return listOf(range) + following
            start = reading.indexOf(anchor, start + 1)
        }
        failedStates += state
        return null
    }
    return align(0, 0)
}

private fun japaneseTextRuns(value: String): List<JapaneseTextRun> {
    if (value.isEmpty()) return emptyList()
    val result = mutableListOf<JapaneseTextRun>()
    var runStart = 0
    var currentKind: JapaneseTextKind? = null
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val kind = when {
            isHanCodePoint(codePoint) -> JapaneseTextKind.KANJI
            isKanaCodePoint(codePoint) -> JapaneseTextKind.KANA
            else -> JapaneseTextKind.OTHER
        }
        if (currentKind != kind) {
            if (currentKind != null) result += JapaneseTextRun(value.substring(runStart, index), currentKind)
            runStart = index
            currentKind = kind
        }
        index += Character.charCount(codePoint)
    }
    result += JapaneseTextRun(value.substring(runStart), currentKind!!)
    return result
}

private fun normalizeKana(value: String): String {
    val compatible = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    return buildString {
        var index = 0
        while (index < compatible.length) {
            val codePoint = compatible.codePointAt(index)
            val hiraganaCodePoint = when (codePoint) {
                in 0x30A1..0x30F6 -> codePoint - 0x60
                in 0x30FD..0x30FE -> codePoint - 0x60
                else -> codePoint
            }
            appendCodePoint(hiraganaCodePoint)
            index += Character.charCount(codePoint)
        }
    }
}

internal fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
