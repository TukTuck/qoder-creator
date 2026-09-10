package app.consolepocket.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.consolepocket.R
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.JsDialogUi
import app.consolepocket.ui.UiState

/**
 * Saemtliche Dialoge der App. Alle sind Compose-Elemente in derselben Activity:
 * kein `AlertDialog` des Systems aus dem WebView, kein externes Fenster (PLAN Kapitel 7).
 */
@Composable
fun GlobalDialogs(ui: UiState, viewModel: AppViewModel) {

    // ---- JS-Dialoge (alert / confirm / prompt) -------------------------------
    ui.jsDialog?.let { dialog ->
        JsDialog(
            dialog = dialog,
            onResolve = { confirmed, input -> viewModel.resolveJsDialog(confirmed, input) },
        )
    }

    // ---- Aktion wuerde die App verlassen (mailto:/tel:/intent:) --------------
    ui.externalAction?.let { action ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveExternalAction(false) },
            title = { Text(stringResource(R.string.external_confirm_title)) },
            text = {
                Text(stringResource(R.string.external_confirm_body, action.label) + "\n\n" + action.url)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveExternalAction(true) }) {
                    Text(stringResource(R.string.external_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveExternalAction(false) }) {
                    Text(stringResource(R.string.external_confirm_cancel))
                }
            },
        )
    }

    // ---- Google blockiert den WebView-Login -> sicheren Modus anbieten -------
    if (ui.loginBlocked) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLoginBlocked() },
            title = { Text(stringResource(R.string.state_login_blocked_title)) },
            text = { Text(stringResource(R.string.state_login_blocked_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.activateSafeMode() }) {
                    Text(stringResource(R.string.state_login_blocked_primary))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLoginBlocked() }) {
                    Text(stringResource(R.string.state_login_blocked_secondary))
                }
            },
        )
    }

    // ---- SSL-Fehler: niemals fortsetzen, nur informieren ---------------------
    ui.sslError?.let { url ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeSslError() },
            title = { Text("Zertifikatsfehler") },
            text = { Text("Die Verbindung zu $url wurde abgebrochen. Das Zertifikat konnte nicht geprüft werden.") },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeSslError() }) { Text("OK") }
            },
        )
    }

    ui.loadError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeLoadError() },
            title = { Text(stringResource(R.string.state_error_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeLoadError() }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun JsDialog(dialog: JsDialogUi, onResolve: (Boolean, String?) -> Unit) {
    var input by remember(dialog) { mutableStateOf(dialog.defaultText) }

    AlertDialog(
        onDismissRequest = { onResolve(false, null) },
        title = { Text("Meldung der Console") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
                if (dialog.kind == JsDialogUi.Kind.PROMPT) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true, input) }) {
                Text(if (dialog.kind == JsDialogUi.Kind.ALERT) "OK" else "Bestätigen")
            }
        },
        dismissButton = if (dialog.kind == JsDialogUi.Kind.ALERT) {
            null
        } else {
            {
                TextButton(onClick = { onResolve(false, null) }) { Text("Abbrechen") }
            }
        },
    )
}
