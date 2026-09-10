package app.consolepocket.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Erzeugt und konfiguriert WebView-Instanzen (PLAN, Schicht L6).
 *
 * Zwei Tricks sind hier entscheidend:
 * 1. [MutableContextWrapper]: die Instanz gehoert der Application, wird aber fuer die Anzeige
 *    auf den Activity-Context umgebogen. Dadurch ueberlebt sie Configuration-Changes und
 *    Activity-Neuaufbau OHNE Reload - und leakt keine Activity.
 * 2. Alle Settings zentral an einer Stelle, damit Tabs sich nicht unterscheiden.
 */
class WebViewConfigurer(
    private val application: Context,
) {

    data class Settings(
        val textZoom: Int = 100,
        val imagesOff: Boolean = false,
        val webDarkening: Boolean = true,
        val chromeUserAgent: Boolean = true,
        val debugRemoteInspection: Boolean = false,
    )

    @SuppressLint("SetJavaScriptEnabled") // ohne JS keine Cloud Console
    fun create(context: Context = application): WebView {
        val wrapped = MutableContextWrapper(context)
        return WebView(wrapped).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Fluessigeres Scrollen auf Kosten von RAM - bei der Console lohnt das.
            settings.offscreenPreRaster = true
            // Cookies: Die Console braucht auch Third-Party-Cookies (SSO ueber accounts.google.com).
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@apply, true)
            }
        }
    }

    fun applySettings(view: WebView, settings: Settings) {
        val s: WebSettings = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = true

        // Desktop-Layout der Console nutzen (die mobile Ansicht ist funktionsarm).
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false

        // Sicherheit
        s.allowFileAccess = false // lokale Shell laeuft ueber WebViewAssetLoader
        s.allowContentAccess = true // Datei-Uploads
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = false

        // "Keine extra Fenster": window.open() laedt im selben View.
        s.setSupportMultipleWindows(false)

        s.loadsImagesAutomatically = !settings.imagesOff
        s.textZoom = settings.textZoom.coerceIn(50, 200)

        if (settings.chromeUserAgent) {
            val defaultUa = s.userAgentString
            s.userAgentString = UserAgentProvider.chromeLike(defaultUa, chromeVersionOf(view.context))
        }

        if (settings.webDarkening &&
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        ) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true)
        }

        if (settings.debugRemoteInspection) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /** Activity-Context fuer Dialoge/File-Picker setzen (Instanz bleibt dieselbe). */
    fun attach(view: WebView, uiContext: Context) {
        (view.context as? MutableContextWrapper)?.baseContext = uiContext
    }

    /** Activity-Referenz wieder abgeben -> kein Leak, Instanz bleibt warm. */
    fun detach(view: WebView) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        (view.context as? MutableContextWrapper)?.baseContext = application
    }

    fun chromeVersionOf(context: Context): String? = runCatching {
        androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName
    }.getOrNull()

    fun webViewMajorVersion(context: Context): Int =
        chromeVersionOf(context)?.substringBefore('.')?.toIntOrNull() ?: 0
}
