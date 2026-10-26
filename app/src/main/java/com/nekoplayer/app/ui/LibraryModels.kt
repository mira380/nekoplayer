package com.nekoplayer.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue

import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.library.AniListAiringEpisode
import com.nekoplayer.app.library.AniListAiringLookup
import com.nekoplayer.app.library.AniListLibraryMatch
import com.nekoplayer.app.library.AnimeLibraryEpisode
import com.nekoplayer.app.library.jimakuTitleKey

import org.json.JSONArray
import org.json.JSONObject

import java.text.SimpleDateFormat
import java.util.Locale

internal enum class LibraryShowSort(val title: String) {
    TITLE("Title A–Z"),
    RECENT("Recently watched"),
    EPISODES("Most episodes");

    companion object {
        fun fromStored(value: String): LibraryShowSort = entries.firstOrNull { it.name == value } ?: TITLE
    }
}

internal fun moveLibraryGroup(
    groups: List<String>,
    groupName: String,
    targetIndex: Int,
): List<String> {
    val currentIndex = groups.indexOfFirst { it.equals(groupName, ignoreCase = true) }
    if (currentIndex < 0 || groups.size < 2) return groups

    val destination = targetIndex.coerceIn(0, groups.lastIndex)
    if (currentIndex == destination) return groups

    return groups.toMutableList().apply {
        val group = removeAt(currentIndex)
        add(destination, group)
    }
}

internal fun normalizeLibraryGroupAssignments(
    assignments: Map<String, Set<String>>,
    groupPriority: List<String>,
): Map<String, Set<String>> = buildMap {
    assignments.forEach { (itemKey, assignedGroups) ->
        if (itemKey.isBlank()) return@forEach
        val selectedGroup = groupPriority.firstOrNull { groupName ->
            assignedGroups.any { it.equals(groupName, ignoreCase = true) }
        }
        if (selectedGroup != null) put(itemKey, setOf(selectedGroup))
    }
}

internal sealed interface LibraryShelfItem {
    val key: String
    val title: String
    val episodeCount: Int
    val lastWatchedAtMs: Long

    data class LocalShow(
        val showName: String,
        val episodes: List<AnimeLibraryEpisode>,
        override val lastWatchedAtMs: Long,
    ) : LibraryShelfItem {
        override val key: String get() = "show:$showName"
        override val title: String get() = showName
        override val episodeCount: Int get() = episodes.size
    }

    data class SourceTitle(
        val entry: AppDatabase.SourceLibraryEntry,
        override val lastWatchedAtMs: Long,
    ) : LibraryShelfItem {
        override val key: String get() = "source:${entry.key}"
        override val title: String get() = entry.title
        override val episodeCount: Int get() = entry.episodeCount ?: 0
    }
}

internal data class LibraryShelf(
    val key: String,
    val title: String,
    val entries: List<LibraryShelfItem>,
)

internal enum class LibrarySubtitleState(val label: String) {
    MATCHING("Matching"),
    NO_MATCH("No subs"),
    ANI_LIST_ERROR("AniList error"),
    CHECKING("Checking"),
    AVAILABLE("Subs"),
    MISSING("No subs"),
    KEY_REQUIRED("Key needed"),
    KEY_REJECTED("Check key"),
    RATE_LIMITED("Rate limited"),
    ERROR("Retrying"),
}

internal data class LibrarySubtitleCheckTarget(
    val key: String,
    val title: String,
    val sourceUrl: String?,
    val episode: Int,
    val aniListId: Int?,
    val lookup: AniListAiringLookup,
)

internal data class LibrarySubtitleStatus(
    val episodeNumber: Int,
    val state: LibrarySubtitleState,
    val checkedAtMs: Long,
    val apiKeyFingerprint: Int = 0,
    val errorMessage: String? = null,
    val retryAfterMs: Long? = null,
    val latestAvailableEpisode: Int? = null,
    val aniListId: Int? = null,
    val jimakuEntryId: Int? = null,
    val titleMatchRevision: Int = JIMAKU_TITLE_MATCH_REVISION,
)

private const val LIBRARY_SUBTITLE_STATUS_CACHE_KEY = "library.subtitleAvailabilityCache"
private const val LIBRARY_ANI_LIST_MATCH_CACHE_KEY = "library.aniListMatchCache"
private const val LIBRARY_MANUAL_JIMAKU_MATCH_CACHE_KEY = "library.manualJimakuMatchCache"
internal const val LIBRARY_JIMAKU_BATCH_CHECK_KEY = "library.jimakuLastBatchCheckMs"
private const val LIBRARY_SEEN_EPISODE_COUNTS_KEY = "library.seenEpisodeCounts.v4"
internal const val JIMAKU_TITLE_MATCH_REVISION = 1

