package com.timestampbeatstudio.whisper

/**
 * JNI bridge to the native whisper.cpp layer (`libtbs_whisper_jni.so`).
 *
 * Low-level API — prefer [WhisperEngine], which implements the `:core`
 * [com.timestampbeatstudio.core.TranscriptionEngine] contract and owns the
 * native context lifecycle.
 *
 * All methods are synchronous and blocking; [nativeTranscribe] in particular
 * must be called from a background thread. Native failures are reported as
 * `0` / empty arrays, never as crashes.
 */
class WhisperNative {

    /**
     * Loads a ggml model file and returns the native `whisper_context*`
     * as a [Long], or `0` on failure (missing/corrupt model, OOM).
     */
    external fun nativeInit(modelPath: String): Long

    /**
     * Runs full transcription on 16 kHz mono float PCM.
     *
     * @param ctx native context pointer from [nativeInit].
     * @param samples 16 kHz mono PCM, values roughly in [-1, 1].
     * @param numThreads worker threads for the whisper encoder/decoder.
     * @param progressCallback invoked on the calling thread after every
     *   decoded segment with progress in 0f..1f; return `true` to cancel.
     *   On cancellation the words decoded so far are returned.
     * @return words in chronological order; empty array on failure.
     */
    external fun nativeTranscribe(
        ctx: Long,
        samples: FloatArray,
        numThreads: Int,
        progressCallback: ProgressCallback
    ): Array<NativeWord>

    /** Frees the native context. Safe to call with `0`. */
    external fun nativeFree(ctx: Long)

    companion object {
        init {
            System.loadLibrary("tbs_whisper_jni")
        }
    }
}
