package com.nekoplayer.app.ui

import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuEntry
import com.nekoplayer.app.library.JimakuSubtitleClient
import com.nekoplayer.app.library.jimakuReferenceTitles
import com.nekoplayer.app.library.jimakuTitleKey
import com.nekoplayer.app.library.rankJimakuEntriesByTitle
import kotlinx.coroutines.CancellationException

internal data class LibrarySubtitleRefreshResult(
    val statuses: Map<String, LibrarySubtitleStatus>,
    val aniListMatches: Map<String, CachedAniListLibraryMatch>,
    val batchCheckedAtMs: Long?,
)

// Loads changed Jimaku collections once, then inspects only changed or previously unchecked titles
@android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
internal fun refreshLibrarySubtitleAvailability(
    aniList: AniListClient,
    jimaku: JimakuSubtitleClient,
    apiKey: String,
    items: List<LibraryShelfItem>,
    cachedStatuses: Map<String, LibrarySubtitleStatus>,
    cachedAniListMatches: Map<String, CachedAniListLibraryMatch>,
    manualEntryIds: Map<String, Int>,
    lastBatchCheckMs: Long,
): LibrarySubtitleRefreshResult {
    val now = System.currentTimeMillis()
    val fingerprint = apiKey.hashCode()
    val statuses = cachedStatuses.toMutableMap()
    val aniListMatches = cachedAniListMatches.toMutableMap()
    val targets = items.distinctBy(LibraryShelfItem::key).map { item ->
        val signature = aniListLookupSignature(item.toAiringLookup())
        val cachedStatus = statuses[item.key]
        val cachedMatch = aniListMatches[item.key]?.takeIf { it.signature == signature }?.match
        val storedAniListId = cachedStatus?.aniListId?.takeIf { it > 0 }
        val referenceTitles = jimakuReferenceTitles(
            showTitle = item.title,
            sourceUrl = (item as? LibraryShelfItem.SourceTitle)?.entry?.animeUrl,
        )
        val manualEntryId = referenceTitles.firstNotNullOfOrNull { manualEntryIds[jimakuTitleKey(it)] }
        Target(
            item = item,
            signature = signature,
            cachedStatus = cachedStatus,
            aniListId = cachedMatch?.aniListId ?: storedAniListId,
            cachedAiring = cachedMatch?.nextAiringEpisode,
            manualEntryId = manualEntryId,
            referenceTitles = referenceTitles,
        )
    }

    val aniListLookups = targets.filter { target ->
        target.aniListId == null &&
            target.manualEntryId == null &&
            target.cachedStatus?.jimakuEntryId == null &&
            target.cachedStatus?.state != LibrarySubtitleState.NO_MATCH &&
            target.item.episodeCount > 0
    }
    if (aniListLookups.isNotEmpty()) {
        val found = aniList.findLibraryMatches(aniListLookups.map { it.item.toAiringLookup() })
        aniListLookups.forEach { target ->
            val match = found[target.item.key] ?: return@forEach
            aniListMatches[target.item.key] = CachedAniListLibraryMatch(target.signature, match)
        }
    }

    val targetsWithMatches = targets.map { target ->
        val cachedMatch = aniListMatches[target.item.key]?.takeIf { it.signature == target.signature }?.match
        target.copy(
            aniListId = target.aniListId ?: cachedMatch?.aniListId,
            cachedAiring = target.cachedAiring ?: cachedMatch?.nextAiringEpisode,
        )
    }

    val batchAfter = if (lastBatchCheckMs > 0L) {
        lastBatchCheckMs / 1_000L
    } else {
        (now - INITIAL_JIMAKU_BATCH_LOOKBACK_MS).coerceAtLeast(0L) / 1_000L
    }
    var batchRequestSucceeded = false
    var batchCheckComplete = true
    val changedEntries = try {
        jimaku.searchRecentlyModifiedEntries(apiKey, batchAfter).also { batchRequestSucceeded = true }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    val checkedEntryEpisodes = mutableMapOf<Pair<Int, Int>, com.nekoplayer.app.library.JimakuSubtitleAvailability>()
    targetsWithMatches.forEach { target ->
        val item = target.item
        val currentEpisode = item.episodeCount.takeIf { it in 1..300 } ?: return@forEach
        val previous = target.cachedStatus
        val cacheIsCurrent = previous != null &&
            previous.apiKeyFingerprint == fingerprint &&
            previous.titleMatchRevision == JIMAKU_TITLE_MATCH_REVISION
        val needsRefresh = !cacheIsCurrent || previous.episodeNumber < currentEpisode
        val manualId = target.manualEntryId
        val cachedEntryId = previous?.jimakuEntryId
        val preferredEntryId = manualId ?: cachedEntryId

        val changedEntry = findChangedEntry(
            entries = changedEntries,
            target = target,
            preferredEntryId = preferredEntryId,
        )
        val entryIdToInspect = changedEntry?.id ?: if (needsRefresh) preferredEntryId else null

        val availability = try {
            when {
                entryIdToInspect != null -> {
                    val cacheKey = entryIdToInspect to currentEpisode
                    checkedEntryEpisodes[cacheKey]
                        ?: jimaku.findSubtitleAvailabilityInEntry(apiKey, entryIdToInspect, currentEpisode)
                            .also { checkedEntryEpisodes[cacheKey] = it }
                }
                needsRefresh -> jimaku.findSubtitleAvailability(
                    apiKey = apiKey,
                    showTitle = item.title,
                    episode = currentEpisode,
                    aniListId = target.aniListId,
                    sourceUrl = (item as? LibraryShelfItem.SourceTitle)?.entry?.animeUrl,
                )
                else -> return@forEach
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            batchCheckComplete = false
            return@forEach
        }

        val state = when {
            !availability.entryFound -> LibrarySubtitleState.NO_MATCH
            availability.availableForEpisode -> LibrarySubtitleState.AVAILABLE
            else -> LibrarySubtitleState.MISSING
        }
        statuses[item.key] = LibrarySubtitleStatus(
            episodeNumber = currentEpisode,
            state = state,
            checkedAtMs = now,
            apiKeyFingerprint = fingerprint,
            latestAvailableEpisode = availability.latestAvailableEpisode,
            aniListId = target.aniListId,
            jimakuEntryId = availability.entryId ?: entryIdToInspect,
        )
    }

    return LibrarySubtitleRefreshResult(
        statuses = statuses,
        aniListMatches = aniListMatches,
        batchCheckedAtMs = now.takeIf { batchRequestSucceeded && batchCheckComplete },
    )
}

private data class Target(
    val item: LibraryShelfItem,
    val signature: String,
    val cachedStatus: LibrarySubtitleStatus?,
    val aniListId: Int?,
    val cachedAiring: com.nekoplayer.app.library.AniListAiringEpisode?,
    val manualEntryId: Int?,
    val referenceTitles: List<String>,
)

private fun findChangedEntry(
    entries: List<JimakuEntry>,
    target: Target,
    preferredEntryId: Int?,
): JimakuEntry? {
    if (entries.isEmpty()) return null
    preferredEntryId?.let { id -> entries.firstOrNull { it.id == id }?.let { return it } }
    target.aniListId?.let { id -> entries.firstOrNull { it.anilistId == id }?.let { return it } }
    return rankJimakuEntriesByTitle(target.referenceTitles, entries)
        .firstOrNull { it.score >= com.nekoplayer.app.library.MIN_LIBRARY_TITLE_MATCH_SCORE }
        ?.entry
}

private const val INITIAL_JIMAKU_BATCH_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1_000
