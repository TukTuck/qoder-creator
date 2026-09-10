package app.consolepocket.cache

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Android-Glue: Outcome der [RequestPipeline] -> `WebResourceResponse`.
 *
 * Wichtig (ADR-002, Abschnitt 4):
 *  - immer den 6-Argumente-Konstruktor verwenden, sonst wird implizit HTTP 200 geliefert
 *  - reasonPhrase darf nicht leer sein
 *  - mimeType darf nicht null sein
 *  - Body wird als dekodierter Stream uebergeben (Content-Encoding-Header wurden entfernt)
 */
internal object WebResourceResponses {

    fun serve(outcome: RequestPipeline.Outcome.Serve): WebResourceResponse {
        val mime = outcome.contentType.ifBlank { "application/octet-stream" }
        val encoding = if (isTextual(mime)) "UTF-8" else null
        return WebResourceResponse(
            mime,
            encoding,
            outcome.statusCode,
            ResponseFactory.reasonPhrase(outcome.statusCode),
            outcome.headers,
            ByteArrayInputStream(outcome.body),
        )
    }

    fun block(status: Int): WebResourceResponse {
        val code = if (status in 200..599) status else 204
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            code,
            ResponseFactory.reasonPhrase(code),
            mapOf(
                "Cache-Control" to "no-store",
                "X-Console-Pocket-Blocked" to "1",
            ),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    fun empty(status: Int = 204): WebResourceResponse = block(status)

    private fun isTextual(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("text/") || m.contains("json") || m.contains("javascript") ||
            m.contains("xml") || m.contains("+json") || m.contains("ecmascript")
    }
}
