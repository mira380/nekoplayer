package com.nekoplayer.app.mining

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

class PlaybackAudioCapture {
    private class Chunk(
        val startUs: Long,
        val endUs: Long,
        val sampleRate: Int,
        val bytes: ByteArray,
    )

    private val chunks = ArrayDeque<Chunk>()
    private var sampleRate = 0
    private var channelCount = 0
    private var durationRemainder = 0L

    @Synchronized
    internal fun append(
        input: ByteBuffer,
        nextPositionMs: Long,
        inputSampleRate: Int,
        inputChannelCount: Int,
        encoding: Int,
    ) {
        if (inputSampleRate <= 0 || inputChannelCount <= 0) return
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frameBytes = inputChannelCount * BYTES_PER_SAMPLE
                val byteCount = input.remaining() / frameBytes * frameBytes
                if (byteCount == 0) return
                ByteArray(byteCount).also { input.get(it) }
            }
            C.ENCODING_PCM_FLOAT -> input.floatPcmToShortBytes()
            else -> return
        }
        val frameBytes = inputChannelCount * BYTES_PER_SAMPLE
        val byteCount = bytes.size / frameBytes * frameBytes
        if (byteCount == 0) return
        if (sampleRate != inputSampleRate || channelCount != inputChannelCount) {
            chunks.clear()
            sampleRate = inputSampleRate
            channelCount = inputChannelCount
            durationRemainder = 0L
        }

        val alignedBytes = if (byteCount == bytes.size) bytes else bytes.copyOf(byteCount)
        val previous = chunks.lastOrNull()
        val playbackPositionUs = nextPositionMs.coerceAtLeast(0L) * MICROS_PER_MS
        if (
            previous != null &&
            abs(playbackPositionUs - previous.endUs) > SEEK_RESET_THRESHOLD_US
        ) {
            chunks.clear()
            durationRemainder = 0L
        }
        val startUs = chunks.lastOrNull()?.endUs ?: playbackPositionUs
        val frames = byteCount / frameBytes
        val durationNumerator = frames.toLong() * MICROS_PER_SECOND + durationRemainder
        val durationUs = (durationNumerator / inputSampleRate).coerceAtLeast(1L)
        durationRemainder = durationNumerator % inputSampleRate
        chunks.addLast(
            Chunk(
                startUs = startUs,
                endUs = startUs + durationUs,
                sampleRate = inputSampleRate,
                bytes = alignedBytes,
            ),
        )
        val oldestAllowedUs = chunks.last().endUs - HISTORY_US
        while (chunks.firstOrNull()?.endUs?.let { it < oldestAllowedUs } == true) {
            chunks.removeFirst()
        }
    }

    @Synchronized
    fun hasSamplesThrough(endMs: Long): Boolean =
        chunks.lastOrNull()?.endUs?.let { it >= endMs * MICROS_PER_MS } == true

    @Synchronized
    fun hasSamplesFrom(startMs: Long): Boolean =
        chunks.firstOrNull()?.startUs?.let { it <= startMs * MICROS_PER_MS } == true

    @Synchronized
    fun hasSamplesCovering(startMs: Long, endMs: Long): Boolean =
        hasSamplesFrom(startMs) && hasSamplesThrough(endMs)

    fun writeClip(
        cueStartMs: Long,
        cueEndMs: Long,
        settings: CaptureSettings,
        output: File,
    ): Boolean {
        if (cueEndMs < cueStartMs) return false
        val snapshot: List<Chunk>
        val formatRate: Int
        val formatChannels: Int
        synchronized(this) {
            if (chunks.isEmpty()) return false
            snapshot = chunks.toList()
            formatRate = sampleRate
            formatChannels = channelCount
        }
        val requestedStartMs = (cueStartMs - settings.prePaddingMs).coerceAtLeast(0L)
        val requestedEndMs = minOf(
            cueEndMs + settings.postPaddingMs,
            settings.mediaDurationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE,
        ).coerceAtLeast(requestedStartMs + 1L)
        val startUs = requestedStartMs * MICROS_PER_MS
        val endUs = requestedEndMs * MICROS_PER_MS
        if (snapshot.first().startUs > startUs || snapshot.last().endUs < endUs) return false

        val bytesPerFrame = formatChannels * BYTES_PER_SAMPLE
        val parts = snapshot.mapNotNull { chunk ->
            if (chunk.endUs <= startUs || chunk.startUs >= endUs) return@mapNotNull null
            val frameCount = chunk.bytes.size / bytesPerFrame
            val clippedStartUs = maxOf(startUs, chunk.startUs)
            val clippedEndUs = minOf(endUs, chunk.endUs)
            val firstFrame = if (clippedStartUs <= chunk.startUs) {
                0
            } else {
                floor(
                    (clippedStartUs - chunk.startUs) * chunk.sampleRate / MICROS_PER_SECOND.toDouble(),
                ).toInt().coerceIn(0, frameCount)
            }
            val lastFrame = if (clippedEndUs >= chunk.endUs) {
                frameCount
            } else {
                ceil(
                    (clippedEndUs - chunk.startUs) * chunk.sampleRate / MICROS_PER_SECOND.toDouble(),
                ).toInt().coerceIn(firstFrame, frameCount)
            }
            if (lastFrame <= firstFrame) return@mapNotNull null
            val offset = firstFrame * bytesPerFrame
            val length = (lastFrame - firstFrame) * bytesPerFrame
            chunk.bytes to (offset until offset + length)
        }
        val dataSize = parts.sumOf { (_, range) -> range.last - range.first + 1 }
        if (dataSize <= 0 || dataSize > MAX_WAVE_DATA_BYTES) return false

        return try {
            output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create the temporary media directory.") }
            FileOutputStream(output).use { stream ->
                stream.writeWaveHeader(dataSize, formatRate, formatChannels)
                parts.forEach { (bytes, range) -> stream.write(bytes, range.first, range.last - range.first + 1) }
            }
            true
        } catch (_: IOException) {
            output.delete()
            false
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    class Processor(
        private val capture: PlaybackAudioCapture,
        private val positionMs: () -> Long,
    ) : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
            if (
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            ) {
                inputAudioFormat
            } else {
                AudioProcessor.AudioFormat.NOT_SET
            }

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            capture.append(
                input = inputBuffer.duplicate(),
                nextPositionMs = positionMs(),
                inputSampleRate = inputAudioFormat.sampleRate,
                inputChannelCount = inputAudioFormat.channelCount,
                encoding = inputAudioFormat.encoding,
            )
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
        }
    }

    private fun ByteBuffer.floatPcmToShortBytes(): ByteArray {
        val source = duplicate().order(ByteOrder.nativeOrder())
        val sampleCount = source.remaining() / Float.SIZE_BYTES
        val result = ByteArray(sampleCount * BYTES_PER_SAMPLE)
        repeat(sampleCount) { index ->
            val sample = (source.float.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .roundToLong()
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[index * BYTES_PER_SAMPLE] = sample.toByte()
            result[index * BYTES_PER_SAMPLE + 1] = (sample shr 8).toByte()
        }
        return result
    }

    private fun FileOutputStream.writeWaveHeader(dataSize: Int, sampleRate: Int, channels: Int) {
        val bytesPerSecond = sampleRate.toLong() * channels * BYTES_PER_SAMPLE
        writeAscii("RIFF")
        writeLe(36L + dataSize, 4)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLe(16, 4)
        writeLe(1, 2)
        writeLe(channels.toLong(), 2)
        writeLe(sampleRate.toLong(), 4)
        writeLe(bytesPerSecond, 4)
        writeLe((channels * BYTES_PER_SAMPLE).toLong(), 2)
        writeLe((BYTES_PER_SAMPLE * 8).toLong(), 2)
        writeAscii("data")
        writeLe(dataSize.toLong(), 4)
    }

    private fun FileOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun FileOutputStream.writeLe(value: Long, bytes: Int) {
        repeat(bytes) { shift ->
            write(((value ushr (shift * 8)) and 0xff).toInt())
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MICROS_PER_MS = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val HISTORY_US = 30_000_000L
        const val SEEK_RESET_THRESHOLD_US = 2_000_000L
        const val MAX_WAVE_DATA_BYTES = 128 * 1024 * 1024
    }
}
