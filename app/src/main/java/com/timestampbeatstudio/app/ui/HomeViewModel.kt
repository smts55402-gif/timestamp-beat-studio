package com.timestampbeatstudio.app.ui

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timestampbeatstudio.app.audio.AudioDecoder
import com.timestampbeatstudio.app.data.ProjectEntity
import com.timestampbeatstudio.app.data.ProjectRepository
import com.timestampbeatstudio.app.json.MiniJson
import com.timestampbeatstudio.core.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val repository: ProjectRepository,
    private val audioDecoder: AudioDecoder
) : ViewModel() {

    val recentProjects: StateFlow<List<ProjectEntity>> =
        repository.recentProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a draft project for [uri] and reports its id. */
    fun createDraftProject(uri: Uri, displayName: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { audioDecoder.getDurationSec(uri) }
            val name = displayName.substringBeforeLast('.').ifBlank { "Untitled Project" }
            val id = repository.createDraft(name, displayName, uri.toString(), duration)
            onDone(id)
        }
    }

    /**
     * Imports a previously exported Stage-01 JSON beat sheet. Words (with their
     * internal precise timing) are recovered from the JSON; inaudible ranges are
     * derived from consecutive inaudible seconds. Manual corrections are not
     * recoverable from the export (only original words are stored there).
     */
    suspend fun importProjectJson(jsonText: String, fileName: String): Long =
        withContext(Dispatchers.Default) {
            val root = (MiniJson.parse(jsonText) as? Map<*, *>)
                ?: throw IllegalArgumentException("Not a project JSON file")
            val name = (root["project"] as? String)?.ifBlank { null } ?: fileName.substringBeforeLast('.')
            val durationSec = (root["durationSec"] as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Project JSON has no durationSec")
            val seconds = (root["seconds"] as? List<*>)
                ?: throw IllegalArgumentException("Project JSON has no seconds")

            val words = ArrayList<Word>()
            val inaudibleSeconds = ArrayList<Int>()
            for (s in seconds) {
                val sm = s as? Map<*, *> ?: continue
                val second = (sm["start_seconds"] as? Number)?.toInt() ?: continue
                if ((sm["status"] as? String) == "inaudible") inaudibleSeconds.add(second)
                val wordList = (sm["words"] as? List<*>) ?: emptyList<Any?>()
                for (w in wordList) {
                    val wm = w as? Map<*, *> ?: continue
                    val text = wm["text"]?.toString() ?: continue
                    val start = (wm["start"] as? Number)?.toDouble() ?: continue
                    val end = (wm["end"] as? Number)?.toDouble() ?: continue
                    words.add(Word(text, start, end))
                }
            }
            val inaudibleRanges = toRanges(inaudibleSeconds.sorted())
            repository.importCompletedProject(
                name = name,
                audioDisplayName = fileName,
                audioUriString = "",
                words = words.sortedBy { it.startSec },
                inaudibleRangesSec = inaudibleRanges,
                durationSec = durationSec,
                language = null
            )
        }

    private fun toRanges(seconds: List<Int>): List<IntRange> {
        if (seconds.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var start = seconds[0]
        var prev = start
        for (s in seconds.drop(1)) {
            if (s == prev + 1) {
                prev = s
            } else {
                ranges.add(IntRange(start, prev))
                start = s
                prev = s
            }
        }
        ranges.add(IntRange(start, prev))
        return ranges
    }

    companion object {
        /** Display name of a content Uri via OpenableColumns, falling back to the last path segment. */
        fun displayName(resolver: ContentResolver, uri: Uri): String {
            var name: String? = null
            val cursor: Cursor? = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            }.getOrNull()
            cursor?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
            return name ?: uri.lastPathSegment ?: "audio"
        }
    }
}
