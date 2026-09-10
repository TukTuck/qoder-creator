package app.consolepocket.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.consolepocket.R
import app.consolepocket.model.ConsoleUrls
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.Screen
import app.consolepocket.ui.UiState

/**
 * Hauptbildschirm: Tab-Strip, Fortschritt, WebView, Schnellzugriffe.
 *
 * Alles bleibt in dieser Activity: neue Tabs sind Compose-Zustaende, kein Fenster
 * (PLAN Kapitel 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    ui: UiState,
    viewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val activeTab = ui.activeTab

    // Back: erst WebView-History, dann verlaesst die App (System-Back) in den Hintergrund.
    BackHandler(enabled = activeTab?.canGoBack == true) { viewModel.goBack() }

    LaunchedEffect(ui.rendererRestarted) {
        if (ui.rendererRestarted) {
            snackbarHostState.showSnackbar("Web-Ansicht wurde neu gestartet")
            viewModel.consumeRendererRestart()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val fallbackTitle = stringResource(R.string.app_name)
                        val tabTitle = activeTab?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
                        Column {
                            Text(
                                text = tabTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val tabUrl = activeTab?.url.orEmpty()
                            if (tabUrl.isNotBlank()) {
                                Text(
                                    text = tabUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (activeTab?.canGoBack == true) {
                            IconButton(onClick = { viewModel.goBack() }) {
                                Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                            }
                        } else {
                            IconButton(onClick = { viewModel.openUrl(ConsoleUrls.DASHBOARD) }) {
                                Icon(Icons.Filled.Home, stringResource(R.string.quick_home))
                            }
                        }
                    },
                    actions = {
                        if (ui.isLoading) {
                            IconButton(onClick = { viewModel.stopLoading() }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.action_stop))
                            }
                        } else {
                            IconButton(onClick = { viewModel.reload() }) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.action_reload))
                            }
                        }
                        IconButton(onClick = { viewModel.createTab() }) {
                            Icon(Icons.Filled.Add, stringResource(R.string.action_new_tab))
                        }
                        BrowserOverflowMenu(ui, viewModel)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )

                if (ui.tabs.size > 1) {
                    TabStrip(ui, viewModel)
                }

                if (ui.isLoading) {
                    LinearProgressIndicator(
                        progress = { (ui.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        bottomBar = {
            QuickLinkBar(
                onQuickLink = { url -> viewModel.openUrl(url) },
                onMore = { viewModel.setScreen(Screen.SETTINGS) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (activeTab == null) {
                EmptyBrowserState(onOpen = { viewModel.openUrl(ConsoleUrls.DASHBOARD) })
            } else {
                WebViewPane(
                    tab = activeTab,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // L3: sofort sichtbares Skeleton, bis die echte Seite ersten Inhalt malt
            if (ui.showSkeleton) {
                SkeletonOverlay(Modifier.fillMaxSize())
            }

            Column(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ui.offline) {
                    OfflineBanner(
                        onShowShell = { viewModel.openUrl(ConsoleUrls.SHELL_OFFLINE) },
                        onDismiss = { viewModel.showMessage("Offline-Hinweis ausgeblendet") },
                    )
                }
                if (ui.webViewTooOld) {
                    OldWebViewBanner()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserOverflowMenu(ui: UiState, viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.action_settings))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_reload_no_cache)) },
                onClick = {
                    expanded = false
                    viewModel.reload(bypassCache = true)
                },
            )
            if (ui.activeTab?.canGoForward == true) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_forward)) },
                    onClick = {
                        expanded = false
                        viewModel.goForward()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share_url)) },
                onClick = {
                    expanded = false
                    viewModel.copyCurrentUrl()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_custom_tab)) },
                onClick = {
                    expanded = false
                    viewModel.activateSafeMode()
                    viewModel.openUrl(ui.activeTab?.url ?: ConsoleUrls.DASHBOARD)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_downloads)) },
                leadingIcon = { Icon(Icons.Filled.List, null) },
                onClick = {
                    expanded = false
                    viewModel.setScreen(Screen.DOWNLOADS)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_debug)) },
                onClick = {
                    expanded = false
                    viewModel.setScreen(Screen.DEBUG)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_settings)) },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = {
                    expanded = false
                    viewModel.setScreen(Screen.SETTINGS)
                },
            )
        }
    }
}

@Composable
private fun TabStrip(ui: UiState, viewModel: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.tabs.forEach { tab ->
            val selected = tab.id == ui.activeTabId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { viewModel.selectTab(tab.id) }) {
                    Text(
                        text = tab.title.ifBlank { tab.url.substringAfterLast('/') },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { viewModel.closeTab(tab.id) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_close_tab), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun QuickLinkBar(onQuickLink: (String) -> Unit, onMore: () -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConsoleUrls.quickLinks.forEach { link ->
            AssistChip(
                onClick = { onQuickLink(link.url) },
                label = { Text(stringResource(link.labelRes), style = MaterialTheme.typography.labelMedium) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
        AssistChip(
            onClick = onMore,
            label = { Text("…") },
            leadingIcon = {
                Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp))
            },
        )
    }
}

@Composable
private fun OldWebViewBanner() {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.state_old_webview_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.state_old_webview_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EmptyBrowserState(onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kein Tab offen", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onOpen, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.quick_home) + " öffnen")
        }
    }
}
