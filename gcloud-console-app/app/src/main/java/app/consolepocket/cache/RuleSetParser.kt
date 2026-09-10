package app.consolepocket.cache

import org.json.JSONArray
import org.json.JSONObject

/**
 * Laedt das Regelwerk aus JSON (assets/web-rules.json, Quelle: docs/web-rules.seed.json).
 * org.json ist Teil des Android-Frameworks; fuer JVM-Tests wird `org.json:json` eingebunden.
 *
 * Fehlerhafte Einzelfelder fuehren nie zum Abbruch: unbekannte Werte werden auf konservative
 * Defaults gesetzt (Policy.IGNORE), damit die Console nicht wegen eines Tippfehlers kaputtgeht.
 */
object RuleSetParser {

    fun parse(json: String): RuleSet {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return RuleSet.EMPTY
        val defaults = root.optJSONObject("defaults")

        val neverObj = root.optJSONObject("neverIntercept")
        val never = NeverIntercept(
            methods = neverObj?.optJSONArray("methods")?.toStringSet(upper = true)
                ?: NeverIntercept.DEFAULT_METHODS,
            mainFrame = neverObj?.optBoolean("mainFrame", true) ?: true,
            hostPatterns = neverObj?.optJSONArray("hostPatterns")?.toStringList().orEmpty(),
            pathPatterns = neverObj?.optJSONArray("pathPatterns")?.toStringList().orEmpty(),
        )

        val mode = root.optString("mode", RuleMode.LOG_ONLY.name)
            .let { runCatching { RuleMode.valueOf(it.uppercase()) }.getOrDefault(RuleMode.LOG_ONLY) }

        val rules = root.optJSONArray("rules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> parseRule(arr.optJSONObject(i)) }
        }.orEmpty()

        return RuleSet(
            version = root.optInt("version", 0),
            defaultMode = mode,
            neverIntercept = never,
            rules = rules,
            immutableTtlDays = defaults?.optInt("immutableTtlDays", 30) ?: 30,
            swrTtlDays = defaults?.optInt("swrTtlDays", 7) ?: 7,
            connectTimeoutSeconds = defaults?.optInt("connectTimeoutSeconds", 5) ?: 5,
            readTimeoutSeconds = defaults?.optInt("readTimeoutSeconds", 15) ?: 15,
            cacheMaxBytes = defaults?.optLong("cacheMaxBytes", 256L * 1024 * 1024)
                ?: (256L * 1024 * 1024),
        )
    }

    private fun parseRule(obj: JSONObject?): Rule? {
        if (obj == null) return null
        val id = obj.optString("id").ifBlank { return null }
        val matchObj = obj.optJSONObject("match") ?: return null
        val match = MatchSpec(
            hostPatterns = matchObj.optJSONArray("hostPatterns")?.toStringList().orEmpty(),
            pathPatterns = matchObj.optJSONArray("pathPatterns")?.toStringList().orEmpty(),
            extensionIn = matchObj.optJSONArray("extensionIn")?.toStringSet(upper = false)
                .orEmpty(),
            queryContains = matchObj.optJSONArray("queryContains")?.toStringList().orEmpty(),
        )
        if (match.isEmpty) return null // Sicherheitsnetz: Regel ohne Bedingung wird ignoriert

        val policy = obj.optString("policy", Policy.IGNORE.name)
            .let { runCatching { Policy.valueOf(it.uppercase()) }.getOrDefault(Policy.IGNORE) }

        val status = obj.optJSONObject("response")?.optInt("status", 204) ?: 204

        return Rule(
            id = id,
            enabled = obj.optBoolean("enabled", true),
            priority = obj.optInt("priority", 0),
            match = match,
            policy = policy,
            ttlDays = obj.optInt("ttlDays", 0),
            blockStatus = if (status in 200..599) status else 204,
            localPath = obj.optString("local").ifBlank { null },
            note = obj.optString("note"),
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    private fun JSONArray.toStringSet(upper: Boolean): Set<String> =
        toStringList().map { if (upper) it.uppercase() else it.lowercase() }.toSet()
}
