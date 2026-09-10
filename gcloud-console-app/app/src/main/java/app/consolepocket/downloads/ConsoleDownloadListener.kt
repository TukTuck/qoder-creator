package app.consolepocket.downloads

import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import app.consolepocket.web.WebViewHost

/**
 * Downloads bleiben in der App: kein `DownloadManager`-Handoff an den Browser, kein ACTION_VIEW.
 * Die Cookies der Console-Session werden mitgegeben, damit authentifizierte Exporte funktionieren.
 */
class ConsoleDownloadListener(
    private val tabId: String,
    private val downloads: DownloadStore,
    private val host: WebViewHost,
) : DownloadListener {

    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        if (url.startsWith("blob:")) {
            // Blob-Downloads (z. B. CSV-Export im Browser-Kontext) kann der native Fetcher nicht
            // hoeren. Hinweis statt stiller Fehler.
            host.onMessage(
                tabId,
                "Blob-Download erkannt - bitte den Export erneut ausloesen (Chrome-Blobs sind app-intern nicht lesbar).",
            )
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        host.onDownloadStarted(fileName)
        downloads.enqueue(url, fileName, mimeType, contentLength, userAgent, cookie)
    }
}
