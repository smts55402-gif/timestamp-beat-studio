package com.timestampbeatstudio.whisper

/**
 * Invoked from native code after each decoded whisper segment.
 *
 * Called on the same background thread that runs the transcription —
 * never on the main thread. Callers that touch the UI must marshal
 * to the main thread themselves.
 */
fun interface ProgressCallback {
    /**
     * @param progress 0f..1f fraction of segments processed so far.
     * @return true to request cancellation of the in-flight transcription.
     */
    fun onProgress(progress: Float): Boolean
}
