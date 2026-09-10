package app.consolepocket.ui

import app.consolepocket.downloads.DownloadStore
import app.consolepocket.metrics.CacheStats
import app.consolepocket.model.ActiveEngine
import app.consolepocket.state.Prefs

enum class Screen { BROWSER, SETTINGS, DOWNLOADS, DEBUG }

/** Ein In-App-Tab. Kein Systemfenster, kein zweiter Task. */
data class TabUi(
    val id: String,
    val title: String = "",
    val url: String = "",
    val initialUrl: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

data class JsDialogUi(
    val kind: Kind,
    val message: String,
    val defaultText: String = "",
) {
    enum class Kind { ALERT, CONFIRM, PROMPT }
}

data class ExternalActionUi(val url: String, val label: String, val scheme: String)

data class FileChooserRequest(val acceptTypes: Array<String>, val allowMultiple: Boolean) {
    val mimeTypes: Array<String>
        get() = acceptTypes
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith(".") }
            .let { if (it.isEmpty()) arrayOf("*/*") else it.toTypedArray() }

    override fun equals(other: Any?): Boolean =
        other is FileChooserRequest && other.acceptTypes.contentEquals(acceptTypes) &&
            other.allowMultiple == allowMultiple

    override fun hashCode(): Int = acceptTypes.contentHashCode() * 31 + allowMultiple.hashCode()
}

data class UiState(
    val screen: Screen = Screen.BROWSER,
    val prefs: Prefs = Prefs(),
    val engine: ActiveEngine = ActiveEngine.WEBVIEW,
    val tabs: List<TabUi> = emptyList(),
    val activeTabId: String? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val showSkeleton: Boolean = true,
    val offline: Boolean = false,
    val loginBlocked: Boolean = false,
    val rendererRestarted: Boolean = false,
    val sslError: String? = null,
    val loadError: String? = null,
    val message: String? = null,
    val jsDialog: JsDialogUi? = null,
    val externalAction: ExternalActionUi? = null,
    val downloads: List<DownloadStore.Item> = emptyList(),
    val stats: CacheStats.Snapshot = CacheStats.Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0),
    val statsLog: List<CacheStats.RequestLogEntry> = emptyList(),
    val cacheBytes: Long = 0,
    val cacheEntries: Int = 0,
    val webViewVersion: String = "",
    val webViewTooOld: Boolean = false,
    val customTabsAvailable: Boolean = true,
) {
    val activeTab: TabUi? get() = tabs.firstOrNull { it.id == activeTabId }
}
