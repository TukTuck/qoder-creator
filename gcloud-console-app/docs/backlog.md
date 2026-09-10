# Backlog — abhakbare Aufgaben je Meilenstein

Legende: `[ ]` offen · `[x]` erledigt (Code liegt **und** verifiziert) · `[~]` Code liegt, **noch nicht
kompiliert/getestet** · ⚠️ Gate-Entscheidung

---

## Umsetzungsstand 2026-09-08

Der Code für M0–M6 liegt vollständig im Ordner `app/`, wurde aber in der Arbeitsumgebung **nicht
kompiliert** (kein JDK/Android-SDK). Deshalb steht fast alles auf `[~]`. Die Reihenfolge zum
Abarbeiten: erst bauen (CI oder lokal), dann Gate M2 (Login), dann Gate M3 (Cache).

| Meilenstein | Code | Verifiziert |
|---|---|---|
| M0 Setup | ✅ komplett (Gradle, Manifest, CI, Res) | ❌ erster Build ausstehend |
| M1 WebView-Basis | ✅ komplett | ❌ |
| M2 Login/Session | ✅ Erkennung + Engine-Umschaltung | ❌ **Gate am echten Gerät** |
| M3 Cache-Kern | ✅ Pipeline/Regeln/Disk-Cache + 7 Testklassen | ❌ Tests laufen erst nach Build |
| M4 Blocker/Prewarm | ✅ (Blocker im Modus `LOG_ONLY`) | ❌ Regeln am echten Traffic prüfen |
| M5 Skeleton/Shell | ✅ Compose-Skeleton + `assets/shell` | ❌ |
| M6 Keine extra Fenster | ✅ Policy, Tabs, Downloads, Picker, Dialoge | ❌ Testmatrix ausstehend |
| M7 Messen/Tunen | ⚠️ Debug-Screen da, FCP/LCP-API fehlt | ❌ |
| M8 Härtung/Release | ⚠️ R8/CI vorbereitet, Keystore offen | ❌ |

---

## M0 — Projekt-Setup (1–2 PT)

- [~] Gradle-Projekt (`settings.gradle.kts`, `app/`, Version Catalog `gradle/libs.versions.toml`)
- [~] Toolchain: AGP 9.4.0, Gradle 9.6, JDK 17, Compose BOM 2026.08.00, `androidx.webkit:1.16.0`
- [~] `compileSdk 37`, `targetSdk 37`, `minSdk 28`
- [~] Dependencies bewusst klein: Compose, webkit, browser, datastore, OkHttp, coroutines — **kein** Hilt,
      **kein** WorkManager, **kein** Navigation-Compose (manuelles DI-Objekt `di/AppGraph.kt`)
- [~] Manifest: eine Activity (`singleTask`, breite `configChanges`), `allowBackup=false`,
      `usesCleartextTraffic=false`, nur `INTERNET` + `ACCESS_NETWORK_STATE`, FileProvider, `<queries>`
- [~] `.gitignore` (Android), Build-Artefakte und Wrapper-JAR bleiben out of Git
- [~] R8/ProGuard-Grundkonfiguration; Release nutzt vorläufig die Debug-Signatur (Sideload)
- [~] CI: `.github/workflows/android-console-pocket.yml` (Tests, Lint, Debug-+Release-APK, Artefakte)
- [ ] **Wrapper erzeugen und ersten Build fahren** (`gradle wrapper --gradle-version 9.6`) → `docs/BUILD.md`
- [ ] **DoD:** APK baut in CI, installiert, zeigt UI

## M1 — WebView-Basis (2–3 PT)

- [~] `web/WebViewConfigurer.kt`: Settings gemäß PLAN L6 (JS, DOM-Storage, Wide-Viewport,
      `MIXED_CONTENT_NEVER_ALLOW`, `allowFileAccess=false`, `setSupportMultipleWindows(false)`,
      Zoom, Text-Zoom, Bilder-Toggle, algorithmic Darkening via `androidx.webkit`)
- [~] `MutableContextWrapper`-Trick: Instanz gehört der Application, überlebt Activity-Recreation ohne Reload
- [~] `web/ConsoleWebViewClient.kt`: Page-Lifecycle, `onPageCommitVisible`, Fehler, SSL (**immer** cancel),
      HTTP-Auth, `onFormResubmission`, `onRenderProcessGone` (true + Tab-Neuaufbau)
- [~] `web/ConsoleWebChromeClient.kt`: File-Picker, JS-Dialoge, Permission-Prompts, Geolocation,
      Fortschritt, Titel, `onCreateWindow`, Console-Log im Debug-Build
