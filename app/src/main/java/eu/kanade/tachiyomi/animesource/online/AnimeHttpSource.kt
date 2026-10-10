// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.awaitSingleValue
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

/**
 * Base class for HTTP-backed video extensions.
 *
 * Request/parse hooks intentionally remain open with throwing defaults, as in
 * Aniyomi's API. Extensions may implement the older helper hooks, the suspend
 * API directly, or a mixture. Making the legacy hooks abstract prevents newer
 * concrete subclasses from loading when they implement only suspend methods.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate", "DEPRECATION")
abstract class AnimeHttpSource : AnimeCatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    /** Stable source identifier shared with Aniyomi extensions. */
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    // ------------------------------------------------------------- catalogue

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchPopularAnime(page).awaitSingleValue()

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        client.newCall(popularAnimeRequest(page)).asObservableSuccess().map { response ->
            response.use(::popularAnimeParse)
        }

    protected open fun popularAnimeRequest(page: Int): Request =
        throw UnsupportedOperationException("Not implemented")

    protected open fun popularAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("Not implemented")

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchLatestUpdates(page).awaitSingleValue()

    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        client.newCall(latestUpdatesRequest(page)).asObservableSuccess().map { response ->
            response.use(::latestUpdatesParse)
        }

    protected open fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Not implemented")

    protected open fun latestUpdatesParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("Not implemented")

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = fetchSearchAnime(page, query, filters).awaitSingleValue()

    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> =
        client.newCall(searchAnimeRequest(page, query, filters)).asObservableSuccess().map { response ->
            response.use(::searchAnimeParse)
        }

    protected open fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = throw UnsupportedOperationException("Not implemented")

    protected open fun searchAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("Not implemented")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // --------------------------------------------------------------- details

    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        fetchAnimeDetails(anime).awaitSingleValue()

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        client.newCall(animeDetailsRequest(anime)).asObservableSuccess().map { response ->
            response.use { animeDetailsParse(it).apply { initialized = true } }
        }

    open fun animeDetailsRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected open fun animeDetailsParse(response: Response): SAnime =
        throw UnsupportedOperationException("Not implemented")

    // -------------------------------------------------------------- episodes

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        fetchEpisodeList(anime).awaitSingleValue()

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        client.newCall(episodeListRequest(anime)).asObservableSuccess().map { response ->
            response.use(::episodeListParse)
        }

    protected open fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected open fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("Not implemented")

    protected open fun episodeVideoParse(response: Response): SEpisode =
        throw UnsupportedOperationException("Not implemented")

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val response = client.newCall(seasonListRequest(anime)).awaitSuccess()
        return response.use(::seasonListParse)
    }

    protected open fun seasonListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected open fun seasonListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("Not implemented")

    // --------------------------------------------------------------- hosters

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(hosterListRequest(episode)).awaitSuccess()
        return response.use(::hosterListParse)
    }

    protected open fun hosterListRequest(episode: SEpisode): Request =
        GET(baseUrl + episode.url, headers)

    protected open fun hosterListParse(response: Response): List<Hoster> =
        throw UnsupportedOperationException("Not implemented")

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val response = client.newCall(videoListRequest(hoster)).awaitSuccess()
        return response.use { videoListParse(it, hoster) }
    }

    protected open fun videoListRequest(hoster: Hoster): Request =
        GET(hoster.hosterUrl, headers)

    protected open fun videoListParse(
        response: Response,
        hoster: Hoster,
    ): List<Video> = throw UnsupportedOperationException("Not implemented")

    // --------------------------------------------------------------- videos

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        fetchVideoList(episode).awaitSingleValue()

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        client.newCall(videoListRequest(episode)).asObservableSuccess().map { response ->
            response.use(::videoListParse)
        }

    protected open fun videoListRequest(episode: SEpisode): Request =
        GET(baseUrl + episode.url, headers)

    protected open fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException("Not implemented")

    open suspend fun resolveVideo(video: Video): Video? = video

    open fun List<Hoster>.sortHosters(): List<Hoster> = this

    open fun List<Video>.sortVideos(): List<Video> = sort()

    protected open fun List<Video>.sort(): List<Video> = this

    open suspend fun getVideoUrl(video: Video): String =
        fetchVideoUrl(video).awaitSingleValue()

    open fun fetchVideoUrl(video: Video): Observable<String> =
        client.newCall(videoUrlRequest(video)).asObservableSuccess().map { response ->
            response.use(::videoUrlParse)
        }

    protected open fun videoUrlRequest(video: Video): Request =
        GET(video.url, headers)

    protected open fun videoUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not implemented")

    // -------------------------------------------------------- related anime

    open val supportsRelatedAnimes: Boolean get() = false

    open val disableRelatedAnimes: Boolean get() = false

    open val disableRelatedAnimesBySearch: Boolean get() = false

    open suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val response = client.newCall(relatedAnimeListRequest(anime)).awaitSuccess()
        return response.use(::relatedAnimeListParse)
    }

    protected open fun relatedAnimeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected open fun relatedAnimeListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("Not implemented")

    // -------------------------------------------------------------- helpers

    fun setUrlWithoutDomain(anime: SAnime, url: String) {
        anime.url = getUrlWithoutDomain(url)
    }

    fun setUrlWithoutDomain(episode: SEpisode, url: String) {
        episode.url = getUrlWithoutDomain(url)
    }

    protected fun getUrlWithoutDomain(orig: String): String = try {
        val uri = java.net.URI(orig.replace(" ", "%20"))
        buildString {
            append(uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    } catch (_: Exception) {
        orig
    }

    open fun getAnimeUrl(anime: SAnime): String =
        animeDetailsRequest(anime).url.toString()

    open fun getEpisodeUrl(episode: SEpisode): String = episode.url

    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) = Unit
}
