package com.timestampbeatstudio.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.timestampbeatstudio.core.PcmAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes an audio [Uri] to 16 kHz mono float PCM.
 *
 * Decoding is chunked: compressed input is processed in small output buffers and
 * resampled incrementally, so only the final [FloatArray] is materialized at the
 * end (no huge intermediate allocations). Cancellation is cooperative via
 * [isCancelled].
 */
class AudioDecoder(private val context: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
    }

    /** Actual media duration in seconds, via MediaMetadataRetriever. Returns 0.0 if unknown. */
    fun getDurationSec(uri: Uri): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (_: Exception) {
            0.0
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Decodes [uri] to [PcmAudio] (16 kHz mono float).
     * @param progress 0f..1f decode progress
     * @param isCancelled polled cooperatively; throws [CancellationException] when true
     */
    suspend fun decode(
        uri: Uri,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): PcmAudio = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track found in file")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Audio track has no MIME type")
            // NB: MediaFormat.getLong(key, default) is API 29+; minSdk is 26.
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }
            } else null

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val chunks = ArrayList<FloatArray>(256)
            var totalSamples = 0
            var outputFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var inputEos = false

            while (true) {
                if (isCancelled()) throw CancellationException("Decode cancelled")

                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                            ?: throw IOException("Decoder input buffer unavailable")
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        val format = outputFormat
                        if (outBuf != null && format != null && info.size > 0) {
                            val floats = decodeChunkToMono16k(outBuf, info, format)
                            if (floats.isNotEmpty()) {
                                chunks.add(floats)
                                totalSamples += floats.size
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (durationUs != null && durationUs > 0) {
                            progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output yet; loop again (input EOS already queued drains eventually).
                    }
                }
            }

            progress(1f)
            val samples = FloatArray(totalSamples)
            var pos = 0
            for (chunk in chunks) {
                chunk.copyInto(samples, pos)
                pos += chunk.size
            }
            chunks.clear()
            PcmAudio(samples, TARGET_SAMPLE_RATE)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Converts one decoded output buffer (PCM16 or PCM float, any channel count /
     * sample rate) to mono 16 kHz float via averaging + linear resampling.
     */
    private fun decodeChunkToMono16k(
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        format: MediaFormat
    ): FloatArray {
        // NB: MediaFormat.getInteger(key, default) is API 29+; minSdk is 26.
        val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else android.media.AudioFormat.ENCODING_PCM_16BIT
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val frameCount = when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> info.size / 4 / channels
            else -> info.size / 2 / channels // assume PCM16
        }
        if (frameCount <= 0) return FloatArray(0)

        // Downmix to mono.
        val mono = FloatArray(frameCount)
        repeat(frameCount) { f ->
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    else -> buffer.short / 32768f
                }
            }
            mono[f] = sum / channels
        }

        if (srcRate == TARGET_SAMPLE_RATE) return mono

        // Linear resample to 16 kHz.
        val outLen = ((frameCount.toLong() * TARGET_SAMPLE_RATE) / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = frameCount.toDouble() / outLen
        for (i in 0 until outLen) {
            val pos = i * ratio
            val p0 = pos.toInt().coerceAtMost(frameCount - 1)
            val p1 = (p0 + 1).coerceAtMost(frameCount - 1)
            val frac = (pos - p0).toFloat()
            out[i] = mono[p0] * (1f - frac) + mono[p1] * frac
        }
        return out
    }
}
