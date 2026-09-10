package app.consolepocket.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.consolepocket.ConsolePocketApp
import app.consolepocket.downloads.DownloadStore
import app.consolepocket.model.ActiveEngine
import app.consolepocket.model.ConsoleUrls
import app.consolepocket.model.EngineMode
import app.consolepocket.model.TabSession
import app.consolepocket.state.Prefs
import app.consolepocket.web.WebViewHost
import app.consolepocket.web.WebViewPool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hält den gesamten UI-Zustand und ist gleichzeitig der [WebViewHost] fuer die WebView-Clients.
 *
 * Der WebViewPool lebt im Application-Graph und uebersteht damit Activity-Recreation und
 * ViewModel-Neuaufbau; dieses ViewModel ist nur die Bruecke zur Compose-UI.
 */
class AppViewModel(application: Application) : AndroidViewModel(application), WebViewHost {

    private val app: Application = application
    private val graph = (application as ConsolePocketApp).graph
    private val pool: WebViewPool get() = graph.webViewPool

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _fileChooser = MutableStateFlow<FileChooserRequest?>(null)
    val fileChooser: StateFlow<FileChooserRequest?> = _fileChooser.asStateFlow()

    private var pendingJsResult: JsResult? = null
    private var pendingJsPromptResult: JsPromptResult? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var persistJob: Job? = null
    private var statsJob: Job? = null

    init {
        viewModelScope.launch {
            graph.prefs.flow.collectLatest { p ->
                _ui.update { state ->
                    state.copy(
                        prefs = p,
                        engine = resolveEngine(p),
                        webViewVersion = graph.configurer.chromeVersionOf(app).orEmpty(),
                        webViewTooOld = graph.configurer.webViewMajorVersion(app) in 1..119,
                        customTabsAvailable = graph.customTabs.isAvailable(),
                    )
                }
                if (resolveEngine(p) == ActiveEngine.WEBVIEW && _ui.value.tabs.isEmpty()) {
                    restoreOrCreateTabs(p)
                }
            }
        }

        viewModelScope.launch {
            graph.downloads.items.collect { items ->
                _ui.update { it.copy(downloads = items) }
            }
        }

        observeConnectivity()
    }

    // ------------------------------------------------------------------ Engine

    private fun resolveEngine(p: Prefs): ActiveEngine = when (p.engineMode) {
        EngineMode.WEBVIEW -> ActiveEngine.WEBVIEW
        EngineMode.CUSTOM_TABS -> ActiveEngine.CUSTOM_TABS
        EngineMode.AUTO -> if (p.degradedToCustomTabs) ActiveEngine.CUSTOM_TABS else ActiveEngine.WEBVIEW
    }

    fun setEngineMode(mode: EngineMode) {
        viewModelScope.launch {
            graph.prefs.setEngineMode(mode)
            if (mode == EngineMode.CUSTOM_TABS) {
                // RAM freigeben: WebViews werden nicht mehr angezeigt
                pool.destroyAll()
                _ui.update { it.copy(tabs = emptyList(), activeTabId = null, showSkeleton = false) }
            } else {
                if (_ui.value.tabs.isEmpty()) restoreOrCreateTabs(graph.prefs.current())
                graph.customTabs.unbind()
            }
        }
    }

    fun activateSafeMode() {
        _ui.update { it.copy(loginBlocked = false) }
        setEngineMode(EngineMode.CUSTOM_TABS)
    }

    fun dismissLoginBlocked() = _ui.update { it.copy(loginBlocked = false) }

    // ------------------------------------------------------------------ Navigation / Tabs

    private fun restoreOrCreateTabs(p: Prefs) {
        val restored = p.tabs
        if (restored.isEmpty()) {
            createTab(p.startUrl)
            return
        }
        val tabs = restored.map { session ->
            TabUi(id = session.id, title = session.title, url = session.url, initialUrl = session.url)
        }
        val active = p.activeTabId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        _ui.update { it.copy(tabs = tabs, activeTabId = active, showSkeleton = true) }
    }

    fun createTab(url: String? = null): String {
        val target = url ?: _ui.value.prefs.startUrl.ifBlank { ConsoleUrls.DASHBOARD }
        val id = WebViewPool.newTabId()
        _ui.update { state ->
            val tabs = state.tabs + TabUi(id = id, url = target, initialUrl = target)
            state.copy(tabs = tabs, activeTabId = id, showSkeleton = true, screen = Screen.BROWSER)
        }
        persistTabs()
        return id
    }

