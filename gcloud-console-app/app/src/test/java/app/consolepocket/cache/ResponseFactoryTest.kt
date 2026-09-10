package app.consolepocket.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Header-Treue ist die wichtigste Fehlerquelle eines Interceptors (ADR-002, Abschnitt 4).
 * Ohne CORS-Header schlagen fetch()/XHR der Console fehl; mit Content-Encoding auf einem
 * dekodierten Body double-decoded Chromium und die Seite bleibt weiss.
 */
class ResponseFactoryTest {

    private val original = mapOf(
        "Content-Type" to listOf("application/javascript; charset=utf-8"),
        "Content-Encoding" to listOf("gzip"),
        "Content-Length" to listOf("123456"),
        "Connection" to listOf("keep-alive"),
        "Transfer-Encoding" to listOf("chunked"),
        "Set-Cookie" to listOf("SID=secret; Path=/"),
        "Access-Control-Allow-Origin" to listOf("*"),
        "Access-Control-Allow-Credentials" to listOf("true"),
        "Timing-Allow-Origin" to listOf("*"),
        "ETag" to listOf("\"abc123\""),
        "Vary" to listOf("Accept-Encoding, Origin, Cookie"),
        "Cache-Control" to listOf("private, max-age=0, must-revalidate"),
        "Server" to listOf("sffe"),
        "X-Frame-Options" to listOf("SAMEORIGIN"),
    )

    @Test
    fun `hop-by-hop und body-laenge fliegen raus`() {
        val headers = ResponseFactory.headersForWebView(original, cacheHit = true, maxAgeSeconds = 3600, statusCode = 200)
        assertFalse(headers.keys.any { it.equals("Content-Encoding", true) })
        assertFalse(headers.keys.any { it.equals("Content-Length", true) })
        assertFalse(headers.keys.any { it.equals("Connection", true) })
        assertFalse(headers.keys.any { it.equals("Transfer-Encoding", true) })
        assertFalse(headers.keys.any { it.equals("Set-Cookie", true) })
    }

    @Test
    fun `cors- und validierungs-header bleiben erhalten`() {
        val headers = ResponseFactory.headersForWebView(original, cacheHit = true, maxAgeSeconds = 3600, statusCode = 200)
        assertEquals("*", headers["Access-Control-Allow-Origin"])
        assertEquals("true", headers["Access-Control-Allow-Credentials"])
        assertEquals("*", headers["Timing-Allow-Origin"])
        assertEquals("\"abc123\"", headers["ETag"])
        assertEquals("application/javascript; charset=utf-8", headers["Content-Type"])
    }

    @Test
    fun `vary wird um cookie und encoding bereinigt`() {
        val headers = ResponseFactory.headersForWebView(original, cacheHit = true, maxAgeSeconds = 60, statusCode = 200)
        assertEquals("Origin", headers["Vary"])
    }

    @Test
    fun `cache-control wird auf unsere ttl gesetzt`() {
        val headers = ResponseFactory.headersForWebView(original, cacheHit = true, maxAgeSeconds = 86_400, statusCode = 200)
        assertEquals("public, max-age=86400", headers["Cache-Control"])
    }

    @Test
    fun `ohne ttl wird no-store gesetzt`() {
        val headers = ResponseFactory.headersForWebView(original, cacheHit = false, maxAgeSeconds = 0, statusCode = 200)
        assertEquals("no-store", headers["Cache-Control"])
    }

    @Test
    fun `fehlerhafte statuscodes bekommen no-store`() {
        val headers = ResponseFactory.headersForWebView(original, cacheHit = false, maxAgeSeconds = 600, statusCode = 404)
        assertEquals("no-store", headers["Cache-Control"])
    }

    @Test
    fun `debug-marker ist gesetzt`() {
        val hit = ResponseFactory.headersForWebView(original, cacheHit = true, maxAgeSeconds = 60, statusCode = 200)
        val miss = ResponseFactory.headersForWebView(original, cacheHit = false, maxAgeSeconds = 60, statusCode = 200)
        assertEquals("hit", hit["X-Console-Pocket-Cache"])
        assertEquals("miss", miss["X-Console-Pocket-Cache"])
    }

    @Test
    fun `reason phrase ist nie leer`() {
        listOf(200, 204, 304, 404, 500, 418).forEach { code ->
            assertTrue(
                "ReasonPhrase fuer $code ist leer",
                ResponseFactory.reasonPhrase(code).isNotBlank(),
            )
        }
        assertEquals("No Content", ResponseFactory.reasonPhrase(204))
    }
}
