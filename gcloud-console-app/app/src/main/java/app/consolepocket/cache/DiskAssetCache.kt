package app.consolepocket.cache

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dauerhafter LRU-Disk-Cache fuer statische Console-Assets (PLAN, Schicht L1).
 *
 * Bewusst KEIN OkHttp-Cache: OkHttp wuerde die `Cache-Control`-Antworten des Servers respektieren,
 * wir wollen gehashte Assets aber unabhaengig davon 30 Tage behalten (ADR-002, Abschnitt 5).
 *
 * Layout:
 *   <root>/journal.json     Index (URL, Header, Zeiten, Groesse) - verlusttolerant
 *   <root>/bodies/<key>.bin Body (dekodiert, also OHNE gzip/br)
 *
 * Threading: alle Zugriffe auf den Index sind synchronisiert; Datei-I/O passiert pro Eintrag.
 * `get`/`put` werden nur von Nicht-UI-Threads aufgerufen (shouldInterceptRequest, Dispatchers.IO).
 */
class DiskAssetCache(
    private val rootDir: File,
    initialMaxBytes: Long = 256L * 1024 * 1024,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    data class Meta(
        val key: String,
        val url: String,
        val contentType: String,
        val headers: Map<String, List<String>>,
        val sizeBytes: Long,
        val storedAt: Long,
        val expiresAt: Long,
        var lastAccess: Long,
        val bodyHash: String = "",
    )

    data class Entry(val meta: Meta, val body: ByteArray) {
        fun isExpired(now: Long): Boolean = meta.expiresAt in 1..now
    }

    private val lock = Any()
    private val index = LinkedHashMap<String, Meta>()
    private var maxBytes: Long = initialMaxBytes
    private var totalBytes: AtomicLong = AtomicLong(0)
    private var loaded = false
    private var dirty = false
    private var lastFlushAt = 0L

    private val bodiesDir: File get() = File(rootDir, "bodies")
    private val journalFile: File get() = File(rootDir, "journal.json")

    fun setMaxBytes(bytes: Long) {
        synchronized(lock) {
            maxBytes = bytes.coerceAtLeast(8L * 1024 * 1024)
            ensureLoadedLocked()
            trimLocked()
        }
    }

    fun maxBytes(): Long = synchronized(lock) { maxBytes }

    /** Frischer Treffer (nicht abgelaufen). Aktualisiert die LRU-Reihenfolge. */
    fun get(key: String): Entry? = synchronized(lock) {
        ensureLoadedLocked()
        val meta = index[key] ?: return null
        val now = timeSource()
        if (meta.expiresAt in 1..now) {
            // abgelaufen -> fuer SWR weiterhin lesbar, aber nicht als frischer Treffer
            return null
        }
        readEntryLocked(meta, now)
    }

    /** Auch abgelaufene Eintraege (fuer Stale-While-Revalidate). */
    fun getStale(key: String): Entry? = synchronized(lock) {
        ensureLoadedLocked()
        val meta = index[key] ?: return null
        readEntryLocked(meta, timeSource())
    }

    fun put(
        key: String,
        url: String,
        contentType: String,
        headers: Map<String, List<String>>,
        body: ByteArray,
        ttlSeconds: Long,
    ): Boolean {
        if (body.isEmpty() || body.size > MAX_ENTRY_BYTES) return false
        val now = timeSource()
        synchronized(lock) {
            ensureLoadedLocked()
            val file = File(bodiesDir, "$key.bin")
            file.parentFile?.mkdirs()
            val written = runCatching { file.writeBytes(body) }.isSuccess
            if (!written) return false

            index.remove(key)?.let { totalBytes.addAndGet(-it.sizeBytes) }
            val bodyHash = sha256Hex(body)
            val meta = Meta(
                key = key,
                url = url,
                contentType = contentType,
                headers = headers,
                sizeBytes = body.size.toLong(),
                storedAt = now,
                expiresAt = if (ttlSeconds > 0) now + ttlSeconds * 1000 else 0,
                lastAccess = now,
                bodyHash = bodyHash,
            )
            index[key] = meta
            totalBytes.addAndGet(meta.sizeBytes)
            dirty = true
            trimLocked()
            flushIfDueLocked(now)
        }
        return true
    }

    fun remove(key: String) = synchronized(lock) {
        ensureLoadedLocked()
        index.remove(key)?.let {
            totalBytes.addAndGet(-it.sizeBytes)
            runCatching { File(bodiesDir, "$key.bin").delete() }
            dirty = true
        }
    }

    fun clear() = synchronized(lock) {
        index.clear()
        totalBytes.set(0)
        runCatching { bodiesDir.listFiles()?.forEach { it.delete() } }
        runCatching { journalFile.delete() }
        dirty = false
        loaded = true
    }

    fun sizeBytes(): Long = synchronized(lock) {
        ensureLoadedLocked()
        totalBytes.get()
    }

    fun entryCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        index.size
    }

    /** Fuer den Debug-Screen: zuletzt verwendet zuerst. */
    fun snapshot(limit: Int = 200): List<Meta> = synchronized(lock) {
        ensureLoadedLocked()
        index.values.sortedByDescending { it.lastAccess }.take(limit)
    }

    /**
     * Prueft Body-Hashes und wirft korrupte Eintraege raus. Nicht beim normalen Lesen aufrufen
     * (CPU-Kosten), sondern aus dem Debug-Screen oder einmalig beim App-Start im Hintergrund.
     *
     * @return Anzahl verworfener Eintraege
     */
    fun verifyIntegrity(): Int {
        var dropped = 0
        synchronized(lock) {
            ensureLoadedLocked()
            for (meta in index.values.toList()) {
                if (meta.bodyHash.isEmpty()) continue
                val file = File(bodiesDir, "${meta.key}.bin")
                val body = runCatching { file.readBytes() }.getOrNull()
                if (body == null || sha256Hex(body) != meta.bodyHash) {
                    index.remove(meta.key)
                    totalBytes.addAndGet(-meta.sizeBytes)
                    runCatching { file.delete() }
                    dirty = true
                    dropped++
                }
            }
        }
        return dropped
    }

    fun flush() = synchronized(lock) {
        flushIfDueLocked(timeSource(), force = true)
    }

    // ---------------------------------------------------------------- internals

    private fun readEntryLocked(meta: Meta, now: Long): Entry? {
        val file = File(bodiesDir, "${meta.key}.bin")
        if (!file.exists() || file.length() != meta.sizeBytes) {
            // Journal und Platte stimmen nicht ueberein -> Eintrag verwerfen
            index.remove(meta.key)
            totalBytes.addAndGet(-meta.sizeBytes)
            dirty = true
            return null
        }
        // Absichtlich nur eine billige Laengenpruefung: SHA-256 ueber mehrere MB wuerde den
        // WebView-Netzwerk-Thread spuerbar blocken. Volle Integritaetspruefung laeuft ueber
        // verifyIntegrity() (Debug-Screen / Start-GC).
        val body = runCatching { file.readBytes() }.getOrNull() ?: return null
        meta.lastAccess = now
        dirty = true
        return Entry(meta, body)
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        rootDir.mkdirs()
        bodiesDir.mkdirs()
        val json = runCatching { journalFile.readText() }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").ifBlank { continue }
                val file = File(bodiesDir, "$key.bin")
                if (!file.exists()) continue
                val headers = JSONObject(o.optString("headers", "{}")).let { ho ->
                    ho.keys().asSequence().associateWith { k -> ho.optJSONArray(k)?.toStringList().orEmpty() }
                        .filterValues { it.isNotEmpty() }
                }
                val meta = Meta(
                    key = key,
                    url = o.optString("url"),
                    contentType = o.optString("contentType"),
                    headers = headers,
                    sizeBytes = file.length(),
                    storedAt = o.optLong("storedAt"),
                    expiresAt = o.optLong("expiresAt"),
                    lastAccess = o.optLong("lastAccess", o.optLong("storedAt")),
                    bodyHash = o.optString("bodyHash"),
                )
                index[key] = meta
                totalBytes.addAndGet(meta.sizeBytes)
            }
        }
        trimLocked()
    }

    private fun trimLocked() {
        if (totalBytes.get() <= maxBytes) return
        val byAge = index.values.sortedBy { it.lastAccess }
        for (meta in byAge) {
            if (totalBytes.get() <= maxBytes) break
            index.remove(meta.key)
            totalBytes.addAndGet(-meta.sizeBytes)
            runCatching { File(bodiesDir, "${meta.key}.bin").delete() }
            dirty = true
        }
    }

    private fun flushIfDueLocked(now: Long, force: Boolean = false) {
        if (!dirty) return
        if (!force && now - lastFlushAt < FLUSH_INTERVAL_MS) return
        lastFlushAt = now
        dirty = false
        val arr = JSONArray()
        for (meta in index.values) {
            val headers = JSONObject()
            meta.headers.forEach { (k, v) -> headers.put(k, JSONArray(v)) }
            arr.put(
                JSONObject()
                    .put("key", meta.key)
                    .put("url", meta.url)
                    .put("contentType", meta.contentType)
                    .put("headers", headers.toString())
                    .put("sizeBytes", meta.sizeBytes)
                    .put("storedAt", meta.storedAt)
                    .put("expiresAt", meta.expiresAt)
                    .put("lastAccess", meta.lastAccess)
                    .put("bodyHash", meta.bodyHash),
            )
        }
        runCatching {
            rootDir.mkdirs()
            journalFile.writeText(arr.toString())
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    companion object {
        private const val FLUSH_INTERVAL_MS = 5_000L

        /** Einzelne Assets ueber 24 MB nicht cachen (Speicher/Read-Kosten). */
        private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024

        /**
         * Schluessel = sha256(url + "|" + vary-relevante Header)Praefix + Body-Hash-Praefix.
         * Der Body-Anteil wird beim Schreiben ergaenzt, damit Korruption auffaellt.
         */
        fun keyFor(url: String, varyHeaders: Map<String, String>): String {
            val vary = varyHeaders.entries
                .filter { it.key.lowercase() in VARY_KEYS }
                .sortedBy { it.key.lowercase() }
                .joinToString("&") { "${it.key.lowercase()}=${it.value}" }
            return sha256Hex("$url|$vary").take(32)
        }

        fun sha256Hex(input: String): String = sha256Hex(input.toByteArray())

        fun sha256Hex(input: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString()
        }

        private val VARY_KEYS = setOf("accept-encoding", "origin", "accept-language")
    }
}
