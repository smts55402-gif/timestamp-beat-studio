package com.timestampbeatstudio.app

import android.content.Context
import androidx.room.Room
import com.timestampbeatstudio.app.audio.AudioDecoder
import com.timestampbeatstudio.app.data.AppDatabase
import com.timestampbeatstudio.app.data.ProjectRepository
import com.timestampbeatstudio.app.data.SettingsStore
import com.timestampbeatstudio.app.engine.EngineFactory
import com.timestampbeatstudio.app.export.ExportManager
import com.timestampbeatstudio.whisper.ModelDownloader

/** Manual dependency container for the app module. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "timestamp-beat-studio.db").build()
    }

    val repository: ProjectRepository by lazy {
        ProjectRepository(database.projectDao(), database.correctionDao(), database.exportDao())
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val audioDecoder: AudioDecoder by lazy { AudioDecoder(appContext) }

    val modelDownloader: ModelDownloader by lazy { ModelDownloader(appContext.filesDir) }

    val engineFactory: EngineFactory by lazy { EngineFactory(appContext, settings, modelDownloader) }

    val exportManager: ExportManager by lazy { ExportManager(appContext, repository) }
}