    fun openUrl(url: String, newTab: Boolean = false) {
        val engine = _ui.value.engine
        if (engine == ActiveEngine.CUSTOM_TABS) {
            launchCustomTab(url)
            return
        }
        if (newTab || _ui.value.activeTab == null) {
            createTab(url)
            return
        }
        val tabId = _ui.value.activeTabId
        if (tabId == null) {
            createTab(url)
            return
        }
        _ui.update { state ->
            state.copy(
                tabs = state.tabs.map { if (it.id == tabId) it.copy(url = url, initialUrl = url) else it },
                isLoading = true,
                showSkeleton = false,
            )
        }
        pool.load(tabId, url)
        persistTabs()
    }

    fun selectTab(tabId: String) {
        _ui.update { state ->
            if (state.tabs.none { it.id == tabId }) return@update state
            state.copy(
                activeTabId = tabId,
                progress = 0,
                isLoading = false,
                showSkeleton = false,
            )
        }
        persistTabs()
    }

    fun closeTab(tabId: String) {
        pool.close(tabId)
        _ui.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            val active = if (state.activeTabId == tabId) remaining.lastOrNull()?.id else state.activeTabId
            state.copy(tabs = remaining, activeTabId = active, showSkeleton = remaining.isEmpty())
        }
        persistTabs()
    }

    fun reload(bypassCache: Boolean = false) {
        val tabId = _ui.value.activeTabId ?: return
        _ui.update { it.copy(isLoading = true) }
        pool.reload(tabId, bypassCache)
        if (bypassCache) showMessage("Cache für diese Seite umgangen")
    }

    fun goBack() = _ui.value.activeTabId?.let { pool.goBack(it) }

    fun goForward() = _ui.value.activeTabId?.let { pool.goForward(it) }

    fun stopLoading() = _ui.value.activeTabId?.let { pool.stop(it) }

    fun copyCurrentUrl() {
        val url = _ui.value.activeTab?.url ?: return
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("console-url", url))
        showMessage("URL kopiert")
    }

    // ------------------------------------------------------------------ Custom Tabs (Fallback)

    private fun launchCustomTab(url: String) {
        if (!graph.customTabs.isAvailable()) {
            showMessage("Kein Browser mit Custom-Tabs-Unterstützung gefunden")
            return
        }
        runCatching { graph.customTabs.bind() }
        graph.customTabs.open(url)
    }

    fun prelaunchCustomTab(url: String) {
        runCatching {
            graph.customTabs.bind()
            graph.customTabs.prelaunch(url)
        }
    }

    // ------------------------------------------------------------------ Screens

    fun setScreen(screen: Screen) {
        _ui.update { it.copy(screen = screen) }
        if (screen == Screen.DEBUG) startStatsTicker() else stopStatsTicker()
    }

    private fun startStatsTicker() {
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            while (true) {
                _ui.update {
                    it.copy(
                        stats = graph.stats.snapshot(),
                        statsLog = graph.stats.recent(200),
                        cacheBytes = graph.cacheSizeBytes(),
                        cacheEntries = graph.cacheEntryCount(),
                    )
                }
                delay(1000)
            }
        }
    }

    private fun stopStatsTicker() {
        statsJob?.cancel()
        statsJob = null
    }

    // ------------------------------------------------------------------ Debug / Messen

    /** Kill-Switch fuer die komplette Interception (Referenzmessung mit nacktem Chromium). */
    fun interceptorEnabled(): Boolean = graph.pipeline.enabled

    fun setInterceptorEnabled(enabled: Boolean) {
        graph.pipeline.enabled = enabled
        showMessage(if (enabled) "Interceptor aktiv" else "Interceptor aus (Referenzmessung)")
    }

    fun verifyCacheIntegrity() {
        viewModelScope.launch {
            val dropped = graph.io { graph.assetCache.verifyIntegrity() }
            showMessage(if (dropped == 0) "Cache intakt" else "$dropped korrupte Einträge verworfen")
            _ui.update { it.copy(cacheBytes = graph.cacheSizeBytes(), cacheEntries = graph.cacheEntryCount()) }
        }
    }

    fun clearLog() = graph.stats.clearLog()

    fun clearCache() {
        graph.clearCache()
        showMessage("Cache wird geleert")
        viewModelScope.launch {
            delay(400)
            _ui.update { it.copy(cacheBytes = graph.cacheSizeBytes(), cacheEntries = graph.cacheEntryCount()) }
        }
    }

    fun setRuleMode(mode: app.consolepocket.cache.RuleMode) {
        viewModelScope.launch { graph.prefs.setRuleMode(mode) }
    }

    // ------------------------------------------------------------------ Prefs-Aktionen

    fun setCacheLimitMb(mb: Int) = viewModelScope.launch { graph.prefs.setCacheLimitMb(mb) }

    fun setDarkMode(mode: app.consolepocket.model.DarkMode) =
        viewModelScope.launch { graph.prefs.setDarkMode(mode) }

    fun setWebDarkening(enabled: Boolean) = viewModelScope.launch { graph.prefs.setWebDarkening(enabled) }

    fun setTextZoom(zoom: Int) = viewModelScope.launch { graph.prefs.setTextZoom(zoom) }

    fun setImagesOff(off: Boolean) = viewModelScope.launch { graph.prefs.setImagesOff(off) }

    fun setStartUrl(url: String) = viewModelScope.launch { graph.prefs.setStartUrl(url) }

    fun setKeepLinksInApp(keep: Boolean) = viewModelScope.launch { graph.prefs.setKeepLinksInApp(keep) }

    // ------------------------------------------------------------------ Dialoge (in-app)

    fun resolveJsDialog(confirmed: Boolean, input: String? = null) {
        _ui.update { it.copy(jsDialog = null) }
        pendingJsPromptResult?.let { result ->
            if (confirmed) result.confirm(input.orEmpty()) else result.cancel()
            pendingJsPromptResult = null
            pendingJsResult = null
            return
        }
        pendingJsResult?.let { result ->
            if (confirmed) result.confirm() else result.cancel()
            pendingJsResult = null
        }
    }

    fun resolveExternalAction(confirmed: Boolean) {
        val action = _ui.value.externalAction ?: return
        _ui.update { it.copy(externalAction = null) }
        if (!confirmed) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }.onFailure { showMessage("Keine App für ${action.label} gefunden") }
    }

    fun onFilesPicked(uris: List<Uri>) {
        val callback = pendingFileCallback
        pendingFileCallback = null
        _fileChooser.value = null
        if (callback == null) return
        if (uris.isEmpty()) callback.onReceiveValue(null)
        else callback.onReceiveValue(uris.toTypedArray())
    }

    fun deleteDownload(id: String) = graph.downloads.delete(id)

    fun openDownload(item: DownloadStore.Item) {
        val uri = graph.downloads.shareUri(item)
        if (uri == null) {
            showMessage("Datei nicht gefunden")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                .setDataAndType(uri, item.mimeType)
            app.startActivity(intent)
        }.onFailure { showMessage("Keine App zum Öffnen dieses Dateityps gefunden") }
    }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun consumeSslError() = _ui.update { it.copy(sslError = null) }

    fun consumeLoadError() = _ui.update { it.copy(loadError = null) }

    fun consumeRendererRestart() = _ui.update { it.copy(rendererRestarted = false) }

    // ------------------------------------------------------------------ WebViewHost

    override fun onProgress(tabId: String, progress: Int) {
        _ui.update { it.copy(progress = progress, isLoading = progress in 1..99) }
    }

    override fun onPageStarted(tabId: String, url: String) {
        _ui.update { state ->
            state.copy(
                isLoading = true,
                progress = 0,
                loadError = null,
                tabs = state.tabs.map { if (it.id == tabId) it.copy(url = url) else it },
            )
        }
    }

    override fun onPageCommitVisible(tabId: String, url: String) {
        if (tabId != _ui.value.activeTabId) return
        _ui.update { it.copy(showSkeleton = false) }
    }

    override fun onPageFinished(tabId: String, url: String) {
        _ui.update { state ->
            state.copy(
                isLoading = false,
                progress = 100,
                showSkeleton = if (tabId == state.activeTabId) false else state.showSkeleton,
                tabs = state.tabs.map { if (it.id == tabId) it.copy(url = url) else it },
            )
        }
        persistTabs()
    }

    override fun onTitle(tabId: String, title: String) {
        _ui.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(title = title) else it })
        }
        persistTabs()
    }

    override fun onHistory(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        _ui.update { state ->
            state.copy(
                tabs = state.tabs.map {
                    if (it.id == tabId) it.copy(canGoBack = canGoBack, canGoForward = canGoForward) else it
                },
            )
        }
    }

    override fun onUrlSettled(tabId: String, url: String) {
        _ui.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(url = url) else it })
        }
        persistTabs()
    }

    override fun onExternalAction(tabId: String, url: String, scheme: String, label: String) {
        _ui.update { it.copy(externalAction = ExternalActionUi(url, label, scheme)) }
    }

    override fun onLoginBlocked(tabId: String, url: String) {
        if (_ui.value.prefs.engineMode == EngineMode.CUSTOM_TABS) return
        _ui.update { it.copy(loginBlocked = true) }
        viewModelScope.launch { graph.prefs.markDegradedToCustomTabs() }
    }

    override fun onRendererGone(tabId: String) {
        _ui.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            state.copy(
                tabs = remaining,
                activeTabId = remaining.lastOrNull()?.id,
                rendererRestarted = true,
                showSkeleton = remaining.isEmpty(),
            )
        }
        // Tab neu aufbauen - dieselbe URL, in derselben App, kein Fenster.
        val lastUrl = _ui.value.tabs.firstOrNull()?.url ?: _ui.value.prefs.startUrl
        createTab(lastUrl)
        persistTabs()
    }

    override fun onLoadError(tabId: String, url: String, code: Int, description: String) {
        if (tabId != _ui.value.activeTabId) return
        _ui.update { it.copy(loadError = "$description ($code)", isLoading = false, showSkeleton = false) }
    }

    override fun onSslError(tabId: String, url: String) {
        _ui.update { it.copy(sslError = url) }
    }

    override fun onJsAlert(tabId: String, url: String, message: String, result: JsResult) {
        pendingJsResult = result
        _ui.update { it.copy(jsDialog = JsDialogUi(JsDialogUi.Kind.ALERT, message)) }
    }

    override fun onJsConfirm(tabId: String, url: String, message: String, result: JsResult) {
        pendingJsResult = result
        _ui.update { it.copy(jsDialog = JsDialogUi(JsDialogUi.Kind.CONFIRM, message)) }
    }

    override fun onJsPrompt(
        tabId: String,
        url: String,
        message: String,
        defaultText: String,
        result: JsPromptResult,
    ) {
        pendingJsPromptResult = result
        _ui.update { it.copy(jsDialog = JsDialogUi(JsDialogUi.Kind.PROMPT, message, defaultText)) }
    }

    override fun onFileChooser(
        tabId: String,
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        isCaptureEnabled: Boolean,
    ) {
        pendingFileCallback = callback
        _fileChooser.value = FileChooserRequest(acceptTypes, allowMultiple = false)
    }

    override fun onPermissionBlocked(tabId: String, resource: String) {
        showMessage("Berechtigungsanfrage der Seite abgelehnt: $resource")
    }

    override fun onTabCreated(tabId: String) {
        _ui.update { state ->
            if (state.tabs.any { it.id == tabId }) return@update state
            state.copy(
                tabs = state.tabs + TabUi(id = tabId, title = "Neuer Tab"),
                activeTabId = tabId,
                screen = Screen.BROWSER,
            )
        }
        persistTabs()
    }

    override fun onDownloadStarted(fileName: String) {
        showMessage("Download gestartet: $fileName")
    }

    override fun onMessage(tabId: String, message: String) = showMessage(message)

    // ------------------------------------------------------------------ Persistenz

    private fun persistTabs() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(600) // entprellt: nicht pro History-Eintrag schreiben
            val state = _ui.value
            val sessions = state.tabs.map {
                TabSession(id = it.id, url = it.url.ifBlank { it.initialUrl }, title = it.title, lastAccess = System.currentTimeMillis())
            }
            runCatching { graph.prefs.saveTabs(sessions, state.activeTabId) }
        }
    }

    private fun observeConnectivity() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val update: () -> Unit = {
            val online = runCatching {
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(true)
            _ui.update { it.copy(offline = !online) }
        }
        update()
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = update()
                    override fun onLost(network: android.net.Network) = update()
                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        capabilities: NetworkCapabilities,
                    ) = update()
                },
            )
        }
    }

    override fun onCleared() {
        stopStatsTicker()
        graph.onAppBackgrounded()
        pool.destroyAll()
        super.onCleared()
    }
}