internal fun readLibrarySeenEpisodeCounts(database: AppDatabase): Map<String, Int> {
    val stored = database.readSetting(LIBRARY_SEEN_EPISODE_COUNTS_KEY) as? JSONObject ?: return emptyMap()
    return buildMap {
        val keys = stored.keys()
        while (keys.hasNext()) {
            val key = keys.next().takeIf(String::isNotBlank) ?: continue
            stored.optInt(key, 0).takeIf { it > 0 }?.let { put(key, it) }
        }
    }
}

internal fun writeLibrarySeenEpisodeCounts(database: AppDatabase, counts: Map<String, Int>) {
    val stored = JSONObject()
    counts.forEach { (key, count) ->
        if (key.isNotBlank() && count > 0) stored.put(key, count)
    }
    database.writeSetting(LIBRARY_SEEN_EPISODE_COUNTS_KEY, stored)
}

internal data class CachedAniListLibraryMatch(
    val signature: String,
    val match: AniListLibraryMatch,
)

internal fun aniListLookupSignature(lookup: AniListAiringLookup): String = buildString {
    append(normalizeAniListCacheText(lookup.showName))
    lookup.episodeFileNames.map(::normalizeAniListCacheText).filter(String::isNotBlank).forEach { fileName ->
        append('|').append(fileName)
    }
}

private fun normalizeAniListCacheText(value: String): String = value.trim().lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun readAniListMatchCache(database: AppDatabase): Map<String, CachedAniListLibraryMatch> {
    val stored = database.readSetting(LIBRARY_ANI_LIST_MATCH_CACHE_KEY) as? JSONArray ?: return emptyMap()
    return buildMap {
        for (index in 0 until stored.length()) {
            val row = stored.optJSONObject(index) ?: continue
            val key = row.optString("key").takeIf(String::isNotBlank) ?: continue
            val signature = row.optString("signature").takeIf(String::isNotBlank) ?: continue
            val aniListId = row.optInt("aniListId", 0).takeIf { it > 0 } ?: continue
            val nextEpisode = row.optInt("nextEpisode", 0).takeIf { it in 1..300 }
            val airingAt = row.optLong("airingAt", 0L).takeIf { it > 0L }
            val airingAniListId = row.optInt("airingAniListId", 0).takeIf { it > 0 } ?: aniListId
            val nextAiring = if (nextEpisode != null && airingAt != null) {
                AniListAiringEpisode(nextEpisode, airingAt, airingAniListId)
            } else {
                null
            }
            put(key, CachedAniListLibraryMatch(signature, AniListLibraryMatch(aniListId, nextAiring)))
        }
    }
}

internal fun writeAniListMatchCache(
    database: AppDatabase,
    matches: Map<String, CachedAniListLibraryMatch>,
) {
    val stored = JSONArray()
    matches.forEach { (key, cached) ->
        if (key.isBlank() || cached.signature.isBlank() || cached.match.aniListId <= 0) return@forEach
        val nextAiring = cached.match.nextAiringEpisode
        stored.put(
            JSONObject()
                .put("key", key)
                .put("signature", cached.signature)
                .put("aniListId", cached.match.aniListId)
                .put("nextEpisode", nextAiring?.episode ?: JSONObject.NULL)
                .put("airingAt", nextAiring?.airingAtEpochSeconds ?: JSONObject.NULL)
                .put("airingAniListId", nextAiring?.aniListId ?: JSONObject.NULL),
        )
    }
    database.writeSetting(LIBRARY_ANI_LIST_MATCH_CACHE_KEY, stored)
}

internal fun readManualJimakuMatchCache(database: AppDatabase): Map<String, Int> {
    val stored = database.readSetting(LIBRARY_MANUAL_JIMAKU_MATCH_CACHE_KEY) as? JSONObject ?: return emptyMap()
    return buildMap {
        val keys = stored.keys()
        while (keys.hasNext()) {
            val titleKey = keys.next().takeIf(String::isNotBlank) ?: continue
            stored.optInt(titleKey, 0).takeIf { it > 0 }?.let { put(titleKey, it) }
        }
    }
}

internal fun saveManualJimakuMatch(database: AppDatabase, title: String, entryId: Int) {
    val titleKey = jimakuTitleKey(title)
    if (titleKey.isBlank() || entryId <= 0) return
    val updated = JSONObject()
    (readManualJimakuMatchCache(database) + (titleKey to entryId)).forEach { (key, id) ->
        updated.put(key, id)
    }
    database.writeSetting(LIBRARY_MANUAL_JIMAKU_MATCH_CACHE_KEY, updated)
}

