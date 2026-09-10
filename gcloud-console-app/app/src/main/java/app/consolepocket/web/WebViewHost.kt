package app.consolepocket.web

import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback

/**
 * Rueckkanaele der WebView-Clients in die App (ViewModel/UI).
 *
 * Alles landet in DERSELBEN Activity: keine Dialoge des Systems, keine zweiten Activities,
 * keine Intents nach aussen (Anforderung A3 / PLAN Kapitel 7).
 */
interface WebViewHost {

    fun onProgress(tabId: String, progress: Int)

    fun onPageStarted(tabId: String, url: String)

    fun onPageCommitVisible(tabId: String, url: String)

    fun onPageFinished(tabId: String, url: String)

    fun onTitle(tabId: String, title: String)

    fun onHistory(tabId: String, canGoBack: Boolean, canGoForward: Boolean)

    /** URL wurde geladen - zum Persistieren des Tab-Zustands (Schicht L5). */
    fun onUrlSettled(tabId: String, url: String)

    /**
     * Aktion wuerde die App verlassen (mailto:, tel:, intent:, ...).
     * Die UI fragt den Nutzer - es wird NICHT automatisch ausgelöst.
     */
    fun onExternalAction(tabId: String, url: String, scheme: String, label: String)

    /** Google blockiert die Anmeldung im eingebetteten WebView -> Engine-Fallback anbieten. */
    fun onLoginBlocked(tabId: String, url: String)

    fun onRendererGone(tabId: String)

    fun onLoadError(tabId: String, url: String, code: Int, description: String)

    fun onSslError(tabId: String, url: String)

    fun onJsAlert(tabId: String, url: String, message: String, result: JsResult)

    fun onJsConfirm(tabId: String, url: String, message: String, result: JsResult)

    fun onJsPrompt(tabId: String, url: String, message: String, defaultText: String, result: JsPromptResult)

    fun onFileChooser(
        tabId: String,
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        isCaptureEnabled: Boolean,
    )

    fun onPermissionBlocked(tabId: String, resource: String)

    /** Ein In-App-Tab wurde erzeugt (target=_blank / window.open) -> UI haengt ihn ein. */
    fun onTabCreated(tabId: String)

    fun onDownloadStarted(fileName: String)

    fun onMessage(tabId: String, message: String)
}
