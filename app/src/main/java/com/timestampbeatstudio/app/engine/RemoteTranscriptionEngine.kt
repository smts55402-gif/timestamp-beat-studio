package com.timestampbeatstudio.app.engine

import com.timestampbeatstudio.app.json.MiniJson
import com.timestampbeatstudio.core.PcmAudio
import com.timestampbeatstudio.core.TranscriptionEngine
import com.timestampbeatstudio.core.TranscriptionException
import com.timestampbeatstudio.core.TranscriptionResult
import com.timestampbeatstudio.core.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [TranscriptionEngine] that POSTs 16 kHz mono WAV audio to a remote server and
 * parses the word-level response. The server is never asked to rewrite text —
 * the returned words flow into the bucketizer untouched.
 *
 * Expected response JSON:
 * ```
 * {
 *   "language": "en",
 *   "duration": 838.7,
 *   "words": [ {"text":"hello","start":0.0,"end":0.42,"confidence":0.9} ],
 *   "inaudible": [[12,13]]        // optional, inclusive second ranges
 * }
 * ```
 * `language`, `duration`, `confidence` and `inaudible` are optional.
 */
class RemoteTranscriptionEngine(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient()
) : TranscriptionEngine {

    override val engineName: String = "remote"

    private val transcribeUrl = baseUrl.trimEnd('/') + "/transcribe"

    @Throws(TranscriptionException::class)
    override suspend fun transcribe(
        pcm: PcmAudio,
        durationSec: Double,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        if (pcm.sampleRate != 16000) {
            throw TranscriptionException("Remote engine requires 16 kHz PCM, got ${pcm.sampleRate}")
        }
        progress(0.05f)
        val wav = pcmToWav(pcm)
        progress(0.1f)

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(transcribeUrl).post(body).build()
        val call = client.newCall(request)

        // Cooperative cancellation: OkHttp has no suspend API here, so poll and cancel the call.
        val watcher: Job = launch {
            while (isActive) {
                if (isCancelled()) {
                    call.cancel()
                    break
                }
                delay(200)
            }
        }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (isCancelled() || e.message?.contains("Canceled", ignoreCase = true) == true) {
                    throw CancellationException("Transcription cancelled")
                }
                throw TranscriptionException("Transcription request failed: ${e.message}", e)
            } finally {
                watcher.cancel()
            }
            progress(0.8f)
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw TranscriptionException("Server returned HTTP ${resp.code}")
                }
                val json = resp.body?.string()
                    ?: throw TranscriptionException("Empty response from transcription server")
                progress(0.9f)
                parseTranscriptionResponse(json, durationSec).also { progress(1f) }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()

        /**
         * Pure function: parses a server JSON response into a [TranscriptionResult].
         * Dependency-free and JVM-unit-testable. Throws [TranscriptionException]
         * on malformed input (never guesses or invents words).
         */
        @Throws(TranscriptionException::class)
        fun parseTranscriptionResponse(json: String, durationSec: Double): TranscriptionResult {
            val root = try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (e: IllegalArgumentException) {
                throw TranscriptionException("Invalid JSON in transcription response: ${e.message}", e)
            } ?: throw TranscriptionException("Transcription response is not a JSON object")

            val words = (root["words"] as? List<*>)?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val text = m["text"]?.toString() ?: return@mapNotNull null
                val start = (m["start"] as? Number)?.toDouble() ?: return@mapNotNull null
                val end = (m["end"] as? Number)?.toDouble() ?: return@mapNotNull null
                Word(text, start, end, (m["confidence"] as? Number)?.toDouble())
            } ?: emptyList()

            val language = root["language"] as? String
            val duration = (root["duration"] as? Number)?.toDouble() ?: durationSec
            val inaudible = when (val inaud = root["inaudible"]) {
                is List<*> -> inaud.mapNotNull { r ->
                    when (r) {
                        is List<*> -> {
                            val a = (r.getOrNull(0) as? Number)?.toInt()
                            val b = (r.getOrNull(1) as? Number)?.toInt()
                            if (a != null && b != null) IntRange(a, b) else null
                        }
                        is Map<*, *> -> {
                            val a = (r["start"] as? Number)?.toInt()
                            val b = (r["end"] as? Number)?.toInt()
                            if (a != null && b != null) IntRange(a, b) else null
                        }
                        else -> null
                    }
                }
                else -> emptyList()
            }

            return TranscriptionResult(
                words = words.sortedBy { it.startSec },
                language = language,
                durationSec = duration,
                inaudibleRangesSec = inaudible
            )
        }

        /** Encodes 16 kHz mono float PCM as a 16-bit WAV byte array. */
        fun pcmToWav(pcm: PcmAudio): ByteArray {
            val dataSize = pcm.samples.size * 2
            val out = ByteArray(44 + dataSize)
            fun writeInt(offset: Int, value: Int) {
                out[offset] = (value and 0xFF).toByte()
                out[offset + 1] = ((value shr 8) and 0xFF).toByte()
                out[offset + 2] = ((value shr 16) and 0xFF).toByte()
                out[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun writeShort(offset: Int, value: Int) {
                out[offset] = (value and 0xFF).toByte()
                out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }
            // RIFF header
            out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
            out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
            writeInt(4, 36 + dataSize)
            out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte()
            out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
            // fmt chunk
            out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte()
            out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
            writeInt(16, 16)
            writeShort(20, 1) // PCM
            writeShort(22, 1) // mono
            writeInt(24, pcm.sampleRate)
            writeInt(28, pcm.sampleRate * 2) // byte rate
            writeShort(32, 2) // block align
            writeShort(34, 16) // bits per sample
            // data chunk
            out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte()
            out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
            writeInt(40, dataSize)
            var o = 44
            for (sample in pcm.samples) {
                val v = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                writeShort(o, v)
                o += 2
            }
            return out
        }
    }
}
