package app.consolepocket.cache

import app.consolepocket.metrics.CacheStats
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-End-Test der Pipeline gegen einen echten HTTP-Server (MockWebServer):
 * Miss -> Fetch -> Store, Hit -> ohne Netzwerk, Blocker nur im ENFORCE-Modus,
 * POST/Main-Frame/Antworten mit Set-Cookie werden nicht gecacht.
 */
class RequestPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var stats: CacheStats
    private lateinit var pipeline: RequestPipeline
    private lateinit var cache: DiskAssetCache
    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val host = server.hostName // localhost
        engine = RuleEngine(
            RuleSetParser.parse(
                """
                {
                  "mode": "ENFORCE",
                  "neverIntercept": { "methods": ["POST"], "mainFrame": true },
                  "rules": [
                    { "id": "block-log", "priority": 100,
                      "match": { "hostPatterns": ["$host"], "pathPatterns": ["/log**"] },
                      "policy": "BLOCK", "response": { "status": 204 } },
                    { "id": "cache-assets", "priority": 50,
                      "match": { "hostPatterns": ["$host"], "pathPatterns": ["/assets/**"] },
                      "policy": "CACHE_FIRST", "ttlDays": 30 },
                    { "id": "swr-ui", "priority": 40,
                      "match": { "hostPatterns": ["$host"], "pathPatterns": ["/ui/**"] },
                      "policy": "SWR", "ttlDays": 1 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        stats = CacheStats()
        cache = DiskAssetCache(tmp.root)
        pipeline = RequestPipeline(
            ruleEngine = engine,
            cache = cache,
            fetcher = HttpAssetFetcher(HttpAssetFetcher.defaultClient()),
            stats = stats,
        )
        pipeline.mode = RuleMode.ENFORCE
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun assetUrl(path: String) = server.url(path).toString()

    @Test
    fun `erster aufruf kommt aus dem netz und wird gespeichert`() {
        server.enqueue(
            MockResponse()
                .setBody("var a = 1;")
                .setHeader("Content-Type", "application/javascript")
                .addHeader("Access-Control-Allow-Origin", "*"),
        )
        val url = assetUrl("/assets/main.js")

        val first = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertTrue(first is RequestPipeline.Outcome.Serve)
        first as RequestPipeline.Outcome.Serve
        assertEquals(CacheStats.SOURCE_NETWORK, first.source)
        assertEquals("var a = 1;", String(first.body))
        assertEquals("*", first.headers["Access-Control-Allow-Origin"])
        assertEquals(200, first.statusCode)
    }

    @Test
    fun `zweiter aufruf kommt ohne netzwerk aus dem cache`() {
        server.enqueue(
            MockResponse().setBody("body { color: red }").setHeader("Content-Type", "text/css"),
        )
        val url = assetUrl("/assets/style.css")

        pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertEquals(1, server.requestCount)

        val second = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertEquals("Cache-Treffer darf keinen Request ausloesen", 1, server.requestCount)
        assertTrue(second is RequestPipeline.Outcome.Serve)
        second as RequestPipeline.Outcome.Serve
        assertEquals(CacheStats.SOURCE_CACHE, second.source)
        assertEquals("body { color: red }", String(second.body))
        assertTrue(second.headers["Cache-Control"]!!.contains("max-age="))
    }

    @Test
    fun `bypass-fenster liefert trotz cache frisch vom server`() {
        server.enqueue(MockResponse().setBody("v1").setHeader("Content-Type", "text/plain"))
        server.enqueue(MockResponse().setBody("v2").setHeader("Content-Type", "text/plain"))
        val url = assetUrl("/assets/data.txt")

        pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        pipeline.bypassCacheFor(5_000)
        val second = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false)) as RequestPipeline.Outcome.Serve
        assertEquals("v2", String(second.body))
        assertEquals(CacheStats.SOURCE_NETWORK, second.source)
    }

    @Test
    fun `block-regel greift nur im enforce-modus`() {
        val url = assetUrl("/log?format=json")

        pipeline.mode = RuleMode.LOG_ONLY
        val logged = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertEquals(RequestPipeline.Outcome.Pass, logged)

        pipeline.mode = RuleMode.ENFORCE
        val blocked = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertTrue(blocked is RequestPipeline.Outcome.Block)
        assertEquals(204, (blocked as RequestPipeline.Outcome.Block).status)
    }

    @Test
    fun `ausgeschaltete pipeline macht gar nichts`() {
        pipeline.mode = RuleMode.OFF
        val outcome = pipeline.handle(RequestPipeline.Incoming("GET", assetUrl("/assets/x.js"), isMainFrame = false))
        assertEquals(RequestPipeline.Outcome.Pass, outcome)
        pipeline.enabled = false
        pipeline.mode = RuleMode.ENFORCE
        assertEquals(
            RequestPipeline.Outcome.Pass,
            pipeline.handle(RequestPipeline.Incoming("GET", assetUrl("/assets/y.js"), isMainFrame = false)),
        )
    }

    @Test
    fun `post und hauptdokument werden durchgereicht`() {
        assertEquals(
            RequestPipeline.Outcome.Pass,
            pipeline.handle(RequestPipeline.Incoming("POST", assetUrl("/assets/x.js"), isMainFrame = false)),
        )
        assertEquals(
            RequestPipeline.Outcome.Pass,
            pipeline.handle(RequestPipeline.Incoming("GET", assetUrl("/assets/x.js"), isMainFrame = true)),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `antworten mit set-cookie werden nicht gecacht`() {
        server.enqueue(
            MockResponse()
                .setBody("personalisiert")
                .setHeader("Content-Type", "application/javascript")
                .addHeader("Set-Cookie", "SID=abc; Path=/"),
        )
        server.enqueue(
            MockResponse().setBody("personalisiert").setHeader("Content-Type", "application/javascript"),
        )
        val url = assetUrl("/assets/personal.js")

        val first = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false)) as RequestPipeline.Outcome.Serve
        assertEquals(CacheStats.SOURCE_NETWORK, first.source)

        pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertEquals("Set-Cookie-Antworten duerfen nicht gecacht werden", 2, server.requestCount)
    }

    @Test
    fun `swr liefert sofort und stoesst hintergrund-refresh an`() {
        server.enqueue(MockResponse().setBody("alt").setHeader("Content-Type", "text/plain"))
        server.enqueue(MockResponse().setBody("neu").setHeader("Content-Type", "text/plain"))
        val url = assetUrl("/ui/config.json")

        pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))

        val revalidated = mutableListOf<String>()
        val swrPipeline = RequestPipeline(
            ruleEngine = engine,
            cache = cache,
            fetcher = HttpAssetFetcher(HttpAssetFetcher.defaultClient()),
            stats = stats,
            revalidate = { revalidated += it },
        )
        swrPipeline.mode = RuleMode.ENFORCE
        // Eintrag ist zwar frisch, SWR darf ihn aber trotzdem sofort liefern
        val outcome = swrPipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false)) as RequestPipeline.Outcome.Serve
        assertEquals(CacheStats.SOURCE_CACHE, outcome.source)
        assertEquals(listOf(url), revalidated)
    }

    @Test
    fun `netzwerkfehler fuehrt zu pass statt zu kaputter antwort`() {
        val url = "http://${server.hostName}:1/assets/dead.js" // kein Server
        val outcome = pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        assertEquals(RequestPipeline.Outcome.Pass, outcome)
    }

    @Test
    fun `statistik zaehlt treffer und misses`() {
        server.enqueue(MockResponse().setBody("a").setHeader("Content-Type", "text/plain"))
        val url = assetUrl("/assets/count.txt")
        pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))
        pipeline.handle(RequestPipeline.Incoming("GET", url, isMainFrame = false))

        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.networkFetches)
        assertEquals(1, snapshot.cacheHits)
        assertEquals(50, snapshot.hitRatePercent)
        assertTrue(snapshot.bytesFromCache > 0)
    }
}
