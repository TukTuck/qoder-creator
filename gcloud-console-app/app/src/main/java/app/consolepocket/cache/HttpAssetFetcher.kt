package app.consolepocket.cache

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Netzwerkzugriff fuer Cache-Misses (PLAN L1). Ein geteilter OkHttp-Client mit HTTP/2 und
 * Connection-Pool, damit hundert parallele Asset-Requests nicht hundert TLS-Handshakes kosten.
 *
 * Wichtige Details:
 * - `Accept-Encoding` wird NICHT weitergereicht: OkHttp setzt dann selbst `gzip`, dekodiert
 *   transparent und entfernt `Content-Encoding`/`Content-Length` aus den Antwort-Headern.
 *   Wir speichern also immer den dekodierten Body (ADR-002, Abschnitt 4.3, Variante B).
 * - `Cookie`/`Authorization` werden entfernt und Antworten mit `Set-Cookie`/`Vary: Cookie`
 *   nicht gecacht: sonst koennten personalisierte Inhalte zwischen Konten "kleben" bleiben.
 * - Kurze Timeouts: shouldInterceptRequest laeuft auf einem WebView-Netzwerk-Thread, lange
 *   Blockaden wuerden die ganze Seite ausbremsen.
 */
class HttpAssetFetcher(
    private val client: OkHttpClient,
) {

    data class Result(
        val statusCode: Int,
        val contentType: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val durationMs: Long,
        val cacheable: Boolean,
    )

    fun fetch(url: String, method: String = "GET", requestHeaders: Map<String, String> = emptyMap()): Result? {
        val builder = Request.Builder().url(url)
        var sawAuthHeader = false
        requestHeaders.forEach { (name, value) ->
            val lower = name.lowercase()
            when {
                lower in SKIP_REQUEST_HEADERS -> Unit
                lower == "cookie" || lower == "authorization" || lower == "proxy-authorization" -> {
                    sawAuthHeader = true
                }

                else -> runCatching { builder.addHeader(name, value) }
            }
        }
        if (method.equals("HEAD", ignoreCase = true)) builder.head() else builder.get()

        val started = System.nanoTime()
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val headers = response.headers.toMultimap()
                    .mapKeys { it.key }
                    .filterKeys { it.isNotBlank() }
                val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val body = response.body?.bytes() ?: ByteArray(0)
                val contentType = response.header("Content-Type") ?: guessContentType(url)
                val cacheable = response.isSuccessful &&
                    !sawAuthHeader &&
                    headers.keys.none { it.equals("Set-Cookie", true) } &&
                    !variesOnCookie(headers) &&
                    response.code != 206 // Range-Antworten nicht als vollstaendiges Asset cachen
                Result(
                    statusCode = response.code,
                    contentType = contentType,
                    headers = headers,
                    body = body,
                    durationMs = duration,
                    cacheable = cacheable,
                )
            }
        }.getOrNull()
    }

    private fun variesOnCookie(headers: Map<String, List<String>>): Boolean =
        headers.entries.any { (k, v) -> k.equals("Vary", true) && v.any { it.contains("Cookie", true) } }

    companion object {
        private val SKIP_REQUEST_HEADERS = setOf(
            "accept-encoding", // OkHttp macht das selbst -> transparentes Dekodieren
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length",
            "host", // setzt OkHttp aus der URL
        )

        fun guessContentType(url: String): String = when (UrlExtension.of(url)) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "html", "htm" -> "text/html"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

        fun defaultClient(connectTimeoutSeconds: Int = 5, readTimeoutSeconds: Int = 15): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}

/** Kleine Hilfe, damit guessContentType ohne Android-Uri auskommt. */
internal object UrlExtension {
    fun of(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/', "")
        if (!last.contains('.')) return ""
        return last.substringAfterLast('.', "").lowercase()
    }
}
