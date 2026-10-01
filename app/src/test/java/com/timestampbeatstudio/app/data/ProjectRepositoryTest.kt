package com.timestampbeatstudio.app.data

import com.timestampbeatstudio.core.BucketStatus
import com.timestampbeatstudio.core.CheckStatus
import com.timestampbeatstudio.core.TranscriptionResult
import com.timestampbeatstudio.core.Word
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fake DAO implementations (room-testing is not available; no Android needed). */
private class FakeProjectDao : ProjectDao {
    private val store = LinkedHashMap<Long, ProjectEntity>()
    private var nextId = 1L
    private val flow = MutableStateFlow<List<ProjectEntity>>(emptyList())

    override suspend fun insert(project: ProjectEntity): Long {
        val id = nextId++
        store[id] = project.copy(id = id)
        emit()
        return id
    }

    override suspend fun update(project: ProjectEntity) {
        store[project.id] = project
        emit()
    }

    override suspend fun getById(id: Long): ProjectEntity? = store[id]
    override fun observeRecent(): Flow<List<ProjectEntity>> = flow
    override suspend fun listRecent(): List<ProjectEntity> = flow.value

    private fun emit() {
        // Mirrors the real DAO ordering (createdAt DESC, id DESC) so the
        // newest project is first even when two rows share a millisecond.
        flow.value = store.values.sortedWith(
            compareByDescending<ProjectEntity> { it.createdAt }.thenByDescending { it.id }
        )
    }
}

private class FakeCorrectionDao : CorrectionDao {
    private val store = mutableMapOf<Pair<Long, Int>, CorrectionEntity>()
    override suspend fun upsert(correction: CorrectionEntity) {
        store[correction.projectId to correction.second] = correction
    }

    override suspend fun forProject(projectId: Long): List<CorrectionEntity> =
        store.values.filter { it.projectId == projectId }
}

private class FakeExportDao : ExportDao {
    private val store = mutableListOf<ExportEntity>()
    override suspend fun insert(export: ExportEntity): Long {
        store.add(export)
        return store.size.toLong()
    }

    override suspend fun forProject(projectId: Long): List<ExportEntity> =
        store.filter { it.projectId == projectId }
}

class ProjectRepositoryTest {

    private lateinit var repository: ProjectRepository
    private lateinit var projectDao: FakeProjectDao

    @Before
    fun setUp() {
        projectDao = FakeProjectDao()
        repository = ProjectRepository(projectDao, FakeCorrectionDao(), FakeExportDao())
    }

    @Test
    fun `saveTranscription then loadProject rebuilds timeline deterministically`() = runTest {
        val id = repository.createDraft("Test Project", "audio.m4a", "content://audio/1", 10.0)
        val words = listOf(
            Word("hello", 0.1, 0.4, 0.9),
            Word("world", 1.2, 1.6),
            Word("again", 9.1, 9.8)
        )
        repository.saveTranscription(id, TranscriptionResult(words, "en", 10.0, listOf(5..5)))

        val data = repository.loadProject(id)
        assertNotNull(data)
        data!!

        assertEquals(10, data.timeline.durationSec)
        assertEquals(10, data.timeline.buckets.size)
        assertEquals("hello", data.timeline.buckets[0].displayText)
        assertEquals("world", data.timeline.buckets[1].displayText)
        assertEquals("[SILENCE]", data.timeline.buckets[2].displayText)
        assertEquals("[INAUDIBLE]", data.timeline.buckets[5].displayText)
        assertEquals("again", data.timeline.buckets[9].displayText)
        assertEquals(BucketStatus.SPEECH, data.timeline.buckets[0].status)

        // Deterministic validation of the raw pipeline output.
        assertEquals(CheckStatus.PASS, data.report.overall)
        assertEquals(0, data.report.missingWords)
        assertEquals(0, data.report.duplicateWords)

        // Stored entity round-trips the transcription.
        val entity = repository.getProject(id)!!
        assertEquals("en", entity.language)
        assertEquals(10.0, entity.durationSec, 0.0)
        assertEquals(words, WordJson.decodeWords(entity.rawWordsJson))
    }

    @Test
    fun `corrections are applied to display timeline but raw stays untouched`() = runTest {
        val id = repository.createDraft("P", "a.m4a", "content://a", 3.0)
        repository.saveTranscription(
            id,
            TranscriptionResult(listOf(Word("hello", 0.1, 0.4)), "en", 3.0, emptyList())
        )

        repository.saveCorrection(id, 0, "hello", "hallo")

        val data = repository.loadProject(id)!!
        assertEquals("hallo", data.timeline.buckets[0].displayText)
        assertEquals("hello", data.rawTimeline.buckets[0].displayText)
        assertEquals("hello", data.corrections[0]!!.originalText)
        assertEquals("hallo", data.corrections[0]!!.correctedText)
    }

    @Test
    fun `recentProjects lists newest first`() = runTest {
        val first = repository.createDraft("First", "a.m4a", "content://a", 1.0)
        val second = repository.createDraft("Second", "b.m4a", "content://b", 1.0)
        val recent = repository.recentProjects().first()
        assertEquals(2, recent.size)
        assertEquals(second, recent[0].id)
        assertEquals(first, recent[1].id)
    }

    @Test
    fun `loadProject returns null for unknown id`() = runTest {
        assertNull(repository.loadProject(999L))
    }

    @Test
    fun `recordExport stores export history`() = runTest {
        val id = repository.createDraft("P", "a.m4a", "content://a", 1.0)
        repository.recordExport(id, "txt", "P_stage01.txt")
        val exports = repository.listExports(id)
        assertEquals(1, exports.size)
        assertEquals("txt", exports[0].format)
        assertEquals("P_stage01.txt", exports[0].fileName)
        assertTrue(repository.listExports(12345L).isEmpty())
    }
}
