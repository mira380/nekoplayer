package com.nekoplayer.app.media

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

// Cue times are source-relative, and endMs is exclusive.
data class SubtitleCue(
    val id: String,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    // Display text with ASS overrides removed and line breaks retained.
    val text: String,
    // Plain text used by dictionary lookup and accessibility.
    val plainText: String,
)

enum class SubtitleTrackSource {
    MANUAL,
    JIMAKU,
}

// offsetMs shifts cue times relative to the video.
data class SubtitleTrack(
    val id: String,
    val name: String,
    val format: String,
    val cues: List<SubtitleCue>,
    val offsetMs: Long = 0,
    val source: SubtitleTrackSource = SubtitleTrackSource.MANUAL,
)

enum class SubtitleParseFailure {
    FILE_TOO_LARGE,
    INVALID_UTF8,
    UNSUPPORTED_FORMAT,
    NO_VALID_CUES,
}

class SubtitleParseException(
    val failure: SubtitleParseFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

// Parses SRT, WebVTT, ASS, and SSA files without Android dependencies.
// Keep URI reads under 20 MiB too, so oversized files are not loaded into memory first.
object SubtitleParser {
    const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("srt", "vtt", "ass", "ssa")

    fun parse(bytes: ByteArray, fileName: String): SubtitleTrack {
        if (bytes.size > MAX_FILE_BYTES) {
            throw SubtitleParseException(
                SubtitleParseFailure.FILE_TOO_LARGE,
                "Subtitle files must be 20 MiB or smaller.",
            )
        }
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val input = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        val content = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input))
                .toString()
        } catch (error: Exception) {
            throw SubtitleParseException(
                SubtitleParseFailure.INVALID_UTF8,
                "The subtitle file is not valid UTF-8 text.",
                error,
            )
        }
        return parseText(content, fileName)
    }

    fun parseText(content: String, fileName: String): SubtitleTrack {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS) {
            throw SubtitleParseException(
                SubtitleParseFailure.UNSUPPORTED_FORMAT,
                "Choose an SRT, WebVTT, ASS, or SSA subtitle file.",
            )
        }
        val normalized = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val rawCues = when (extension) {
            "srt" -> parseTimedBlocks(normalized)
            "vtt" -> parseWebVtt(normalized)
            "ass", "ssa" -> parseAss(normalized)
            else -> emptyList()
        }.asSequence()
            .filter { it.startMs >= 0 && it.endMs > it.startMs && plainText(it.text).isNotEmpty() }
            .sortedBy { it.startMs }
            .toList()

        if (rawCues.isEmpty()) {
            throw SubtitleParseException(
                SubtitleParseFailure.NO_VALID_CUES,
                "No valid subtitle cues were found in this file.",
            )
        }

        val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.', "").trim()
        val name = baseName.ifEmpty { "Subtitles" }
        val trackId = "external-$extension-${baseName.ifEmpty { "subtitles" }}"
        val cues = rawCues.mapIndexed { index, cue ->
            SubtitleCue(
                id = "$trackId-${index + 1}",
                trackId = trackId,
                startMs = cue.startMs,
                endMs = cue.endMs,
                text = cue.text,
                plainText = plainText(cue.text),
            )
        }
        return SubtitleTrack(trackId, name, extension, cues)
    }

    private fun parseTimedBlocks(content: String): List<RawCue> = buildList {
        for (block in content.split(Regex("\\n[ \\t]*\\n+"))) {
            val lines = block.split('\n').map { it.trimEnd() }
            val timingIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingIndex < 0) continue
            val timing = parseTimingLine(lines[timingIndex]) ?: continue
            val text = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) add(RawCue(timing.first, timing.second, text))
        }
    }

    private fun parseWebVtt(content: String): List<RawCue> {
        val cueBlocks = content.split(Regex("\\n[ \\t]*\\n+")).filter { block ->
            val firstLine = block.trimStart().substringBefore('\n').uppercase(Locale.ROOT)
            !firstLine.startsWith("WEBVTT") && !firstLine.startsWith("NOTE") &&
                !firstLine.startsWith("STYLE") && !firstLine.startsWith("REGION")
        }
        return cueBlocks.flatMap(::parseTimedBlocks)
    }

    private fun parseAss(content: String): List<RawCue> {
        val defaults = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        var fields = defaults
        val hasEventsSection = content.lineSequence().any { it.trim().equals("[Events]", ignoreCase = true) }
        var inEvents = !hasEventsSection
        return buildList {
            for (line in content.lineSequence()) {
                val value = line.trim()
                if (value.startsWith('[') && value.endsWith(']')) {
                    inEvents = value.equals("[Events]", ignoreCase = true)
                    continue
                }
                if (!inEvents) continue
                if (value.startsWith("Format:", ignoreCase = true)) {
                    fields = value.substringAfter(':').split(',').map { it.trim() }
                    continue
                }
                if (!value.startsWith("Dialogue:", ignoreCase = true)) continue
                val values = splitAssFields(value.substringAfter(':').trimStart(), fields.size) ?: continue
                val startIndex = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }
                val endIndex = fields.indexOfFirst { it.equals("End", ignoreCase = true) }
                val textIndex = fields.indexOfFirst { it.equals("Text", ignoreCase = true) }
                if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue
                val start = parseTimestamp(values[startIndex]) ?: continue
                val end = parseTimestamp(values[endIndex]) ?: continue
                val text = values[textIndex]
                    .replace(Regex("\\{[^\\}]*\\}"), "")
                    .replace("\\N", "\n", ignoreCase = true)
                    .replace("\\h", " ", ignoreCase = true)
                    .trim()
                if (end > start && plainText(text).isNotEmpty()) add(RawCue(start, end, text))
            }
        }
    }

    // ASS text can contain commas, so only split the first N-1 fields.
    private fun splitAssFields(line: String, fieldCount: Int): List<String>? {
        if (fieldCount < 1) return null
        val values = ArrayList<String>(fieldCount)
        var start = 0
        repeat(fieldCount - 1) {
            val comma = line.indexOf(',', start)
            if (comma < 0) return null
            values += line.substring(start, comma).trim()
            start = comma + 1
        }
        values += line.substring(start).trim()
        return values
    }

    private fun parseTimingLine(line: String): Pair<Long, Long>? {
        val arrow = line.indexOf("-->")
        if (arrow < 0 || line.indexOf("-->", arrow + 3) >= 0) return null
        val start = parseTimestamp(line.substring(0, arrow).trim()) ?: return null
        val endToken = line.substring(arrow + 3).trim().split(Regex("\\s+"), limit = 2).firstOrNull() ?: return null
        val end = parseTimestamp(endToken) ?: return null
        return if (end > start) start to end else null
    }

    private fun parseTimestamp(raw: String): Long? {
        val parts = raw.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secParts = parts.last().split('.')
        if (secParts.size != 2 || secParts[1].isEmpty() || secParts[1].length > 3) return null
        val seconds = secParts[0].toLongOrNull() ?: return null
        val millis = secParts[1].padEnd(3, '0').toLongOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
        return try {
            Math.addExact(
                Math.addExact(
                    Math.addExact(Math.multiplyExact(hours, 3_600_000L), Math.multiplyExact(minutes, 60_000L)),
                    Math.multiplyExact(seconds, 1_000L),
                ),
                millis,
            )
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun plainText(input: String): String = input
        .replace(Regex("\\{[^\\}]*\\}"), "")
        .replace(Regex("<[^>]*>"), "")
        .replace("\\N", "\n", ignoreCase = true)
        .replace("\\h", " ", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        .trim()

    private data class RawCue(val startMs: Long, val endMs: Long, val text: String)
}

// Cues are sorted by start time, so binary search narrows the active range.
object SubtitleCueIndex {
    fun activeCueIndices(track: SubtitleTrack, positionMs: Long): List<Int> {
        val cues = track.cues
        if (cues.isEmpty()) return emptyList()
        val subtitleTime = positionMs - track.offsetMs
        val lastStartedCue = lastStartedCueIndex(cues, subtitleTime)
        if (lastStartedCue < 0) return emptyList()
        return buildList {
            for (index in 0..lastStartedCue) {
                if (cues[index].endMs > subtitleTime) add(index)
            }
        }
    }

    fun activeCueIndex(track: SubtitleTrack, positionMs: Long): Int? {
        val cues = track.cues
        if (cues.isEmpty()) return null
        val subtitleTime = positionMs - track.offsetMs
        val lastStartedCue = lastStartedCueIndex(cues, subtitleTime)
        if (lastStartedCue < 0) return null
        for (index in lastStartedCue downTo 0) {
            if (cues[index].endMs > subtitleTime) return index
        }
        return null
    }

    private fun lastStartedCueIndex(cues: List<SubtitleCue>, subtitleTime: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = low + ((high - low) ushr 1)
            if (cues[middle].startMs <= subtitleTime) low = middle + 1 else high = middle
        }
        return low - 1
    }

    fun nextCueIndex(track: SubtitleTrack, positionMs: Long): Int? {
        val active = activeCueIndex(track, positionMs)
        val threshold = if (active == null) positionMs - track.offsetMs else track.cues[active].startMs
        var low = 0
        var high = track.cues.size
        while (low < high) {
            val middle = low + ((high - low) ushr 1)
            if (track.cues[middle].startMs <= threshold) low = middle + 1 else high = middle
        }
        return low.takeIf { it < track.cues.size }
    }

    fun previousCueIndex(track: SubtitleTrack, positionMs: Long): Int? {
        val active = activeCueIndex(track, positionMs)
        val threshold = if (active == null) positionMs - track.offsetMs else track.cues[active].startMs
        var low = 0
        var high = track.cues.size
        while (low < high) {
            val middle = low + ((high - low) ushr 1)
            if (track.cues[middle].startMs < threshold) low = middle + 1 else high = middle
        }
        val candidate = low - 1
        return candidate.takeIf { it >= 0 }
    }

    fun replayCueIndex(track: SubtitleTrack, positionMs: Long): Int? =
        activeCueIndex(track, positionMs) ?: previousCueIndex(track, positionMs) ?: 0.takeIf { track.cues.isNotEmpty() }

    fun seekPositionMs(track: SubtitleTrack, cue: SubtitleCue, durationMs: Long = 0): Long {
        val shifted = (cue.startMs + track.offsetMs).coerceAtLeast(0)
        return if (durationMs > 0) shifted.coerceAtMost(durationMs) else shifted
    }
}
