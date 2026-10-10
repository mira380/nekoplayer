package com.nekoplayer.app.media

import com.nekoplayer.app.data.DictionaryEntry

private fun dictionaryEntryKey(entry: DictionaryEntry): String =
    if (entry.id == 0L) "object:${System.identityHashCode(entry)}" else "${entry.dictionaryId}:${entry.id}"

fun interface ExactTermLookup {
    fun lookupExactTerms(terms: Set<String>): List<DictionaryEntry>
}

// Match offsets use UTF-16 indices so they line up with Compose text layout.
data class SubtitleScanResult(
    val surfaceText: String,
    val startUtf16Offset: Int,
    val endUtf16Offset: Int,
    val entries: List<DictionaryEntry>,
    // Shorter dictionary matches that start at the same character as this match.
    val relatedEntries: List<DictionaryEntry>,
    val deinflectionReasons: List<String>,
)

// Checks dictionary candidates synchronously so callers can keep each result tied to its subtitle cue.
class SubtitleScanner(
    private val scanLength: Int = DEFAULT_SCAN_LENGTH,
    private val deinflector: JapaneseDeinflector = JapaneseDeinflector(),
) {
    fun scanAt(
        text: String,
        utf16Offset: Int,
        lookupExactTerms: ExactTermLookup,
    ): SubtitleScanResult? = scan(text, utf16Offset, lookupExactTerms, includeWordsBeforeTap = false)

    // Include matches that begin before the tapped character.
    fun scanContaining(
        text: String,
        utf16Offset: Int,
        lookupExactTerms: ExactTermLookup,
    ): SubtitleScanResult? = scan(text, utf16Offset, lookupExactTerms, includeWordsBeforeTap = true)

    private fun scan(
        text: String,
        utf16Offset: Int,
        lookupExactTerms: ExactTermLookup,
        includeWordsBeforeTap: Boolean,
    ): SubtitleScanResult? {
        val safeOffset = normalizeUtf16Offset(text, utf16Offset)
            ?: if (includeWordsBeforeTap && utf16Offset == text.length && text.isNotEmpty()) {
                text.offsetByCodePoints(text.length, -1)
            } else return null
        val totalCodePoints = text.codePointCount(0, text.length)
        val tappedCodePoint = text.codePointCount(0, safeOffset)
        if (tappedCodePoint >= totalCodePoints) return null

        val maxLength = scanLength.coerceIn(1, MAX_SCAN_LENGTH)
        val firstStartCodePoint = if (includeWordsBeforeTap) {
            (tappedCodePoint - maxLength + 1).coerceAtLeast(0)
        } else tappedCodePoint

        val candidatesByTerm = LinkedHashMap<String, MutableList<SurfaceCandidate>>()
        val lookupTerms = LinkedHashSet<String>()
        val surfaces = HashMap<SurfaceSpan, String>()
        for (startCodePoint in firstStartCodePoint..tappedCodePoint) {
            val startOffset = text.offsetByCodePoints(0, startCodePoint)
            val availableLength = minOf(totalCodePoints - startCodePoint, maxLength)
            for (length in 1..availableLength) {
                val endOffset = text.offsetByCodePoints(startOffset, length)
                if (includeWordsBeforeTap && safeOffset !in startOffset until endOffset) continue
                val span = SurfaceSpan(startOffset, length)
                val surface = text.substring(startOffset, endOffset)
                surfaces[span] = surface
                for (candidate in deinflector.deinflect(surface)) {
                    if (candidate.term.isEmpty()) continue
                    lookupTerms += candidate.term
                    candidatesByTerm.getOrPut(candidate.term, ::ArrayList)
                        .add(SurfaceCandidate(startOffset, length, candidate))
                }
            }
        }
        if (lookupTerms.isEmpty()) return null

        val dictionaryEntries = lookupExactTerms.lookupExactTerms(lookupTerms)
        if (dictionaryEntries.isEmpty()) return null

        val foundBySpan = LinkedHashMap<SurfaceSpan, MatchedSurface>()
        for (entry in dictionaryEntries) {
            val exactTerms = linkedSetOf(entry.expression, entry.reading).apply { remove("") }
            for (term in exactTerms) {
                for (match in candidatesByTerm[term].orEmpty()) {
                    if (!match.candidate.matchesDictionaryRules(entry.rules)) continue
                    val span = SurfaceSpan(match.startOffset, match.surfaceLength)
                    val found = foundBySpan.getOrPut(span) {
                        MatchedSurface(surfaces.getValue(span))
                    }
                    if (found.addEntry(entry)) {
                        val reasons = match.candidate.reasons
                        if (found.deinflectionReasons == null ||
                            reasons.size < found.deinflectionReasons!!.size
                        ) {
                            found.deinflectionReasons = reasons
                        }
                    }
                }
            }
        }
        if (foundBySpan.isEmpty()) return null

        val winningMatch = foundBySpan.entries
            .filter { (span, _) ->
                val endOffset = text.offsetByCodePoints(span.startOffset, span.surfaceLength)
                safeOffset in span.startOffset until endOffset
            }
            .sortedWith(
                compareByDescending<Map.Entry<SurfaceSpan, MatchedSurface>> { it.key.surfaceLength }
                    .thenBy { safeOffset - it.key.startOffset },
            )
            .firstOrNull() ?: return null
        val winningSpan = winningMatch.key
        val winner = winningMatch.value
        val shorterMatchesAtSameStart = foundBySpan.entries
            .filter { it.key.startOffset == winningSpan.startOffset && it.key.surfaceLength < winningSpan.surfaceLength }
            .associate { it.key.surfaceLength to it.value }
        val matchEndOffset = text.offsetByCodePoints(winningSpan.startOffset, winningSpan.surfaceLength)
        return SubtitleScanResult(
            surfaceText = winner.surfaceText,
            startUtf16Offset = winningSpan.startOffset,
            endUtf16Offset = matchEndOffset,
            entries = winner.entries.toList(),
            relatedEntries = plainWordEntries(shorterMatchesAtSameStart, winningSpan.surfaceLength, winner),
            deinflectionReasons = winner.deinflectionReasons?.toList().orEmpty(),
        )
    }

    private fun plainWordEntries(
        foundByLength: Map<Int, MatchedSurface>,
        longestLength: Int,
        winner: MatchedSurface,
    ): List<DictionaryEntry> {
        val related = ArrayList<DictionaryEntry>()
        val seen = HashSet<String>()
        for (length in foundByLength.keys.sortedDescending()) {
            if (length >= longestLength) continue
            for (entry in foundByLength.getValue(length).entries) {
                if (winner.contains(entry) || !seen.add(dictionaryEntryKey(entry))) continue
                related += entry
            }
        }
        return related.toList()
    }

    private fun normalizeUtf16Offset(text: String, offset: Int): Int? {
        if (offset < 0 || offset >= text.length) return null
        if (offset == 0) return 0
        val current = text[offset]
        val previous = text[offset - 1]
        return if (current in '\uDC00'..'\uDFFF' && previous in '\uD800'..'\uDBFF') offset - 1 else offset
    }

    private data class SurfaceCandidate(
        val startOffset: Int,
        val surfaceLength: Int,
        val candidate: DeinflectedCandidate,
    )

    private data class SurfaceSpan(val startOffset: Int, val surfaceLength: Int)

    private class MatchedSurface(val surfaceText: String) {
        val entries = ArrayList<DictionaryEntry>()
        private val entryKeys = HashSet<String>()
        var deinflectionReasons: List<String>? = null

        fun addEntry(entry: DictionaryEntry): Boolean {
            if (!entryKeys.add(dictionaryEntryKey(entry))) return false
            entries += entry
            return true
        }

        fun contains(entry: DictionaryEntry): Boolean = dictionaryEntryKey(entry) in entryKeys
    }

    private companion object {
        const val DEFAULT_SCAN_LENGTH = 20
        const val MAX_SCAN_LENGTH = 100
    }
}
