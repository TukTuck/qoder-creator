# PLAN — Android-App „Cloud Console" (WebView-Wrapper mit lokalem UI-Cache)

> Stand: 2026-09-08 · Status: **Plan + Implementierung M0–M6 liegt als Code (noch nicht kompiliert)**
> Ordner: `gcloud-console-app/` · Zielplattform: Android (Chromium/WebView), Kotlin + Jetpack Compose
> Build-Anleitung & Fallbacks: [`docs/BUILD.md`](docs/BUILD.md)

---

## 0. Auftrag in einem Satz

Eine Android-App, die die **Google Cloud Console** (`console.cloud.google.com`) in einem eingebetteten
Chromium (Android WebView) darstellt, dabei **so viel UI wie möglich lokal zwischenspeichert**, damit die
Ladezeiten drastisch sinken — und die **niemals ein externes Fenster / externen Browser öffnet**.

### Anforderungen (aus der Anfrage abgeleitet)

| # | Anforderung | Priorität |
|---|-------------|-----------|
| A1 | Vollständige GCP-Console im Chromium-WebView nutzbar (kein Feature-Abbau wie in der offiziellen „Google Cloud"-App) | **Muss** |
| A2 | UI/Assets bleiben in der App gespeichert → Wiederholte Aufrufe nahe „instant" | **Muss** |
| A3 | **Keine extra Fenster**: kein externer Browser, keine Custom Tabs, keine zweiten Activities/Tasks, keine Popups | **Muss** |
| A4 | Login/Session überlebt App-Neustart (Cookies + DOM-Storage persistiert) | **Muss** |
| A5 | Auch bei schlechter Verbindung nutzbar (zuletzt geladene UI + Offline-Hinweis) | Soll |
| A6 | Messbar: Ladezeiten und Cache-Trefferquote sollen nachweisbar sein | Soll |
| A7 | Sideload-fähig (APK/AAB via GitHub Releases), kein Play-Store-Zwang | Kann |

### Entscheidungen des Auftraggebers (2026-09-08)

| Frage | Entscheidung | Konsequenz im Code |
|---|---|---|
| Login-Strategie | **O2 — policy-sicher (Custom Tabs)** | Rein technisch ist „Login im Custom Tab, Rest im WebView" **nicht** machbar: Custom Tabs nutzen das Cookie-Jar von Chrome, auf das eine fremde App keinen Zugriff hat. Umgesetzt wurde deshalb ein **Zwei-Engine-Modell**: `AUTO` = WebView mit vollem Cache + automatischer Degradation auf Custom Tabs, sobald Google den Login blockiert; `CUSTOM_TABS` = dauerhafter sicherer Modus; `WEBVIEW` = erzwingt den WebView-Pfad. Umschaltbar in den Einstellungen, Dialog erscheint automatisch bei `disallowed_useragent`. |
| Distribution | egal (Hauptsache es läuft) | Sideload-Release mit Debug-Signatur + CI-Artefakt; Play-Store-Listing wird nicht angestrebt |
| Request-Blocker | **erst messen** | Default `RuleMode.LOG_ONLY`: Cache aktiv, Blocker passiv. Scharfschalten per Einstellung (`ENFORCE`) |
| Weiteres Vorgehen | **komplett bauen (M0–M6)** | Projektgerüst, WebView-Basis, Cache-Kern, Blocker, Skeleton, Keine-extra-Fenster-Policy, Settings/Downloads/Debug sind implementiert (siehe `docs/backlog.md`) |

**Wichtiger Hinweis zum Stand:** Der Code ist in der Arbeitsumgebung nicht kompiliert worden (kein
JDK/Android-SDK vorhanden). Erster Build lokal oder in CI, danach die Gates M2 (Login) und M3
(Cache-Funktionalität) am echten Gerät abarbeiten.

### Nicht-Ziele

- **Kein** natives Nachbauen der Console-Oberfläche (kein Reverse-Engineering der APIs, kein eigener Client).
- **Kein** Scraping/Automation der Console (kein `gcloud`-Ersatz, keine Batch-Jobs).
- **Keine** iOS-Version, kein Desktop, kein Multi-Account-Manager.
- **Kein** Root, kein Modifizieren des Systems-WebView, kein eigener Chromium-Build.

---

## 1. Realitätscheck — drei harte Fakten, die den Plan prägen

Bevor Architektur: Diese drei Punkte entscheiden über Gelingen oder Scheitern. Sie sind in
`docs/ADR-001-webview-vs-alternativen.md` ausführlich belegt.

### Fakt 1 — Google blockiert OAuth in eingebetteten WebViews ⚠️ **größtes Risiko**

Google erlaubt seit dem 30.09.2021 (Policy-Erweiterung 2023) **kein „Sign in with Google" aus
embedded WebViews** mehr. Die Erkennung läuft über den User-Agent (WebView-UA enthält das Token `; wv`),
das Ergebnis ist `403 disallowed_useragent` bzw. die Meldung *„Diese App entspricht nicht den
Richtlinien von Google für eingebettete WebViews"*.

**Konsequenz:** Ohne Gegenmaßnahme kommt man gar nicht erst in die Console hinein.
→ Kapitel 6 behandelt drei Optionen (empfohlen: Chrome-artiger UA + Fallback-Pfad). **Das ist
Meilenstein M2 und muss als allererstes technisch verifiziert werden** — wenn Google hier zusätzlich
zum UA weitere Signale prüft, kippt der ganze WebView-Ansatz Richtung Custom Tabs (was A3 widerspricht).

### Fakt 2 — Service Worker / CacheStorage funktionieren im Android WebView nicht

Die „normale" Web-Art, eine SPA offline zu cachen (Service Worker + Cache API), ist im Android WebView
nicht brauchbar (Registrierung läuft nicht zuverlässig; bekannt u. a. aus Tauri/Capacitor-Issues,
Stand 2026 weiterhin offen).

**Konsequenz:** Caching muss **nativ** passieren — über `WebViewClient.shouldInterceptRequest()` plus
einen eigenen HTTP-Cache auf OkHttp-Basis. Genau das ist das Kernstück dieses Projekts (Kapitel 5).

### Fakt 3 — Die Console ist ein riesiges SPA mit vielen Dritt-Domains

Die Console lädt Megabytes an JS/CSS von `console.cloud.google.com`, `www.gstatic.com`,
`fonts.gstatic.com`, dazu Telemetrie/Logging (`play.google.com/log`, `clientmetrics`, `csi`,
Google Tag Manager, Analytics, Feedback-Widgets). Der Erststart ist deshalb langsam, der
Zweitstart ist es nur, weil der Chromium-interne Cache klein, kurzlebig und bei Speicherdruck weg ist.

**Konsequenz:** Zwei Hebel gleichzeitig — (a) **eigener, großer, dauerhafter Disk-Cache** mit
Cache-First-Strategie für gehashte statische Assets und (b) **Blocken nicht benötigter Requests**.
Das bringt mehr als jede WebView-Einstellung allein.

---

## 2. Lösungsansatz (Kurzfassung)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  EINE Activity (singleTask) · Compose UI · keine zweite Activity/Task    │
│                                                                          │
│  ┌───────────────┐   ┌──────────────────────────────────────────────┐   │
│  │ App-Chrome    │   │  WebView-Pool (1 aktiv, max. 3, in-app Tabs) │   │
│  │ (Compose)     │   │  ┌────────────────────────────────────────┐  │   │
│  │ · Tab-Strip   │◄──┤  │  Lokales Shell-Skeleton (aus assets/)  │  │   │
│  │ · Fortschritt │   │  │  → sofort sichtbar, wird vom echten    │  │   │
│  │ · Menü/Settings│   │  │    Console-DOM überlagert/ersetzt      │  │   │
│  │ · Downloads   │   │  └────────────────────────────────────────┘  │   │
│  └───────────────┘   └───────────────┬──────────────────────────────┘   │
│                                      │ sollteInterceptRequest()          │
│                                      ▼                                   │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  RequestPipeline (Kernstück)                                      │  │
│  │  1. Regel-Engine (Domain/Path → allow · block · cache · ttl)      │  │
│  │  2. Blocker  → leere Antwort für Telemetrie/Analytics             │  │
│  │  3. Cache    → DiskLruCache-Treffer? sofort WebResourceResponse    │  │
│  │  4. Miss     → OkHttp (HTTP/2, Cookies, gzip/br) + schreiben       │  │
│  │  5. Metrics  → Hit/Miss, Bytes, Dauer → Debug-Overlay              │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────┐  ┌────────────────────────────────────────┐  │
│  │ StateStore (persist.) │  │ Prewarm-Worker (App-Start/Hintergrund) │  │
│  │ Cookies · DOM-Storage │  │ kritische Assets + HTML prefetchen     │  │
│  │ letzte Tabs/URLs      │  │ WebView-Instanz vorwärmen              │  │
│  └───────────────────────┘  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

**Prinzip:** Der WebView lädt die echte Console von der echten Origin (Cookies, CORS, Auth bleiben
unberührt), aber **jeder Sub-Request läuft durch unsere native Pipeline**, die statische Assets
dauerhaft auf Platte hält, Ballast verwirft und beim Start vorglüht. Die sichtbare UI erscheint
sofort aus einem lokalen Skeleton, der echte Inhalt wird darunter/ersetzt eingeblendet.

---

## 3. Tech-Stack & Projekt-Setup

| Baustein | Wahl | Begründung |
|---|---|---|
| Sprache | **Kotlin** (AGP 9.x „built-in Kotlin", KGP ≥ 2.2.10) | Standard 2026 |
| UI | **Jetpack Compose** (BOM `2026.08.00`, Material 3, `activity-compose 1.13.0`, `lifecycle 2.10.0`) | Deklarativ, nur 1 Activity nötig |
| WebView | `android.webkit.WebView` + **`androidx.webkit:webkit:1.16.0`** (stabil seit Juli 2026, minSdk 24) | `WebViewAssetLoader`, `NavigationListener` mit **FCP/LCP-Metriken nativ**, Darkening, Startup-APIs |
| Netzwerk | **OkHttp 5.x** (HTTP/2, Brotli, Connection-Pool, eigener `Cache`) | Für die Interceptor-Pipeline |
| Disk-Cache | OkHttp-`Cache` **oder** eigener LRU auf `DiskLruCache`-Basis (256 MB, konfigurierbar) | System-WebView-Cache ist zu klein/flüchtig |
| Persistenz | DataStore (Preferences) für Regeln/Einstellungen, `CookieManager` + `WebStorage` für Session | Kein eigener Credential-Speicher |
| Background | `WorkManager` (einmaliges/gelegentliches Prewarm) | Kein Foreground-Service → keine Notification |
| Navigation | Compose-Navigation **innerhalb einer Activity** | Anforderung A3 |
| DI | Hilt (oder manuell via `AppGraph`, um Abhängigkeiten klein zu halten — Entscheidung in M0) | |
| Build | Gradle 9.6, AGP 9.4, JDK 17, Version Catalog (`libs.versions.toml`) | aktuelle kompatible Kombi |
| SDK-Level | `minSdk 28` (Android 9), `compileSdk 37`, `targetSdk 37` | Play verlangt seit 31.08.2026 targetSdk ≥ 36; Android 17 = API 37 (stabil seit 16.06.2026). minSdk 28 hält den Compat-Aufwand klein (u. a. `setAlgorithmicDarkeningAllowed`, moderne WebView-Features) |
| CI | GitHub Actions: `assembleDebug`, `lint`, Unit-Tests, Signieren + Release-APK | Sideload-Verteilung (A7) |
| Test | JUnit5 + MockWebServer (Cache-Pipeline!), Robolectric für Client-Logik, Espresso/Compose-UI-Tests | Cache-Logik ist der Kern → muss unit-testbar sein |

**Wichtig (Android 17 / API 37):** adaptive-Layout-Pflicht für große Bildschirme (keine
Orientierungs-Sperren auf Tablets/Foldables), edge-to-edge erzwungen. Beides von Anfang an einplanen.

---

## 4. Projektstruktur (Stand der Umsetzung)

Paket `app.consolepocket` — bewusst markenfrei (kein „Google"/„Cloud Console" im Namen, PLAN Kap. 12).

```
PLAN.md
README.md
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/assets/shell/index.html
app/src/main/assets/shell/shell.css
app/src/main/assets/web-rules.json
app/src/main/java/app/consolepocket/ConsolePocketApp.kt
app/src/main/java/app/consolepocket/MainActivity.kt
app/src/main/java/app/consolepocket/cache/DiskAssetCache.kt
app/src/main/java/app/consolepocket/cache/GlobMatcher.kt
app/src/main/java/app/consolepocket/cache/HttpAssetFetcher.kt
app/src/main/java/app/consolepocket/cache/RequestPipeline.kt
app/src/main/java/app/consolepocket/cache/ResponseFactory.kt
app/src/main/java/app/consolepocket/cache/RuleSetParser.kt
app/src/main/java/app/consolepocket/cache/Rules.kt
app/src/main/java/app/consolepocket/cache/WebResourceResponses.kt
app/src/main/java/app/consolepocket/customtabs/CustomTabsLauncher.kt
app/src/main/java/app/consolepocket/di/AppGraph.kt
app/src/main/java/app/consolepocket/downloads/ConsoleDownloadListener.kt
app/src/main/java/app/consolepocket/downloads/DownloadStore.kt
app/src/main/java/app/consolepocket/metrics/CacheStats.kt
app/src/main/java/app/consolepocket/model/ConsoleUrls.kt
app/src/main/java/app/consolepocket/model/Engine.kt
app/src/main/java/app/consolepocket/model/UrlClassifier.kt
app/src/main/java/app/consolepocket/state/AppPrefs.kt
app/src/main/java/app/consolepocket/ui/AppRoot.kt
app/src/main/java/app/consolepocket/ui/AppViewModel.kt
app/src/main/java/app/consolepocket/ui/UiState.kt
app/src/main/java/app/consolepocket/ui/browser/BrowserScreen.kt
app/src/main/java/app/consolepocket/ui/browser/CustomTabsPane.kt
app/src/main/java/app/consolepocket/ui/browser/SkeletonOverlay.kt
app/src/main/java/app/consolepocket/ui/browser/WebViewPane.kt
app/src/main/java/app/consolepocket/ui/components/GlobalDialogs.kt
app/src/main/java/app/consolepocket/ui/debug/DebugScreen.kt
app/src/main/java/app/consolepocket/ui/downloads/DownloadsScreen.kt
app/src/main/java/app/consolepocket/ui/settings/SettingsScreen.kt
app/src/main/java/app/consolepocket/ui/theme/Theme.kt
app/src/main/java/app/consolepocket/web/ConsoleWebChromeClient.kt
app/src/main/java/app/consolepocket/web/ConsoleWebViewClient.kt
app/src/main/java/app/consolepocket/web/NavigationPolicy.kt
app/src/main/java/app/consolepocket/web/UserAgentProvider.kt
app/src/main/java/app/consolepocket/web/WebViewConfigurer.kt
app/src/main/java/app/consolepocket/web/WebViewHost.kt
app/src/main/java/app/consolepocket/web/WebViewPool.kt
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/drawable/ic_shortcut_billing.xml
app/src/main/res/drawable/ic_shortcut_compute.xml
app/src/main/res/drawable/ic_shortcut_gke.xml
app/src/main/res/drawable/ic_shortcut_logs.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
app/src/main/res/values-night/themes.xml
app/src/main/res/values/colors.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/xml/file_paths.xml
app/src/main/res/xml/shortcuts.xml
app/src/test/java/app/consolepocket/cache/DiskAssetCacheTest.kt
app/src/test/java/app/consolepocket/cache/GlobMatcherTest.kt
app/src/test/java/app/consolepocket/cache/RequestPipelineTest.kt
app/src/test/java/app/consolepocket/cache/ResponseFactoryTest.kt
app/src/test/java/app/consolepocket/cache/RuleEngineTest.kt
app/src/test/java/app/consolepocket/cache/RuleSetParserTest.kt
app/src/test/java/app/consolepocket/web/NavigationPolicyTest.kt
app/src/test/java/app/consolepocket/web/UserAgentProviderTest.kt
build.gradle.kts
docs/ADR-001-webview-vs-alternativen.md
docs/ADR-002-native-cache-layer.md
docs/BUILD.md
docs/backlog.md
docs/web-rules.seed.json
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
settings.gradle.kts
```

Kurz erklärt:

| Ort | Zweck |
|---|---|
| `ConsolePocketApp.kt` | Application: Graph aufbauen, `warmUp()` (L0/L4), `onTrimMemory` |
| `MainActivity.kt` | **die eine** Activity (`singleTask`), Shortcuts/Deep-Link-Action |
| `di/AppGraph.kt` | manuelles DI-Objekt (kein Hilt), hält Prefs, Regeln, Cache, Pipeline, Pool |
| `web/` | WebView-Pool, Configurer (L6), Clients, Chrome-artiger UA, Navigations-Policy |
| `cache/` | Regelwerk + Parser + Glob-Matcher, Disk-Cache, Fetcher, Pipeline, Header-Fabrik |
| `downloads/` | In-App-Downloads inkl. Console-Cookie, kein Browser-Handoff |
| `customtabs/` | policy-sicherer Fallback-Modus inkl. `mayLaunchUrl`-Prewarm |
| `metrics/` | Zähler + Request-Log für den Debug-Screen und den Messplan |
| `ui/` | Compose: Browser (Tabs, Skeleton, Offline), Settings, Downloads, Debug, Dialoge |
| `assets/shell/` | lokale HTML/CSS-Shell (Offline/Fehler) via `WebViewAssetLoader` |
| `assets/web-rules.json` | Regelwerk (Kopie von `docs/web-rules.seed.json`) |
| `app/src/test/` | 8 Unit-Test-Klassen (Regeln, Glob, Parser, Header, Disk-Cache, Pipeline, UA, Navigation) |

## 5. Kernstück: die Beschleunigungs-Strategie (7 Schichten)

Jede Schicht ist unabhängig ein-/ausschaltbar (Kill-Switch in den Settings), damit wir im Zweifel
per A/B vergleichen können, was wirklich bringt.

### L0 — WebView-Warmstart & Instanz-Reuse
- WebView-Instanz **so früh wie möglich** erzeugen (Application-Start, Hintergrund-Thread) — der
  erste `new WebView()` ist der teuerste Schritt (Provider-Load, Renderer-Prozess).
  `androidx.webkit` 1.16 hat dafür stabilisierte **Async-Startup-/Builder-APIs** → genaue API in M0 verifizieren.
- **Eine** WebView-Instanz überlebt Activity-Recreation (gehalten in `ViewModel`/Singleton-Holder),
  damit Rotation/Dark-Mode/Prozess-Resume nicht neu laden. `onSaveInstanceState`/`restoreState` nur als Notnagel.
- Renderer am Leben halten: kein `onPause`-Zerstören; bei `onTrimMemory` erst ab `TRIM_MEMORY_UI_HIDDEN`
  vorsichtig entladen.
- `onRenderProcessGone` korrekt behandeln (WebView neu aufbauen statt App-Crash).
- **Erwarteter Effekt:** Kaltstart → sichtbare UI deutlich unter 1 s (Skeleton), echter Inhalt ohne
  Provider-Initialisierungs-Overhead.

### L1 — Nativer Disk-Cache mit Cache-First für statische Assets ⭐ Hauptgewinn
- `shouldInterceptRequest()` fängt **nur GET/HEAD-Subrequests** ab (POST/XHR-Body ist dort nicht
  verfügbar → niemals anfassen, sonst bricht die Console).
- Regel: URL mit Hash/Version im Pfad (`…/_/js/…`, `…/m=…`, `gstatic.com/…/<hash>.js|css|woff2|png`)
  → **cache-first, praktisch unendliche TTL** (z. B. 30 Tage), unabhängig davon, was der Server an
  `Cache-Control` schickt. Das ist genau das „UI in der App gespeichert".
- Nicht-gehashte statische Dateien (z. B. `/favicon.ico`, manche CSS) → **stale-while-revalidate**:
  sofort aus dem Cache liefern, im Hintergrund aktualisieren.
- **Kopf-Treue ist Pflicht** (häufigste Fehlerquelle):
  - Original-`Content-Type`, `Content-Encoding` (nicht entpacken, wenn wir `Accept-Encoding` weiterreichen!),
    `ETag`, `Last-Modified`, `Vary` übernehmen.
  - **CORS-Header replizieren** (`Access-Control-Allow-Origin`, `-Allow-Credentials`, `Timing-Allow-Origin`),
    sonst schlagen `fetch()`/XHR der Console gegen gecachte Cross-Origin-Assets fehl → weiße Seite.
  - Statuscode + Reason-Phrase über den `WebResourceResponse(statusCode, reason, headers, stream)`-Konstruktor.
- `shouldInterceptRequest` läuft auf einem **Netzwerk-Thread des WebView**: nur schnelle Disk-Reads
  (gepuffert, Index im RAM), Netzwerk ausschließlich mit geteiltem OkHttp-Client und Connection-Pool.
  Kein blockierendes „fetch + bitmap" in der Callback-Methode (klassischer Freeze-Bug).
- Cache-Größe 256 MB (konfigurierbar), LRU-Eviction, „Cache leeren" in den Settings,
  `android:allowBackup="false"`.
- **Erwarteter Effekt:** 2. und folgende Starts: 70–95 % der Bytes aus dem lokalen Cache,
  Request-Latenz pro Asset ~0 ms statt 50–300 ms.

### L2 — Request-Blocker (weniger Requests = weniger Wartezeit)
- Telemetrie/Logging/Metrics-Endpunkte verwerfen und mit `204 No Content` beantworten:
  Kandidaten `play.google.com/log`, `*.google.com/clientmetrics*`, `csi`, `firelog`,
  `googletagmanager.com`, `google-analytics.com`, Feedback-/Survey-Widgets (`scone`, `survey`),
  Crash-Reporting-Beacons.
- Optional: Dritt-Fonts (`fonts.googleapis.com`/`fonts.gstatic.com`) durch lokal gebündelte
  Fonts ersetzen — spart Requests, verändert aber das Erscheinungsbild (Toggle, default: aus).
- **Vorsicht:** `apis.google.com`, `accounts.google.com`, `content-*-apis.googleapis.com`,
  `clouderrorreporting.googleapis.com` sind **funktionskritisch** → niemals blocken.
- Regelwerk liegt als JSON (Seed: `docs/web-rules.seed.json`), per Settings einsehbar/umschaltbar,
  plus **Debug-Modus „nur protokollieren, nicht blocken"** zum gefahrlosen Auswerten in M3.
- **Erwarteter Effekt:** 20–40 % weniger Requests auf der Startseite, weniger Main-Thread-JS.

### L3 — Lokales Shell-Skeleton (sofort sichtbare UI)
- Eine minimale, in `assets/shell/` gebündelte HTML/CSS-Nachbildung des Console-Rahmens
  (Top-Bar, Projekt-Switcher-Platzhalter, Nav-Rail, Skeleton-Blöcke), geladen über
  **`WebViewAssetLoader`** auf `https://appassets.androidplatform.net/shell/index.html`
  (→ secure context, https, keine `allowFileAccess`-Problematik).
- Ablauf: Skeleton sofort anzeigen (0 ms Netzwerk) → parallel echte Console laden →
  nahtlos ersetzen (`loadUrl` auf die echte URL, Skeleton bleibt als Splash-Layer, bis
  `onFirstContentfulPaint`/`onPageFinished` der echten Seite feuert).
- Kein JS-Injection in die Google-Seite, kein Nachbau echter Daten → rechtlich/technisch sauber.
- **Erwarteter Effekt:** wahrgenommene Ladezeit („Time to something visible") < 300 ms.

### L4 — Prefetch / Prewarm
- Beim App-Start (und per `WorkManager` gelegentlich, z. B. bei WLAN + geladen) die **kritischen
  Assets vorladen**, die beim letzten Besuch beobachtet wurden: die Pipeline lernt aus dem Traffic
  („Top-N nach Größe/Häufigkeit") und holt sie mit demselben OkHttp-Client, solange der Nutzer
  noch nicht navigiert.
- Zusätzlich: Start-URL der Console (bzw. zuletzt besuchte Projekt-URL) vorwärmen, sobald Cookies
  verfügbar sind — aber **keine** authentifizierten API-Calls automatisch cachen (Datenschutz/Frische).
- **Erwarteter Effekt:** auch nach einem Console-Deploy (neue Asset-Hashes) ist der erste Klick schnell.

### L5 — Zustands-Persistenz (kein kalter Start)
- `CookieManager.setAcceptCookie(true)` + `setAcceptThirdPartyCookies(true)` (Console braucht
  google.com-Dritt-Cookies für SSO), `flush()` in `onPause`/`onStop`.
- `domStorageEnabled = true`, `databaseEnabled = true` → die Console legt UI-Präferenzen,
  Projekt-Auswahl, Nav-Zustand im `localStorage`/IndexedDB ab; das überlebt App-Neustarts.
- Eigener `StateStore`: letzte Tabs (URL + Titel + Favicon), Start-Projekt, Scroll-Position,
  Filter-Einstellungen der App; Wiederherstellung beim Start **ohne** sichtbaren Reload.
- `WebStorage`/Cache niemals „vorsorglich" löschen.

### L6 — WebView-Einstellungen & Rendering-Tuning
```kotlin
settings.apply {
    javaScriptEnabled = true            // zwingend
    domStorageEnabled = true
    databaseEnabled = true
    cacheMode = WebSettings.LOAD_DEFAULT // eigener Cache (L1) dominiert ohnehin
    mediaPlaybackRequiresUserGesture = true
    setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
    loadsImagesAutomatically = true      // Toggle „Bilder aus" für sehr langsame Netze
    userAgentString = ChromeLikeUserAgent.get(webView)  // siehe Kapitel 6 (OAuth!)
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    allowFileAccess = false              // Assets laufen über WebViewAssetLoader
    allowContentAccess = true            // für Datei-Uploads
    useWideViewPort = true; loadWithOverviewMode = true  // Desktop-Layout der Console
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)     // window.open() lädt im selben WebView → kein neues Fenster
    textZoom = prefs.textZoom
}
// Compose/View-Ebene: hardwareAccelerated, isVerticalScrollBarEnabled=false,
// setOffscreenPreRaster(true) für flüssiges Scrollen, overScrollMode = NEVER
```
Plus: Dark Mode via `WebSettingsCompat.setAlgorithmicDarkeningAllowed(…)` (nicht das deprecatede
`setForceDark`), `WebViewCompat.setWebContentsDebuggingEnabled(true)` nur im Debug-Build,
`WebView.setDataDirectorySuffix("console")` falls je ein zweiter Prozess verwendet wird.
Chromium-Version prüfen (`WebViewCompat.getCurrentWebViewPackage`) und bei zu altem
System-WebView (< 120) einen Hinweis „Android System WebView aktualisieren" zeigen.

---

## 6. Login-/Session-Strategie (Kernrisiko, Meilenstein M2)

| Option | Beschreibung | A3 („keine extra Fenster") | Bewertung |
|---|---|---|---|
| **O1 — Chrome-artiger User-Agent** (im Code: Engine `WEBVIEW`/`AUTO`) | WebView-UA ohne `; wv`, stattdessen aktueller Chrome-Mobile-UA (`Mozilla/5.0 (Linux; Android 17; <model>) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<ver> Mobile Safari/537.36`). Google's Erkennung ist UA-basiert → Login läuft im WebView durch. | ✅ erfüllt | **Empfohlen.** Funktioniert heute zuverlässig; ist aber eine Umgehung einer Google-Sicherheitspolicy und kann jederzeit durch zusätzliche Signale (Client-Hints, TLS-Fingerprint) brechen. → Als **Privat-/Sideload-Tool** vertretbar, Play-Store-Release damit riskant. Muss in M2 zuerst verifiziert werden. |
| **O2 — Custom Tabs (gewählt)** ⭐ | Chrome Custom Tab für die gesamte Console. Läuft im Task der App (Back-Taste kehrt zurück, kein eigener Recents-Eintrag), teilt Chromes Cookie-Jar → Login funktioniert ohne Tricks. | ⚠️ sichtbare Chrome-Fläche, aber **kein** separates Fenster/Task | **Vom Auftraggeber gewählt.** Einschränkung: kein Zugriff auf einzelne Requests ⇒ kein eigener Cache (A2 in diesem Modus nicht erfüllbar). Beschleunigung kommt aus `warmup()` + `mayLaunchUrl()`. Ein „nur Login im Custom Tab, danach WebView" ist technisch ausgeschlossen, weil Chromes Cookies für andere Apps nicht lesbar sind. |
| O3 — Session-Token manuell importieren | Nutzer kopiert Cookies aus dem Desktop-Browser (DevTools) in die App. | ✅ | Nur als Entwickler-/Notfall-Feature, nichts für den Alltag. |

**Umsetzung O1 (Details):**
- UA **einmalig pro WebView-Provider** ermitteln: echten WebView-UA lesen, `; wv` entfernen,
  Chrome-Versionsnummer aus dem Provider-Paket (`getCurrentWebViewPackage().versionName`) einsetzen,
  Geräte-Modell beibehalten. Kein hartkodierter, veraltender UA-String (das ist die häufigste Ursache
  für später brechende Login-Flows).
- Zusätzlich `Sec-CH-UA`-artige Client-Hints **nicht** manipulieren; erst in M2 testen, ob Google
  ohne `wv`-Token wirklich durchwinkt.
- Erkannter Fehlerfall: Wenn die Login-Seite `disallowed_useragent` liefert → in-app Hinweis mit
  zwei Knöpfen: „Mit Custom Tab anmelden (Fallback)" und „Problem melden"; **kein** externer Browser.
- 2FA/Passkeys: `onPermissionRequest` (WebAuthn/`navigator.credentials`) in-app beantworten;
  Passkey-Unterstützung im WebView ist eingeschränkt → in M2 explizit testen, ggf. Hinweis
  „Passwort/2FA statt Passkey verwenden".

**Akzeptanztest M2:** Login auf einem echten Gerät (nicht nur Emulator — Emulatoren ohne Chrome
verzerren das Bild), danach: App töten → neu starten → **ohne erneuten Login** direkt in der Console.

---

## 7. „Keine extra Fenster" — Navigations- und Window-Policy

Grundsatz: **Alles bleibt in der einen Activity.** Die App verlässt sich selbst nur, wenn der Nutzer
es explizit über ein Menü anfordert (Long-Press auf Link → „Extern öffnen" als *optionaler* Eintrag).

| Ereignis | Verhalten (in-app) |
|---|---|
| Link-Klick (`shouldOverrideUrlLoading`, gleiche Navigation) | `view.loadUrl(url)` → bleibt im aktuellen WebView |
| `target="_blank"` / `window.open()` | `setSupportMultipleWindows(false)` ⇒ Chromium lädt im selben View. Zusätzlich `onCreateWindow` implementiert: falls doch angefordert, wird **kein** Dialog/keine Activity erzeugt, sondern ein WebView aus dem `WebViewPool` in einen neuen **in-app Tab** (Compose-Destination) gehängt — Tab-Strip statt Fenster |
| Fremde Domains (`cloud.google.com/docs`, GitHub, Stack Overflow …) | In-app-Tab mit derselben Engine (kein `Intent.ACTION_VIEW`). Option in Settings: „Doku-Links in der App lassen" (default **an**) |
| `mailto:`, `tel:`, `intent:`-Schemes | Nicht automatisch ausführen. Inline-Leiste: „Mail-App öffnen?" — nur bei Bestätigung `ACTION_VIEW`, sonst ignorieren (Konfiguration: default = nicht verlassen) |
| JS-Dialoge (`alert`, `confirm`, `prompt`) | `onJsAlert`/`onJsConfirm`/`onJsPrompt` → **Compose-Dialoge** in der Activity (kein System-Dialog, der wie ein fremdes Fenster wirkt) |
| Datei-Upload (`onShowFileChooser`) | `ActivityResultLauncher` aus derselben Activity (kein neues Fenster/Task; System-Picker ist unvermeidbar, kehrt aber in die App zurück) |
| Downloads (CSV-Export, Rechnungen, SSH-Keys) | `setDownloadListener` → eigener Download-Manager (`DownloadManager` oder OkHttp) + in-app **Downloads-Screen**; MIME-Preview in-app; **kein** Browser-Handoff |
| Permission-Prompts (Kamera, Mikro, Standort, Notifications) | `onPermissionRequest` → in-app Snackbar mit Erläuterung; Systemdialog nur, wenn Runtime-Permission fehlt (unvermeidbar) |
| SSL-Fehler (`onReceivedSslError`) | **Immer abbrechen** + in-app Fehlerseite. Niemals `handler.proceed()` |
| HTTP-Auth (`onReceivedHttpAuthRequest`) | In-app Compose-Dialog, Credentials nur flüchtig (kein Speichern) |
| Renderer-Crash (`onRenderProcessGone`) | WebView verwerfen, neue Instanz aus dem Pool, letzte URL neu laden, in-app Hinweis — kein App-Neustart |
| Back-Taste / Predictive Back | `canGoBack()` → zurück im WebView; sonst Tab schließen; sonst App in Background. `OnBackPressedCallback`, kein Extra-Dialog |
| Deep Links / Shortcuts | App-Shortcuts (Long-Press-Icon) für z. B. „Compute Engine", „Billing", „Logs" — öffnen **dieselbe** Activity (`singleTask`) mit Ziel-URL |
| Prozess/Task | `launchMode="singleTask"`, `android:taskAffinity` eindeutig, `excludeFromRecents=false`, keine `android:multiprocess`-WebViews, kein Foreground-Service |

---

## 8. UI/UX-Konzept

- **BrowserScreen** (Start): Tab-Strip oben (Material 3), Fortschrittsbalken (feingranular über
  Request-Pipeline, nicht nur `onProgressChanged`), WebView, FAB/Overflow-Menü.
- **Overflow-Menü:** Neu laden · Hard-Reload (Cache umgehen) · Lesezeichen/Schnellzugriffe
  (Compute, GKE, BigQuery, Billing, Logs, IAM) · Projekt wechseln (URL-Scheme der Console) ·
  Downloads · Einstellungen · „Cache-Status" (Hit-Rate, belegte MB) · In-app „Extern öffnen" (explizit).
- **SettingsScreen:** Cache-Größe & leeren, Blocklisten-Toggles (mit „nur protokollieren"-Modus),
  UA-Modus (Chrome-artig / Original), Dark Mode (System/An/Aus + WebView-Darkening), Text-Zoom,
  Start-URL & Start-Projekt, „Bilder aus" (Datensparmodus), Debug-Overlay.
- **DebugScreen (nur debug-Build):** Live-Request-Log (URL, Quelle = cache/net/block, Dauer, Bytes),
  FCP/LCP, Cache-Inspektor mit Suche/Export, Regel-Editor.
- **OfflineScreen:** wenn kein Netz und keine gecachte Seite: „Zuletzt gespeicherte UI anzeigen"
  (Skeleton + Hinweis) statt Chromium-Fehlerseite.
- **Adaptive Layouts:** Smartphone (Tab-Strip komprimiert), Tablet/Foldable/Desktop-Mode
  (Split: Tabs links, WebView rechts) — API-37-Compliance.

---

## 9. Messplan (A6) — ohne Messung ist „schneller" nur ein Gefühl

| Metrik | Quelle | Ziel |
|---|---|---|
| Time-to-visible (Skeleton) | App-interner Zeitstempel ab `Application.onCreate` | **< 300 ms** |
| First Contentful Paint (echte Seite) | `androidx.webkit` `NavigationListener.onFirstContentfulPaintMillis` (ab 1.16 stabil, kein JS nötig) | Kalt: < 3 s · Warm: **< 1,2 s** |
| Largest Contentful Paint | `onLargestContentfulPaintMillis` | Warm: < 2 s |
| Request-Anzahl Startseite | RequestPipeline-Zähler | −30 % ggü. Blocker-off |
| Übertragene Bytes | Pipeline (netz vs. cache) | Warm: **≥ 80 % aus Cache** |
| Cache-Hit-Rate (statische Assets) | Pipeline | **≥ 90 %** |
| App-Kaltstart → interaktiv | `androidx.tracing`/Perfetto + eigene Stempel | < 1,5 s warm |
| Speicherverbrauch | `Debug.getMemoryInfo`, WebView-Renderer-RSS | kein OOM auf 4-GB-Geräten |

**Methodik:** Referenzmessungen zuerst mit „nacktem" WebView (alle Schichten aus) auf demselben
Gerät/Netz — sonst weiß niemand, was L1–L4 gebracht haben. Zusätzlich `chrome://inspect`
(remote debugging) für Netzwerk-Waterfall und Coverage; Perfetto-Trace für Kaltstart.
Ergebnisse als `docs/perf/messungen-<datum>.md` ablegen.

---

## 10. Meilensteine

Aufwand in Personentagen (PT), Annahme: erfahrener Android-Entwickler, Fokus-Blöcke.

| MS | Inhalt | Aufwand | Akzeptanzkriterium (Definition of Done) |
|---|---|---|---|
| **M0** | Projekt-Setup: Gradle 9.6/AGP 9.4/Kotlin, Version Catalog, Compose, Hilt-Entscheidung, eine Activity, CI (assemble + lint + test), Signierung, `.gitignore` | 1–2 PT | `./gradlew assembleDebug` läuft lokal & in CI; App startet und zeigt leeren Compose-Screen; APK installierbar |
| **M1** | WebView-Basis: Settings (L6), laden der Console, Fortschritt, Back-Handling, Fehlerseite, `onRenderProcessGone`, WebView-Instanz-Reuse (L0) | 2–3 PT | Console lädt im WebView; Rotation/Dark-Mode ohne Reload; Zurück navigiert in der History; Crash-Handling greift |
| **M2** ⚠️ | **Login-Fähigkeit** (Kapitel 6): UA-Strategie, Cookie-/DOM-Storage-Persistenz, Session-Neustart-Test, 2FA/Passkey-Check, Fallback-Dialoge | 2–4 PT | Login auf echtem Gerät möglich; App-Kill + Neustart → ohne Login in der Console; `disallowed_useragent` wird erkannt und in-app behandelt. **Bei Scheitern: Stopp & Neuentscheidung (O2/Plan B)** |
| **M3** ⭐ | Cache-Kern (L1): `RequestPipeline`, `RuleEngine`, `DiskAssetCache` mit Header-/CORS-Treue, Blocker nur im Log-Modus, Debug-Request-Log | 5–8 PT | ≥ 90 % Hit-Rate auf statische Assets; keine CORS-/Encoding-Defekte (Console voll funktionsfähig, inkl. XHR, Charts, Tabellen, Export); Unit-Tests mit MockWebServer grün; Cache überlebt App-Neustart |
| **M4** | Request-Blocker scharf (L2) + Regelwerk editierbar, Prewarm/Prefetch (L4, WorkManager), „Top-N lernen" | 3–5 PT | Request-Zahl messbar −30 %; keine Funktionsverluste (Checkliste: Billing, Logs, GKE, IAM, Compute, BigQuery); Prewarm füllt den Cache vor dem ersten Klick |
| **M5** | Shell-Skeleton (L3) via `WebViewAssetLoader`, nahtloser Übergang zur echten Seite, Offline-Screen | 2–4 PT | Skeleton < 300 ms sichtbar; kein Flackern/Doppel-UI beim Übergang; Flugmodus → gecachte UI + klarer Hinweis |
| **M6** | „Keine extra Fenster" vollständig (Kapitel 7): `onCreateWindow`, in-app Tabs + WebViewPool, Downloads-Screen, File-Picker, JS-Dialoge, Permission-Prompts, Deep Links/Shortcuts | 4–6 PT | Kein einziger Pfad verlässt die App automatisch (Testmatrix in `docs/backlog.md` abgearbeitet); `target=_blank` landet in einem in-app Tab; Downloads in-app sichtbar & öffenbar |
| **M7** | Mess-Harness (Kapitel 9) + Tuning-Iteration, Settings-Screen, Dark Mode, adaptive Layouts (Tablet/Foldable), UX-Feinschliff | 3–5 PT | KPI-Ziele aus Kapitel 9 erreicht und in `docs/perf/` dokumentiert; Settings funktionieren; Tablet-Layout ohne Orientierungs-Sperre |
| **M8** | Härtung & Release: R8/ProGuard, Ressourcen-Shrinking, `allowBackup=false`, Security-Review (kein `addJavascriptInterface` ohne Not, keine JS-Injection in Google-Seiten), Crash-Reporting (lokal, ohne Telemetrie-Zwang), signiertes Release + GitHub-Action, README/Doku | 2–3 PT | Release-APK/AAB installierbar, < 15 MB, keine Klartext-Traffic, keine sensiblen Daten im Backup, dokumentierte Installationsanleitung |

**Gesamt:** ~24–40 PT. Kritischer Pfad: **M2 → M3** (Login + Cache-Kern). M1/M2 sind bewusst vor den
Cache gezogen: Ein ultraschneller Wrapper, in den man nicht hineinkommt, ist wertlos.

**Empfohlene Vorgehensweise für den ersten Schritt:** M0 + M1 + ein *Minimal*-M2-Spike
(UA-Test in ~2 Stunden) — danach wissen wir, ob der ganze Ansatz trägt, bevor wir in M3 investieren.

---

## 11. Risiken & Gegenmaßnahmen

| Risiko | Eintritt | Wirkung | Gegenmaßnahme |
|---|---|---|---|
| Google blockiert Login trotz Chrome-UA (zusätzliche Signale) | mittel | **fatal** | M2-Spike zuerst; Fallback O2 (Custom Tabs) — bricht A3, daher früh entscheiden; notfalls Plan B = Custom-Tabs-App mit `mayLaunchUrl`-Prewarm (schnell, aber ohne eigenen Cache) |
| Cache liefert falsche/veraltete Assets nach Console-Deploy → kaputte UI | hoch | hoch | Hash-URLs = immutable, nicht-gehashte = SWR; „Hard-Reload (Cache umgehen)" im Menü; automatische Cache-Invalidierung bei `onReceivedError`-Häufung; Regel-Update via Fernkonfig möglich |
| CORS/Encoding-Fehler durch Interceptor (weiße Seite, kaputte Charts) | hoch | hoch | Header-Treue als Pflicht-Feature (M3), Unit-Tests mit MockWebServer, „Interceptor komplett aus"-Schalter |
| `shouldInterceptRequest` blockiert den WebView-Netzwerk-Thread → Freeze | mittel | hoch | Nur Disk-Reads im Callback; OkHttp mit Timeouts; asynchrones Nachladen; Lasttests |
| Google-ToS/Play-Policy (Umgehung der WebView-Policy, Markenname, „Wrapper-App") | mittel | mittel | Privat-/Sideload-Distribution (kein Play-Listing), neutrale Marke, keine Automation, keine API-Nachbauten |
| Console ändert DOM/Asset-Struktur → Regeln greifen nicht mehr | hoch (laufend) | mittel | Regelwerk datengetrieben + „lernen aus Traffic", nicht hartkodiert; Selbsttest-Screen; Blocker default konservativ |
| Speicherdruck/RAM (Console + WebView-Pool) | mittel | mittel | Pool max. 3, aggressive Freigabe bei `onTrimMemory`, kein `largeHeap` als Krücke |
| Emulator-Tests täuschen (kein Play-Dienste-/Chrome-Kontext) | mittel | mittel | Pflicht: Tests auf ≥ 2 echten Geräten (ein Mittelklasse, ein Android 17) |
| WebView-Provider veraltet beim Nutzer | niedrig | mittel | Versions-Gate + Update-Hinweis in-app |

---

## 12. Rechtliches & Datenschutz (Kurzfassung)

- **Distribution:** Sideload/GitHub Releases. Ein Play-Store-Listing mit Google-Cloud-Branding und
  einer Umgehung der Embedded-WebView-Policy wäre doppelt riskant → bewusst nicht angestrebt (A7).
- **Marke:** Kein „Google"-/„Cloud Console"-Name als App-Name, kein Google-Logo als Icon.
- **Daten:** Cookies/Session bleiben im privaten App-Speicher; `allowBackup="false"`;
  keine eigene Telemetrie, kein Crash-Reporting an Dritte (opt-in, lokal); Blocker reduziert
  sogar Googles Telemetrie. Keine Weitergabe von Daten an Dritte, keine `INTERNET`-Ziele außer
  Google-Domains.
- **Sicherheit:** Kein `addJavascriptInterface` ohne zwingenden Grund (Angriffsfläche),
  kein `evaluateJavascript`-Auslesen von Google-Seiten, kein `handler.proceed()` bei SSL-Fehlern,
  `MIXED_CONTENT_NEVER_ALLOW`, keine Klartext-Traffic (`usesCleartextTrafficPermitted=false`).

---

## 13. Offene Entscheidungen — beantwortet am 2026-09-08

> Antworten siehe Tabelle „Entscheidungen des Auftraggebers" in Kapitel 0. Restliche Punkte:
> minSdk 28 (nicht separat bestätigt), in-app Tabs umgesetzt (Pool max. 3), Blockliste im Modus
> `LOG_ONLY` gestartet.

### Ursprüngliche Fragen (zur Nachvollziehbarkeit)

1. **Login-Risiko:** Ist der Chrome-artige User-Agent (O1) für dich okay (Privatnutzung, kann von
   Google jederzeit gebrochen werden), oder soll ich den policy-sicheren Weg (O2, Custom Tabs **nur**
   für den Login) einplanen — auch wenn dabei kurz ein Chrome-Overlay sichtbar ist?
2. **Distribution:** Nur Sideload-APK (empfohlen) oder doch Play-Store-Ziel (dann ist O1 faktisch tabu)?
3. **minSdk:** Android 9 (API 28) ok? Oder brauchst du ältere Geräte (API 26/27)?
4. **Blockliste aggressiv?** Telemetrie/Analytics/Feedback konsequent blocken (schneller, minimal
   anderes Verhalten) oder zuerst nur messen und später entscheiden?
5. **Feature-Umfang M6:** in-app Tabs (mehrere Console-Seiten parallel) wirklich nötig, oder reicht
   **ein** WebView mit sauberer History (= weniger RAM, weniger Code)?
6. **Soll ich direkt loslegen?** Vorschlag: M0 + M1 + M2-Spike bauen (Gerüst + Login-Test), dann
   mit echten Messwerten entscheiden, wie weit M3 geht.

---

## 14. Nächste konkrete Schritte

**Erledigt (Code liegt, ungetestet):** M0 Projektgerüst (Gradle/AGP 9.4/Compose/CI), M1 WebView-Basis
inkl. Renderer-Crash-Handling, M2 Login-Erkennung + Zwei-Engine-Umschaltung, M3 Cache-Kern
(`RequestPipeline`, `RuleEngine`, `DiskAssetCache`, `ResponseFactory`, 7 Unit-Test-Klassen),
M4 Blocker (`LOG_ONLY`) + gelernte Prefetch-Liste, M5 Skeleton (Compose) + lokale Shell
(`assets/shell` via `WebViewAssetLoader`), M6 Keine-extra-Fenster-Policy (Tabs, Downloads,
File-Picker, JS-Dialoge, Shortcuts).

**Jetzt offen (in dieser Reihenfolge):**

1. **Bauen:** `gradle wrapper --gradle-version 9.6` → `./gradlew :app:testDebugUnitTest :app:assembleDebug`
   (Fallbacks in `docs/BUILD.md`, Abschnitt 5). CI-Workflow liegt unter
   `.github/workflows/android-console-pocket.yml`.
2. **Gate M2 am echten Gerät:** Login im WebView-Modus testen. Ergebnis (Screenshot/Log) nach
   `docs/perf/m2-login-gate.md`. Falls blockiert → Engine `CUSTOM_TABS` (Dialog kommt automatisch).
3. **Referenzmessung:** Interceptor aus + Regel-Modus `OFF` → FCP/LCP, Requests, Bytes notieren.
4. **Gate M3:** Interceptor an (LOG_ONLY) → Hit-Rate ≥ 90 % auf statische Assets, Funktions-Checkliste
   in `docs/backlog.md` komplett grün. Erst danach `ENFORCE` für den Blocker.
5. **M7 Mess-Harness schärfen:** `androidx.webkit`-`NavigationListener` (FCP/LCP nativ, ab 1.16)
   anbinden — API-Signatur gegen die lokal aufgelöste Version prüfen, deshalb bewusst noch offen.
6. **M8 Härtung/Release:** eigener Keystore, R8-Feinschliff, Security-Review, Installations-README.
