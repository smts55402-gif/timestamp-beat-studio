package com.timestampbeatstudio.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timestampbeatstudio.app.audio.AudioPlayer
import com.timestampbeatstudio.app.data.ProjectRepository
import com.timestampbeatstudio.app.export.ExportedFile
import com.timestampbeatstudio.app.export.ExportManager
import com.timestampbeatstudio.core.export.Exporters
import com.timestampbeatstudio.core.TimestampFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result screen state: loaded project, player position, edit dialog, messages. */
class ResultViewModel(
    private val projectId: Long,
    private val repository: ProjectRepository,
    private val exportManager: ExportManager,
    context: Context
) : ViewModel() {

    data class UiState(
        val data: ProjectRepository.ProjectData? = null,
        val loading: Boolean = true,
        val loadError: String? = null,
        val currentSecond: Int = 0,
        val isPlaying: Boolean = false,
        val audioReady: Boolean = false,
        val editingSecond: Int? = null,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val player = AudioPlayer(context.applicationContext)

    init {
        player.onSecondChanged = { sec -> _uiState.update { it.copy(currentSecond = sec) } }
        player.onPlayingChanged = { playing -> _uiState.update { it.copy(isPlaying = playing) } }
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadError = null) }
            try {
                val data = repository.loadProject(projectId)
                    ?: throw IllegalArgumentException("Project $projectId not found")
                _uiState.update { it.copy(data = data, loading = false) }
                if (data.entity.audioUriString.isNotBlank()) {
                    withContext(Dispatchers.IO) { player.load(Uri.parse(data.entity.audioUriString)) }
                    _uiState.update { it.copy(audioReady = true) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, loadError = e.message ?: "Could not load project") }
            }
        }
    }

    fun togglePlayPause() {
        if (!_uiState.value.audioReady) return
        if (player.isPlaying()) player.pause() else player.play()
    }

    fun seekTo(second: Int) {
        if (!_uiState.value.audioReady) return
        player.seekTo(second)
    }

    fun openEdit(second: Int) = _uiState.update { it.copy(editingSecond = second) }

    fun dismissEdit() = _uiState.update { it.copy(editingSecond = null) }

    /** Saves a manual correction; ORIGINAL stays in the raw transcript, CORRECTED is stored separately. */
    fun saveEdit(second: Int, correctedText: String) {
        viewModelScope.launch {
            val data = _uiState.value.data ?: return@launch
            val bucket = data.timeline.buckets.getOrNull(second) ?: return@launch
            repository.saveCorrection(projectId, second, bucket.displayText, correctedText)
            val reloaded = repository.loadProject(projectId)
            _uiState.update {
                it.copy(
                    data = reloaded,
                    editingSecond = null,
                    message = "Correction saved for ${TimestampFormat.mmss(second)}"
                )
            }
        }
    }

    /** Full TXT beat sheet text for COPY ALL. */
    fun copyAllText(): String {
        val data = _uiState.value.data ?: return ""
        return Exporters.txt.export(data.timeline, data.entity.name)
    }

    /** Exports [extension] (txt|md|json|srt) via FileProvider; records the export. */
    suspend fun exportFile(extension: String): ExportedFile {
        val exporter = Exporters.all().firstOrNull { it.extension == extension }
            ?: throw IllegalArgumentException("Unknown export format: $extension")
        return exportManager.export(projectId, exporter)
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
