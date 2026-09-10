package app.consolepocket.cache

import app.consolepocket.metrics.CacheStats
import java.io.File

/**
 * Kernstueck der Beschleunigung (PLAN, Schichten L1/L2/L4).
 *
 * Die Klasse ist bewusst Android-frei (bis auf [LocalAssetSource]): sie nimmt eine
 * [Incoming]-Beschreibung entgegen und liefert ein [Outcome]. Die Uebersetzung in
 * `android.webkit.WebResourceResponse` passiert in `cache/WebResourceResponses.kt`.
 * Dadurch ist die komplette Cache-Logik mit MockWebServer auf der JVM testbar.
 *
 * Threading: `handle` wird aus `shouldInterceptRequest` aufgerufen, also auf einem
 * WebView-Netzwerk-Thread. Hier darf nur schnelle Platten-I/O und ein OkHttp-Call passieren -
 * kein Bitmap-Decoding, keine Retries, keine UI-Callbacks.
 */
class RequestPipeline(
    private val ruleEngine: RuleEngine,
    private val cache: DiskAssetCache,
    private val fetcher: HttpAssetFetcher,
    private val stats: CacheStats,
    private val localAssets: LocalAssetSource? = null,
    private val revalidate: ((url: String) -> Unit)? = null,
) {

    data class Incoming(
        val method: String,
        val url: String,
        val isMainFrame: Boolean,
        val requestHeaders: Map<String, String> = emptyMap(),
    )

    sealed interface Outcome {
        /** WebView/Chromium soll den Request selbst ausfuehren. */
        data object Pass : Outcome

        /** Request verwerfen und mit leerer Antwort beantworten. */
        data class Block(val status: Int, val ruleId: String?) : Outcome

        /** Antwort kommt von uns (Cache, Netzwerk oder lokales Asset). */
        data class Serve(
            val statusCode: Int,
            val contentType: String,
            val headers: Map<String, String>,
            val body: ByteArray,
            val source: String,
            val durationMs: Long,
            val ruleId: String?,
        ) : Outcome
    }

    @Volatile
    var mode: RuleMode = RuleMode.LOG_ONLY

    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var bypassUntil: Long = 0L

    /** "Neu laden (Cache umgehen)": fuer ein Zeitfenster wird nicht aus dem Cache geliefert. */
    fun bypassCacheFor(windowMs: Long = 10_000L) {
        bypassUntil = System.currentTimeMillis() + windowMs
    }

    fun handle(incoming: Incoming): Outcome {
        if (!enabled || mode == RuleMode.OFF) return Outcome.Pass

        val started = System.nanoTime()
        val classification = ruleEngine.classify(incoming.method, incoming.url, incoming.isMainFrame)
        val now = System.currentTimeMillis()
        val bypassing = now < bypassUntil

        return when (classification.policy) {
            Policy.IGNORE, Policy.PASSTHROUGH -> {
                log(incoming, classification, CacheStats.SOURCE_IGNORED, 0, started)
                Outcome.Pass
            }

            Policy.BLOCK -> {
                if (mode == RuleMode.LOG_ONLY) {
                    // Nur protokollieren: Request laeuft normal durch.
                    log(incoming, classification, CacheStats.SOURCE_IGNORED, 0, started)
                    Outcome.Pass
                } else {
                    log(incoming, classification, CacheStats.SOURCE_BLOCKED, 0, started)
                    Outcome.Block(classification.blockStatus, classification.ruleId)
                }
            }

            Policy.LOCAL_OVERRIDE -> {
                val bytes = classification.localPath?.let { localAssets?.open(it) }
                if (bytes == null) {
                    log(incoming, classification, CacheStats.SOURCE_IGNORED, 0, started)
                    Outcome.Pass
                } else {
                    val contentType = HttpAssetFetcher.guessContentType(classification.localPath!!)
                    log(incoming, classification, CacheStats.SOURCE_CACHE, bytes.size.toLong(), started)
                    Outcome.Serve(
                        statusCode = 200,
                        contentType = contentType,
                        headers = mapOf(
                            "Content-Type" to contentType,
                            "Cache-Control" to "public, max-age=${classification.ttlSeconds.coerceAtLeast(3600)}",
                            "X-Console-Pocket-Cache" to "local",
                        ),
                        body = bytes,
                        source = CacheStats.SOURCE_CACHE,
                        durationMs = elapsed(started),
                        ruleId = classification.ruleId,
                    )
                }
            }

            Policy.CACHE_FIRST -> cacheFirst(incoming, classification, started, bypassing)

            Policy.SWR -> swr(incoming, classification, started, bypassing)
        }
    }

    private fun cacheFirst(
        incoming: Incoming,
        classification: Classification,
        started: Long,
        bypassing: Boolean,
    ): Outcome {
        val key = cacheKey(incoming)
        if (!bypassing) {
            cache.get(key)?.let { entry ->
                val remainingTtl = ((entry.meta.expiresAt - System.currentTimeMillis()) / 1000)
                    .coerceIn(60, classification.ttlSeconds.coerceAtLeast(60))
                log(incoming, classification, CacheStats.SOURCE_CACHE, entry.body.size.toLong(), started)
                return Outcome.Serve(
                    statusCode = 200,
                    contentType = entry.meta.contentType,
                    headers = ResponseFactory.headersForWebView(
                        original = entry.meta.headers,
                        cacheHit = true,
                        maxAgeSeconds = remainingTtl,
                        statusCode = 200,
                    ),
                    body = entry.body,
                    source = CacheStats.SOURCE_CACHE,
                    durationMs = elapsed(started),
                    ruleId = classification.ruleId,
                )
            }
        }
        return fetchAndStore(incoming, classification, key, started)
    }

    private fun swr(
        incoming: Incoming,
        classification: Classification,
        started: Long,
        bypassing: Boolean,
    ): Outcome {
        val key = cacheKey(incoming)
        val stale = if (bypassing) null else cache.getStale(key)
        if (stale != null) {
            // Sofort liefern, im Hintergrund aktualisieren - nie auf dem Interceptor-Thread.
            revalidate?.invoke(incoming.url)
            log(incoming, classification, CacheStats.SOURCE_CACHE, stale.body.size.toLong(), started)
            return Outcome.Serve(
                statusCode = 200,
                contentType = stale.meta.contentType,
                headers = ResponseFactory.headersForWebView(
                    original = stale.meta.headers,
                    cacheHit = true,
                    maxAgeSeconds = 60,
                    statusCode = 200,
                ),
                body = stale.body,
                source = CacheStats.SOURCE_CACHE,
                durationMs = elapsed(started),
                ruleId = classification.ruleId,
            )
        }
        return fetchAndStore(incoming, classification, key, started)
    }

    private fun fetchAndStore(
        incoming: Incoming,
        classification: Classification,
        key: String,
        started: Long,
    ): Outcome {
        val result = fetcher.fetch(incoming.url, incoming.method, incoming.requestHeaders)
        if (result == null) {
            // Netzwerkfehler: nicht blocken, Chromium soll es selbst versuchen (bessere Fehlerseite).
            log(incoming, classification, CacheStats.SOURCE_IGNORED, 0, started)
            return Outcome.Pass
        }
        if (result.cacheable && classification.ttlSeconds > 0) {
            cache.put(
                key = key,
                url = incoming.url,
                contentType = result.contentType,
                headers = result.headers,
                body = result.body,
                ttlSeconds = classification.ttlSeconds,
            )
        }
        log(incoming, classification, CacheStats.SOURCE_NETWORK, result.body.size.toLong(), started)
        return Outcome.Serve(
            statusCode = result.statusCode,
            contentType = result.contentType,
            headers = ResponseFactory.headersForWebView(
                original = result.headers,
                cacheHit = false,
                maxAgeSeconds = if (result.cacheable) classification.ttlSeconds else 0,
                statusCode = result.statusCode,
            ),
            body = result.body,
            source = CacheStats.SOURCE_NETWORK,
            durationMs = result.durationMs,
            ruleId = classification.ruleId,
        )
    }

    private fun cacheKey(incoming: Incoming): String {
        val origin = incoming.requestHeaders.entries
            .firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
        val vary = if (origin.isNullOrBlank()) emptyMap() else mapOf("Origin" to origin)
        return DiskAssetCache.keyFor(incoming.url, vary)
    }

    private fun log(
        incoming: Incoming,
        classification: Classification,
        source: String,
        bytes: Long,
        started: Long,
    ) {
        stats.record(
            CacheStats.RequestLogEntry(
                timeMs = System.currentTimeMillis(),
                url = incoming.url,
                policy = classification.policy.name,
                source = source,
                bytes = bytes,
                durationMs = elapsed(started),
                ruleId = classification.ruleId,
            ),
        )
    }

    private fun elapsed(startedNanos: Long): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
}

/** Zugriff auf lokal gebuendelte Dateien (assets/) fuer Policy.LOCAL_OVERRIDE. */
interface LocalAssetSource {
    /** @param assetPath z. B. "shell/fonts/roboto.woff2" */
    fun open(assetPath: String): ByteArray?
}

/** Android-Implementierung; bewusst klein und ohne Coroutines (wird auf IO-Threads genutzt). */
class AndroidLocalAssets(private val assets: android.content.res.AssetManager) : LocalAssetSource {
    override fun open(assetPath: String): ByteArray? = runCatching {
        val clean = assetPath.removePrefix("/").replace("../", "")
        if (clean.isBlank()) return null
        assets.open(clean).use { it.readBytes() }
    }.getOrNull()
}

/** Test-Double / Fallback, wenn keine Assets verfuegbar sind. */
class FileLocalAssets(private val rootDir: File) : LocalAssetSource {
    override fun open(assetPath: String): ByteArray? = runCatching {
        val file = File(rootDir, assetPath.removePrefix("/"))
        if (!file.canonicalPath.startsWith(rootDir.canonicalPath)) return null
        if (!file.isFile) return null
        file.readBytes()
    }.getOrNull()
}
