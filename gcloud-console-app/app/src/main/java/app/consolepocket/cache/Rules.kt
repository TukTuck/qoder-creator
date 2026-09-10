package app.consolepocket.cache

import app.consolepocket.model.UrlClassifier
import app.consolepocket.model.UrlInfo

/**
 * Betriebsmodus der Request-Pipeline.
 *
 * - [OFF]:       nichts abfangen, Chromium macht alles selbst (Referenzmessung / Notaus).
 * - [LOG_ONLY]:  Cache ist AKTIV (cachen + ausliefern), BLOCK-Regeln werden aber nur
 *                protokolliert. Standardmodus, bis der echte Traffic ausgewertet ist (M3 -> M4).
 * - [ENFORCE]:   Cache + Blocker scharf.
 */
enum class RuleMode { OFF, LOG_ONLY, ENFORCE }

enum class Policy {
    /** Chromium soll den Request selbst ausfuehren (wir mischen uns nicht ein). */
    IGNORE,

    /** Request verwerfen und mit einer leeren Antwort beantworten (Telemetrie/Logging). */
    BLOCK,

    /** Funktionskritisch/dynamisch: niemals cachen oder blocken. */
    PASSTHROUGH,

    /** Cache-First: Treffer sofort ausliefern, Miss laden + dauerhaft speichern. */
    CACHE_FIRST,

    /** Stale-While-Revalidate: sofort ausliefern und im Hintergrund aktualisieren. */
    SWR,

    /** Durch lokal gebuendelte Datei ersetzen (z. B. Fonts). */
    LOCAL_OVERRIDE,
}

data class MatchSpec(
    val hostPatterns: List<String> = emptyList(),
    val pathPatterns: List<String> = emptyList(),
    val extensionIn: Set<String> = emptySet(),
    val queryContains: List<String> = emptyList(),
) {
    /** Leere Gruppen bedeuten "egal"; alle nicht-leeren Gruppen muessen treffen (AND, intern OR). */
    fun matches(info: UrlInfo, rawQuery: String?, extension: String): Boolean {
        if (hostPatterns.isNotEmpty()) {
            val host = info.host ?: return false
            if (hostPatterns.none { GlobMatcher.matches(it, host) }) return false
        }
        if (pathPatterns.isNotEmpty()) {
            if (pathPatterns.none { GlobMatcher.matches(it, info.path) }) return false
        }
        if (extensionIn.isNotEmpty()) {
            if (extension.isEmpty() || extension !in extensionIn) return false
        }
        if (queryContains.isNotEmpty()) {
            val q = rawQuery.orEmpty()
            if (queryContains.none { q.contains(it, ignoreCase = true) }) return false
        }
        return true
    }

    val isEmpty: Boolean
        get() = hostPatterns.isEmpty() && pathPatterns.isEmpty() &&
            extensionIn.isEmpty() && queryContains.isEmpty()
}

data class Rule(
    val id: String,
    val enabled: Boolean,
    val priority: Int,
    val match: MatchSpec,
    val policy: Policy,
    val ttlDays: Int,
    val blockStatus: Int = 204,
    val localPath: String? = null,
    val note: String = "",
)

data class NeverIntercept(
    val methods: Set<String> = DEFAULT_METHODS,
    val mainFrame: Boolean = true,
    val hostPatterns: List<String> = emptyList(),
    val pathPatterns: List<String> = emptyList(),
) {
    companion object {
        /** POST/PUT/... haben in shouldInterceptRequest keinen Body -> prinzipiell tabu. */
        val DEFAULT_METHODS: Set<String> = setOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}

data class RuleSet(
    val version: Int = 0,
    val defaultMode: RuleMode = RuleMode.OFF,
    val neverIntercept: NeverIntercept = NeverIntercept(),
    val rules: List<Rule> = emptyList(),
    val immutableTtlDays: Int = 30,
    val swrTtlDays: Int = 7,
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 15,
    val cacheMaxBytes: Long = 256L * 1024 * 1024,
) {
    val sortedRules: List<Rule> by lazy {
        rules.filter { it.enabled && !it.match.isEmpty }.sortedByDescending { it.priority }
    }

    companion object {
        val EMPTY = RuleSet()
    }
}

data class Classification(
    val policy: Policy,
    val ruleId: String?,
    val ttlSeconds: Long,
    val blockStatus: Int = 204,
    val localPath: String? = null,
    val reason: String = "",
)

/**
 * Klassifiziert einen Request rein funktional (keine Android-Abhaengigkeit) -> unit-testbar.
 */
class RuleEngine(initial: RuleSet = RuleSet.EMPTY) {

    @Volatile
    private var current: RuleSet = initial

    val ruleSet: RuleSet get() = current

    fun update(set: RuleSet) {
        current = set
    }

    fun classify(method: String, url: String, isMainFrame: Boolean): Classification {
        val set = current
        val info = UrlClassifier.parse(url)
        val rawQuery = rawQueryOf(url)
        val ext = UrlClassifier.extension(info.path)
        val upperMethod = method.uppercase()

        if (!UrlClassifier.isHttp(info)) {
            return Classification(Policy.PASSTHROUGH, null, 0, reason = "non-http")
        }
        if (isMainFrame && set.neverIntercept.mainFrame) {
            return Classification(Policy.PASSTHROUGH, null, 0, reason = "main-frame")
        }
        if (upperMethod !in ALLOWED_METHODS || upperMethod in set.neverIntercept.methods) {
            return Classification(Policy.PASSTHROUGH, null, 0, reason = "method:$upperMethod")
        }
        if (UrlClassifier.isAuthCritical(info)) {
            return Classification(Policy.PASSTHROUGH, null, 0, reason = "auth-critical")
        }
        val host = info.host.orEmpty()
        if (set.neverIntercept.hostPatterns.any { GlobMatcher.matches(it, host) }) {
            return Classification(Policy.PASSTHROUGH, null, 0, reason = "never-intercept-host")
        }
        if (set.neverIntercept.pathPatterns.any { GlobMatcher.matches(it, info.path) }) {
            return Classification(Policy.PASSTHROUGH, null, 0, reason = "never-intercept-path")
        }

        val rule = set.sortedRules.firstOrNull { it.match.matches(info, rawQuery, ext) }
            ?: return Classification(Policy.IGNORE, null, 0, reason = "no-rule")

        val ttlSeconds = when (rule.policy) {
            Policy.CACHE_FIRST -> daysToSeconds(if (rule.ttlDays > 0) rule.ttlDays else set.immutableTtlDays)
            Policy.SWR -> daysToSeconds(if (rule.ttlDays > 0) rule.ttlDays else set.swrTtlDays)
            else -> 0
        }
        return Classification(
            policy = rule.policy,
            ruleId = rule.id,
            ttlSeconds = ttlSeconds,
            blockStatus = rule.blockStatus,
            localPath = rule.localPath,
            reason = "rule:${rule.id}",
        )
    }

    private fun daysToSeconds(days: Int): Long = days.toLong() * 24 * 60 * 60

    private fun rawQueryOf(url: String): String? {
        val withoutFragment = url.substringBefore('#')
        val q = withoutFragment.indexOf('?')
        return if (q < 0 || q == withoutFragment.length - 1) null else withoutFragment.substring(q + 1)
    }

    companion object {
        val ALLOWED_METHODS = setOf("GET", "HEAD")
    }
}
