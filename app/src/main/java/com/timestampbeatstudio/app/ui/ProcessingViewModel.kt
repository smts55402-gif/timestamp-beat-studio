package com.timestampbeatstudio.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.timestampbeatstudio.app.audio.AudioDecoder
import com.timestampbeatstudio.app.data.ProjectRepository
import com.timestampbeatstudio.app.engine.EngineFactory
import com.timestampbeatstudio.core.PipelineValidator
import com.timestampbeatstudio.core.SecondBucketizer
import com.timestampbeatstudio.core.TimestampFormat
import com.timestampbeatstudio.core.TranscriptionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Runs the Stage-01 pipeline: decode → transcribe → align/bucketize → validate → save.
 * Supports cancellation and retry. Stage labels and progress mapping are fixed by spec.
 */
class ProcessingViewModel(
    private val projectId: Long,
    private val repository: ProjectRepository,
    private val audioDecoder: AudioDecoder,
    private val engineFactory: EngineFactory
) : ViewModel() {

    data class UiState(
        val fileName: String = "",
        val durationText: String = "",
        val stageLabel: String = "Loading",
        val progress: Float = 0f,
        val error: String? = null,
        val doneProjectId: Long? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        job = viewModelScope.launch { runPipeline() }
    }

    fun retry() = start()

    fun cancel() {
        job?.cancel()
    }

    private fun setStage(label: String, progress: Float) {
        _uiState.update { it.copy(stageLabel = label, progress = progress.coerceIn(0f, 1f), error = null) }
    }

    private suspend fun runPipeline() {
        try {
            val draft = repository.getProject(projectId)
                ?: throw IllegalStateException("Project $projectId not found")
            val uri = Uri.parse(draft.audioUriString)
            if (uri == null || draft.audioUriString.isBlank()) {
                throw IllegalStateException("Project has no audio attached")
            }
            val ctx = currentCoroutineContext()
            val isCancelled = { !ctx.isActive }

            _uiState.update {
                it.copy(
                    fileName = draft.audioDisplayName,
                    durationText = TimestampFormat.mmss(draft.durationSec.toInt().coerceAtLeast(0)),
                    error = null,
                    doneProjectId = null
                )
            }

            // 0% — Loading / 10% — Preprocessing
            setStage("Loading", 0f)
            setStage("Preprocessing", 0.05f)
            val pcm = audioDecoder.decode(
                uri,
                progress = { f -> setStage(if (f < 0.5f) "Loading" else "Preprocessing", 0.05f + f * 0.15f) },
                isCancelled = isCancelled
            )

            // 30% — Transcribing
            setStage("Transcribing", 0.2f)
            val engine = engineFactory.createEngine()
            val result = engine.transcribe(
                pcm,
                draft.durationSec.takeIf { it > 0 } ?: audioDecoder.getDurationSec(uri),
                progress = { f -> setStage("Transcribing", 0.2f + f * 0.55f) },
                isCancelled = isCancelled
            )

            // 60% — Aligning / 75% — Building second timeline
            setStage("Aligning", 0.76f)
            val timeline = SecondBucketizer.bucketize(result.words, result.durationSec, result.inaudibleRangesSec)
            setStage("Building second timeline", 0.82f)

            // 90% — Validating
            setStage("Validating", 0.9f)
            // Validation is the gate for the coverage audit; the report is
            // recomputed deterministically whenever the project is loaded.
            PipelineValidator.validate(timeline, result.words, result.durationSec)
            setStage("Validating", 0.95f)

            // Persist (validation report is recomputed on load; stored words are the source of truth).
            repository.saveTranscription(projectId, result)

            // 100% — Complete
            setStage("Complete", 1f)
            _uiState.update { it.copy(doneProjectId = projectId) }
        } catch (e: CancellationException) {
            // Swallowed: the screen navigates away on cancel.
            throw e
        } catch (e: TranscriptionException) {
            _uiState.update { it.copy(error = e.message ?: "Transcription failed") }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Processing failed") }
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
