package app.consolepocket.web

/**
 * Chrome-artiger User-Agent (PLAN Kapitel 6, Option O1).
 *
 * Warum: Google blockiert OAuth aus eingebetteten WebViews und erkennt diese am UA-Token `; wv`
 * (Ergebnis: `403 disallowed_useragent`). Ein Chrome-artiger UA ohne `; wv`, ohne `Build/…` und
 * ohne `Version/4.0` entspricht dem, was Chrome auf demselben Geraet senden wuerde.
 *
 * Die Version wird NICHT hartkodiert, sondern aus dem installierten WebView-Paket uebernommen
 * (siehe [currentChromeVersion]) - hartkodierte, veraltete Strings sind die haeufigste Ursache
 * fuer spaeter brechende Login-Flows.
 *
 * Hinweis: Das ist eine Umgehung einer Google-Sicherheitspolicy. Fuer ein Privat-/Sideload-Tool
 * vertretbar (siehe PLAN Kapitel 12); fuer einen Play-Store-Release nicht empfohlen. Die App
 * erkennt die Blockade trotzdem und bietet den policy-sicheren Custom-Tabs-Modus als Fallback.
 */
object UserAgentProvider {

    private val WV_TOKEN = Regex(";\\s*wv\\s*(?=[;)])")
    private val BUILD_TOKEN = Regex("\\s+Build/[^;)]+")
    private val VERSION_TOKEN = Regex("\\s+Version/[0-9.]+")
    private val CHROME_TOKEN = Regex("Chrome/[0-9][0-9.A-Za-z-]*")

    /**
     * @param defaultWebViewUa Original-UA des WebView (`WebSettings.userAgentString`)
     * @param chromeVersion    Version aus `WebViewCompat.getCurrentWebViewPackage()`, z. B. "148.0.7204.5"
     */
    fun chromeLike(defaultWebViewUa: String, chromeVersion: String? = null): String {
        if (defaultWebViewUa.isBlank()) return defaultWebViewUa
        var ua = WV_TOKEN.replace(defaultWebViewUa, "")
        ua = BUILD_TOKEN.replace(ua, "")
        ua = VERSION_TOKEN.replace(ua, "")

        val major = chromeVersion?.substringBefore('.')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        if (!major.isNullOrEmpty()) {
            val full = normalizeChromeVersion(chromeVersion)
            ua = if (CHROME_TOKEN.containsMatchIn(ua)) {
                CHROME_TOKEN.replace(ua, "Chrome/$full")
            } else {
                ua.replace("AppleWebKit/", "Chrome/$full AppleWebKit/")
            }
        }
        return ua.replace("  ", " ").trim()
    }

    /** WebView-Versionen sehen z. B. "148.0.7204.5" aus; Chrome-UA nutzt "148.0.0.0". */
    private fun normalizeChromeVersion(version: String): String {
        val parts = version.split('.')
        val major = parts.getOrElse(0) { "0" }
        val build = parts.getOrElse(2) { "0" }
        return "$major.0.$build.0"
    }

    fun currentChromeVersion(packageVersionName: String?): String? =
        packageVersionName?.takeIf { it.isNotBlank() && it.first().isDigit() }

    /** Erkennung der Google-Blockade anhand von URL/Titel - ohne JS in Google-Seiten zu lesen. */
    fun looksLikeOAuthBlock(url: String?, title: String?): Boolean {
        val u = url?.lowercase().orEmpty()
        val t = title?.lowercase().orEmpty()
        if (u.contains("disallowed_useragent") || u.contains("oautherror")) return true
        if (t.contains("disallowed_useragent")) return true
        val blockedPhrases = listOf(
            "eingebettete webview",
            "embedded webview",
            "can't sign in from this screen",
            "kannst dich auf diesem bildschirm nicht anmelden",
            "diese app entspricht nicht den richtlinien von google",
        )
        return blockedPhrases.any { t.contains(it) }
    }
}
