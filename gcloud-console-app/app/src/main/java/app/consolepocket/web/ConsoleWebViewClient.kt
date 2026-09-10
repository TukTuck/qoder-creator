package app.consolepocket.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import app.consolepocket.BuildConfig
import app.consolepocket.cache.RequestPipeline
import app.consolepocket.cache.WebResourceResponses
import app.consolepocket.metrics.CacheStats

/**
 * Navigation + Request-Interception der Console (PLAN L1/L3, Kapitel 7).
 *
 * Prinzipien:
 *  - Hauptdokumente werden NIE abgefangen (personalisiert, muss frisch sein).
 *  - SSL-Fehler werden immer abgebrochen (kein `handler.proceed()`).
 *  - Alles, was die App verlassen wuerde, geht als Rueckfrage an die UI.
 *  - Bei einem Defekt im Interceptor liefern wir `null` -> Chromium laedt selbst.
 *    Lieber langsamer als kaputt.
 */
class ConsoleWebViewClient(
    private val tabId: String,
    private val pool: WebViewPool,
    private val hostProvider: () -> WebViewHost?,
    private val assetLoader: WebViewAssetLoader,
    private val pipeline: RequestPipeline,
    private val stats: CacheStats,
) : WebViewClient() {

    private val policy = NavigationPolicy(keepLinksInApp = true)
    private var navigationStartedAt = 0L

    // ------------------------------------------------------------ Interception

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val uri = request.url ?: return null
        val url = uri.toString()

        // 1) Lokale Shell aus assets/shell (https://appassets.androidplatform.net/shell/...)
        runCatching { assetLoader.shouldInterceptRequest(uri) }.getOrNull()?.let { return it }

        // 2) Hauptdokument, POST/XHR & Co: prinzipiell durchreichen
        if (request.isForMainFrame) return null

        val method = request.method ?: "GET"
        val headers = runCatching { request.requestHeaders }.getOrNull().orEmpty()

        return try {
            when (val outcome = pipeline.handle(
                RequestPipeline.Incoming(method = method, url = url, isMainFrame = false, requestHeaders = headers),
            )) {
                RequestPipeline.Outcome.Pass -> null
                is RequestPipeline.Outcome.Block -> WebResourceResponses.block(outcome.status)
                is RequestPipeline.Outcome.Serve -> WebResourceResponses.serve(outcome)
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Interceptor-Fehler fuer $url", t)
            null
        }
    }

    // ------------------------------------------------------------ Navigation

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url?.toString() ?: return false
        val host = hostProvider()

        // Anfragen der lokalen Shell nie umleiten
        if (url.startsWith("https://appassets.androidplatform.net/")) return false

        return when (val decision = policy.decide(url, request.isForMainFrame, isNewWindow = false)) {
            NavigationPolicy.Decision.LoadHere -> false // Chromium laedt selbst (History bleibt korrekt)

            NavigationPolicy.Decision.LoadInNewTab -> {
                val created = host?.let { pool.createChildTab(it) }
                if (created == null) {
                    // Pool voll: im aktuellen View laden statt ein Fenster zu erzwingen
                    false
                } else {
                    created.second.loadUrl(url)
                    true
                }
            }

            is NavigationPolicy.Decision.AskUser -> {
                host?.onExternalAction(tabId, decision.url, decision.scheme, decision.label)
                true
            }

            NavigationPolicy.Decision.Ignore -> true
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        navigationStartedAt = System.currentTimeMillis()
        hostProvider()?.onPageStarted(tabId, url)
        hostProvider()?.onProgress(tabId, 0)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        // Erster sichtbarer Inhalt: hier darf das Skeleton/der Splash ausgeblendet werden.
        hostProvider()?.onPageCommitVisible(tabId, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        val duration = if (navigationStartedAt > 0) System.currentTimeMillis() - navigationStartedAt else 0
        if (duration > 0) stats.lastPageLoadMs = duration
        hostProvider()?.onPageFinished(tabId, url)
        hostProvider()?.onHistory(tabId, view.canGoBack(), view.canGoForward())
        hostProvider()?.onUrlSettled(tabId, url)

        if (UserAgentProvider.looksLikeOAuthBlock(url, view.title)) {
            hostProvider()?.onLoginBlocked(tabId, url)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (url == null) return
        hostProvider()?.onHistory(tabId, view.canGoBack(), view.canGoForward())
        hostProvider()?.onUrlSettled(tabId, url)
        if (UserAgentProvider.looksLikeOAuthBlock(url, view.title)) {
            hostProvider()?.onLoginBlocked(tabId, url)
        }
    }

    // ------------------------------------------------------------ Fehler

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        hostProvider()?.onLoadError(
            tabId,
            request.url?.toString().orEmpty(),
            error.errorCode,
            error.description?.toString().orEmpty(),
        )
    }

    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: SslError) {
        // Niemals proceed(): Zertifikatsfehler = Abbruch + Hinweis in der App.
        handler.cancel()
        hostProvider()?.onSslError(tabId, error.url.orEmpty())
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: android.webkit.HttpAuthHandler,
        host: String,
        realm: String,
    ) {
        // Keine Credentials speichern; Nutzer wird in-app informiert.
        handler.cancel()
        hostProvider()?.onMessage(tabId, "HTTP-Authentifizierung abgelehnt ($host/$realm)")
    }

    override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
        dontResend.sendToTarget()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // true = App lebt weiter; wir bauen den Tab neu auf (PLAN Kapitel 7).
        pool.close(tabId)
        hostProvider()?.onRendererGone(tabId)
        return true
    }

    companion object {
        private const val TAG = "ConsoleWebViewClient"
    }
}
