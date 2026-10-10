// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

/**
 * Anime metadata as produced by a video-source extension.
 *
 * These member names and JVM signatures mirror the Aniyomi extension API.
 * In particular, the snake_case names are part of the ABI.
 */
@Suppress("PropertyName", "VariableNaming")
interface SAnime : java.io.Serializable {

    var url: String

    var title: String

    var thumbnail_url: String?

    var background_url: String?

    var artist: String?

    var author: String?

    var status: Int

    var description: String?

    /** Comma-separated genre names. */
    var genre: String?

    var update_strategy: AnimeUpdateStrategy

    var fetch_type: FetchType

    var season_number: Double

    /** True once details have been fetched, so the host can avoid refetching. */
    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()
    }

    fun copy(): SAnime = create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
        it.background_url = background_url
        it.artist = artist
        it.author = author
        it.status = status
        it.description = description
        it.genre = genre
        it.update_strategy = update_strategy
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.initialized = initialized
    }

    fun copyFrom(other: SAnime) {
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        if (other.background_url != null) background_url = other.background_url
        update_strategy = other.update_strategy
        fetch_type = other.fetch_type
        season_number = other.season_number
        status = other.status
        if (!initialized) initialized = other.initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6
        const val UPCOMING = 7

        fun create(): SAnime = SAnimeImpl()
    }
}
