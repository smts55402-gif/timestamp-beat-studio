package com.timestampbeatstudio.app.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.timestampbeatstudio.app.data.ProjectRepository
import com.timestampbeatstudio.core.export.BeatSheetExporter
import com.timestampbeatstudio.core.export.Exporters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of an export: a shareable content Uri and the safe file name. */
data class ExportedFile(val uri: Uri, val fileName: String)

/**
 * Writes a beat-sheet export to `cacheDir/exports`, records it in the
 * database, and returns a FileProvider content Uri for sharing.
 */
class ExportManager(
    private val context: Context,
    private val repository: ProjectRepository
) {
    companion object {
        /** Must match the android:authorities value in AndroidManifest.xml. */
        fun fileProviderAuthority(context: Context): String =
            "${context.packageName}.fileprovider"
    }

    /** Exports the project with [exporter]; returns a content Uri via FileProvider. */
    suspend fun export(projectId: Long, exporter: BeatSheetExporter): ExportedFile =
        withContext(Dispatchers.IO) {
            val data = repository.loadProject(projectId)
                ?: throw IllegalArgumentException("Project $projectId not found")
            val content = exporter.export(data.timeline, data.entity.name)
            val fileName = Exporters.safeFileName(data.entity.name, exporter)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content)
            repository.recordExport(projectId, exporter.extension, fileName)
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            ExportedFile(uri, fileName)
        }
}
