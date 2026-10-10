// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import rx.Observable

/**
 * A source that can be browsed and searched.
 *
 * Legacy Rx methods remain available so extensions built against older API
 * versions can bridge into the suspend methods.
 */
@Suppress("DEPRECATION")
interface AnimeCatalogueSource : AnimeSource {

    override val lang: String

    override val supportsLatest: Boolean
        get() = false

    suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchPopularAnime(page).awaitSingleValue()

    suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = fetchSearchAnime(page, query, filters).awaitSingleValue()

    suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchLatestUpdates(page).awaitSingleValue()

    @Deprecated("Implement getPopularAnime instead.")
    fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getSearchAnime instead.")
    fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> = Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getLatestUpdates instead.")
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.error(UnsupportedOperationException("Not implemented"))
}
