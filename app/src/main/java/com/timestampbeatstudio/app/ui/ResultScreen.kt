package com.timestampbeatstudio.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timestampbeatstudio.app.data.ProjectRepository
import com.timestampbeatstudio.core.BucketStatus
import com.timestampbeatstudio.core.CheckStatus
import com.timestampbeatstudio.core.TimestampFormat
import com.timestampbeatstudio.core.ValidationReport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(viewModel: ResultViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.data?.entity?.name ?: "Result") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val data = state.data
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            state.loadError != null || data == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Could not load project: ${state.loadError}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("BACK") }
            }

            else -> ResultContent(
                modifier = Modifier.padding(padding),
                data = data,
                currentSecond = state.currentSecond,
                isPlaying = state.isPlaying,
                audioReady = state.audioReady,
                editingSecond = state.editingSecond,
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeek = { viewModel.seekTo(it) },
                onOpenEdit = { viewModel.openEdit(it) },
                onDismissEdit = { viewModel.dismissEdit() },
                onSaveEdit = { second, text -> viewModel.saveEdit(second, text) },
                onCopyAll = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPlainTextSafe("beat-sheet", viewModel.copyAllText())
                    scope.launch { snackbar.showSnackbar("Beat sheet copied") }
                },
                onExport = { ext ->
                    scope.launch {
                        try {
                            val file = viewModel.exportFile(ext)
                            snackbar.showSnackbar("Exported ${file.fileName}")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Export failed: ${e.message}")
                        }
                    }
                },
                onShare = {
                    scope.launch {
                        try {
                            val file = viewModel.exportFile("txt")
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, file.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share beat sheet"))
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Share failed: ${e.message}")
                        }
                    }
                }
            )
        }
    }
}

private fun ClipboardManager.setPlainTextSafe(label: String, text: String) {
    setPrimaryClip(ClipData.newPlainText(label, text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultContent(
    modifier: Modifier = Modifier,
    data: ProjectRepository.ProjectData,
    currentSecond: Int,
    isPlaying: Boolean,
    audioReady: Boolean,
    editingSecond: Int?,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onOpenEdit: (Int) -> Unit,
    onDismissEdit: () -> Unit,
    onSaveEdit: (Int, String) -> Unit,
    onCopyAll: () -> Unit,
    onExport: (String) -> Unit,
    onShare: () -> Unit
) {
    val timeline = data.timeline
    val report = data.report
    val durationSec = timeline.durationSec
    val speechBuckets = timeline.buckets.count { it.status == BucketStatus.SPEECH }
    val coveragePct = if (durationSec > 0) (speechBuckets * 100f / durationSec) else 0f

    Column(modifier.fillMaxSize()) {
        // Header
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(data.entity.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Duration: ${TimestampFormat.mmss(durationSec)}", style = MaterialTheme.typography.bodyMedium)
                Text("Total seconds: $durationSec", style = MaterialTheme.typography.bodyMedium)
                Text("Speech coverage: ${"%.1f".format(coveragePct)}%", style = MaterialTheme.typography.bodyMedium)
                data.entity.language?.let { Text("Language: $it", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Validation:", style = MaterialTheme.typography.labelLarge)
                    StatusChip(report.overall)
                }
            }
        }

        ValidationPanel(report = report)

        // Player
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTogglePlay, enabled = audioReady) {
                        Text(if (isPlaying) "❚❚ Pause" else "▶ Play")
                    }
                    Text(
                        "${TimestampFormat.mmss(currentSecond.coerceIn(0, durationSec))} / ${TimestampFormat.mmss(durationSec)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (durationSec > 0) {
                    Slider(
                        value = currentSecond.coerceIn(0, durationSec).toFloat(),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..durationSec.toFloat(),
                        enabled = audioReady
                    )
                }
                if (!audioReady && data.entity.audioUriString.isBlank()) {
                    Text(
                        "Audio not attached (imported project) — playback unavailable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Actions
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onCopyAll) { Text("COPY ALL") }
            OutlinedButton(onClick = { onExport("txt") }) { Text("EXPORT TXT") }
            OutlinedButton(onClick = { onExport("md") }) { Text("EXPORT MD") }
            OutlinedButton(onClick = { onExport("json") }) { Text("EXPORT JSON") }
            OutlinedButton(onClick = { onExport("srt") }) { Text("EXPORT SRT") }
            OutlinedButton(onClick = onShare) { Text("SHARE") }
        }

        Spacer(Modifier.height(8.dp))

        // Second-by-second list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(timeline.buckets, key = { it.second }) { bucket ->
                val ts = TimestampFormat.mmss(bucket.second)
                val isActive = bucket.second == currentSecond
                val isEdited = data.corrections.containsKey(bucket.second)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onSeek(bucket.second) }) {
                        Text(ts, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(bucket.displayText, style = MaterialTheme.typography.bodyMedium)
                        if (isEdited) {
                            Text(
                                "edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    IconButton(onClick = { onOpenEdit(bucket.second) }) { Text("✎") }
                }
            }
        }
    }

    // Edit dialog
    editingSecond?.let { second ->
        val bucket = data.timeline.buckets.getOrNull(second)
        if (bucket != null) {
            EditSecondDialog(
                second = second,
                initialText = bucket.displayText,
                onDismiss = onDismissEdit,
                onSave = { onSaveEdit(second, it) }
            )
        }
    }
}

@Composable
private fun StatusChip(status: CheckStatus) {
    val (label, color) = when (status) {
        CheckStatus.PASS -> "PASS" to Color(0xFF4CAF50)
        CheckStatus.WARNING -> "WARNING" to Color(0xFFFFB74D)
        CheckStatus.FAIL -> "FAIL" to Color(0xFFFF5252)
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Validation panel: every check from the ValidationReport with PASS/WARNING/FAIL. */
@Composable
private fun ValidationPanel(report: ValidationReport) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Validation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            if (report.overall == CheckStatus.FAIL) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "COVERAGE ERROR — REVIEW REQUIRED",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            report.checks.forEach { check ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(check.status)
                    Column(Modifier.weight(1f)) {
                        Text(check.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (check.detail.isNotBlank()) {
                            Text(
                                check.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Missing words: ${report.missingWords}", style = MaterialTheme.typography.bodySmall)
            Text("Duplicate words: ${report.duplicateWords}", style = MaterialTheme.typography.bodySmall)
            Text("Invented segments: ${report.inventedSegments}", style = MaterialTheme.typography.bodySmall)
            Text("Silent seconds: ${report.silentSeconds}", style = MaterialTheme.typography.bodySmall)
            Text("Inaudible seconds: ${report.inaudibleSeconds}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Overall:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                StatusChip(report.overall)
            }
        }
    }
}

@Composable
private fun EditSecondDialog(
    second: Int,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(second) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${TimestampFormat.mmss(second)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The original transcription is kept separately; your edit is stored as a correction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
