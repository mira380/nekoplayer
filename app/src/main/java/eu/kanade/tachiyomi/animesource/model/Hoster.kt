// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject

/** API 16 group of streams, usually representing a provider or server. */
open class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
) {
    @Volatile
    @Transient
    var status: State = State.IDLE

    /** Binary constructor used by API 16 extensions. */
    @Deprecated("Used only for compatibility with ext lib 16", level = DeprecationLevel.HIDDEN)
    constructor(
        hosterUrl: String = "",
        hosterName: String = "",
        videoList: List<Video>? = null,
        internalData: String = "",
        lazy: Boolean = false,
    ) : this(hosterUrl, hosterName, videoList, internalData, lazy, JsonObject(emptyMap()))

    fun copy(
        hosterUrl: String = this.hosterUrl,
        hosterName: String = this.hosterName,
        videoList: List<Video>? = this.videoList,
        internalData: String = this.internalData,
        lazy: Boolean = this.lazy,
        memo: JsonObject = this.memo,
    ): Hoster = Hoster(hosterUrl, hosterName, videoList, internalData, lazy, memo)

    /** Binary compatibility for the API 16 generated copy method. */
    @Deprecated("Used only for compatibility with ext lib 16", level = DeprecationLevel.HIDDEN)
    fun copy(
        hosterUrl: String = this.hosterUrl,
        hosterName: String = this.hosterName,
        videoList: List<Video>? = this.videoList,
        internalData: String = this.internalData,
        lazy: Boolean = this.lazy,
    ): Hoster = Hoster(hosterUrl, hosterName, videoList, internalData, lazy, JsonObject(emptyMap()))

    enum class State {
        IDLE,
        LOADING,
        READY,
        ERROR,
    }

    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> =
            listOf(Hoster(hosterName = NO_HOSTER_LIST, videoList = this))
    }
}
