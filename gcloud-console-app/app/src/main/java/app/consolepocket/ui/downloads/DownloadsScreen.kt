package app.consolepocket.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.consolepocket.R
import app.consolepocket.downloads.DownloadStore
import app.consolepocket.metrics.CacheStats
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.Screen
import app.consolepocket.ui.UiState

/**
 * In-App-Downloads: Exporte aus der Console (CSV, PDF, Schluessel) bleiben in der App.
 * Oeffnen ist eine explizite Nutzeraktion (FileProvider-Intent), nie ein automatischer
 * Handoff an einen Browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    ui: UiState,
    viewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.setScreen(Screen.BROWSER) }) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        if (ui.downloads.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.downloads_empty), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.downloads, key = { it.id }) { item ->
                    DownloadRow(item, viewModel)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadStore.Item, viewModel: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.fileName, style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append(item.mimeType)
                    if (item.totalBytes > 0) {
                        append(" · ")
                        append(CacheStats.humanBytes(item.downloadedBytes))
                        append(" / ")
                        append(CacheStats.humanBytes(item.totalBytes))
                    }
                    item.error?.let { append(" · Fehler: $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (item.state) {
                DownloadStore.State.RUNNING -> {
                    val progress = if (item.totalBytes > 0) {
                        (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    if (progress > 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                DownloadStore.State.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.openDownload(item) }) {
                        Text(stringResource(R.string.downloads_open))
                    }
                    TextButton(onClick = { viewModel.deleteDownload(item.id) }) {
                        Text(stringResource(R.string.downloads_delete))
                    }
                }

                DownloadStore.State.FAILED -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Fehlgeschlagen", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { viewModel.deleteDownload(item.id) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.downloads_delete))
                    }
                }
            }
        }
    }
}
