package com.timestampbeatstudio.app.data

import com.timestampbeatstudio.core.PipelineValidator
import com.timestampbeatstudio.core.SecondBucketizer
import com.timestampbeatstudio.core.SecondTimeline
import com.timestampbeatstudio.core.TranscriptionResult
import com.timestampbeatstudio.core.ValidationReport
import com.timestampbeatstudio.core.Word
import kotlinx.coroutines.flow.Flow

/**
 * Project persistence. The [SecondTimeline] is never stored: it is rebuilt
 * deterministically from the stored words via core [SecondBucketizer], then
 * manual corrections are applied as [com.timestampbeatstudio.core.SecondBucket.correctedText].
 * Validation always runs against the raw (uncorrected) deterministic timeline.
 */
class ProjectRepository(
    private val projectDao: ProjectDao,
    private val correctionDao: CorrectionDao,
    private val exportDao: ExportDao
) {

    /** Fully loaded project: entity, words, deterministic timelines and validation. */
    data class ProjectData(
        val entity: ProjectEntity,
        val words: List<Word>,
        /** Deterministic timeline straight from the transcription (no user edits). */
        val rawTimeline: SecondTimeline,
        /** Display timeline with manual corrections applied. */
        val timeline: SecondTimeline,
        /** Validation of the raw deterministic pipeline output. */
        val report: ValidationReport,
        val corrections: Map<Int, CorrectionEntity>
    )

    /** Creates a draft row before processing starts (empty transcription). */
    suspend fun createDraft(
        name: String,
        audioDisplayName: String,
        audioUriString: String,
        durationSec: Double
    ): Long = projectDao.insert(
        ProjectEntity(
            name = name,
            audioDisplayName = audioDisplayName,
            audioUriString = audioUriString,
            durationSec = durationSec,
            language = null,
            rawWordsJson = "[]",
            inaudibleJson = "[]",
            createdAt = System.currentTimeMillis()
        )
    )

    /** Stores the finished transcription for a draft project. */
    suspend fun saveTranscription(projectId: Long, result: TranscriptionResult) {
        val entity = projectDao.getById(projectId)
            ?: throw IllegalArgumentException("Project $projectId not found")
        projectDao.update(
            entity.copy(
                durationSec = result.durationSec,
                language = result.language,
                rawWordsJson = WordJson.encodeWords(result.words),
                inaudibleJson = WordJson.encodeRanges(result.inaudibleRangesSec)
            )
        )
    }

    /** Inserts a fully-transcribed project (used by JSON import). */
    suspend fun importCompletedProject(
        name: String,
        audioDisplayName: String,
        audioUriString: String,
        words: List<Word>,
        inaudibleRangesSec: List<IntRange>,
        durationSec: Double,
        language: String?
    ): Long = projectDao.insert(
        ProjectEntity(
            name = name,
            audioDisplayName = audioDisplayName,
            audioUriString = audioUriString,
            durationSec = durationSec,
            language = language,
            rawWordsJson = WordJson.encodeWords(words),
            inaudibleJson = WordJson.encodeRanges(inaudibleRangesSec),
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun getProject(id: Long): ProjectEntity? = projectDao.getById(id)

    /** Loads a project and rebuilds its timeline deterministically. */
    suspend fun loadProject(id: Long): ProjectData? {
        val entity = projectDao.getById(id) ?: return null
        val words = WordJson.decodeWords(entity.rawWordsJson)
        val inaudible = WordJson.decodeRanges(entity.inaudibleJson)
        val rawTimeline = SecondBucketizer.bucketize(words, entity.durationSec, inaudible)
        val report = PipelineValidator.validate(rawTimeline, words, entity.durationSec)
        val corrections = correctionDao.forProject(id).associateBy { it.second }
        val correctedBuckets = rawTimeline.buckets.map { bucket ->
            corrections[bucket.second]?.let { bucket.copy(correctedText = it.correctedText) } ?: bucket
        }
        return ProjectData(
            entity = entity,
            words = words,
            rawTimeline = rawTimeline,
            timeline = rawTimeline.copy(buckets = correctedBuckets),
            report = report,
            corrections = corrections
        )
    }

    /** Stores a manual correction; ORIGINAL and CORRECTED are kept separately. */
    suspend fun saveCorrection(projectId: Long, second: Int, originalText: String, correctedText: String) {
        correctionDao.upsert(CorrectionEntity(projectId, second, originalText, correctedText))
    }

    fun recentProjects(): Flow<List<ProjectEntity>> = projectDao.observeRecent()

    suspend fun recordExport(projectId: Long, format: String, fileName: String): Long =
        exportDao.insert(ExportEntity(projectId = projectId, format = format, fileName = fileName, createdAt = System.currentTimeMillis()))

    suspend fun listExports(projectId: Long): List<ExportEntity> = exportDao.forProject(projectId)
}
