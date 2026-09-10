package app.consolepocket.model

/**
 * Vom Nutzer gewaehlte Engine (Settings) bzw. die tatsaechlich aktive Engine.
 *
 * Hintergrund (PLAN Kapitel 6 / ADR-001):
 * - [EngineMode.WEBVIEW]  = eingebettetes Chromium in der App + eigener nativer Cache
 *   (Anforderung A2 "UI in der App speichern"). Risiko: Google blockiert OAuth in
 *   eingebetteten WebViews -> nur mit Chrome-artigem User-Agent nutzbar.
 * - [EngineMode.CUSTOM_TABS] = policy-sicherer Modus. Der Custom Tab laeuft im Task dieser App
 *   (Back-Taste kehrt zurueck, kein eigener Recents-Eintrag), teilt aber Chromes Cookie-Jar,
 *   weshalb die Anmeldung funktioniert. Eigener Request-Cache ist dort nicht moeglich.
 * - [EngineMode.AUTO] = WebView bevorzugen, bei erkannter Google-Blockade automatisch auf
 *   Custom Tabs degradieren (und den Nutzer informieren).
 */
enum class EngineMode { AUTO, WEBVIEW, CUSTOM_TABS }

enum class ActiveEngine { WEBVIEW, CUSTOM_TABS }

enum class DarkMode { SYSTEM, ON, OFF }
