package app.consolepocket.web

import app.consolepocket.model.UrlClassifier
import app.consolepocket.model.UrlInfo

/**
 * "Keine extra Fenster"-Policy (PLAN Kapitel 7).
 *
 * Grundregel: Alles bleibt in dieser App. Die App verlaesst sich selbst nur, wenn der Nutzer es
 * ausdruecklich bestaetigt ([Decision.AskUser]). Es wird niemals automatisch ein ACTION_VIEW
 * ausgelöst.
 *
 * Reine Logik (java.net.URI statt android.net.Uri) -> ohne Robolectric unit-testbar.
 */
class NavigationPolicy(
    private val keepLinksInApp: Boolean = true,
) {

    sealed interface Decision {
        /** Im aktuellen WebView laden. */
        data object LoadHere : Decision

        /** In einem neuen In-App-Tab laden (kein Systemfenster!). */
        data object LoadInNewTab : Decision

        /**
         * Wuerde eine andere App oeffnen (mailto/tel/intent/market). Nur nach Rueckfrage im
         * In-App-Dialog - niemals automatisch.
         */
        data class AskUser(val scheme: String, val url: String, val label: String) : Decision

        /** Nichts tun (z. B. javascript:, blob:, data: ohne Navigation). */
        data object Ignore : Decision
    }

    fun decide(url: String, isMainFrame: Boolean = true, isNewWindow: Boolean = false): Decision {
        val info: UrlInfo = UrlClassifier.parse(url)

        if (info.scheme == "javascript" || info.scheme == "about" || info.scheme == "data") {
            return Decision.Ignore
        }

        if (UrlClassifier.isExternalScheme(info)) {
            return Decision.AskUser(
                scheme = info.scheme,
                url = url,
                label = labelFor(info.scheme),
            )
        }

        if (!UrlClassifier.isHttp(info)) return Decision.Ignore

        if (isNewWindow) return Decision.LoadInNewTab

        // Console + Google-Auth bleiben immer hier.
        if (UrlClassifier.isConsole(info) || UrlClassifier.isGoogle(info)) return Decision.LoadHere

        // Fremde Domains (Doku, GitHub, ...): per Einstellung in der App lassen.
        return if (keepLinksInApp) {
            if (isMainFrame) Decision.LoadHere else Decision.LoadHere
        } else {
            Decision.LoadHere // auch dann in-app: A3 hat Vorrang, der Toggle steuert nur die UI-Hinweise
        }
    }

    private fun labelFor(scheme: String): String = when (scheme) {
        "mailto" -> "E-Mail"
        "tel" -> "Telefon"
        "sms" -> "SMS"
        "geo" -> "Karten"
        "market", "intent" -> "andere App"
        else -> scheme
    }
}
