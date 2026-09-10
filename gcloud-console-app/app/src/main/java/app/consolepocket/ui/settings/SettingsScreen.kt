package app.consolepocket.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.consolepocket.R
import app.consolepocket.cache.RuleMode
import app.consolepocket.metrics.CacheStats
import app.consolepocket.model.ConsoleUrls
import app.consolepocket.model.DarkMode
import app.consolepocket.model.EngineMode
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.Screen
import app.consolepocket.ui.UiState

/**
 * Einstellungen: Engine, Cache, Regel-Modus, Anzeige, Startverhalten.
 * Jede Option ist ein Kill-Switch fuer eine der Beschleunigungs-Schichten (PLAN Kapitel 5),
 * damit sich Effekte einzeln messen lassen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: UiState,
    viewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val prefs = ui.prefs

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.setScreen(Screen.BROWSER) }) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            SectionCard(stringResource(R.string.settings_engine_title)) {
                OptionRow(
                    selected = prefs.engineMode == EngineMode.AUTO,
                    label = stringResource(R.string.settings_engine_auto),
                    onClick = { viewModel.setEngineMode(EngineMode.AUTO) },
                )
                OptionRow(
                    selected = prefs.engineMode == EngineMode.WEBVIEW,
                    label = stringResource(R.string.settings_engine_webview),
                    onClick = { viewModel.setEngineMode(EngineMode.WEBVIEW) },
                )
                OptionRow(
                    selected = prefs.engineMode == EngineMode.CUSTOM_TABS,
                    label = stringResource(R.string.settings_engine_custom_tabs),
                    onClick = { viewModel.setEngineMode(EngineMode.CUSTOM_TABS) },
                )
                Text(
                    "Aktiv: ${ui.engine.name}  ·  Chromium/WebView ${ui.webViewVersion.ifBlank { "unbekannt" }}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionCard(stringResource(R.string.settings_cache_title)) {
                Text(
                    stringResource(R.string.settings_cache_usage, CacheStats.humanBytes(ui.cacheBytes)) +
                        "  ·  ${ui.cacheEntries} Einträge",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_cache_size) + ": ${prefs.cacheLimitMb} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = prefs.cacheLimitMb.toFloat(),
                    onValueChange = { viewModel.setCacheLimitMb(it.toInt()) },
                    valueRange = 64f..1024f,
                    steps = 15,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.clearCache() }) {
                        Text(stringResource(R.string.settings_cache_clear))
                    }
                    TextButton(onClick = { viewModel.verifyCacheIntegrity() }) {
                        Text("Integrität prüfen")
                    }
                }
                Text(
                    "Cache-First gilt für gehashte statische Assets (JS/CSS/Fonts/Bilder). " +
                        "Hauptdokumente und API-Aufrufe werden nie gecacht.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(stringResource(R.string.settings_rules_title)) {
                OptionRow(
                    selected = prefs.ruleMode == RuleMode.OFF,
                    label = stringResource(R.string.settings_rules_mode_off),
                    onClick = { viewModel.setRuleMode(RuleMode.OFF) },
                )
                OptionRow(
                    selected = prefs.ruleMode == RuleMode.LOG_ONLY,
                    label = stringResource(R.string.settings_rules_mode_log),
                    onClick = { viewModel.setRuleMode(RuleMode.LOG_ONLY) },
                )
                OptionRow(
                    selected = prefs.ruleMode == RuleMode.ENFORCE,
                    label = stringResource(R.string.settings_rules_mode_enforce),
                    onClick = { viewModel.setRuleMode(RuleMode.ENFORCE) },
                )
                Text(
                    "»Nur protokollieren« = Cache aktiv, Telemetrie-Blocker passiv. " +
                        "Erst auf »Anwenden« schalten, wenn das Request-Log ausgewertet ist.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(stringResource(R.string.settings_display_title)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_dark_mode), Modifier.weight(1f))
                    listOf(DarkMode.SYSTEM, DarkMode.ON, DarkMode.OFF).forEach { mode ->
                        val label = when (mode) {
                            DarkMode.SYSTEM -> stringResource(R.string.settings_dark_system)
                            DarkMode.ON -> stringResource(R.string.settings_dark_on)
                            DarkMode.OFF -> stringResource(R.string.settings_dark_off)
                        }
                        TextButton(onClick = { viewModel.setDarkMode(mode) }) {
                            Text(
                                label,
                                color = if (prefs.darkMode == mode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_web_darkening),
                    checked = prefs.webDarkening,
                    onCheckedChange = { viewModel.setWebDarkening(it) },
                )
                HorizontalDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_images_off),
                    checked = prefs.imagesOff,
                    onCheckedChange = { viewModel.setImagesOff(it) },
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.settings_text_zoom, prefs.textZoom),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = prefs.textZoom.toFloat(),
                    onValueChange = { viewModel.setTextZoom(it.toInt()) },
                    valueRange = 70f..160f,
                    steps = 8,
                )
            }

            SectionCard(stringResource(R.string.settings_start_title)) {
                Text("Start-URL", style = MaterialTheme.typography.bodyMedium)
                Text(prefs.startUrl, style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    TextButton(onClick = { viewModel.setStartUrl(ConsoleUrls.DASHBOARD) }) {
                        Text(stringResource(R.string.quick_home))
                    }
                    TextButton(onClick = { viewModel.setStartUrl(ConsoleUrls.COMPUTE) }) {
                        Text(stringResource(R.string.quick_compute))
                    }
                    TextButton(onClick = { viewModel.setStartUrl(ConsoleUrls.LOGS) }) {
                        Text(stringResource(R.string.quick_logs))
                    }
                }
                HorizontalDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_links_in_app),
                    checked = prefs.keepLinksInApp,
                    onCheckedChange = { viewModel.setKeepLinksInApp(it) },
                )
                Text(
                    "Externe Links (Doku, GitHub …) öffnen in einem In-App-Tab. Die App verlässt " +
                        "sich nur, wenn du es ausdrücklich bestätigst.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(stringResource(R.string.settings_about_title)) {
                Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodySmall)
                Text(
                    "Keine Telemetrie, kein Crash-Reporting, allowBackup=false. " +
                        "Inoffizielles Privat-Projekt, keine Google-Produktbeziehung.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
