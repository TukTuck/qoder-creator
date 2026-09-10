package app.consolepocket.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.consolepocket.cache.RuleMode
import app.consolepocket.model.ConsoleUrls
import app.consolepocket.model.DarkMode
import app.consolepocket.model.EngineMode
import app.consolepocket.model.TabSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Einzige DataStore-Instanz der App (top-level Delegate => ein Singleton pro Prozess).
 * Enthaelt Einstellungen UND den persistierten Tab-Zustand (Schicht L5: kein kalter Start).
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "console-pocket")

data class Prefs(
    val engineMode: EngineMode = EngineMode.AUTO,
    val degradedToCustomTabs: Boolean = false,
    val ruleMode: RuleMode = RuleMode.LOG_ONLY,
    val cacheLimitMb: Int = 256,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val webDarkening: Boolean = true,
    val textZoom: Int = 100,
    val imagesOff: Boolean = false,
    val startUrl: String = ConsoleUrls.DASHBOARD,
    val keepLinksInApp: Boolean = true,
    val tabs: List<TabSession> = emptyList(),
    val activeTabId: String? = null,
    /** Gelernte Top-N-Asset-URLs fuer das Prewarm (PLAN, Schicht L4). */
    val prefetchUrls: List<String> = emptyList(),
)

class AppPrefs(private val context: Context) {

    val flow: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            engineMode = p[KEY_ENGINE]?.let { runCatching { EngineMode.valueOf(it) }.getOrNull() }
                ?: EngineMode.AUTO,
            degradedToCustomTabs = p[KEY_DEGRADED] ?: false,
            ruleMode = p[KEY_RULE_MODE]?.let { runCatching { RuleMode.valueOf(it) }.getOrNull() }
                ?: RuleMode.LOG_ONLY,
            cacheLimitMb = p[KEY_CACHE_MB] ?: 256,
            darkMode = p[KEY_DARK]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
                ?: DarkMode.SYSTEM,
            webDarkening = p[KEY_WEB_DARKENING] ?: true,
            textZoom = p[KEY_TEXT_ZOOM] ?: 100,
            imagesOff = p[KEY_IMAGES_OFF] ?: false,
            startUrl = p[KEY_START_URL] ?: ConsoleUrls.DASHBOARD,
            keepLinksInApp = p[KEY_LINKS_IN_APP] ?: true,
            tabs = decodeTabs(p[KEY_TABS]),
            activeTabId = p[KEY_ACTIVE_TAB],
            prefetchUrls = decodeStringList(p[KEY_PREFETCH]),
        )
    }

    suspend fun current(): Prefs = flow.first()

    suspend fun setEngineMode(mode: EngineMode) =
        edit { it[KEY_ENGINE] = mode.name; it[KEY_DEGRADED] = false }

    suspend fun markDegradedToCustomTabs() = edit { it[KEY_DEGRADED] = true }

    suspend fun setRuleMode(mode: RuleMode) = edit { it[KEY_RULE_MODE] = mode.name }

    suspend fun setCacheLimitMb(mb: Int) = edit { it[KEY_CACHE_MB] = mb.coerceIn(32, 2048) }

    suspend fun setDarkMode(mode: DarkMode) = edit { it[KEY_DARK] = mode.name }

    suspend fun setWebDarkening(enabled: Boolean) = edit { it[KEY_WEB_DARKENING] = enabled }

    suspend fun setTextZoom(zoom: Int) = edit { it[KEY_TEXT_ZOOM] = zoom.coerceIn(50, 200) }

    suspend fun setImagesOff(off: Boolean) = edit { it[KEY_IMAGES_OFF] = off }

    suspend fun setStartUrl(url: String) = edit { it[KEY_START_URL] = url }

    suspend fun setKeepLinksInApp(keep: Boolean) = edit { it[KEY_LINKS_IN_APP] = keep }

    suspend fun saveTabs(tabs: List<TabSession>, activeTabId: String?) = edit {
        it[KEY_TABS] = encodeTabs(tabs)
        if (activeTabId == null) it.remove(KEY_ACTIVE_TAB) else it[KEY_ACTIVE_TAB] = activeTabId
    }

    suspend fun setPrefetchUrls(urls: List<String>) =
        edit { it[KEY_PREFETCH] = encodeStringList(urls.take(60)) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { prefs -> block(prefs) }
    }

    companion object {
        private val KEY_ENGINE = stringPreferencesKey("engine_mode")
        private val KEY_DEGRADED = booleanPreferencesKey("degraded_custom_tabs")
        private val KEY_RULE_MODE = stringPreferencesKey("rule_mode")
        private val KEY_CACHE_MB = intPreferencesKey("cache_limit_mb")
        private val KEY_DARK = stringPreferencesKey("dark_mode")
        private val KEY_WEB_DARKENING = booleanPreferencesKey("web_darkening")
        private val KEY_TEXT_ZOOM = intPreferencesKey("text_zoom")
        private val KEY_IMAGES_OFF = booleanPreferencesKey("images_off")
        private val KEY_START_URL = stringPreferencesKey("start_url")
        private val KEY_LINKS_IN_APP = booleanPreferencesKey("keep_links_in_app")
        private val KEY_TABS = stringPreferencesKey("tabs_json")
        private val KEY_ACTIVE_TAB = stringPreferencesKey("active_tab_id")
        private val KEY_PREFETCH = stringPreferencesKey("prefetch_urls")

        fun encodeStringList(values: List<String>): String {
            val arr = JSONArray()
            values.forEach { arr.put(it) }
            return arr.toString()
        }

        fun decodeStringList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
            }.getOrDefault(emptyList())
        }

        fun encodeTabs(tabs: List<TabSession>): String {
            val arr = JSONArray()
            tabs.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("url", t.url)
                        .put("title", t.title)
                        .put("lastAccess", t.lastAccess),
                )
            }
            return arr.toString()
        }

        fun decodeTabs(json: String?): List<TabSession> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").ifBlank { null } ?: return@mapNotNull null
                    val url = o.optString("url").ifBlank { null } ?: return@mapNotNull null
                    TabSession(
                        id = id,
                        url = url,
                        title = o.optString("title"),
                        lastAccess = o.optLong("lastAccess"),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
