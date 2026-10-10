// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

/** Default [SAnime] holder returned by [SAnime.create]. */
@Suppress("PropertyName", "VariableNaming")
class SAnimeImpl : SAnime {

    override var url: String = ""

    override var title: String = ""

    override var thumbnail_url: String? = null

    override var background_url: String? = null

    override var artist: String? = null

    override var author: String? = null

    override var status: Int = SAnime.UNKNOWN

    override var description: String? = null

    override var genre: String? = null

    override var update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE

    override var fetch_type: FetchType = FetchType.Episodes

    override var season_number: Double = 0.0

    override var initialized: Boolean = false

    /** Identity is the source-relative [url]; titles are not unique. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SAnime) return false
        return url == other.url
    }

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = "SAnime(url=$url, title=$title)"
}
