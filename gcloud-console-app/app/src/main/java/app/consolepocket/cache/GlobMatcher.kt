package app.consolepocket.cache

/**
 * Sehr kleiner Glob-Matcher:
 *  - `**` matcht alles (auch `/`)
 *  - `*`  matcht alles ausser `/`
 *  - `?`  matcht genau ein Zeichen ausser `/`
 *
 * Regexe werden gecacht, weil shouldInterceptRequest fuer hunderte Requests pro Seitenladen
 * aufgerufen wird und das Matching dort schnell sein muss.
 */
object GlobMatcher {

    /**
     * Muster-Regexe werden gecacht. Die Anzahl ist durch das Regelwerk begrenzt (wenige Dutzend),
     * daher reicht eine ConcurrentHashMap ohne Eviction; sie ist zudem thread-sicher, weil
     * shouldInterceptRequest von mehreren WebView-Threads aufgerufen werden kann.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun matches(pattern: String, input: String): Boolean {
        if (pattern == "**" || pattern == "*") return true
        val regex = cache.getOrPut(pattern) { compile(pattern) }
        return regex.matches(input)
    }

    private fun compile(pattern: String): Regex {
        val sb = StringBuilder(pattern.length * 2 + 8)
        sb.append('^')
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    // "**/" soll auch "nix davor" erlauben
                    if (i < pattern.length && pattern[i] == '/') {
                        sb.append("/?")
                        i += 1
                    }
                }

                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> {
                    if (c in REGEX_SPECIAL) sb.append('\\')
                    sb.append(c)
                }
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    private val REGEX_SPECIAL = setOf(
        '\\', '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|',
    )
}
