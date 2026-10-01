package com.timestampbeatstudio.whisper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads ggml whisper models from the official whisper.cpp HuggingFace
 * repository into the app's private files dir.
 *
 * Models are English-only (`.en`), matching the engine's fixed `"en"` language:
 * - `tiny.en` (~75 MB) — fastest, lowest quality
 * - `base.en` (~142 MB) — default; good quality/size trade-off for voiceovers
 * - `small.en` (~466 MB) — best quality of the three, slowest
 *
 * Downloads stream to a `.part` file and are renamed atomically on success,
 * so a cancelled or failed download never leaves a corrupt model behind.
 */
class ModelDownloader(
    private val filesDir: File,
    private val client: OkHttpClient = defaultHttpClient()
) {

    /**
     * Local file for [model]; may not exist yet — check [isDownloaded] first.
     */
    fun modelFile(model: String): File = File(filesDir, fileName(model))

    /**
     * True when a non-empty model file is present.
     * Does NOT validate that the file is a working ggml model.
     */
    fun isDownloaded(model: String): Boolean =
        modelFile(model).let { it.isFile && it.length() > 0L }

    /**
     * Streams the model to [modelFile], reporting 0f..1f progress.
     *
     * Already-downloaded models are skipped (reports 1f immediately).
     *
     * @param progress 0f..1f; invoked on a background (IO) thread.
     * @param isCancelled polled between buffer reads.
     * @throws IllegalArgumentException for an unknown [model].
     * @throws IOException on network or filesystem failure.
     * @throws CancellationException when [isCancelled] returns true; the
     *   partial `.part` file is deleted.
     */
    suspend fun download(
        model: String,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Unit = withContext(Dispatchers.IO) {
        require(model in AVAILABLE_MODELS) {
            "Unknown model \"$model\". Available: $AVAILABLE_MODELS"
        }
        if (isDownloaded(model)) {
            progress(1f)
            return@withContext
        }

        val dest = modelFile(model)
        val tmp = File(filesDir, "${fileName(model)}.part")
        val request = Request.Builder()
            .url("$BASE_URL/${fileName(model)}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Model download failed: HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw IOException("Model download failed: empty response body")
                // Content-Length when the server provides it, else the documented
                // approximate size (progress estimate only).
                val total = body.contentLength().takeIf { it > 0 }
                    ?: APPROX_SIZE_BYTES[model]
                    ?: -1L

                var downloaded = 0L
                tmp.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (isCancelled()) {
                                throw CancellationException("Model download cancelled")
                            }
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                progress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                throw IOException("Failed to move downloaded model into place")
            }
            progress(1f)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        /** Default model: best quality/size balance for documentary voiceovers. */
        const val DEFAULT_MODEL = "base.en"

        /** Model ids this module knows how to fetch. */
        val AVAILABLE_MODELS: List<String> = listOf("tiny.en", "base.en", "small.en")

        /**
         * Approximate sizes in bytes, used ONLY for progress when the server
         * omits Content-Length.
         */
        private val APPROX_SIZE_BYTES: Map<String, Long> = mapOf(
            "tiny.en" to 75_000_000L,
            "base.en" to 142_000_000L,
            "small.en" to 466_000_000L
        )

        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        /** ggml file name for a model id, e.g. `"base.en"` -> `"ggml-base.en.bin"`. */
        fun fileName(model: String): String = "ggml-$model.bin"

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
