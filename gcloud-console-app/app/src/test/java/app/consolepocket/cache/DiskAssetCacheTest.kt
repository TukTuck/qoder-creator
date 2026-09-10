package app.consolepocket.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Disk-Cache: LRU, TTL, Integritaet, Journal-Wiederherstellung.
 * Die Uhr wird injiziert, damit TTL-Verhalten deterministisch testbar ist.
 */
class DiskAssetCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val cache get() = DiskAssetCache(tmp.root, initialMaxBytes = 1024 * 1024, timeSource = { now })

    private val headers = mapOf("Access-Control-Allow-Origin" to listOf("*"))

    @Test
    fun `put und get liefern denselben body`() {
        val c = cache
        val key = DiskAssetCache.keyFor("https://www.gstatic.com/a.js", emptyMap())
        val body = "console.log(1)".toByteArray()
        assertTrue(c.put(key, "https://www.gstatic.com/a.js", "application/javascript", headers, body, 3600))

        val entry = c.get(key)
        assertNotNull(entry)
        assertEquals("console.log(1)", String(entry!!.body))
        assertEquals("application/javascript", entry.meta.contentType)
        assertEquals("*", entry.meta.headers["Access-Control-Allow-Origin"]?.first())
    }

    @Test
    fun `abgelaufene eintraege sind kein frischer treffer aber stale lesbar`() {
        val c = cache
        val key = DiskAssetCache.keyFor("https://www.gstatic.com/b.js", emptyMap())
        c.put(key, "https://www.gstatic.com/b.js", "application/javascript", headers, "x".toByteArray(), 60)
        assertNotNull(c.get(key))

        now += 61_000
        assertNull("abgelaufen darf kein frischer Treffer sein", c.get(key))
        assertNotNull("fuer SWR muss der Eintrag lesbar bleiben", c.getStale(key))
    }

    @Test
    fun `lru wirft den aeltesten eintrag raus`() {
        val small = DiskAssetCache(tmp.root, initialMaxBytes = 300, timeSource = { now })
        val body = ByteArray(120) { it.toByte() }
        repeat(3) { i ->
            now += 10
            small.put("key$i", "https://x/$i.js", "application/javascript", headers, body, 3600)
        }
        assertTrue("Cache muss innerhalb des Limits bleiben", small.sizeBytes() <= 300)
        assertNull("aeltester Eintrag muss raus", small.get("key0"))
        assertNotNull("neuester Eintrag bleibt", small.get("key2"))
    }

    @Test
    fun `journal stellt index nach neustart wieder her`() {
        val first = DiskAssetCache(tmp.root, timeSource = { now })
        val key = DiskAssetCache.keyFor("https://www.gstatic.com/c.js", emptyMap())
        first.put(key, "https://www.gstatic.com/c.js", "text/css", headers, "body{}".toByteArray(), 3600)
        first.flush()

        // Neue Instanz auf demselben Verzeichnis = simulierter App-Neustart
        val second = DiskAssetCache(tmp.root, timeSource = { now })
        val entry = second.get(key)
        assertNotNull(entry)
        assertEquals("body{}", String(entry!!.body))
    }

    @Test
    fun `integrity-check verwirft manipulierte dateien`() {
        val c = DiskAssetCache(tmp.root, timeSource = { now })
        val key = DiskAssetCache.keyFor("https://www.gstatic.com/d.js", emptyMap())
        c.put(key, "https://www.gstatic.com/d.js", "application/javascript", headers, "original".toByteArray(), 3600)
        c.flush()

        File(tmp.root, "bodies/$key.bin").writeText("manipuliert".toByteArray())

        val dropped = c.verifyIntegrity()
        assertEquals(1, dropped)
        assertNull(c.get(key))
    }

    @Test
    fun `vary-origin erzeugt unterschiedliche schluessel`() {
        val a = DiskAssetCache.keyFor("https://x/y.js", emptyMap())
        val b = DiskAssetCache.keyFor("https://x/y.js", mapOf("Origin" to "https://console.cloud.google.com"))
        assertTrue(a != b)
    }

    @Test
    fun `clear loescht alles`() {
        val c = cache
        c.put("k1", "https://x/1.js", "application/javascript", headers, "a".toByteArray(), 3600)
        c.clear()
        assertEquals(0, c.entryCount())
        assertEquals(0L, c.sizeBytes())
        assertNull(c.get("k1"))
    }

    @Test
    fun `leere bodies werden nicht gespeichert`() {
        val c = cache
        assertEquals(false, c.put("k2", "https://x/2.js", "application/javascript", headers, ByteArray(0), 3600))
    }
}
