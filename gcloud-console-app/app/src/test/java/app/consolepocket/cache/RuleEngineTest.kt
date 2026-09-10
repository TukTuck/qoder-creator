package app.consolepocket.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val set = RuleSetParser.parse(
        """
        {
          "mode": "ENFORCE",
          "neverIntercept": {
            "methods": ["POST"], "mainFrame": true,
            "hostPatterns": ["accounts.google.com", "*.googleapis.com"],
            "pathPatterns": ["**/batchexecute**"]
          },
          "rules": [
            { "id": "block-csi", "priority": 100,
              "match": { "pathPatterns": ["**/gen_204**"] }, "policy": "BLOCK", "response": { "status": 204 } },
            { "id": "cache-gstatic", "priority": 80,
              "match": { "hostPatterns": ["www.gstatic.com"] }, "policy": "CACHE_FIRST", "ttlDays": 30 },
            { "id": "cache-console-js", "priority": 70,
              "match": { "hostPatterns": ["console.cloud.google.com"], "pathPatterns": ["/m/_/js/**"] },
              "policy": "CACHE_FIRST" },
            { "id": "swr-ui", "priority": 40,
              "match": { "hostPatterns": ["console.cloud.google.com"], "pathPatterns": ["/m/_/ConsoleUi/**"] },
              "policy": "SWR", "ttlDays": 1 }
          ]
        }
        """.trimIndent(),
    )

    private val engine = RuleEngine(set)

    private fun classify(url: String, method: String = "GET", mainFrame: Boolean = false) =
        engine.classify(method, url, mainFrame)

    @Test
    fun `hauptdokument wird nie abgefangen`() {
        val c = classify("https://console.cloud.google.com/home/dashboard", mainFrame = true)
        assertEquals(Policy.PASSTHROUGH, c.policy)
        assertEquals("main-frame", c.reason)
    }

    @Test
    fun `post wird nie abgefangen`() {
        val c = classify("https://www.gstatic.com/x.js", method = "POST")
        assertEquals(Policy.PASSTHROUGH, c.policy)
        assertTrue(c.reason.startsWith("method"))
    }

    @Test
    fun `auth-domains sind tabu`() {
        assertEquals(Policy.PASSTHROUGH, classify("https://accounts.google.com/signin/challenge").policy)
        assertEquals(Policy.PASSTHROUGH, classify("https://www.googleapis.com/batch").policy)
    }

    @Test
    fun `batchexecute-pfad ist tabu`() {
        val c = classify("https://console.cloud.google.com/_/CloudUi/data/batchexecute")
        assertEquals(Policy.PASSTHROUGH, c.policy)
        assertEquals("never-intercept-path", c.reason)
    }

    @Test
    fun `telemetrie wird geblockt`() {
        val c = classify("https://play.google.com/log?format=json")
        // play.google.com/log trifft gen_204 nicht, aber keine andere Regel -> IGNORE
        assertEquals(Policy.IGNORE, c.policy)
        val csi = classify("https://console.cloud.google.com/m/_/gen_204?foo=bar")
        assertEquals(Policy.BLOCK, csi.policy)
        assertEquals("block-csi", csi.ruleId)
    }

    @Test
    fun `gstatic wird cache-first mit 30 tagen`() {
        val c = classify("https://www.gstatic.com/_/mss/boq-cloudconsole/_/js/k=boq.x.de.abc/esmo=1/rs=AA2Y/foo.js")
        assertEquals(Policy.CACHE_FIRST, c.policy)
        assertEquals(30L * 24 * 60 * 60, c.ttlSeconds)
    }

    @Test
    fun `console-js bekommt default-ttl aus den defaults`() {
        val c = classify("https://console.cloud.google.com/m/_/js/main")
        assertEquals(Policy.CACHE_FIRST, c.policy)
        assertEquals(set.immutableTtlDays.toLong() * 86_400, c.ttlSeconds)
    }

    @Test
    fun `swr-regel liefert kurze ttl`() {
        val c = classify("https://console.cloud.google.com/m/_/ConsoleUi/get")
        assertEquals(Policy.SWR, c.policy)
        assertEquals(86_400L, c.ttlSeconds)
    }

    @Test
    fun `unbekannte domain bleibt in chromium-hand`() {
        assertEquals(Policy.IGNORE, classify("https://example.org/foo.js").policy)
    }

    @Test
    fun `non-http wird durchgereicht`() {
        assertEquals(Policy.PASSTHROUGH, classify("blob:https://console.cloud.google.com/abc").policy)
    }

    @Test
    fun `leeres regelwerk blockt gar nichts`() {
        val empty = RuleEngine()
        assertFalse(empty.classify("GET", "https://www.gstatic.com/a.js", false).policy == Policy.BLOCK)
        assertEquals(Policy.IGNORE, empty.classify("GET", "https://www.gstatic.com/a.js", false).policy)
    }
}
