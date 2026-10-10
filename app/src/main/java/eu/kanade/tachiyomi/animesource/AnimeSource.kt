// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import rx.Observable

/**
 * Runtime ABI for Yūzōnō/Ankiyomi video extensions.
 *
 * Methods introduced in newer extension API versions have defaults so older
 * extensions remain loadable while newer ones can override them.
 */
@Suppress("DEPRECATION")
interface AnimeSource {
    val id: Long
    val name: String

    val lang: String
        get() = ""

    val supportsLatest: Boolean
        get() = false

    fun getFilterList(): AnimeFilterList = AnimeFilterList()

    suspend fun getAnimeDetails(anime: SAnime): SAnime =
        fetchAnimeDetails(anime).awaitSingleValue()

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        fetchEpisodeList(anime).awaitSingleValue()

    suspend fun getSeasonList(anime: SAnime): List<SAnime> =
        throw UnsupportedOperationException("This source does not expose seasons")

    suspend fun getVideoList(episode: SEpisode): List<Video> =
        fetchVideoList(episode).awaitSingleValue()

    suspend fun getHosterList(episode: SEpisode): List<Hoster> =
        listOf(Hoster(hosterName = Hoster.NO_HOSTER_LIST, videoList = getVideoList(episode)))

    suspend fun getVideoList(hoster: Hoster): List<Video> =
        hoster.videoList ?: throw UnsupportedOperationException("This hoster resolves videos lazily")

    @Deprecated("Implement getAnimeDetails instead.")
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getEpisodeList instead.")
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getVideoList instead.")
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.error(UnsupportedOperationException("Not implemented"))
}
