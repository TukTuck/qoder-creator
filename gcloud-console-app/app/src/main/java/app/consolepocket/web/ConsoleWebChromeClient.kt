package app.consolepocket.web

import android.net.Uri
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import app.consolepocket.BuildConfig

/**
 * Fenster-/Dialog-Policy (PLAN Kapitel 7):
 *  - `onCreateWindow` erzeugt einen **In-App-Tab** aus dem WebViewPool, kein Systemfenster
 *  - JS-Dialoge werden zu Compose-Dialogen in derselben Activity
 *  - Datei-Picker laeuft ueber einen ActivityResultLauncher derselben Activity
 *  - Berechtigungsanfragen (Kamera/Mikro/Geolocation) werden in-app erklaert und abgelehnt,
 *    solange die Console sie nicht nachweislich braucht (Backlog M2/M6: Passkeys testen)
 */
class ConsoleWebChromeClient(
    private val tabId: String,
    private val hostProvider: () -> WebViewHost?,
    private val poolProvider: () -> WebViewPool?,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        hostProvider()?.onProgress(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (title.isNullOrBlank()) return
        hostProvider()?.onTitle(tabId, title)
        if (UserAgentProvider.looksLikeOAuthBlock(view.url, title)) {
            hostProvider()?.onLoginBlocked(tabId, view.url.orEmpty())
        }
    }

    // ------------------------------------------------------------ Fenster

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        // Ohne Nutzergeste: gar nichts oeffnen (Popups unterbinden).
        if (!isUserGesture) return false
        val host = hostProvider() ?: return false
        val pool = poolProvider() ?: return false
        val created = pool.createChildTab(host) ?: return false

        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = created.second
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) {
        // window.close() aus der Seite: Tab in-app schliessen, kein Fensterverhalten.
        hostProvider()?.onMessage(tabId, "Tab wurde von der Seite geschlossen")
    }

    // ------------------------------------------------------------ JS-Dialoge

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        hostProvider()?.onJsAlert(tabId, url, message, result) ?: result.cancel()
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean {
        hostProvider()?.onJsConfirm(tabId, url, message, result) ?: result.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean {
        hostProvider()?.onJsPrompt(tabId, url, message, defaultValue, result) ?: result.cancel()
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
        // Kein System-Dialog: einfach bestaetigen, damit die Navigation nicht haengt.
        result.confirm()
        return true
    }

    // ------------------------------------------------------------ Datei-Picker

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        val host = hostProvider()
        if (host == null) {
            filePathCallback.onReceiveValue(null) // sonst haengt der WebView dauerhaft
            return true
        }
        host.onFileChooser(
            tabId,
            filePathCallback,
            fileChooserParams.acceptTypes ?: arrayOf("*/*"),
            fileChooserParams.isCaptureEnabled,
        )
        return true
    }

    // ------------------------------------------------------------ Berechtigungen

    override fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources.joinToString(", ")
        hostProvider()?.onPermissionBlocked(tabId, resources)
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        hostProvider()?.onMessage(tabId, "Standortanfrage von $origin abgelehnt")
        callback.invoke(origin, false, false)
    }

    // ------------------------------------------------------------ Debug

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "ConsoleJS",
                "${consoleMessage.messageLevel()} ${consoleMessage.message()} " +
                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
            )
        }
        return true
    }
}
