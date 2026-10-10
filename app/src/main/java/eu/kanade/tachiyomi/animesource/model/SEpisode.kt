// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

/**
 * A single episode entry produced by an extension.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI and
 * why the snake_case member names are required.
 */
@Suppress("PropertyName", "VariableNaming")
interface SEpisode : java.io.Serializable {

    var url: String

    var name: String

    var date_upload: Long

    /** 1-based where known, `-1f` when the extension cannot determine it. */
    var episode_number: Float

    var scanlator: String?

    var fillermark: Boolean

    var summary: String?

    var preview_url: String?

    var memo: kotlinx.serialization.json.JsonObject

    fun copyFrom(other: SEpisode) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        episode_number = other.episode_number
        fillermark = other.fillermark
        scanlator = other.scanlator
        summary = other.summary
        preview_url = other.preview_url
        memo = other.memo
    }

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}
