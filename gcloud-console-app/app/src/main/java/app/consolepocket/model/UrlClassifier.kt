package app.consolepocket.model

/**
 * Reine URL-Klassifikation mit [java.net.URI] statt android.net.Uri - dadurch sind
 * NavigationPolicy und RuleEngine ohne Robolectric auf der JVM unit-testbar.
 */
object UrlClassifier {

    /** Google-Domains, die fuer Login/Session zwingend sind und niemals geblockt werden. */
    private val authHosts = setOf(
        "accounts.google.com",
        "apis.google.com",
        "accounts.youtube.com",
        "myaccount.google.com",
        "oauth2.googleapis.com",
        "www.googleapis.com",
    )

    /** Domains der Console-Familie (inkl. Subdomains). */
    private val consoleRoots = listOf(
        "console.cloud.google.com",
        "console.cloud.google.com.",
        "cloud.google.com",
        "console.developers.google.com",
        "pantheon.corp.google.com",
    )

    fun parse(url: String): UrlInfo {
        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            null
        }
        val scheme = uri?.scheme?.lowercase() ?: substringBefore(url, ":", "").lowercase()
        val host = (uri?.host ?: extractHostFallback(url))?.lowercase()?.trimEnd('.')
        val path = uri?.rawPath ?: ""
        return UrlInfo(url = url, scheme = scheme, host = host, path = path)
    }

    private fun extractHostFallback(url: String): String? {
        val afterScheme = substringAfter(url, "://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBeforeLast('@', "")
            .ifEmpty { afterScheme.substringBefore('/') }
    }

    fun isHttp(info: UrlInfo): Boolean = info.scheme == "http" || info.scheme == "https"

    fun isConsole(info: UrlInfo): Boolean {
        val host = info.host ?: return false
        return consoleRoots.any { host == it || host.endsWith(".$it") }
    }

    fun isGoogle(info: UrlInfo): Boolean {
        val host = info.host ?: return false
        return host == "google.com" || host.endsWith(".google.com") ||
            host == "gstatic.com" || host.endsWith(".gstatic.com") ||
            host == "googleapis.com" || host.endsWith(".googleapis.com") ||
            host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
            host == "ggpht.com" || host.endsWith(".ggpht.com")
    }

    fun isAuthCritical(info: UrlInfo): Boolean {
        val host = info.host ?: return false
        if (authHosts.contains(host)) return true
        // Alle API-Endpunkte (*.googleapis.com) sind funktionskritisch -> nie blocken.
        return host.endsWith(".googleapis.com")
    }

    /** Nicht-http-Schemes, die eine andere App oeffnen wuerden (mailto, tel, intent, market ...). */
    fun isExternalScheme(info: UrlInfo): Boolean =
        !isHttp(info) && info.scheme.isNotEmpty() && info.scheme != "about" &&
            info.scheme != "blob" && info.scheme != "data" && info.scheme != "javascript" &&
            info.scheme != "file"

    /** Grobe Dateiendung aus dem Pfad (ohne Query), lowercase, ohne Punkt. */
    fun extension(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        val last = clean.substringAfterLast('/', "")
        if (!last.contains('.')) return ""
        return last.substringAfterLast('.', "").lowercase()
    }
}

data class UrlInfo(
    val url: String,
    val scheme: String,
    val host: String?,
    val path: String,
)
