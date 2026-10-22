package com.nekoplayer.app.ui

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val MAX_PARALLEL_VIDEOS_PER_HOSTER = 2

internal suspend fun resolveHosterStreamChoices(
    source: AnimeCatalogueSource,
    hoster: Hoster,
    hosterLabel: String,
    preferredStream: StreamIdentity?,
    firstPlayableOnly: Boolean,
    onChoicesUpdated: (List<StreamChoice>) -> Unit = {},
): List<StreamChoice> {
    if (
        preferredStream != null &&
        normalizedStreamLabel(hosterLabel) != preferredStream.hosterName
    ) return emptyList()

    val httpSource = source as? AnimeHttpSource
    val videos = if (!hoster.lazy && hoster.videoList != null) {
        hoster.videoList
    } else {
        source.getVideoList(hoster)
    }
    val sortedVideos = httpSource?.run { videos.sortVideos() } ?: videos
    val matchingVideos = if (preferredStream == null) {
        sortedVideos
    } else {
        sortedVideos.filter { video ->
            video.videoTitle.isBlank() || streamIdentity(hosterLabel, video.videoTitle) == preferredStream
        }
    }
    if (
        firstPlayableOnly &&
        preferredStream != null &&
        preferredStream.videoTitle.isBlank() &&
        matchingVideos.size > 1
    ) return emptyList()

    if (firstPlayableOnly) {
        for (video in matchingVideos) {
            val choice = resolveSingleStreamChoice(
                source = source,
                httpSource = httpSource,
                video = video,
                hosterLabel = hosterLabel,
                preferredStream = preferredStream,
            ) ?: continue
            onChoicesUpdated(listOf(choice))
            return listOf(choice)
        }
        return emptyList()
    }

    if (matchingVideos.isEmpty()) return emptyList()

    val choicesByVideo = arrayOfNulls<StreamChoice>(matchingVideos.size)
    val videoQueue = Channel<Int>(Channel.UNLIMITED)
    val resolvedVideos = Channel<Pair<Int, StreamChoice?>>(Channel.UNLIMITED)
    matchingVideos.indices.forEach { videoQueue.send(it) }
    videoQueue.close()

    coroutineScope {
        repeat(minOf(MAX_PARALLEL_VIDEOS_PER_HOSTER, matchingVideos.size)) {
            launch(Dispatchers.IO) {
                for (index in videoQueue) {
                    val choice = try {
                        resolveSingleStreamChoice(
                            source = source,
                            httpSource = httpSource,
                            video = matchingVideos[index],
                            hosterLabel = hosterLabel,
                            preferredStream = preferredStream,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    resolvedVideos.send(index to choice)
                }
            }
        }

        repeat(matchingVideos.size) {
            val (index, choice) = resolvedVideos.receive()
            choicesByVideo[index] = choice
            if (choice != null) {
                onChoicesUpdated(choicesByVideo.filterNotNull())
            }
        }
    }

    return choicesByVideo.filterNotNull()
}

private suspend fun resolveSingleStreamChoice(
    source: AnimeCatalogueSource,
    httpSource: AnimeHttpSource?,
    video: Video,
    hosterLabel: String,
    preferredStream: StreamIdentity?,
): StreamChoice? {
    if (
        preferredStream != null &&
        video.videoTitle.isNotBlank() &&
        streamIdentity(hosterLabel, video.videoTitle) != preferredStream
    ) return null

    val resolved = if (httpSource != null) {
        httpSource.resolveVideo(video) ?: return null
    } else {
        video
    }
    if (
        preferredStream != null &&
        streamIdentity(hosterLabel, resolved.videoTitle.ifBlank { video.videoTitle }) != preferredStream
    ) return null

    if (httpSource != null && (resolved.videoUrl.isBlank() || resolved.videoUrl == "null")) {
        val resolvedUrl = try {
            httpSource.getVideoUrl(resolved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        resolvedUrl?.takeIf(String::isNotBlank)?.let { resolved.videoUrl = it }
    }
    if (resolved.videoUrl.isBlank() || resolved.videoUrl == "null") return null

    return StreamChoice(
        label = listOf(hosterLabel, resolved.videoTitle)
            .asSequence()
            .filter(String::isNotBlank)
            .joinToString(" · "),
        video = resolved,
        identity = streamIdentity(hosterLabel, resolved.videoTitle.ifBlank { video.videoTitle }),
    )
}
