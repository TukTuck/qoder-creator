package app.consolepocket.customtabs

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.graphics.toColor

/**
 * Policy-sicherer Fallback-Modus (PLAN Kapitel 6, Option O2 / ADR-001 Gate B2).
 *
 * Ein Custom Tab laeuft IM TASK dieser App: die Back-Taste kehrt zur App zurueck und in den
 * Recents erscheint kein eigener Browser-Eintrag. Er teilt das Cookie-Jar von Chrome, weshalb
 * die Google-Anmeldung funktioniert - dafuer haben wir keinen Zugriff auf einzelne Requests
 * (kein eigener Cache).
 *
 * Beschleunigung hier: `warmup()` + `mayLaunchUrl()` - Chrome rendert die Console im
 * Hintergrund vor, bevor der Tab sichtbar wird.
 */
class CustomTabsLauncher(private val context: Context) {

    private var client: CustomTabsClient? = null
    private var session: CustomTabsSession? = null

    private val connection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: android.content.ComponentName, newClient: CustomTabsClient) {
            client = newClient
            runCatching {
                newClient.warmup(0)
                session = newClient.newSession(null)
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            client = null
            session = null
        }
    }

    fun providerPackage(): String? = runCatching {
        CustomTabsClient.getPackageName(context, CANDIDATE_PACKAGES)
    }.getOrNull()

    fun isAvailable(): Boolean = providerPackage() != null

    /** Service binden und Renderer vorwaermen (beim App-Start im Hintergrund aufrufen). */
    fun bind() {
        val pkg = providerPackage() ?: return
        runCatching { CustomTabsClient.bindCustomTabsService(context, pkg, connection) }
    }

    fun unbind() {
        runCatching { context.applicationContext.unbindService(connection) }
        client = null
        session = null
    }

    /** URL vorab ankuendigen -> Chrome laedt und rendert im Hintergrund. */
    fun prelaunch(url: String) {
        val s = session ?: return
        runCatching { s.mayLaunchUrl(Uri.parse(url), null, null) }
    }

    fun open(url: String) {
        val intent = CustomTabsIntent.Builder(session)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setDefaultColorSchemeParams(
                androidx.browser.customtabs.CustomTabsColorSchemeParams.Builder()
                    .setToolbarColor(BRAND.toColor())
                    .build(),
            )
            .build()
        intent.intent.`package` = providerPackage()
        // Kein FLAG_ACTIVITY_NEW_TASK: Der Custom Tab soll im Task DIESER App laufen
        // (Back-Taste kehrt zurueck, kein eigener Recents-Eintrag).
        runCatching { intent.launchUrl(context, Uri.parse(url)) }
    }

    companion object {
        private val CANDIDATE_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.chromium.chrome",
            "com.brave.browser",
            "com.microsoft.emmx",
        )
        private const val BRAND = "#0B5C63"
    }
}
