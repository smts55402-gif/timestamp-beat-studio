package com.timestampbeatstudio.app.engine

import android.content.Context
import com.timestampbeatstudio.app.data.EngineChoice
import com.timestampbeatstudio.app.data.SettingsStore
import com.timestampbeatstudio.core.TranscriptionEngine
import com.timestampbeatstudio.core.TranscriptionException
import com.timestampbeatstudio.whisper.ModelDownloader
import com.timestampbeatstudio.whisper.WhisperEngine

/**
 * Creates the [TranscriptionEngine] selected in settings.
 *
 * The on-device engine comes directly from the `:whisper` module
 * ([WhisperEngine]) — a compile-time dependency, no reflection. Model files
 * live where [ModelDownloader] puts them, so the factory and the Settings
 * download UI always agree on the location.
 *
 * Never silently substitutes one engine for another: if on-device Whisper is
 * selected but its model is missing, this throws an actionable
 * [TranscriptionException] (the processing screen shows it with a Retry option).
 */
class EngineFactory(
    private val context: Context,
    private val settings: SettingsStore,
    private val modelDownloader: ModelDownloader =
        ModelDownloader(context.applicationContext.filesDir)
) {

    /** True when the ggml model file for the selected size exists locally. */
    fun isWhisperModelAvailable(): Boolean =
        modelDownloader.isDownloaded(whisperModel())

    /** Returns the configured engine. */
    fun createEngine(): TranscriptionEngine {
        if (settings.engineChoice == EngineChoice.WHISPER) {
            val model = whisperModel()
            if (!modelDownloader.isDownloaded(model)) {
                throw TranscriptionException(
                    "Whisper model \"$model\" is not downloaded. " +
                        "Download it in Settings first."
                )
            }
            return WhisperEngine(context.applicationContext.filesDir, model)
        }
        return RemoteTranscriptionEngine(settings.remoteBaseUrl)
    }

    /**
     * The effective model id. Normalizes legacy size ids ("base", "tiny",
     * "small") to their `.en` counterparts and falls back to
     * [ModelDownloader.DEFAULT_MODEL] for anything unknown.
     */
    fun whisperModel(): String {
        val raw = settings.whisperModelSize
        if (raw in ModelDownloader.AVAILABLE_MODELS) return raw
        val withEn = "$raw.en"
        return if (withEn in ModelDownloader.AVAILABLE_MODELS) withEn
        else ModelDownloader.DEFAULT_MODEL
    }
}
