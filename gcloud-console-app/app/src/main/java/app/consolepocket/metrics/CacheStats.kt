package app.consolepocket.metrics

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Zaehler und Request-Log fuer den Debug-Screen und den Messplan (PLAN Kapitel 9).
 * Thread-safe: wird aus WebView-Threads und Coroutines befuellt.
 */
class CacheStats {

    data class RequestLogEntry(
        val timeMs: Long,
        val url: String,
        val policy: String,
        val source: String,
        val bytes: Long,
        val durationMs: Long,
        val ruleId: String?,
    )

    data class Snapshot(
        val total: Int,
        val cacheHits: Int,
        val networkFetches: Int,
        val blocked: Int,
        val ignored: Int,
        val bytesFromCache: Long,
        val bytesFromNetwork: Long,
        val hitRatePercent: Int,
        val lastPageLoadMs: Long,
    )

    private val total = AtomicInteger()
    private val cacheHits = AtomicInteger()
    private val networkFetches = AtomicInteger()
    private val blocked = AtomicInteger()
    private val ignored = AtomicInteger()
    private val bytesFromCache = AtomicLong()
    private val bytesFromNetwork = AtomicLong()

    @Volatile
    var lastPageLoadMs: Long = 0L

    private val logLock = Any()
    private val log = ArrayDeque<RequestLogEntry>()

    fun record(entry: RequestLogEntry) {
        total.incrementAndGet()
        when (entry.source) {
            SOURCE_CACHE -> {
                cacheHits.incrementAndGet()
                bytesFromCache.addAndGet(entry.bytes)
            }

            SOURCE_NETWORK -> {
                networkFetches.incrementAndGet()
                bytesFromNetwork.addAndGet(entry.bytes)
            }

            SOURCE_BLOCKED -> blocked.incrementAndGet()
            else -> ignored.incrementAndGet()
        }
        synchronized(logLock) {
            log.addLast(entry)
            while (log.size > MAX_LOG) log.removeFirst()
        }
    }

    fun recent(limit: Int = 200): List<RequestLogEntry> = synchronized(logLock) {
        log.toList().reversed().take(limit)
    }

    fun clearLog() = synchronized(logLock) { log.clear() }

    fun reset() {
        total.set(0)
        cacheHits.set(0)
        networkFetches.set(0)
        blocked.set(0)
        ignored.set(0)
        bytesFromCache.set(0)
        bytesFromNetwork.set(0)
        lastPageLoadMs = 0
        clearLog()
    }

    fun snapshot(): Snapshot {
        val hits = cacheHits.get()
        val net = networkFetches.get()
        val relevant = hits + net
        return Snapshot(
            total = total.get(),
            cacheHits = hits,
            networkFetches = net,
            blocked = blocked.get(),
            ignored = ignored.get(),
            bytesFromCache = bytesFromCache.get(),
            bytesFromNetwork = bytesFromNetwork.get(),
            hitRatePercent = if (relevant == 0) 0 else (hits * 100) / relevant,
            lastPageLoadMs = lastPageLoadMs,
        )
    }

    companion object {
        const val SOURCE_CACHE = "cache"
        const val SOURCE_NETWORK = "network"
        const val SOURCE_BLOCKED = "blocked"
        const val SOURCE_IGNORED = "passthrough"
        private const val MAX_LOG = 500

        fun humanBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f kB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
