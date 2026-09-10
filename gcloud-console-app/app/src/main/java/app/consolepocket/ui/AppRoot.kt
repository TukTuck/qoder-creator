package app.consolepocket.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.consolepocket.model.ActiveEngine
import app.consolepocket.ui.browser.BrowserScreen
import app.consolepocket.ui.browser.CustomTabsPane
import app.consolepocket.ui.components.GlobalDialogs
import app.consolepocket.ui.debug.DebugScreen
import app.consolepocket.ui.downloads.DownloadsScreen
import app.consolepocket.ui.settings.SettingsScreen
import app.consolepocket.ui.theme.ConsolePocketTheme

/**
 * Wurzel der UI: EIN Screen-Stack in EINER Activity.
 *
 * Alle Dialoge (JS-alert/confirm/prompt, "App verlassen?", Google-Login-Blockade) sind
 * Compose-Dialoge in derselben Activity - es wird kein System-/Browserfenster geoeffnet (A3).
 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    ConsolePocketTheme(darkMode = ui.prefs.darkMode) {

        // Datei-Uploads der Console: Picker der gleichen Activity, Rueckkehr in den WebView.
        val fileChooser by viewModel.fileChooser.collectAsStateWithLifecycle()
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris -> viewModel.onFilesPicked(uris.orEmpty()) }
        LaunchedEffect(fileChooser) {
            fileChooser?.let { picker.launch(it.mimeTypes) }
        }

        LaunchedEffect(ui.message) {
            ui.message?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.consumeMessage()
            }
        }

        // Back: aus Unter-Screens zurueck zum Browser, dort uebernimmt BrowserScreen (History).
        BackHandler(enabled = ui.screen != Screen.BROWSER) {
            viewModel.setScreen(Screen.BROWSER)
        }

        when (ui.screen) {
            Screen.BROWSER ->
                if (ui.engine == ActiveEngine.WEBVIEW) {
                    BrowserScreen(ui, viewModel, snackbarHostState)
                } else {
                    CustomTabsPane(ui, viewModel, snackbarHostState)
                }

            Screen.SETTINGS -> SettingsScreen(ui, viewModel, snackbarHostState)
            Screen.DOWNLOADS -> DownloadsScreen(ui, viewModel, snackbarHostState)
            Screen.DEBUG -> DebugScreen(ui, viewModel, snackbarHostState)
        }

        GlobalDialogs(ui, viewModel)
    }
}
