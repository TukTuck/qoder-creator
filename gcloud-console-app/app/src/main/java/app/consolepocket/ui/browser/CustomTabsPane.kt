package app.consolepocket.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.consolepocket.R
import app.consolepocket.model.ConsoleUrls
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.Screen
import app.consolepocket.ui.UiState

/**
 * Policy-sicherer Modus (PLAN Kapitel 6, Option O2 / ADR-001 Gate B2).
 *
 * Die Console laeuft in einem Chrome Custom Tab, der im Task DIESER App gestartet wird:
 * die Back-Taste kehrt zurueck, es erscheint kein eigener Browser-Eintrag in den Recents.
 * Die Anmeldung funktioniert, weil Chrome sein eigenes Cookie-Jar nutzt.
 *
 * Kosten: kein Zugriff auf einzelne Requests, also kein eigener Cache (Anforderung A2 ist in
 * diesem Modus nicht erfuellbar). Beschleunigung kommt hier aus `warmup()` + `mayLaunchUrl()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTabsPane(
    ui: UiState,
    viewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
) {
    // Vorwärmen: Chrome rendert die Console im Hintergrund, bevor der Tab sichtbar wird.
    LaunchedEffect(Unit) {
        viewModel.prelaunchCustomTab(ui.prefs.startUrl)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name) + " · sicherer Modus") },
                actions = {
                    IconButton(onClick = { viewModel.prelaunchCustomTab(ui.prefs.startUrl) }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.action_reload))
                    }
                    IconButton(onClick = { viewModel.setScreen(Screen.DOWNLOADS) }) {
                        Icon(Icons.Filled.List, stringResource(R.string.action_downloads))
                    }
                    IconButton(onClick = { viewModel.setScreen(Screen.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Warum dieser Modus?",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Google erlaubt die Konto-Anmeldung nicht in eingebetteten Web-Ansichten. " +
                                "Im sicheren Modus läuft die Console in einem Chrome Custom Tab im Task " +
                                "dieser App: Anmeldung und Session funktionieren, die Back-Taste kehrt " +
                                "hierher zurück. Der eigene Cache der App ist hier nicht aktiv.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.openUrl(ui.prefs.startUrl) }) {
                                Text("Console jetzt öffnen")
                            }
                            TextButton(onClick = { viewModel.setEngineMode(app.consolepocket.model.EngineMode.AUTO) }) {
                                Text("Zurück zu automatisch")
                            }
                        }
                    }
                }
            }

            if (!ui.customTabsAvailable) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Kein Browser mit Custom-Tabs-Unterstützung gefunden. Bitte Chrome (oder einen " +
                                "anderen Chromium-Browser) installieren oder den WebView-Modus wählen.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(ConsoleUrls.quickLinks, key = { it.url }) { link ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(link.labelRes),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                link.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.openUrl(link.url) }) { Text("Öffnen") }
                    }
                }
            }
        }
    }
}