- [~] Compose-Anbindung `ui/browser/WebViewPane.kt` (Pool-Instanz, kein Reload bei Tab-Wechsel)
- [~] Back-Handling: `BackHandler` + `canGoBack` aus `doUpdateVisitedHistory`
- [~] Fehler-/Offline-Zustände als Compose-Elemente statt Chromium-Error-Page
- [~] Chromium-Versions-Gate (`WebViewCompat.getCurrentWebViewPackage`) + Banner bei < 120
- [ ] Config-Change-/Prozess-Kill-Verhalten am Gerät testen
- [ ] **DoD:** Console lädt, überlebt Rotation/Dark-Mode, Fehlerfälle landen in der in-app-Seite

## M2 — Login & Session ⚠️ **GATE** (2–4 PT)

- [~] `web/UserAgentProvider.kt`: Chrome-artiger UA (entfernt `; wv`, `Build/…`, `Version/4.0`;
      Chrome-Version aus dem WebView-Paket, nie hartkodiert) + Unit-Tests
- [~] Blockade-Erkennung (`disallowed_useragent`, Policy-Warnseite) über URL/Titel — **ohne** JS in
      Google-Seiten zu lesen
- [~] Zwei-Engine-Modell: `AUTO` / `WEBVIEW` / `CUSTOM_TABS`, automatische Degradation + Dialog
- [~] `customtabs/CustomTabsLauncher.kt`: Provider-Erkennung, `warmup()`, `mayLaunchUrl()`, Start im App-Task
- [~] `CookieManager`: `setAcceptCookie`, Third-Party-Cookies, `flush()` in `onPause`/`onStop`
- [~] `domStorageEnabled`/`databaseEnabled` + Tab-Persistenz (DataStore)
- [ ] **Spike am echten Gerät:** Login im WebView-Modus → Ergebnis in `docs/perf/m2-login-gate.md`
- [ ] 2FA/Passkey-Verhalten testen (`onPermissionRequest` lehnt aktuell ab — ggf. anpassen)
- [ ] Session-Test: App-Kill → Neustart → ohne Login in der Console
- [ ] **DoD:** Akzeptanztest aus PLAN Kapitel 6 grün (WebView **oder** sauberer Custom-Tabs-Fallback)

## M3 — Cache-Kern (L1) ⭐ (5–8 PT)

- [~] `cache/Rules.kt` + `cache/RuleSetParser.kt` + `cache/GlobMatcher.kt`: Regelwerk aus JSON
      (`assets/web-rules.json`), Modi `OFF|LOG_ONLY|ENFORCE`, harte Ausschlüsse (POST, Main-Frame,
      Auth-Domains, `batchexecute`)
- [~] `cache/DiskAssetCache.kt`: Journal + LRU + Größenlimit, TTL, `getStale` für SWR,
      Integritätsprüfung, Flush-Throttling
- [~] `cache/HttpAssetFetcher.kt`: geteilter OkHttp-Client, `Accept-Encoding` bewusst nicht
      weitergereicht (transparent dekodiert), `Cookie`/`Authorization` entfernt,
      `Set-Cookie`/`Vary: Cookie`/206 → nicht cachebar
- [~] `cache/ResponseFactory.kt`: Header-Treue (Hop-by-Hop raus, CORS/Timing/ETag rein,
      `Vary` bereinigt, `Cache-Control` = unsere TTL) + Reason-Phrases
- [~] `cache/RequestPipeline.kt`: Klassifikation → Block/Cache/Miss/SWR/LocalOverride,
      Bypass-Fenster für „Cache umgehen", Revalidate im Hintergrund
- [~] `cache/WebResourceResponses.kt`: 6-Argumente-Konstruktor, Encoding nur bei Text
- [~] Unit-Tests: `GlobMatcherTest`, `RuleSetParserTest`, `RuleEngineTest`, `ResponseFactoryTest`,
      `DiskAssetCacheTest`, `RequestPipelineTest` (MockWebServer), `UserAgentProviderTest`,
      `NavigationPolicyTest`
