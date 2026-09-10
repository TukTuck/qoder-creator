package app.consolepocket.cache

/**
 * Baut die Header-Menge, die wir dem WebView in einer WebResourceResponse zurueckgeben.
 *
 * Header-Treue ist die wichtigste Fehlerquelle eines Interceptors (ADR-002, Abschnitt 4):
 *  - Hop-by-Hop-Header muessen weg
 *  - `Content-Encoding`/`Content-Length` muessen weg, weil wir den DEKODIERTEN Body liefern
 *  - CORS-Header muessen erhalten bleiben, sonst schlagen fetch()/XHR der Console fehl
 *  - `Cache-Control` wird auf unsere TTL gesetzt, damit Chromium nicht zusaetzlich verwirft
 */
object ResponseFactory {

    private val HOP_BY_HOP = setOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "content-length",
        "content-encoding",
    )

    /** Header, die fuer die Console funktionieren muessen (CORS + Timing + Validierung). */
    private val KEEP = setOf(
        "access-control-allow-origin",
        "access-control-allow-credentials",
        "access-control-allow-methods",
        "access-control-allow-headers",
        "access-control-expose-headers",
        "access-control-max-age",
        "timing-allow-origin",
        "etag",
        "last-modified",
        "vary",
        "content-type",
        "cross-origin-resource-policy",
        "cross-origin-opener-policy",
        "x-content-type-options",
        "service-worker-allowed",
    )

    fun headersForWebView(
        original: Map<String, List<String>>,
        cacheHit: Boolean,
        maxAgeSeconds: Long,
        statusCode: Int,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(original.size + 4)
        for ((rawKey, values) in original) {
            val key = rawKey.trim()
            if (key.isEmpty()) continue
            val lower = key.lowercase()
            if (lower in HOP_BY_HOP) continue
            if (lower == "set-cookie") continue
            if (lower == "cache-control" || lower == "expires" || lower == "pragma") continue
            if (lower !in KEEP) continue // konservativ: nur bekannte, relevante Header
            val value = values.firstOrNull()?.trim().orEmpty()
            if (value.isEmpty()) continue
            // Vary darf sich nicht mehr auf Cookie/Encoding beziehen, wenn wir den
            // dekodierten Body ohne Cookies liefern.
            if (lower == "vary") {
                val cleaned = value.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals("Cookie", true) && !it.equals("Accept-Encoding", true) }
                if (cleaned.isEmpty()) continue
                out[key] = cleaned.joinToString(", ")
                continue
            }
            out[key] = value
        }

        if (maxAgeSeconds > 0) {
            val directive = if (statusCode in 200..299) "public, max-age=$maxAgeSeconds" else "no-store"
            out["Cache-Control"] = directive
        } else {
            out["Cache-Control"] = "no-store"
        }
        // Debug-Hilfe, sichtbar in chrome://inspect; fuer die Seite selbst wirkungslos.
        out["X-Console-Pocket-Cache"] = if (cacheHit) "hit" else "miss"
        return out
    }

    fun reasonPhrase(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> if (statusCode in 200..299) "OK" else "Status $statusCode"
    }
}
