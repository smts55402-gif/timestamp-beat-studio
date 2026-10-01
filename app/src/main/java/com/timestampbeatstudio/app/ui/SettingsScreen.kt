package com.timestampbeatstudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timestampbeatstudio.app.data.EngineChoice
import com.timestampbeatstudio.app.data.SettingsStore
import com.timestampbeatstudio.whisper.ModelDownloader
import kotlinx.coroutines.launch

/**
 * Settings: engine choice (on-device Whisper / remote server), remote server
 * URL, Whisper model size with download status + download button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    modelDownloader: ModelDownloader,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var engineChoice by remember { mutableStateOf(settings.engineChoice) }
    var remoteUrl by remember { mutableStateOf(settings.remoteBaseUrl) }
    var modelSize by remember { mutableStateOf(settings.whisperModelSize) }
    var modelDownloaded by remember { mutableStateOf(modelDownloader.isDownloaded(modelSize)) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var sizeMenuOpen by remember { mutableStateOf(false) }

    fun refreshModelStatus() {
        modelDownloaded = modelDownloader.isDownloaded(modelSize)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Engine choice
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Transcription engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    EngineChoice.entries.forEach { choice ->
                        val label = when (choice) {
                            EngineChoice.WHISPER -> "On-device Whisper"
                            EngineChoice.REMOTE -> "Remote server"
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = engineChoice == choice,
                                    role = Role.RadioButton,
                                    onClick = {
                                        engineChoice = choice
                                        settings.engineChoice = choice
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = engineChoice == choice,
                                onClick = {
                                    engineChoice = choice
                                    settings.engineChoice = choice
                                }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            // Remote URL
            if (engineChoice == EngineChoice.REMOTE) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Remote server URL", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = remoteUrl,
                            onValueChange = {
                                remoteUrl = it
                                settings.remoteBaseUrl = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(SettingsStore.DEFAULT_REMOTE_URL) }
                        )
                        Text(
                            "Default ${SettingsStore.DEFAULT_REMOTE_URL} reaches host-machine localhost from the Android emulator.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Whisper model
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Whisper model", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { sizeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Model size: $modelSize")
                    }
                    DropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                        ModelDownloader.AVAILABLE_MODELS.forEach { size ->
                            DropdownMenuItem(
                                text = { Text(size) },
                                onClick = {
                                    modelSize = size
                                    settings.whisperModelSize = size
                                    sizeMenuOpen = false
                                    refreshModelStatus()
                                }
                            )
                        }
                    }
                    val sizeBytes = modelDownloader.modelFile(modelSize)
                        .takeIf { it.exists() }?.length() ?: 0L
                    Text(
                        if (modelDownloaded) "Downloaded (${"%.1f".format(sizeBytes / 1_048_576.0)} MB)"
                        else "Not downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (modelDownloaded) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (downloading) {
                        LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    downloadError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            downloading = true
                            downloadProgress = 0f
                            downloadError = null
                            scope.launch {
                                try {
                                    modelDownloader.download(
                                        modelSize,
                                        progress = { downloadProgress = it },
                                        isCancelled = { false }
                                    )
                                    refreshModelStatus()
                                } catch (e: Exception) {
                                    downloadError = "Download failed: ${e.message}"
                                } finally {
                                    downloading = false
                                }
                            }
                        },
                        enabled = !downloading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (modelDownloaded) "RE-DOWNLOAD" else "DOWNLOAD MODEL") }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "Stage 01 only: transcription → fixed 1-second buckets → export. No visual/character features.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