- [~] Debug-Screen mit Live-Request-Log, Hit-Rate, Bytes, Interceptor-Kill-Switch
- [ ] **Tests laufen lassen** (erst nach dem ersten Build möglich)
- [ ] Regelwerk am echten Traffic verifizieren (chrome://inspect) → `assets/web-rules.json` schärfen
- [ ] Funktions-Checkliste unten komplett grün
- [ ] **DoD:** Hit-Rate ≥ 90 % auf statische Assets, FCP warm < 1,2 s, keine Funktionsdefekte

## M4 — Blocker & Prewarm (L2/L4) (3–5 PT)

- [~] Blocker-Regeln im Seed (Telemetrie, `gen_204`, GTM/Analytics; Feedback/Survey default **aus**)
- [~] Modus `LOG_ONLY` als Default (messen, bevor verworfen wird) + Umschalter in den Einstellungen
- [~] Lernen aus Traffic: Top-N nach Bytes → `AppPrefs.prefetchUrls` (persistiert)
- [~] `AppGraph.prewarmLearnedAssets()` beim App-Start im Hintergrund (Limit 48 MB)
- [ ] `WorkManager`-Job für gelegentliches Prewarm (bewusst noch nicht eingebaut, um die
      Abhängigkeitsliste klein zu halten)
- [ ] Blocker auf `ENFORCE` schalten und Funktionsverluste prüfen
- [ ] Vorher/Nachher-Messung → `docs/perf/`
- [ ] **DoD:** −30 % Requests, keine Funktionsverluste

## M5 — Lokales Shell-Skeleton (L3) (2–4 PT)

- [~] `ui/browser/SkeletonOverlay.kt`: Compose-Skeleton (Top-Bar, Nav-Rail, Karten) mit Shimmer
- [~] `assets/shell/index.html` + `shell.css`: lokale Shell für Offline/Fehler (kein JS, kein Netzwerk)
- [~] `WebViewAssetLoader` auf `/shell/` → `https://appassets.androidplatform.net/shell/index.html`
- [~] Ausblenden bei `onPageCommitVisible` (kein Flackern, kein Doppel-UI)
- [~] Offline-Banner mit Aktion „Gespeicherte Shell anzeigen"
- [ ] „Time-to-visible" ab `Application.onCreate` messen (Ziel < 300 ms)
- [ ] **DoD:** Skeleton < 300 ms sichtbar, Übergang sauber

## M6 — „Keine extra Fenster" (4–6 PT)

- [~] `setSupportMultipleWindows(false)` + `javaScriptCanOpenWindowsAutomatically=false`
- [~] `onCreateWindow` → `WebViewPool.createChildTab()` → **In-App-Tab** (max. 3), kein Fenster
- [~] `web/NavigationPolicy.kt` + Tests: gleiche Navigation in-app, fremde Domains in-app,
      `mailto:`/`tel:`/`intent:` nur nach Bestätigung, `javascript:`/`about:`/`data:` ignorieren
- [~] JS-Dialoge als Compose-`AlertDialog` (`GlobalDialogs.kt`), inkl. `onJsBeforeUnload`
- [~] File-Picker über `rememberLauncherForActivityResult` in derselben Activity
      (Callback wird **immer** beantwortet, sonst hängt der WebView)
- [~] Downloads: `ConsoleDownloadListener` + `DownloadStore` (eigener OkHttp-Download,
      Console-Cookie, app-privater Ordner, FileProvider) + `DownloadsScreen`
- [~] Permission-Prompts: `request.deny()` + in-app-Erklärung
- [~] App-Shortcuts (`res/xml/shortcuts.xml`) öffnen dieselbe Activity mit Ziel-URL
- [~] Tab-Strip, Tab-Persistenz, `onTrimMemory`-Freigabe der Warm-Instanzen
- [~] Long-Press-Kontextmenü auf Links (**offen**: noch nicht implementiert — nur Overflow-Menü)
- [ ] **Testmatrix unten vollständig am Gerät abarbeiten**
- [ ] **DoD:** kein automatischer Pfad verlässt die App

## M7 — Messen, Tunen, Polish (3–5 PT)

- [~] Debug-Screen mit Zählern, Log, Cache-Größe, Engine/Regel-Status
- [ ] `androidx.webkit`-`NavigationListener` (`onFirstContentfulPaintMillis`,
      `onLargestContentfulPaintMillis`) anbinden — API-Signatur gegen 1.16.0 prüfen
- [ ] Baseline „nackter WebView" (Interceptor aus, Regeln `OFF`) auf 2 Geräten
- [ ] Perfetto/`androidx.tracing` für Kaltstart
- [ ] KPI-Ziele (PLAN §9) prüfen, Engpässe beheben → `docs/perf/messungen-<datum>.md`
- [~] Settings-Screen (Engine, Cache-Größe/-leeren, Regel-Modus, Dark Mode, Text-Zoom, Bilder,
      Start-URL, Links-in-App)
- [~] Dark Mode: Compose-Thema (System/An/Aus) + WebView-Darkening
- [ ] Adaptive Layouts: Tablet/Foldable/Desktop-Mode (API-37-Pflicht) — aktuell nur Phone-Layout
- [ ] **DoD:** KPIs erreicht und dokumentiert

## M8 — Härtung & Release (2–3 PT)

- [~] Security-Grundeinstellungen: kein `addJavascriptInterface`, keine JS-Injection in Google-Seiten,
      kein `handler.proceed()`, `MIXED_CONTENT_NEVER_ALLOW`, `allowBackup=false`, kein Klartext
- [~] R8/ProGuard-Regeln, `isShrinkResources`
- [ ] eigener Release-Keystore (`keystore.properties`, nicht im Repo)
- [ ] Crash-Handling: `Thread.setDefaultUncaughtExceptionHandler` → in-app-Fehlerseite
- [ ] OSS-Lizenzhinweise, Installations-README (Sideload)
- [ ] APK-Größe prüfen (Ziel < 15 MB)
- [ ] **DoD:** signiertes Release-APK installierbar und dokumentiert

---

## Testmatrix „keine extra Fenster" (M6)

Jeder Fall muss **in-app** bleiben (oder explizit vom Nutzer bestätigt werden).

| # | Auslöser | Erwartung | Code liegt | Am Gerät geprüft |
|---|---|---|---|---|
| 1 | Normaler Link in der Console | Navigation im selben WebView | ✅ | [ ] |
| 2 | `target="_blank"`-Link | in-app Tab, kein System-Browser | ✅ | [ ] |
| 3 | `window.open()` aus JS | in-app Tab (nur mit Nutzergeste) | ✅ | [ ] |
| 4 | Link zu `cloud.google.com/docs` | in-app | ✅ | [ ] |
| 5 | `mailto:`-Link | in-app Rückfrage | ✅ | [ ] |
| 6 | `tel:`-Link | in-app Rückfrage | ✅ | [ ] |
| 7 | `intent:`/`market:`-Scheme | Rückfrage, nie automatisch | ✅ | [ ] |
| 8 | JS `alert`/`confirm`/`prompt` | Compose-Dialog in der Activity | ✅ | [ ] |
| 9 | Datei-Upload | System-Picker, Rückkehr in dieselbe Activity | ✅ | [ ] |
| 10 | Download (Billing-CSV, PDF) | in-app Downloads-Screen | ✅ | [ ] |
| 11 | OAuth-Redirect | bleibt im WebView bzw. Engine-Fallback | ✅ | [ ] |
| 12 | Permission-Prompt | ablehnen + in-app-Erklärung | ✅ | [ ] |
| 13 | SSL-Fehler | Abbruch + in-app-Dialog | ✅ | [ ] |
| 14 | Renderer-Crash | neue Instanz, letzte URL, Hinweis | ✅ | [ ] |
| 15 | Back-Taste / Predictive Back | History → Tab → Background | ✅ | [ ] |
| 16 | Rotation / Dark-Mode / Split-Screen | kein Reload (`configChanges`) | ✅ | [ ] |
| 17 | App-Shortcut | dieselbe Activity (`singleTask`) | ✅ | [ ] |
| 18 | Prozess-Kill + Neustart | Session + Tabs wiederhergestellt | ✅ | [ ] |
| 19 | Long-Press auf Link | Kontextmenü in-app | ❌ offen | [ ] |
| 20 | Flugmodus | Offline-Banner + gespeicherte Shell | ✅ | [ ] |

---

## Funktions-Checkliste Console (nach jedem Cache-Release)

| Bereich | Prüfung | Status |
|---|---|---|
| Login | Anmelden, Konto wechseln, Abmelden | [ ] |
| Projekt | Projekt-Switcher, Projekte anlegen | [ ] |
| Compute Engine | VM-Liste, VM-Details, Start/Stopp | [ ] |
| GKE | Cluster-Liste, Workloads | [ ] |
| Logs Explorer | große Ergebnismengen, Zeitfilter, Export | [ ] |
| Monitoring | Dashboards, Charts (SVG/Canvas) | [ ] |
| Billing | Berichte mit Diagrammen, CSV-/PDF-Export | [ ] |
| IAM | Rollen zuweisen, Mitgliederliste | [ ] |
| BigQuery | Editor öffnen, Query ausführen | [ ] |
| Cloud Shell | Öffnen im WebView (Terminal-Rendering) | [ ] |
| Storage | Bucket-Liste, Datei-Upload/-Download | [ ] |
| Suche | Omni-Box/Schnellsuche | [ ] |
| UI | Dark Mode, Text-Zoom, Scroll-Performance | [ ] |
