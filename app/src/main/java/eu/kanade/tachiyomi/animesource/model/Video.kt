// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers

/** A subtitle or audio track attached to a [Video]. */
data class Track(val url: String, val lang: String)

/** Video chapter marker types supported by extensions-lib 16. */
enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

/** A chapter marker in a video stream. */
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

/**
 * A playable stream returned by an extension.
 *
 * The primary constructor matches the current Aniyomi model. Hidden legacy
 * overloads below keep API 12–16 extension bytecode linkable, including the
 * older page-url constructor and the API 16 constructor/copy shape.
 */
data class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
) {
    /** Compatibility alias used by extensions-lib versions before 16. */
    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String
        get() = videoTitle

    /** Compatibility alias for the page URL used by the old constructor. */
    val url: String
        get() = videoPageUrl

    private var videoPageUrl: String = ""

    /** Legacy shape retained for extensions compiled before API 16. */
    @Deprecated("Use the primary constructor.")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoUrl = videoUrl ?: "null",
        videoTitle = quality,
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        this.videoPageUrl = url
    }

    /** Legacy constructor that also carried a Uri. */
    @Deprecated("The uri parameter is ignored; use the primary constructor.")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        @Suppress("UNUSED_PARAMETER") uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    /** Binary compatibility for extensions compiled against extensions-lib 16. */
    @Deprecated("Used only for compatibility with ext lib 16", level = DeprecationLevel.HIDDEN)
    constructor(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        internalData: String = "",
        initialized: Boolean = false,
    ) : this(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        internalData = internalData,
        initialized = initialized,
    )

    /** Binary compatibility for the API 16 generated data-class copy method. */
    @Deprecated("Used only for compatibility with ext lib 16", level = DeprecationLevel.HIDDEN)
    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
    ): Video = Video(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        internalData = internalData,
        initialized = initialized,
    )

    @Transient
    @Volatile
    var status: State = State.QUEUE

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }

    /** Numeric height parsed out of the title, or zero when unknown. */
    val resolutionOrZero: Int
        get() = resolution ?: RESOLUTION.find(videoTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    companion object {
        const val MPV_ARGS_TAG = "ANIYOMI_MPV_ARGS"

        private val RESOLUTION = Regex("""(\d{3,4})\s*[pP]""")
    }
}
