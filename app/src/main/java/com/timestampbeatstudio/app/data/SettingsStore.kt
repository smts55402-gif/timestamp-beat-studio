package com.timestampbeatstudio.app.data

import android.content.Context

/** Which transcription backend to use. */
enum class EngineChoice { WHISPER, REMOTE }

/** Simple SharedPreferences-backed settings (no new dependencies). */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var engineChoice: EngineChoice
        get() = runCatching {
            EngineChoice.valueOf(prefs.getString(KEY_ENGINE, EngineChoice.WHISPER.name)!!)
        }.getOrDefault(EngineChoice.WHISPER)
        set(value) = prefs.edit().putString(KEY_ENGINE, value.name).apply()

    /** Base URL of the remote transcription server. Default targets host localhost from the emulator. */
    var remoteBaseUrl: String
        get() = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL) ?: DEFAULT_REMOTE_URL
        set(value) = prefs.edit().putString(KEY_REMOTE_URL, value).apply()

    /** Whisper model size id, e.g. "base.en". */
    var whisperModelSize: String
        get() = prefs.getString(KEY_MODEL_SIZE, DEFAULT_MODEL_SIZE) ?: DEFAULT_MODEL_SIZE
        set(value) = prefs.edit().putString(KEY_MODEL_SIZE, value).apply()

    companion object {
        const val DEFAULT_REMOTE_URL = "http://10.0.2.2:8000"
        const val DEFAULT_MODEL_SIZE = "base.en"
        private const val PREFS_NAME = "tbs_settings"
        private const val KEY_ENGINE = "engine_choice"
        private const val KEY_REMOTE_URL = "remote_base_url"
        private const val KEY_MODEL_SIZE = "whisper_model_size"
    }
}
