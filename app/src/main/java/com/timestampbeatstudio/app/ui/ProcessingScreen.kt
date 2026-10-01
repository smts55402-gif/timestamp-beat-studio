package com.timestampbeatstudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Processing screen: fixed stage list, real progress, cancel, error + retry. */
@Composable
fun ProcessingScreen(
    viewModel: ProcessingViewModel,
    onDone: (Long) -> Unit,
    onCancelled: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.doneProjectId) {
        state.doneProjectId?.let { onDone(it) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Processing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Audio:", style = MaterialTheme.typography.labelMedium)
                    Text(state.fileName.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Duration:", style = MaterialTheme.typography.labelMedium)
                    Text(state.durationText.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text("Progress", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${state.stageLabel} — ${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.error != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Processing failed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(state.error!!, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                            Text("RETRY")
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    viewModel.cancel()
                    onCancelled()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("CANCEL") }
        }
    }
}
