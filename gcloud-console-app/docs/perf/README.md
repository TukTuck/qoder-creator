# Messungen (Messplan aus `PLAN.md` Kapitel 9)

Hier landen die Messprotokolle als `messungen-<JJJJ-MM-TT>.md`. Traces (`.perfetto-trace`,
`*.trace`) und Profiler-Exporte bleiben über `.gitignore` außerhalb des Repos.

## Vorlage

```markdown
# Messung <JJJJ-MM-TT>

- Gerät: <Modell, Android-Version, RAM>
- Chromium/WebView: <Version aus Einstellungen → „Chromium/WebView">
- Netzwerk: <WLAN / LTE / gedrosselt>
- Engine: WEBVIEW | CUSTOM_TABS
- Regel-Modus: OFF | LOG_ONLY | ENFORCE
- Interceptor: an | aus   (aus = Referenzmessung mit nacktem Chromium-Cache)
- App-Version: <versionName / commit>

## Referenz (alle Schichten aus)

| Metrik | Kaltstart | Warmstart |
|---|---|---|
| Time-to-visible (Skeleton) |  |  |
| First Contentful Paint |  |  |
| Largest Contentful Paint |  |
| Requests Startseite |  |  |
| Bytes gesamt |  |  |
| App-Kaltstart → interaktiv |  |  |

## Mit Cache-Schicht (L1–L6)

| Metrik | Kaltstart | Warmstart | Δ zur Referenz |
|---|---|---|---|
| Time-to-visible (Skeleton) |  |  |  |
| First Contentful Paint |  |  |  |
| Largest Contentful Paint |  |  |  |
| Requests Startseite |  |  |  |
| Bytes aus Netzwerk |  |  |  |
| Bytes aus Cache |  |  |  |
| Cache-Hit-Rate (statische Assets) |  |  |  |
| Letzter Seitenladen (`lastPageLoadMs`) |  |  |  |

## Zielabgleich (PLAN Kapitel 9)

- [ ] Time-to-visible < 300 ms
- [ ] FCP warm < 1,2 s
- [ ] LCP warm < 2 s
- [ ] Hit-Rate ≥ 90 %
- [ ] ≥ 80 % der Bytes warm aus dem Cache
- [ ] Requests −30 % (nur mit ENFORCE messbar)

## Beobachtungen / Regeländerungen

- <welche Rules wurden angepasst, welche Requests waren überraschend, welche Funktionen sind
  nach dem Cachen kaputtgegangen?>
```

## Messquellen

| Metrik | Quelle |
|---|---|
| Hit-Rate, Bytes, Requests, `lastPageLoadMs` | In-App: Menü → „Cache & Messwerte" (`ui/debug/DebugScreen.kt`) |
| Netzwerk-Waterfall, Coverage | `chrome://inspect` (nur Debug-Build, `WebView.setWebContentsDebuggingEnabled`) |
| FCP/LCP nativ | `androidx.webkit` `NavigationListener` (ab 1.16) — **noch nicht angebunden**, siehe Backlog M7 |
| Kaltstart | Perfetto / `androidx.tracing` (Activity-Start, erster Frame) |
| Cache-Belegung | Einstellungen → Cache („Belegt: …") |
