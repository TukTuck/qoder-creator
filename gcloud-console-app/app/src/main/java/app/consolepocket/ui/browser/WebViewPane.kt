package app.consolepocket.ui.browser

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.consolepocket.ConsolePocketApp
import app.consolepocket.ui.AppViewModel
import app.consolepocket.ui.TabUi

/**
 * Compose-Anbindung eines gepoolten WebView.
 *
 * Die Instanz kommt aus dem Application-scoped [app.consolepocket.web.WebViewPool] und wird
 * daher NICHT bei jeder Recomposition neu erzeugt (das waere der teuerste Fehler bei
 * WebView-in-Compose). Beim Verlassen der Composition wird sie nur gelöst, nicht zerstört:
 * Der Seitenzustand bleibt erhalten, zurueckkommen ist sofort (PLAN L0/L5).
 */
@Composable
fun WebViewPane(
    tab: TabUi,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pool = remember { (context.applicationContext as ConsolePocketApp).graph.webViewPool }

    val webView: WebView = remember(tab.id) {
        pool.acquire(tab.id, context, viewModel)
    }

    DisposableEffect(tab.id) {
        onDispose { pool.release(tab.id) }
    }

    // Erstinhalt nur laden, wenn der WebView noch leer ist (sonst: kein Reload bei Tab-Wechsel!)
    LaunchedEffect(tab.id) {
        if (webView.url == null && tab.initialUrl.isNotBlank()) {
            webView.loadUrl(tab.initialUrl)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}
