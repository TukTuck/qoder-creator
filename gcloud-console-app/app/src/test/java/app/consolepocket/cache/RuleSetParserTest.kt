package app.consolepocket.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetParserTest {

    /** Kompaktes Regelwerk, das dieselbe Struktur wie assets/web-rules.json hat. */
    private val json = """
    {
      "version": 7,
      "mode": "LOG_ONLY",
      "defaults": { "immutableTtlDays": 21, "swrTtlDays": 3, "cacheMaxBytes": 1048576 },
      "neverIntercept": {
        "methods": ["POST", "PUT"],
        "mainFrame": true,
        "hostPatterns": ["accounts.google.com", "*.googleapis.com"],
        "pathPatterns": ["**/batchexecute**", "/m/_/Diagnostics**"]
      },
      "rules": [
        { "id": "block-log", "enabled": true, "priority": 100,
          "match": { "hostPatterns": ["play.google.com"], "pathPatterns": ["/log**"] },
          "policy": "BLOCK", "response": { "status": 204 } },
        { "id": "cache-js", "enabled": true, "priority": 80,
          "match": { "hostPatterns": ["console.cloud.google.com"], "pathPatterns": ["/m/_/js/**"] },
          "policy": "CACHE_FIRST", "ttlDays": 30 },
        { "id": "disabled-rule", "enabled": false, "priority": 90,
          "match": { "hostPatterns": ["www.gstatic.com"] }, "policy": "BLOCK" },
        { "id": "kaputt-ohne-match", "enabled": true, "priority": 99, "match": {}, "policy": "BLOCK" }
      ]
    }
    """.trimIndent()

    @Test
    fun `parsed defaults und neverIntercept`() {
        val set = RuleSetParser.parse(json)
        assertEquals(7, set.version)
        assertEquals(RuleMode.LOG_ONLY, set.defaultMode)
        assertEquals(21, set.immutableTtlDays)
        assertEquals(3, set.swrTtlDays)
        assertTrue(set.neverIntercept.methods.contains("POST"))
        assertTrue(set.neverIntercept.mainFrame)
        assertEquals(listOf("accounts.google.com", "*.googleapis.com"), set.neverIntercept.hostPatterns)
    }

    @Test
    fun `deaktivierte regeln und regeln ohne match fliegen raus`() {
        val set = RuleSetParser.parse(json)
        // "kaputt-ohne-match" wird verworfen, "disabled-rule" ist nicht enabled
        assertEquals(listOf("block-log", "cache-js"), set.sortedRules.map { it.id })
    }

    @Test
    fun `unbekannte werte fallen auf konservative defaults`() {
        val broken = """{ "mode": "QUATSCH", "rules": [
            { "id": "r1", "match": { "hostPatterns": ["x.com"] }, "policy": "SUPERPOLICY" } ] }"""
        val set = RuleSetParser.parse(broken)
        assertEquals(RuleMode.LOG_ONLY, set.defaultMode)
        assertEquals(Policy.IGNORE, set.rules.first().policy)
    }

    @Test
    fun `kaputtes json ergibt leeres Regelwerk statt Absturz`() {
        val set = RuleSetParser.parse("das ist kein json")
        assertEquals(0, set.rules.size)
        assertEquals(RuleMode.OFF, set.defaultMode)
    }

    @Test
    fun `seed-datei aus den assets ist parsebar`() {
        val file = java.io.File("src/main/assets/web-rules.json")
        org.junit.Assume.assumeTrue("web-rules.json nicht gefunden", file.exists())
        val set = RuleSetParser.parse(file.readText())
        assertTrue("Seed muss Regeln enthalten", set.rules.isNotEmpty())
        assertTrue("Seed darf keine Regel ohne Match enthalten", set.sortedRules.isNotEmpty())
    }
}
