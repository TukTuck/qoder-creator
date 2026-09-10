package app.consolepocket.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.consolepocket.R
import app.consolepocket.metrics.CacheStats
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.Screen
import app.consolepocket.ui.UiState

/**
 * Messwerte und Request-Log (PLAN Kapitel 9). Ohne diesen Screen ist "schneller" nur ein
 * Gefuehl: Hier stehen Hit-Rate, gesparte Bytes und die letzte Seitenladezeit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    ui: UiState,
    viewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val stats = ui.stats

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.setScreen(Screen.BROWSER) }) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Kernzahlen", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider()
                        MetricRow("Cache-Trefferquote", "${stats.hitRatePercent} %")
                        MetricRow("Anfragen gesamt", stats.total.toString())
                        MetricRow("aus Cache", stats.cacheHits.toString())
                        MetricRow("aus Netzwerk", stats.networkFetches.toString())
                        MetricRow("geblockt", stats.blocked.toString())
                        MetricRow("durchgereicht", stats.ignored.toString())
                        MetricRow("Bytes aus Cache", CacheStats.humanBytes(stats.bytesFromCache))
                        MetricRow("Bytes aus Netzwerk", CacheStats.humanBytes(stats.bytesFromNetwork))
                        MetricRow("Letzte Seitenladezeit", "${stats.lastPageLoadMs} ms")
                        MetricRow(
                            "Cache auf Platte",
                            "${CacheStats.humanBytes(ui.cacheBytes)} · ${ui.cacheEntries} Einträge",
                        )
                        MetricRow("Engine", ui.engine.name)
                        MetricRow("Regel-Modus", ui.prefs.ruleMode.name)
                        MetricRow(
                            stringResource(R.string.debug_webview_version, ui.webViewVersion.ifBlank { "?" }),
                            "",
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.debug_interceptor_toggle),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = viewModel.interceptorEnabled(),
                                onCheckedChange = { viewModel.setInterceptorEnabled(it) },
                            )
                        }
                        Text(
                            "Aus = Referenzmessung mit nacktem Chromium-Cache. Damit lassen sich die " +
                                "Gewinne der eigenen Cache-Schicht beziffern.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.clearLog() }) {
                                Text(stringResource(R.string.debug_clear_log))
                            }
                            TextButton(onClick = { viewModel.clearCache() }) {
                                Text(stringResource(R.string.settings_cache_clear))
                            }
                            TextButton(onClick = { viewModel.verifyCacheIntegrity() }) {
                                Text("Integrität prüfen")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Letzte Requests",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(ui.statsLog, key = { it.timeMs.toString() + it.url + it.source }) { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LogRow(entry: CacheStats.RequestLogEntry) {
    val badgeColor = when (entry.source) {
        CacheStats.SOURCE_CACHE -> Color(0xFF1E8E3E)
        CacheStats.SOURCE_NETWORK -> Color(0xFFB06000)
        CacheStats.SOURCE_BLOCKED -> Color(0xFFC5221F)
        else -> Color(0xFF5F6368)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.source.uppercase(),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(entry.policy, style = MaterialTheme.typography.labelSmall)
            entry.ruleId?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "${entry.durationMs} ms · ${CacheStats.humanBytes(entry.bytes)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Text(
            entry.url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
