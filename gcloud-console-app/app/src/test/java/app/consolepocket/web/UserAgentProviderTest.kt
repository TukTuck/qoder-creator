package app.consolepocket.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Chrome-artige UA ist der Schluessel zum Login im WebView (PLAN Kapitel 6, Option O1).
 * Diese Tests sichern, dass die WebView-Marker wirklich entfernt werden - ein vergessenes
 * "; wv" bedeutet "disallowed_useragent".
 */
class UserAgentProviderTest {

    private val webviewUa =
        "Mozilla/5.0 (Linux; Android 17; Pixel 10 Build/AP3A.240617.008; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/148.0.7204.5 Mobile Safari/537.36"

    @Test
    fun `entfernt wv-token build-token und version-4`() {
        val ua = UserAgentProvider.chromeLike(webviewUa, "148.0.7204.5")
        assertFalse("wv-Token muss weg: $ua", ua.contains("; wv"))
        assertFalse("Build/-Token muss weg: $ua", ua.contains("Build/"))
        assertFalse("Version/4.0 muss weg: $ua", ua.contains("Version/4.0"))
        assertTrue("Geraetemodell bleibt erhalten: $ua", ua.contains("Pixel 10"))
    }

    @Test
    fun `chrome-version wird normalisiert uebernommen`() {
        val ua = UserAgentProvider.chromeLike(webviewUa, "148.0.7204.5")
        assertTrue("Chrome/148.0.7204.0 erwartet: $ua", ua.contains("Chrome/148.0.7204.0"))
    }

    @Test
    fun `ohne version bleibt der vorhandene chrome-token`() {
        val ua = UserAgentProvider.chromeLike(webviewUa, null)
        assertTrue(ua.contains("Chrome/148.0.7204.5"))
    }

    @Test
    fun `ergebnis entspricht dem chrome-muster`() {
        val ua = UserAgentProvider.chromeLike(webviewUa, "148.0.7204.5")
        assertEquals(
            "Mozilla/5.0 (Linux; Android 17; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/148.0.7204.0 Mobile Safari/537.36",
            ua,
        )
    }

    @Test
    fun `leerer ua bleibt leer`() {
        assertEquals("", UserAgentProvider.chromeLike("", "148.0.0.0"))
    }

    @Test
    fun `version ohne chrome-token wird eingefuegt`() {
        val odd = "Mozilla/5.0 (Linux; Android 17; Pixel 10; wv) AppleWebKit/537.36 Mobile Safari/537.36"
        val ua = UserAgentProvider.chromeLike(odd, "150.0.9999.1")
        assertTrue(ua.contains("Chrome/150.0.9999.0"))
        assertFalse(ua.contains("; wv"))
    }

    @Test
    fun `oauth-blockade wird an url und titel erkannt`() {
        assertTrue(
            UserAgentProvider.looksLikeOAuthBlock(
                "https://accounts.google.com/signin/oautherror?oautherror=disallowed_useragent",
                "Anmelden",
            ),
        )
        assertTrue(
            UserAgentProvider.looksLikeOAuthBlock(
                "https://accounts.google.com/x",
                "You can't sign in from this screen because this app doesn't comply with Google's embedded webview policy",
            ),
        )
        assertTrue(
            UserAgentProvider.looksLikeOAuthBlock(
                "https://accounts.google.com/x",
                "Diese App entspricht nicht den Richtlinien von Google für eingebettete WebViews",
            ),
        )
        assertFalse(UserAgentProvider.looksLikeOAuthBlock("https://console.cloud.google.com/home", "Dashboard"))
    }
}
