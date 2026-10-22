package com.nekoplayer.app.ui

import android.annotation.SuppressLint
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.extensions.ExtensionManager
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LIBRARY_EPISODE_COUNT_CHECKS_KEY = "library.episodeCountChecks"
private const val LIBRARY_EPISODE_COUNT_CHECK_INTERVAL_MS = 60L * 60L * 1000L

@SuppressLint("DirectSystemCurrentTimeMillisUsage")
internal suspend fun refreshLibrarySourceEpisodeCounts(
    database: AppDatabase,
    extensionManager: ExtensionManager,
): List<AppDatabase.SourceLibraryEntry> {
    extensionManager.start()
    extensionManager.loading.first { isLoading -> !isLoading }

    return withContext(Dispatchers.IO) {
        val entries = database.listSourceLibraryEntries()
        val checkTimes = (database.readSetting(LIBRARY_EPISODE_COUNT_CHECKS_KEY) as? JSONObject)
            ?: JSONObject()
        val now = System.currentTimeMillis()
        val sourcesById = extensionManager.catalogueSources().associate { it.source.id to it.source }

        entries.forEach { entry ->
            val source = sourcesById[entry.sourceId] ?: return@forEach
            val lastCheckedAt = checkTimes.optLong(entry.key, 0L)
            if (lastCheckedAt > 0L && now - lastCheckedAt in 0 until LIBRARY_EPISODE_COUNT_CHECK_INTERVAL_MS) {
                return@forEach
            }

            try {
                val anime = SAnime.create().apply {
                    url = entry.animeUrl
                    title = entry.title
                    thumbnail_url = entry.thumbnailUrl
                    description = entry.description
                }
                val details = source.getAnimeDetails(anime)
                if (details.fetch_type != FetchType.Seasons) {
                    val episodeCount = source.getEpisodeList(details).size
                    if (episodeCount > 0 && episodeCount != entry.episodeCount) {
                        database.saveSourceLibraryEntry(entry.copy(episodeCount = episodeCount))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the cached count and retry after the refresh interval.
            } catch (_: LinkageError) {
                // An incompatible source should not interrupt the other saved titles.
            }
            checkTimes.put(entry.key, System.currentTimeMillis())
        }

        database.writeSetting(LIBRARY_EPISODE_COUNT_CHECKS_KEY, checkTimes)
        database.listSourceLibraryEntries()
    }
}