internal fun readLibrarySubtitleStatusCache(database: AppDatabase): Map<String, LibrarySubtitleStatus> {
    val stored = database.readSetting(LIBRARY_SUBTITLE_STATUS_CACHE_KEY) as? JSONArray ?: return emptyMap()
    return buildMap {
        for (index in 0 until stored.length()) {
            val row = stored.optJSONObject(index) ?: continue
            val key = row.optString("key").takeIf(String::isNotBlank) ?: continue
            val episode = row.optInt("episode", 0).takeIf { it in 1..300 } ?: continue
            val state = LibrarySubtitleState.entries.firstOrNull { it.name == row.optString("state") } ?: continue
            if (state !in PERSISTED_SUBTITLE_STATES) continue
            val checkedAtMs = row.optLong("checkedAtMs", 0L).takeIf { it > 0L } ?: continue
            put(
                key,
                LibrarySubtitleStatus(
                    episodeNumber = episode,
                    state = state,
                    checkedAtMs = checkedAtMs,
                    apiKeyFingerprint = row.optInt("apiKeyFingerprint", 0),
                    latestAvailableEpisode = if (row.isNull("latestAvailableEpisode")) null
                    else row.optInt("latestAvailableEpisode", 0).takeIf { it in 1..300 },
                    aniListId = row.optInt("aniListId", 0).takeIf { it > 0 },
                    jimakuEntryId = row.optInt("jimakuEntryId", 0).takeIf { it > 0 },
                    titleMatchRevision = row.optInt("titleMatchRevision", 0),
                ),
            )
        }
    }
}

internal fun writeLibrarySubtitleStatusCache(
    database: AppDatabase,
    statuses: Map<String, LibrarySubtitleStatus>,
) {
    val stored = JSONArray()
    statuses.forEach { (key, status) ->
        if (key.isBlank() || status.state !in PERSISTED_SUBTITLE_STATES) return@forEach
        stored.put(
            JSONObject()
                .put("key", key)
                .put("episode", status.episodeNumber)
                .put("state", status.state.name)
                .put("checkedAtMs", status.checkedAtMs)
                .put("apiKeyFingerprint", status.apiKeyFingerprint)
                .put("latestAvailableEpisode", status.latestAvailableEpisode ?: JSONObject.NULL)
                .put("aniListId", status.aniListId ?: JSONObject.NULL)
                .put("jimakuEntryId", status.jimakuEntryId ?: JSONObject.NULL)
                .put("titleMatchRevision", status.titleMatchRevision),
        )
    }
    database.writeSetting(LIBRARY_SUBTITLE_STATUS_CACHE_KEY, stored)
}

private val PERSISTED_SUBTITLE_STATES = setOf(
    LibrarySubtitleState.AVAILABLE,
    LibrarySubtitleState.MISSING,
    LibrarySubtitleState.NO_MATCH,
)

internal fun LibraryShelfItem.toAiringLookup(): AniListAiringLookup = when (this) {
    is LibraryShelfItem.LocalShow -> AniListAiringLookup(
        key = key,
        showName = showName,
        episodeFileNames = episodes.take(2).map(AnimeLibraryEpisode::displayName),
    )
    is LibraryShelfItem.SourceTitle -> AniListAiringLookup(
        key = key,
        showName = entry.title,
    )
}

internal fun LibraryShelfItem.toSubtitleCheckTarget(aniListId: Int? = null): LibrarySubtitleCheckTarget =
    when (this) {
        is LibraryShelfItem.LocalShow -> LibrarySubtitleCheckTarget(
            key = key,
            title = showName,
            sourceUrl = null,
            episode = episodeCount,
            aniListId = aniListId,
            lookup = toAiringLookup(),
        )
        is LibraryShelfItem.SourceTitle -> LibrarySubtitleCheckTarget(
            key = key,
            title = entry.title,
            sourceUrl = entry.animeUrl,
            episode = episodeCount,
            aniListId = aniListId,
            lookup = toAiringLookup(),
        )
    }

internal fun sourceTitleSortKey(entry: AppDatabase.SourceLibraryEntry): String =
    entry.title.lowercase()

internal fun sourceEpisodeCount(entry: AppDatabase.SourceLibraryEntry): Int =
    entry.episodeCount ?: 0

internal fun String.toLibraryTimestampMs(): Long = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .parse(this)?.time ?: 0L
}.getOrDefault(0L)
