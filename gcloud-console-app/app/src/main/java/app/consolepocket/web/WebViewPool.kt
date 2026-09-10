package app.consolepocket.web

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import app.consolepocket.cache.RequestPipeline
import app.consolepocket.downloads.ConsoleDownloadListener
import app.consolepocket.downloads.DownloadStore
import app.consolepocket.metrics.CacheStats
import java.util.UUID

/**
 * Haelt die WebView-Instanzen der In-App-Tabs (PLAN L0 + Kapitel 7).
 *
 * Warum ein Pool und nicht `remember { WebView(ctx) }` in Compose:
 *  - der erste `new WebView()` ist extrem teuer (Provider-Load + Renderer-Prozess) -> vorwärmen
 *  - Instanzen ueberleben Activity-Recreation und Tab-Wechsel ohne Reload
 *  - `target=_blank`/`window.open()` bekommt eine Instanz IN DIESER APP statt eines Fensters
 *
 * Alle Methoden, die WebViews erzeugen/zerstoeren, muessen auf dem Main-Thread laufen.
 */
class WebViewPool(
    private val application: Application,
    private val configurer: WebViewConfigurer,
    private val settingsProvider: () -> WebViewConfigurer.Settings,
    private val pipeline: RequestPipeline,
    private val stats: CacheStats,
    private val downloads: DownloadStore,
    private val maxLiveTabs: Int = 3,
    private val maxWarm: Int = 1,
) {

    private class Entry(
        val tabId: String,
        val webView: WebView,
        var host: WebViewHost?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<String, Entry>()
    private val warm = ArrayDeque<WebView>()

    /** Lokale Shell (assets/shell) ueber https://appassets.androidplatform.net (secure context). */
    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/shell/", WebViewAssetLoader.AssetsPathHandler(application))
        .build()

    // ------------------------------------------------------------------ lifecycle

    /** L0: WebView vorwärmen, bevor die UI ihn braucht. */
    fun prewarm(count: Int = 1) {
        runOnMain {
            repeat(count) {
                if (warm.size < maxWarm) {
                    runCatching {
                        warm.addLast(configurer.create(application).also { configurer.applySettings(it, settingsProvider()) })
                    }
                }
            }
        }
    }

    /** Eine Instanz fuer einen Tab holen (oder die vorhandene zurueckgeben) und an die UI binden. */
    fun acquire(tabId: String, uiContext: Context, host: WebViewHost): WebView {
        requireMainThread()
        val existing = entries[tabId]
        if (existing != null) {
            existing.host = host
            // Erst loesen (falls die View noch irgendwo haengt), dann an die neue UI binden:
            // verhindert "child already has a parent" nach Activity-Recreation/Tab-Wechsel.
            configurer.detach(existing.webView)
            configurer.attach(existing.webView, uiContext)
            configurer.applySettings(existing.webView, settingsProvider())
            touch(tabId)
            return existing.webView
        }
        val view = warm.removeFirstOrNull() ?: configurer.create(application)
        configurer.attach(view, uiContext)
        configurer.applySettings(view, settingsProvider())
        bindClients(view, tabId) { entries[tabId]?.host }
        view.setDownloadListener(ConsoleDownloadListener(tabId, downloads, host))
        entries[tabId] = Entry(tabId, view, host)
        touch(tabId)
        evictOldestIfFull()
        return view
    }

    /**
     * Erzeugt einen neuen Tab fuer `onCreateWindow` (target=_blank, window.open).
     * Die UI haengt den Tab danach ein; geladen wird ueber WebView.WebViewTransport.
     */
    fun createChildTab(host: WebViewHost): Pair<String, WebView>? {
        requireMainThread()
        if (entries.size >= maxLiveTabs) return null
        val tabId = newTabId()
        val view = warm.removeFirstOrNull() ?: configurer.create(application)
        configurer.applySettings(view, settingsProvider())
        bindClients(view, tabId) { entries[tabId]?.host }
        view.setDownloadListener(ConsoleDownloadListener(tabId, downloads, host))
        entries[tabId] = Entry(tabId, view, host)
        host.onTabCreated(tabId)
        return tabId to view
    }

    fun get(tabId: String): WebView? = entries[tabId]?.webView

    fun tabIds(): List<String> = entries.keys.toList()

    fun activeTabCount(): Int = entries.size

    /** WebView aus der UI entfernen, aber warm halten (kein Reload beim Zurueckkommen). */
    fun release(tabId: String) {
        requireMainThread()
        val entry = entries[tabId] ?: return
        configurer.detach(entry.webView)
    }

    /** Tab endgueltig schliessen. */
    fun close(tabId: String) {
        requireMainThread()
        val entry = entries.remove(tabId) ?: return
        destroyQuietly(entry.webView)
    }

    fun destroyAll() {
        runOnMain {
            entries.values.forEach { destroyQuietly(it.webView) }
            entries.clear()
            warm.forEach { destroyQuietly(it) }
            warm.clear()
        }
    }

    fun reload(tabId: String, bypassCache: Boolean) {
        runOnMain {
            val view = entries[tabId]?.webView ?: return@runOnMain
            if (bypassCache) {
                pipeline.bypassCacheFor(10_000)
                val url = view.url
                if (url != null) view.loadUrl(url) else view.reload()
            } else {
                view.reload()
            }
        }
    }

    fun load(tabId: String, url: String) {
        runOnMain { entries[tabId]?.webView?.loadUrl(url) }
    }

    fun goBack(tabId: String) = runOnMain { entries[tabId]?.webView?.takeIf { it.canGoBack() }?.goBack() }

    fun goForward(tabId: String) = runOnMain { entries[tabId]?.webView?.takeIf { it.canGoForward() }?.goForward() }

    fun stop(tabId: String) = runOnMain { entries[tabId]?.webView?.stopLoading() }

    /** Einstellungen haben sich geaendert (Settings-Screen) -> auf alle Tabs anwenden. */
    fun applySettingsEverywhere() {
        runOnMain {
            val settings = settingsProvider()
            entries.values.forEach { configurer.applySettings(it.webView, settings) }
            warm.forEach { configurer.applySettings(it, settings) }
        }
    }

    /** Cookies sofort auf Platte schreiben (L5: Session ueberlebt App-Kill). */
    fun persistCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        runOnMain {
            warm.forEach { destroyQuietly(it) }
            warm.clear()
            // Tabs behalten wir (Nutzerversprechen "kein Reload"); nur Warm-Instanzen gehen.
        }
    }

    // ------------------------------------------------------------------ internals

    private fun bindClients(view: WebView, tabId: String, hostProvider: () -> WebViewHost?) {
        view.webViewClient = ConsoleWebViewClient(
            tabId = tabId,
            pool = this,
            hostProvider = hostProvider,
            assetLoader = assetLoader,
            pipeline = pipeline,
            stats = stats,
        )
        view.webChromeClient = ConsoleWebChromeClient(
            tabId = tabId,
            hostProvider = hostProvider,
            poolProvider = { this },
        )
    }

    private fun touch(tabId: String) {
        val entry = entries.remove(tabId) ?: return
        entries[tabId] = entry // LRU: zuletzt benutzt ans Ende
    }

    private fun evictOldestIfFull() {
        while (entries.size > maxLiveTabs) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
            destroyQuietly(oldest.value.webView)
        }
    }

    private fun destroyQuietly(view: WebView) {
        runCatching {
            configurer.detach(view)
            view.stopLoading()
            view.webViewClient = android.webkit.WebViewClient()
            view.webChromeClient = android.webkit.WebChromeClient()
            view.destroy()
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebViewPool muss auf dem Main-Thread verwendet werden"
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        fun newTabId(): String = UUID.randomUUID().toString().take(8)
    }
}
