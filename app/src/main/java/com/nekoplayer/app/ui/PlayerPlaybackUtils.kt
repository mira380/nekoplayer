package com.nekoplayer.app.ui

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp

import java.security.MessageDigest
import java.util.Locale

import kotlin.math.roundToLong

internal data class PlaybackSkipPoint(
    val type: ChapterType,
    val startMs: Long,
    val endMs: Long,
)

internal fun Double.toPlaybackSkipTimeMsOrNull(): Long? {
    if (!isFinite() || this < 0.0 || this > Long.MAX_VALUE.toDouble() / 1_000.0) return null
    return (this * 1_000.0).roundToLong()
}

internal fun TimeStamp.toPlaybackSkipPointOrNull(): PlaybackSkipPoint? {
    if (type != ChapterType.Opening && type != ChapterType.Ending) return null
    val startMs = start.toPlaybackSkipTimeMsOrNull() ?: return null
    val endMs = end.toPlaybackSkipTimeMsOrNull() ?: return null
    if (endMs <= startMs) return null
    return PlaybackSkipPoint(type, startMs, endMs)
}

internal fun formatPlayerTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

internal fun Long.saturatedPlus(other: Long): Long = when {
    other > 0L && this > Long.MAX_VALUE - other -> Long.MAX_VALUE
    other < 0L && this < Long.MIN_VALUE - other -> Long.MIN_VALUE
    else -> this + other
}

internal fun Long.saturatedMinus(other: Long): Long = when {
    other > 0L && this < Long.MIN_VALUE + other -> Long.MIN_VALUE
    other < 0L && this > Long.MAX_VALUE + other -> Long.MAX_VALUE
    else -> this - other
}

internal fun subtitleOffsetSettingsKeyFor(identity: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val stableHash = digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "player.subtitleOffset.$stableHash"
}

internal fun formatSubtitleOffset(offsetMs: Long): String = when {
    offsetMs > 0L -> "+${offsetMs} ms"
    offsetMs < 0L -> "−${offsetMs.toString().drop(1)} ms"
    else -> "0 ms"
}
