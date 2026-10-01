package com.timestampbeatstudio.whisper

import com.timestampbeatstudio.core.PcmAudio
import com.timestampbeatstudio.core.TranscriptionEngine
import com.timestampbeatstudio.core.TranscriptionException
import com.timestampbeatstudio.core.TranscriptionResult
import com.timestampbeatstudio.core.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [TranscriptionEngine] backed by on-device whisper.cpp (`libtbs_whisper_jni.so`).
 *
 * Pipeline: 16 kHz mono PCM -> [WhisperNative.nativeTranscribe] (greedy
 * decoding, token timestamps enabled) -> [NativeWord] list -> [Word] list.
 * The transcript flows through untouched: no rewriting, no summarising, no
 * guessing. Words the recogniser could not time keep their native timing
 * (clamped to >= 0); bucketing into whole seconds is `:core`'s job.
 *
 * The native `whisper_context` is created lazily on first [transcribe],
 * reused across calls, and guarded by a [Mutex] because the context is not
 * thread-safe for concurrent use. [close] frees it.
 *
 * Only English `.en` models are shipped, so [TranscriptionResult.language]
 * is always `"en"`. `inaudibleRangesSec` is always empty: this engine never
 * invents inaudible ranges — unclear speech surfaces as low-confidence words.
 *
 * Threading: [transcribe] and [ModelDownloader.download] are `suspend` and
 * safe to call from any dispatcher; blocking native work runs on
 * [Dispatchers.Default]/[Dispatchers.IO]. Progress callbacks fire on a
 * background thread — marshal to main in UI code.
 */
class WhisperEngine(
    /** App-private dir holding (or to hold) the ggml model file. */
    private val filesDir: File,
    /** One of [ModelDownloader.AVAILABLE_MODELS]. `.en` models => language "en". */
    val model: String = ModelDownloader.DEFAULT_MODEL,
    /** Worker threads for the whisper encoder/decoder. */
    private val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
) : TranscriptionEngine, AutoCloseable {

    override val engineName: String = "whisper-on-device"

    /**
     * Exposed so the UI can check/download the model before transcribing.
     * See [isModelDownloaded] and [downloadModel].
     */
    val modelDownloader: ModelDownloader = ModelDownloader(filesDir)

    // Fails fast with UnsatisfiedLinkError if libtbs_whisper_jni.so is missing.
    private val native = WhisperNative()

    private val mutex = Mutex()
    private var ctxPtr: Long = 0L

    /** True once [close] has run; further [transcribe] calls fail. */
    @Volatile
    private var closed = false

    /** True when a non-empty ggml file for [model] is present. */
    fun isModelDownloaded(): Boolean = modelDownloader.isDownloaded(model)

    /**
     * Downloads [model] if needed. See [ModelDownloader.download].
     */
    suspend fun downloadModel(
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) = modelDownloader.download(model, progress, isCancelled)

    @Throws(TranscriptionException::class)
    override suspend fun transcribe(
        pcm: PcmAudio,
        durationSec: Double,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): TranscriptionResult {
        if (pcm.sampleRate != 16000) {
            throw TranscriptionException(
                "WhisperEngine requires 16 kHz mono PCM, got ${pcm.sampleRate} Hz"
            )
        }
        if (pcm.samples.isEmpty()) {
            throw TranscriptionException("WhisperEngine received empty audio")
        }
        if (!isModelDownloaded()) {
            throw TranscriptionException(
                "whisper model \"$model\" is not downloaded — call downloadModel() first"
            )
        }

        val nativeWords: Array<NativeWord> = mutex.withLock {
            if (closed) throw TranscriptionException("WhisperEngine is closed")
            ensureInit()
            if (isCancelled()) throw CancellationException("transcription cancelled before start")
            progress(0f)
            // Native per-segment progress only covers the decode tail; map it
            // into 0.05..1.0 so the bar keeps moving monotonically.
            val callback = ProgressCallback { p ->
                progress((0.05f + 0.95f * p).coerceIn(0f, 1f))
                isCancelled() // true => native aborts after this segment
            }
            withContext(Dispatchers.Default) {
                native.nativeTranscribe(ctxPtr, pcm.samples, numThreads, callback)
            }
        }

        if (isCancelled()) throw CancellationException("transcription cancelled")

        val words = nativeWords.asSequence()
            .filter { it.text.isNotBlank() }
            .map { nw ->
                val start = nw.startSec.coerceAtLeast(0.0)
                Word(
                    text = nw.text.trim(),
                    startSec = start,
                    endSec = nw.endSec.coerceAtLeast(start),
                    confidence = nw.confidence.takeIf { it >= 0.0 }
                )
            }
            .sortedBy { it.startSec } // stable: keeps native order on ties
            .toList()

        progress(1f)
        return TranscriptionResult(
            words = words,
            language = "en",
            durationSec = durationSec,
            inaudibleRangesSec = emptyList()
        )
    }

    private suspend fun ensureInit() {
        if (ctxPtr != 0L) return
        val path = modelDownloader.modelFile(model).absolutePath
        val ptr = withContext(Dispatchers.IO) { native.nativeInit(path) }
        if (ptr == 0L) {
            throw TranscriptionException(
                "whisper failed to load model \"$model\" (missing or corrupt file?)"
            )
        }
        ctxPtr = ptr
    }

    /**
     * Frees the native whisper context. Blocks briefly until any in-flight
     * [transcribe] finishes. Do not call from the UI thread while a
     * transcription may be running.
     */
    override fun close() = runBlocking {
        mutex.withLock {
            closed = true
            val ptr = ctxPtr
            ctxPtr = 0L
            if (ptr != 0L) native.nativeFree(ptr)
        }
    }
}
