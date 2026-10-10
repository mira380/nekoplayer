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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.max

data class CaptureSettings(
    val prePaddingMs: Long = 900L,
    val postPaddingMs: Long = 900L,
    val screenshotDelayMs: Long = 0L,
    val screenshotFormat: String = "webp",
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val trimBlackBars: Boolean = false,
)

data class CapturedMedia(
    val audioFile: File,
    val screenshotFile: File,
)

// Copies audio samples without decoding, so memory use doesn't grow with clip length.
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
        val audioFile = File(outputDirectory, "$id-sentence-audio.m4a")
        val imageFile = File(outputDirectory, "$id-screenshot.${settings.screenshotFormat.lowercase()}")
        try {
            extractAudio(uri, positionMs, cueEndMs, settings, audioTrackIndex, audioFile)
            captureScreenshot(uri, positionMs, settings, imageFile)
            CapturedMedia(audioFile, imageFile)
        } catch (error: Throwable) {
            audioFile.delete()
            imageFile.delete()
            throw error
        }
    }

    private fun extractAudio(
        uri: Uri,
        positionMs: Long,
        cueEndMs: Long,
        settings: CaptureSettings,
        audioTrackOrdinal: Int?,
        output: File,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(context, uri, null)
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
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC,
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

    private fun captureScreenshot(uri: Uri, positionMs: Long, settings: CaptureSettings, output: File) {
        val targetMs = (positionMs + settings.screenshotDelayMs).coerceAtLeast(0L)
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            retriever.setDataSource(context, uri)
            bitmap = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                (settings.maxWidth > 0 || settings.maxHeight > 0)
            ) {
                val dimensions = requestedFrameSize(retriever, settings.maxWidth, settings.maxHeight)
                retriever.getScaledFrameAtTime(
                    targetMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    dimensions.first,
                    dimensions.second,
                )
            } else {
                retriever.getFrameAtTime(targetMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: throw IOException("A frame could not be captured at this position.")

            cropped = if (settings.trimBlackBars) cropDarkBars(bitmap!!) else bitmap!!
            scaled = scaleToBounds(cropped, settings.maxWidth, settings.maxHeight)
            output.outputStream().use { stream ->
                val format = if (settings.screenshotFormat.equals("png", ignoreCase = true)) {
                    Bitmap.CompressFormat.PNG
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                if (!scaled!!.compress(format, 92, stream)) throw IOException("The screenshot could not be encoded.")
            }
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

    private fun requestedFrameSize(retriever: MediaMetadataRetriever, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        val bounds = dimensions(sourceWidth, sourceHeight, maxWidth, maxHeight)
        return Pair(bounds.first.coerceAtLeast(2), bounds.second.coerceAtLeast(2))
    }

    private fun scaleToBounds(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (maxWidth <= 0 && maxHeight <= 0) return bitmap
        val size = dimensions(bitmap.width, bitmap.height, maxWidth, maxHeight)
        if (size.first == bitmap.width && size.second == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, size.first, size.second, true)
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
        const val INITIAL_SAMPLE_BUFFER_BYTES = 256 * 1024
        const val MAX_SAMPLE_BYTES = 64L * 1024L * 1024L
        const val DARK_CHANNEL_THRESHOLD = 14
        const val DARK_EDGE_FRACTION = 0.98
    }
}
