package app.consolepocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.consolepocket.ui.AppRoot
import app.consolepocket.ui.AppViewModel

/**
 * DIE eine Activity der App (singleTask, breite configChanges).
 *
 * - Keine zweite Activity, kein zweiter Task, kein Dialog-Fenster einer anderen App (A3).
 * - App-Shortcuts und die eigene OPEN_URL-Action landen hier und oeffnen einen In-App-Tab.
 * - onPause/onStop sichern Cookies, Cache-Journal und die gelernte Prefetch-Liste (L5/L4).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Das Theme wird in AppRoot gesetzt, weil dort die Dark-Mode-Einstellung bekannt ist.
        setContent {
            AppRoot(viewModel)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_URL) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        viewModel.openUrl(url)
    }

    override fun onPause() {
        super.onPause()
        graph()?.webViewPool?.persistCookies()
    }

    override fun onStop() {
        super.onStop()
        graph()?.onAppBackgrounded()
    }

    private fun graph() = (application as? ConsolePocketApp)?.graph

    companion object {
        const val ACTION_OPEN_URL = "app.consolepocket.action.OPEN_URL"
        const val EXTRA_URL = "app.consolepocket.extra.URL"
    }
}
