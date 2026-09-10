package app.consolepocket.di

import android.app.Application
import app.consolepocket.BuildConfig
import app.consolepocket.cache.AndroidLocalAssets
import app.consolepocket.cache.DiskAssetCache
import app.consolepocket.cache.HttpAssetFetcher
import app.consolepocket.cache.Policy
import app.consolepocket.cache.RequestPipeline
import app.consolepocket.cache.RuleEngine
import app.consolepocket.cache.RuleSetParser
import app.consolepocket.customtabs.CustomTabsLauncher
import app.consolepocket.downloads.DownloadStore
import app.consolepocket.metrics.CacheStats
import app.consolepocket.model.EngineMode
import app.consolepocket.state.AppPrefs
import app.consolepocket.state.Prefs
import app.consolepocket.web.WebViewConfigurer
import app.consolepocket.web.WebViewPool
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manueller Application-Graph statt Hilt: weniger Build-Komplexitaet (kein KSP), und die
 * Abhaengigkeiten sind ueberschaubar. Ein Singleton pro Prozess, gehalten von
 * [app.consolepocket.ConsolePocketApp].
 *
 * Reihenfolge ist relevant: Prefs -> Regeln -> Cache -> Pipeline -> WebView-Pool.
 */
class AppGraph(val application: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = AppPrefs(application)
    val stats = CacheStats()
    val ruleEngine = RuleEngine()
    val configurer = WebViewConfigurer(application)
    val downloads = DownloadStore(application)
    val customTabs = CustomTabsLauncher(application)

    @Volatile
    var prefsSnapshot: Prefs = Prefs()
        private set

    /** Wird erst beim ersten Zugriff erzeugt (nicht auf dem Main-Thread). */
    val assetCache: DiskAssetCache by lazy {
        DiskAssetCache(File(application.cacheDir, "webcache"))
    }

    private val fetcher = HttpAssetFetcher(HttpAssetFetcher.defaultClient())

    val pipeline: RequestPipeline = RequestPipeline(
        ruleEngine = ruleEngine,
        cache = assetCache,
        fetcher = fetcher,
        stats = stats,
        localAssets = AndroidLocalAssets(application.assets),
        revalidate = { url -> revalidateInBackground(url) },
    )

    val webViewPool: WebViewPool = WebViewPool(
        application = application,
        configurer = configurer,
        settingsProvider = { webSettings() },
        pipeline = pipeline,
        stats = stats,
        downloads = downloads,
    )

    init {
        scope.launch {
            prefs.flow.collect { p ->
                prefsSnapshot = p
                pipeline.mode = p.ruleMode
                runCatching { assetCache.setMaxBytes(p.cacheLimitMb * 1024L * 1024) }
                webViewPool.applySettingsEverywhere()
            }
        }
    }

    /**
     * L0/L4: Regelwerk laden, Cache konfigurieren, WebView vorwärmen, Prewarm starten.
     * Laeuft bewusst komplett im Hintergrund - der UI-Start wird nicht blockiert.
     */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                application.assets.open(RULES_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()?.let { json ->
                runCatching { ruleEngine.update(RuleSetParser.parse(json)) }
            }

            val p = runCatching { prefs.current() }.getOrDefault(Prefs())
            prefsSnapshot = p
            pipeline.mode = p.ruleMode
            runCatching { assetCache.setMaxBytes(p.cacheLimitMb * 1024L * 1024) }

            // WebView-Instanz vorwärmen (muss auf dem Main-Thread passieren - Pool postet selbst)
            webViewPool.prewarm(1)

            // Custom-Tab-Renderer vorwärmen, falls der Fallback gebraucht wird
            if (p.engineMode != EngineMode.WEBVIEW) {
                runCatching { customTabs.bind() }
            }

            prewarmLearnedAssets(p)
        }
    }

    /** App geht in den Hintergrund: Zustand sichern (L5). */
    fun onAppBackgrounded() {
        webViewPool.persistCookies()
        runCatching { assetCache.flush() }
        learnFromTraffic()
    }

    fun onTrimMemory(level: Int) {
        webViewPool.onTrimMemory(level)
    }

    /** Cache leeren (Settings) - inklusive Chromium-eigenem WebView-Cache. */
    fun clearCache() {
        scope.launch(Dispatchers.IO) {
            runCatching { assetCache.clear() }
            stats.reset()
        }
    }

    fun cacheSizeBytes(): Long = runCatching { assetCache.sizeBytes() }.getOrDefault(0L)

    fun cacheEntryCount(): Int = runCatching { assetCache.entryCount() }.getOrDefault(0)

    // ------------------------------------------------------------------ L4: Prewarm

    private suspend fun prewarmLearnedAssets(p: Prefs) {
        val urls = p.prefetchUrls
        if (urls.isEmpty()) return
        var bytes = 0L
        for (url in urls) {
            if (bytes > MAX_PREWARM_BYTES) break
            val classification = ruleEngine.classify("GET", url, isMainFrame = false)
            if (classification.policy != Policy.CACHE_FIRST && classification.policy != Policy.SWR) continue
            val key = DiskAssetCache.keyFor(url, emptyMap())
            if (assetCache.get(key) != null) continue
            val result = fetcher.fetch(url) ?: continue
            if (!result.cacheable) continue
            assetCache.put(key, url, result.contentType, result.headers, result.body, classification.ttlSeconds)
            bytes += result.body.size
        }
    }

    /** Beobachteten Traffic in eine Top-N-Prefetch-Liste ueberfuehren (Bytes x Haeufigkeit). */
    fun learnFromTraffic() {
        val ranked = stats.recent(500)
            .filter { it.source == CacheStats.SOURCE_CACHE || it.source == CacheStats.SOURCE_NETWORK }
            .groupBy { it.url }
            .map { (url, entries) -> url to entries.sumOf { it.bytes } }
            .sortedByDescending { it.second }
            .take(40)
            .map { it.first }
        if (ranked.isEmpty()) return
        scope.launch { runCatching { prefs.setPrefetchUrls(ranked) } }
    }

    /** Stale-While-Revalidate: im Hintergrund aktualisieren, nie auf dem Interceptor-Thread. */
    private fun revalidateInBackground(url: String) {
        scope.launch(Dispatchers.IO) {
            val classification = ruleEngine.classify("GET", url, isMainFrame = false)
            if (classification.ttlSeconds <= 0) return@launch
            val key = DiskAssetCache.keyFor(url, emptyMap())
            val result = fetcher.fetch(url) ?: return@launch
            if (!result.cacheable) return@launch
            assetCache.put(key, url, result.contentType, result.headers, result.body, classification.ttlSeconds)
        }
    }

    private fun webSettings() = WebViewConfigurer.Settings(
        textZoom = prefsSnapshot.textZoom,
        imagesOff = prefsSnapshot.imagesOff,
        webDarkening = prefsSnapshot.webDarkening,
        chromeUserAgent = prefsSnapshot.engineMode != EngineMode.CUSTOM_TABS,
        debugRemoteInspection = BuildConfig.DEBUG,
    )

    suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val RULES_ASSET = "web-rules.json"
        private const val MAX_PREWARM_BYTES = 48L * 1024 * 1024
    }
}
