package com.timestampbeatstudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timestampbeatstudio.app.data.ProjectEntity
import com.timestampbeatstudio.core.TimestampFormat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenProcessing: (Long) -> Unit,
    onOpenResult: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val recent by viewModel.recentProjects.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            val displayName = HomeViewModel.displayName(context.contentResolver, uri)
            viewModel.createDraftProject(uri, displayName) { id ->
                busy = false
                onOpenProcessing(id)
            }
        }
    }
    val pickProjectJson = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: throw IllegalArgumentException("Could not read file")
                    val name = HomeViewModel.displayName(context.contentResolver, uri)
                    val id = viewModel.importProjectJson(text, name)
                    onOpenResult(id)
                } catch (e: Exception) {
                    snackbar.showSnackbar("Import failed: ${e.message}")
                } finally {
                    busy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timestamp Beat Studio") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Text("⚙") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { pickAudio.launch("audio/*") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("SELECT AUDIO") }
            OutlinedButton(
                onClick = { pickProjectJson.launch("application/json") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("IMPORT PROJECT") }

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }

            Spacer(Modifier.height(4.dp))
            Text("Recent Projects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (recent.isEmpty()) {
                Text(
                    "No projects yet. Select an audio file to start Stage 01.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent, key = { it.id }) { project ->
                        ProjectRow(project = project, onClick = { onOpenResult(project.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectEntity, onClick: () -> Unit) {
    val date = remember(project.createdAt) {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(project.createdAt))
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${TimestampFormat.mmss(project.durationSec.toInt().coerceAtLeast(0))} · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                project.audioDisplayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
