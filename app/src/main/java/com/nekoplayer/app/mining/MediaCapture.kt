package com.nekoplayer.app.mining

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.scale
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

data class CaptureSettings(
    val prePaddingMs: Long = 900L,
    val postPaddingMs: Long = 5_000L,
    val screenshotDelayMs: Long = 0L,
    val screenshotFormat: String = "webp",
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val trimBlackBars: Boolean = false,
    val mediaDurationMs: Long? = null,
)

data class CapturedMedia(
    val audioFile: File,
    val screenshotFile: File,
)

// Copies audio samples without decoding so memory use doesn't grow with clip length.
// The selected audio codec must be supported by Android's MP4 muxer; AAC is common.
class MediaCapture(private val context: Context) {
    // Audio spans the cue plus padding; capture the frame at cue start + screenshotDelayMs.
    // audioTrackIndex is the zero-based audio-track position, not the container's track number.
    suspend fun capture(
        uri: Uri,
        positionMs: Long,
        settings: CaptureSettings,
        cueEndMs: Long = positionMs,
        audioTrackIndex: Int? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        playbackAudioCapture: PlaybackAudioCapture? = null,
        renderedFrameProvider: (suspend () -> Bitmap?)? = null,
        preferPlaybackCapture: Boolean = false,
    ): CapturedMedia = withContext(Dispatchers.IO) {
        require(positionMs >= 0L) { "Capture position cannot be negative." }
        require(cueEndMs >= positionMs) { "Cue end cannot precede its start." }
        require(settings.prePaddingMs >= 0L && settings.postPaddingMs >= 0L) {
            "Audio padding cannot be negative."
        }
        require(settings.screenshotFormat.lowercase() in setOf("png", "webp")) {
            "Screenshot format must be PNG or WebP."
        }
        val outputDirectory = File(context.cacheDir, "mining")
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IOException("Could not create the temporary media directory.")
        }
        val id = "mine-${UUID.randomUUID()}"
        val extractedAudioFile = File(outputDirectory, "$id-sentence-audio.m4a")
        val playbackAudioFile = File(outputDirectory, "$id-sentence-audio.wav")
        val imageFile = File(outputDirectory, "$id-screenshot.${settings.screenshotFormat.lowercase()}")
        var audioFile: File? = null
        try {
            val playbackCapture = playbackAudioCapture
            val captureStartMs = (positionMs - settings.prePaddingMs).coerceAtLeast(0L)
            val captureEndMs = minOf(
                cueEndMs + settings.postPaddingMs,
                settings.mediaDurationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE,
            ).coerceAtLeast(captureStartMs + 1L)
            val existingAudioWritten = playbackCapture?.let { capture ->
                capture.hasSamplesCovering(captureStartMs, captureEndMs) &&
                    capture.writeClip(positionMs, cueEndMs, settings, playbackAudioFile)
            } == true
            if (existingAudioWritten) {
                audioFile = playbackAudioFile
            } else if (
                preferPlaybackCapture &&
                capturePlaybackAudio(
                    uri = uri,
                    cueStartMs = positionMs,
                    cueEndMs = cueEndMs,
                    settings = settings,
                    requestHeaders = requestHeaders,
                    output = playbackAudioFile,
                )
            ) {
                audioFile = playbackAudioFile
            }

            captureScreenshot(
                uri = uri,
                positionMs = positionMs,
                settings = settings,
                output = imageFile,
                requestHeaders = requestHeaders,
                renderedFrameProvider = renderedFrameProvider,
                preferPlaybackFrame = preferPlaybackCapture,
            )

            if (audioFile == null) {
                if (preferPlaybackCapture) {
                    throw IOException("Could not capture the complete audio clip from this source.")
                }
                extractAudio(uri, positionMs, cueEndMs, settings, audioTrackIndex, requestHeaders, extractedAudioFile)
                audioFile = extractedAudioFile
            }
            CapturedMedia(audioFile, imageFile)
        } catch (error: Throwable) {
            extractedAudioFile.delete()
            playbackAudioFile.delete()
            imageFile.delete()
            throw error
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private suspend fun capturePlaybackAudio(
        uri: Uri,
        cueStartMs: Long,
        cueEndMs: Long,
        settings: CaptureSettings,
        requestHeaders: Map<String, String>,
        output: File,
    ): Boolean {
        val startMs = (cueStartMs - settings.prePaddingMs).coerceAtLeast(0L)
        val endMs = minOf(
            cueEndMs + settings.postPaddingMs,
            settings.mediaDurationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE,
        ).coerceAtLeast(startMs + 1L)
        val capture = PlaybackAudioCapture()
        val playbackPositionMs = AtomicLong(startMs)
        val audioProcessor = PlaybackAudioCapture.Processor(capture) { playbackPositionMs.get() }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(audioProcessor))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val player = withContext(Dispatchers.Main.immediate) {
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(uri))
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
                    prepare()
                    seekTo(startMs)
                    playWhenReady = true
                }
        }
        val captureFailed = AtomicBoolean(false)
        val positionMonitor = CoroutineScope(
            currentCoroutineContext() + Dispatchers.Main.immediate,
        ).launch {
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    captureFailed.set(true)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) captureFailed.set(true)
                }
            })
            while (isActive) {
                playbackPositionMs.set(player.currentPosition.coerceAtLeast(0L))
                delay(25.milliseconds)
            }
        }
        try {
            val timeoutMs = ((endMs - startMs).coerceAtLeast(1L) * 3L + 15_000L)
                .coerceAtMost(MAX_BACKGROUND_CAPTURE_MS)
            val captured = withTimeoutOrNull(timeoutMs) {
                while (!capture.hasSamplesCovering(startMs, endMs)) {
                    if (captureFailed.get()) return@withTimeoutOrNull false
                    delay(50.milliseconds)
                }
                true
            } == true
            return captured && capture.writeClip(cueStartMs, cueEndMs, settings, output)
        } finally {
            positionMonitor.cancel()
            withContext(Dispatchers.Main.immediate) { player.release() }
        }
    }

    private fun extractAudio(
        uri: Uri,
        positionMs: Long,
        cueEndMs: Long,
        settings: CaptureSettings,
        audioTrackOrdinal: Int?,
        requestHeaders: Map<String, String>,
        output: File,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(context, uri, requestHeaders)
            val audioTracks = (0 until extractor.trackCount).filter { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            if (audioTracks.isEmpty()) throw IOException("This video has no audio track.")
            val ordinal = audioTrackOrdinal ?: 0
            if (ordinal !in audioTracks.indices) throw IOException("The selected audio track is unavailable.")
            val sourceTrack = audioTracks[ordinal]
            val format = extractor.getTrackFormat(sourceTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == "audio/raw") {
                throw IOException("Uncompressed audio cannot be copied to an M4A clip.")
            }

            val startMs = (positionMs - settings.prePaddingMs).coerceAtLeast(0L)
            val endMs = max(startMs + 1L, cueEndMs + settings.postPaddingMs)
            val startUs = startMs * 1_000L
            val endUs = endMs * 1_000L
            extractor.selectTrack(sourceTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = try {
                muxer.addTrack(format)
            } catch (error: RuntimeException) {
                throw IOException(
                    "This Android device cannot save the selected audio codec in an M4A clip.",
                    error,
                )
            }
            muxer.start()
            muxerStarted = true

            var firstPresentationTimeUs = -1L
            var wroteSample = false
            val hintedBufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(
                    INITIAL_SAMPLE_BUFFER_BYTES,
                    MAX_SAMPLE_BYTES.toInt(),
                )
            } else {
                INITIAL_SAMPLE_BUFFER_BYTES
            }
            var buffer = ByteBuffer.allocateDirect(hintedBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L || sampleTimeUs >= endUs) break
                if (sampleTimeUs < startUs) {
                    if (!extractor.advance()) break
                    continue
                }

                // API 28 added getSampleSize(); older Android versions use the track's KEY_MAX_INPUT_SIZE hint.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val sampleSize = extractor.sampleSize
                    if (sampleSize <= 0L) {
                        if (!extractor.advance()) break
                        continue
                    }
                    if (sampleSize > MAX_SAMPLE_BYTES) {
                        throw IOException("An audio sample is too large to capture safely.")
                    }
                    if (sampleSize > buffer.capacity()) {
                        buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                    }
                }
                buffer.clear()
                val bytesRead = extractor.readSampleData(buffer, 0)
                if (bytesRead < 0) break

                if (firstPresentationTimeUs < 0L) firstPresentationTimeUs = sampleTimeUs
                bufferInfo.set(
                    0,
                    bytesRead,
                    (sampleTimeUs - firstPresentationTimeUs).coerceAtLeast(0L),
                    if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    },
                )
                buffer.position(0)
                buffer.limit(bytesRead)
                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                wroteSample = true
                if (!extractor.advance()) break
            }
            if (!wroteSample) throw IOException("No audio samples were found in the selected clip range.")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is IOException) throw error
            throw IOException("Audio could not be extracted from this video.", error)
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: RuntimeException) {
                    // Keep the capture error; the caller deletes any partial output.
                }
            }
            muxer?.release()
            extractor.release()
        }
    }

    private suspend fun captureScreenshot(
        uri: Uri,
        positionMs: Long,
        settings: CaptureSettings,
        output: File,
        requestHeaders: Map<String, String>,
        renderedFrameProvider: (suspend () -> Bitmap?)?,
        preferPlaybackFrame: Boolean,
    ) {
        if (preferPlaybackFrame) {
            val frame = renderedFrameProvider?.invoke()
                ?: throw IOException("A video frame could not be captured from playback.")
            saveRenderedFrame(frame, settings, output)
            return
        }
        val targetMs = (positionMs + settings.screenshotDelayMs).coerceAtLeast(0L)
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var fallbackUsed = false
        var expectedSize: Pair<Int, Int>? = null
        var primarySize: Pair<Int, Int>? = null
        var decodedSize: Pair<Int, Int>? = null
        try {
            if (requestHeaders.isEmpty()) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(uri.toString(), requestHeaders)
            }
            val sourceSize = sourceDimensions(retriever)
            expectedSize = sourceSize?.let { (width, height) ->
                dimensions(width, height, settings.maxWidth, settings.maxHeight)
            }
            val primaryFrame = retriever.getFrameAtTime(
                targetMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: throw IOException("A frame could not be captured at this position.")
            var decodedFrame = primaryFrame
            primarySize = primaryFrame.width to primaryFrame.height
            decodedSize = primarySize
            val requestedSize = expectedSize
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                requestedSize != null && isMateriallySmaller(primaryFrame, requestedSize)
            ) {
                val retryFrame = try {
                    retriever.getScaledFrameAtTime(
                        targetMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        requestedSize.first,
                        requestedSize.second,
                    )
                } catch (_: RuntimeException) {
                    null
                }
                if (retryFrame != null && pixelCount(retryFrame) > pixelCount(primaryFrame)) {
                    decodedFrame = retryFrame
                    decodedSize = retryFrame.width to retryFrame.height
                    fallbackUsed = true
                    primaryFrame.recycle()
                } else {
                    retryFrame?.recycle()
                }
            }
            bitmap = decodedFrame

            cropped = if (settings.trimBlackBars) cropDarkBars(decodedFrame) else decodedFrame
            val outputBitmap = scaleToBounds(cropped, settings.maxWidth, settings.maxHeight)
            scaled = outputBitmap
            output.outputStream().use { stream ->
                val format = if (settings.screenshotFormat.equals("png", ignoreCase = true)) {
                    Bitmap.CompressFormat.PNG
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                if (!outputBitmap.compress(format, 100, stream)) throw IOException("The screenshot could not be encoded.")
            }
            Log.d(
                TAG,
                "Mining image source=${sourceSize?.asSizeLabel() ?: "unknown"} " +
                    "expected=${expectedSize?.asSizeLabel() ?: "unknown"} " +
                    "primary=${primarySize.asSizeLabel()} " +
                    "decoder=${decodedSize.asSizeLabel()} " +
                    "usedFallback=$fallbackUsed output=${outputBitmap.width}x${outputBitmap.height} " +
                    "bytes=${output.length()} format=${settings.screenshotFormat}",
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is IOException) throw error
            throw IOException("A screenshot could not be captured from this video.", error)
        } finally {
            if (scaled != null && scaled !== cropped && scaled !== bitmap) scaled.recycle()
            if (cropped != null && cropped !== bitmap) cropped.recycle()
            bitmap?.recycle()
            retriever.release()
        }
    }

    private fun saveRenderedFrame(frame: Bitmap, settings: CaptureSettings, output: File) {
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            cropped = if (settings.trimBlackBars) cropDarkBars(frame) else frame
            val outputBitmap = scaleToBounds(cropped, settings.maxWidth, settings.maxHeight)
            scaled = outputBitmap
            output.outputStream().use { stream ->
                val format = if (settings.screenshotFormat.equals("png", ignoreCase = true)) {
                    Bitmap.CompressFormat.PNG
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                if (!outputBitmap.compress(format, 100, stream)) {
                    throw IOException("The screenshot could not be encoded.")
                }
            }
            Log.d(
                TAG,
                "Mining playback frame output=${outputBitmap.width}x${outputBitmap.height} " +
                    "bytes=${output.length()} format=${settings.screenshotFormat}",
            )
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("A screenshot could not be captured from playback.", error)
        } finally {
            if (scaled != null && scaled !== cropped && scaled !== frame) scaled.recycle()
            if (cropped != null && cropped !== frame) cropped.recycle()
            frame.recycle()
        }
    }

    private fun sourceDimensions(retriever: MediaMetadataRetriever): Pair<Int, Int>? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        return if (rotation == 90 || rotation == 270) height to width else width to height
    }

    private fun isMateriallySmaller(bitmap: Bitmap, expectedSize: Pair<Int, Int>): Boolean {
        val expectedPixels = expectedSize.first.toLong() * expectedSize.second
        val actualPixels = pixelCount(bitmap)
        return actualPixels < expectedPixels / 2L &&
            (bitmap.width < expectedSize.first * 3 / 4 || bitmap.height < expectedSize.second * 3 / 4)
    }

    private fun pixelCount(bitmap: Bitmap): Long = bitmap.width.toLong() * bitmap.height

    private fun Pair<Int, Int>.asSizeLabel(): String = "${first}x$second"

    private fun scaleToBounds(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (maxWidth <= 0 && maxHeight <= 0) return bitmap
        val size = dimensions(bitmap.width, bitmap.height, maxWidth, maxHeight)
        if (size.first == bitmap.width && size.second == bitmap.height) return bitmap
        return bitmap.scale(size.first, size.second, filter = true)
    }

    private fun dimensions(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val widthScale = if (maxWidth > 0) maxWidth.toDouble() / width else Double.POSITIVE_INFINITY
        val heightScale = if (maxHeight > 0) maxHeight.toDouble() / height else Double.POSITIVE_INFINITY
        val scale = minOf(1.0, widthScale, heightScale)
        return Pair((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    // Crop only when the dark borders are clear and wide enough to be actual bars.
    private fun cropDarkBars(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 16 || height < 16) return source
        val row = IntArray(width)
        val column = IntArray(height)
        fun darkRow(y: Int): Boolean {
            source.getPixels(row, 0, width, 0, y, width, 1)
            var dark = 0
            for (pixel in row) if (isDark(pixel)) dark++
            return dark >= width * DARK_EDGE_FRACTION
        }
        fun darkColumn(x: Int): Boolean {
            source.getPixels(column, 0, 1, x, 0, 1, height)
            var dark = 0
            for (pixel in column) if (isDark(pixel)) dark++
            return dark >= height * DARK_EDGE_FRACTION
        }
        val minEdge = max(2, minOf(width, height) / 100)
        var top = 0
        var bottom = height
        var left = 0
        var right = width
        while (top < height / 3 && darkRow(top)) top++
        while (bottom > height * 2 / 3 && darkRow(bottom - 1)) bottom--
        while (left < width / 3 && darkColumn(left)) left++
        while (right > width * 2 / 3 && darkColumn(right - 1)) right--
        if (top < minEdge) top = 0
        if (height - bottom < minEdge) bottom = height
        if (left < minEdge) left = 0
        if (width - right < minEdge) right = width
        if (right - left < width / 2 || bottom - top < height / 2) return source
        if (left == 0 && top == 0 && right == width && bottom == height) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun isDark(color: Int): Boolean =
        ((color shr 16) and 0xff) <= DARK_CHANNEL_THRESHOLD &&
            ((color shr 8) and 0xff) <= DARK_CHANNEL_THRESHOLD &&
            (color and 0xff) <= DARK_CHANNEL_THRESHOLD

    private companion object {
        const val TAG = "MediaCapture"
        const val INITIAL_SAMPLE_BUFFER_BYTES = 256 * 1024
        const val MAX_SAMPLE_BYTES = 64L * 1024L * 1024L
        const val MAX_BACKGROUND_CAPTURE_MS = 120_000L
        const val DARK_CHANNEL_THRESHOLD = 14
        const val DARK_EDGE_FRACTION = 0.98
    }
}
