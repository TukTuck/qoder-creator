# Console Pocket — Übergabe, Review-Guide und vollständiger Projektstatus

> Stand: 2026-09-10  
> Repository: `TukTuck/qoder-creator`  
> Teilprojekt: `gcloud-console-app/`  
> Branch: `arena/01a08151-qoder-creator`  
> PR: <https://github.com/TukTuck/qoder-creator/pull/1>

---

## 1. Worum es in diesem PR geht

Dieser PR fügt **ein komplett neues Android-Teilprojekt** unter `gcloud-console-app/` hinzu.
Das bestehende Python-Projekt im Repository-Root bleibt fachlich unberührt.

Ziel der App ist ein privater Android-Wrapper für die **Google Cloud Console**
(`console.cloud.google.com`) mit folgenden Leitplanken:

1. **Die echte Web-Console bleibt erhalten** — kein nativer Nachbau, keine Feature-Reduktion.
2. **So viel UI wie möglich lokal speichern/cachen**, um Wiederholungsaufrufe deutlich zu beschleunigen.
3. **Keine automatischen extra Fenster** — kein externer Browser-Handoff, keine zweite Activity,
   keine Popups; `target="_blank"` wird in einen In-App-Tab umgeleitet.
4. **Login und Session sollen einen Neustart überleben**.
5. **Messbarkeit statt Bauchgefühl** — Cache-Hit-Rate, Ladezeiten und Request-Verhalten sind als
   explizite Gates dokumentiert.

---

## 2. Kurzfazit zum Lieferstand

### Geliefert
- neues Android-Projekt mit Kotlin/Compose/Gradle-Struktur
- Single-Activity-App mit WebView/Chromium-Fokus
- Zwei-Engine-Modell für Login-Risiko:
  - `AUTO`
  - `WEBVIEW`
  - `CUSTOM_TABS`
- eigene Cache-/Intercept-Pipeline für Web-Ressourcen
- lokales Regelwerk für Allow/Cache/Block
- WebView-Pool für In-App-Tabs statt neue Fenster
- Compose-Screens für Browser, Settings, Downloads, Debug, Dialoge
- persistente Einstellungen/Tab-Zustände
- Unit-Tests für Kernlogik
- umfangreiche Dokumentation: Plan, ADRs, Build, Backlog, Messvorlagen

### Nicht abschließend verifiziert
- **kein echter Android-Build in der Sandbox**
- **kein Geräte-Login-Test**
- **keine echte FCP/LCP-Messung am laufenden Gerät**
- **kein GitHub Actions Workflow im PR selbst**, weil die aktuelle GitHub-Verbindung in Arena
  keine Schreibrechte für `.github/workflows/*` hatte

### Deshalb ist der aktuelle Status ehrlich gesagt
**Code und Dokumentation sind geliefert, statisch geprüft und gepusht — aber die ersten echten
Build-/Geräte-Gates müssen jetzt außerhalb der Sandbox erfolgen.**

---

## 3. Warum dieses Projekt ein Sonderfall ist

Die Google Cloud Console ist keine einfache Website, sondern eine große Single-Page-App mit vielen
Ressourcen, Dritt-Domains, Sessions, Redirects und OAuth-/Google-Login-Besonderheiten.

Es gibt dabei drei architektonische Realitäten:

### 3.1 Google blockiert Login in eingebetteten WebViews
Google erkennt eingebettete WebViews üblicherweise am User-Agent (`; wv`) und blockiert Login-Flows
mit `disallowed_useragent`.

**Konsequenz:** Ein „normales" reines WebView-Produkt ist für Login riskant.

### 3.2 Android WebView ist kein verlässlicher Service-Worker-Offline-Browser
Die typische Web-Lösung „Service Worker + CacheStorage" ist im Android-WebView-Kontext nicht
zuverlässig genug.

**Konsequenz:** Das Projekt braucht eine **native Request-Pipeline** über
`shouldInterceptRequest()` + eigenen Disk-Cache.

### 3.3 „Nur Login extern, danach intern" ist technisch nicht sauber übertragbar
Chrome Custom Tabs teilen ihre Session nicht so mit fremden Apps, dass man danach dieselben Cookies
im eigenen WebView sicher weiterverwenden könnte.

**Konsequenz:** Statt „nur kurz Login extern" wurde ein **Zwei-Engine-Modell** gebaut.

---

## 4. Die entscheidende Produktentscheidung: Zwei Engines

Die App hat drei Modi:

| Modus | Zweck | Vorteil | Nachteil |
|---|---|---|---|
| `AUTO` | Standardmodus | versucht schnellen WebView-Pfad, fällt bei Login-Blockade automatisch zurück | Verhalten hängt vom echten Google-Login ab |
| `WEBVIEW` | erzwungener WebView-Pfad | maximaler eigener Cache, schnellster Zielpfad | kann an Google-Login-Policy scheitern |
| `CUSTOM_TABS` | sicherer Fallback | Google-Login funktioniert deutlich robuster | kein Zugriff auf einzelne Requests, also kein eigener Asset-Cache |

### Wichtiger Realitätscheck
Der Nutzerwunsch „möglichst alles im WebView, aber Login muss trotzdem gehen" ist nur begrenzt
kompatibel mit Googles Login-Policy. Deshalb ist `AUTO` der pragmatische Kompromiss:

- **wenn WebView-Login funktioniert** → beste Performance-Story
- **wenn Google blockiert** → App degradiert kontrolliert auf `CUSTOM_TABS`

---

## 5. Architekturüberblick

### 5.1 UI und Prozessmodell
- genau **eine Activity**: `MainActivity`
- `singleTask`
- Compose für App-Chrome, Menüs, Einstellungen, Dialoge
- keine zweite Activity als Browser-Ersatz
- kein automatischer externer Browser-Sprung

### 5.2 WebView-Schicht
- `WebViewConfigurer` konfiguriert WebView-Einstellungen
- `ConsoleWebViewClient` steuert Navigation, Interception und Fehlerbehandlung
- `ConsoleWebChromeClient` behandelt Popups/JS-Dialoge/Fortschritt/Dateiauswahl
- `WebViewPool` hält aktive und warme Instanzen vor

### 5.3 Cache-/Netzwerkschicht
- `RequestPipeline` ist der zentrale Einstieg für abgefangene Requests
- `RuleEngine` klassifiziert URLs nach Regelwerk
- `DiskAssetCache` speichert statische Assets lokal
- `HttpAssetFetcher` lädt Cache-Misses mit OkHttp nach
- `ResponseFactory` baut korrekte `WebResourceResponse`-Objekte

### 5.4 App-Zustand
- `AppPrefs` speichert Einstellungen, Tabs, Modus, Flags
- `AppViewModel` ist die zentrale Orchestrierung zwischen UI, Tabs, Engine und Host-Callbacks
- `AppGraph` ist das manuelle DI-/Wiring-Zentrum

### 5.5 Zusatzfunktionen
- `DownloadStore` + `ConsoleDownloadListener` für In-App-Downloads
- `CustomTabsLauncher` für den sicheren Engine-Fallback
- `CacheStats` und Debug-Screen für Messung und Einsicht

---

## 6. Was funktional umgesetzt wurde

## M0 — Projekt-Setup
- vollständiges Android-Modul unter `gcloud-console-app/`
- Gradle-Konfiguration mit Version Catalog
- Kotlin/Compose/Material3-Grundgerüst
- Manifest, Ressourcen, Strings, Theme
- Build-Dokumentation

## M1 — WebView-Basis
- WebView-Konfiguration
- Fortschritt und Grundnavigation
- Fehlerfälle und Lifecycle-Grundlagen
- Single-Activity-Struktur

## M2 — Login-/Session-Strategie
- Engine-Modi `AUTO`, `WEBVIEW`, `CUSTOM_TABS`
- Erkennung problematischer Login-Situationen
- Persistenz relevanter Zustände
- Dialog-/Fallback-Mechanik im ViewModel

## M3 — Cache-Kern
- eigener Disk-Cache
- Regelparser und Pattern-Matching
- Response-Erzeugung mit Header-Bereinigung
- Request-Interception mit `LOG_ONLY` / `ENFORCE`
- Tests für Hit/Miss, SWR, Bypass, Block-Fälle

## M4 — Regeln / Vorwärmung / Observability
- Regelwerk aus JSON-Seed
- lernende Prefetch-/Warmup-Ansätze im Graph
- Metriken und Debug-Ansicht

## M5 — wahrgenommene Performance
- lokales Skeleton/Offline-Shell unter `assets/shell/`
- vorbereitete Einbettung für schnellen sichtbaren Start
- Messvorlagen unter `docs/perf/`

## M6 — „Keine extra Fenster"
- In-App-Tabs statt neuer Fenster
- `target="_blank"`-Abfangstrategie
- Compose-Dialoge statt ausgelagerter JS-Dialoge
- Downloads-Screen
- File-Chooser-Integration
- Shortcuts / Deep-Link-Ansatz in derselben App-Struktur

---

## 7. Was besonders wichtig ist: Keine-extra-Fenster-Policy

Der Nutzerwunsch war ausdrücklich: **keine externen Fenster, keine Popups, keine zweite Activity,
kein automatischer Sprung in einen Browser**.

Diese Policy wurde im Design durchgezogen:

| Fall | Verhalten |
|---|---|
| normaler Link | im bestehenden WebView |
| `target="_blank"` / `window.open()` | als In-App-Tab |
| JS-Alert/Confirm/Prompt | Compose-Dialog |
| Datei-Upload | System-Picker, aber Rückkehr in dieselbe Activity |
| Download | In-App-Downloadpfad statt Browser-Handoff |
| Back | WebView-History → Tab schließen → App bleibt konsistent |
| fremde Inhalte | soweit möglich innerhalb derselben Engine |

### Wichtige Ehrlichkeit
`CUSTOM_TABS` ist zwar **kein separater Task / kein klassischer Browser-Handoff**, aber optisch
natürlich trotzdem eine Chrome-Fläche. Das ist der Kompromiss für den policy-sicheren Login-Pfad.

---

## 8. Cache-Design im Klartext

Das Cache-System ist der technische Kern des Projekts.

### Ziel
Nicht einfach „Seite im WebView öffnen", sondern gezielt:
- statische UI-Artefakte lokal vorhalten,
- unnötige Requests reduzieren,
- Wiederholungsaufrufe drastisch beschleunigen.

### Wichtige Designentscheidungen
- nur sichere Kandidaten werden aggressiv gecacht
- Hash-/Version-Assets sind ideale Cache-Kandidaten
- heikle Inhalte wie auth-bezogene oder Cookie-kritische Antworten werden konservativ behandelt
- `Set-Cookie`, `Vary: Cookie`, partielle Antworten (`206`) etc. werden nicht einfach blind gecacht
- Hop-by-hop-Header und problematische Header werden bereinigt
- CORS-/Timing-/ETag-relevante Header werden erhalten

### Modi
- `OFF` = Interceptor aus
- `LOG_ONLY` = Requests klassifizieren und messen, aber nicht aggressiv blocken
- `ENFORCE` = Block-/Cache-Regeln aktiv durchsetzen

### Warum `LOG_ONLY` default ist
Weil bei einer komplexen SaaS-Konsole falsches Blocken schnell zu subtilen Defekten führt.
Darum zuerst messen, dann schrittweise scharf schalten.

---

## 9. Tests und statische Prüfungen

## Unit-Tests im Projekt
Es gibt 8 JVM-Testklassen:

- `GlobMatcherTest`
- `RuleSetParserTest`
- `RuleEngineTest`
- `ResponseFactoryTest`
- `DiskAssetCacheTest`
- `RequestPipelineTest`
- `NavigationPolicyTest`
- `UserAgentProviderTest`

### Was diese Tests abdecken
- Pattern-/Glob-Matching
- Seed-Parsing
- Regelklassifikation
- Header- und Response-Verhalten
- Disk-Cache-Verhalten
- Pipeline-Szenarien inkl. Miss/Hit/SWR/Bypass/Block
- Navigation-Policy
- User-Agent-Logik

## Zusätzliche statische Prüfung außerhalb eines echten Builds
Vor dem Push wurden u. a. geprüft:
- Ressourcenreferenzen (`R.string`, `R.drawable`, `@xml`, `@color`, `@mipmap`)
- XML-/Manifest-Parsebarkeit
- projektinterne Imports
- wichtige Empfänger-/Methodenverweise
- Material-Icons-Verfügbarkeit
- Compose-Fehlmuster (z. B. Ressourcenaufrufe in problematischen Lambdas)
- `Modifier.weight`-Einsatz im korrekten Scope
- Klammer-/Brace-Balance aller Kotlin-Dateien

### Wichtig
Diese Prüfungen reduzieren Fehler stark, **ersetzen aber keinen echten Gradle-/Android-Build**.

---

## 10. Was in der Sandbox bewusst nicht möglich war

Die Arena-Sandbox hatte in diesem Arbeitslauf:
- **kein JDK**
- **kein Android SDK**
- **kein Gradle-Online-Fetch aus bash**

Daraus folgt:
- `./gradlew assembleDebug` konnte hier nicht real ausgeführt werden
- Android-Lint konnte hier nicht real laufen
- UI/Integration auf Gerät/Emulator konnte hier nicht verifiziert werden

### Das bedeutet für den Review
Der richtige nächste Schritt ist **nicht** noch mehr Theorie, sondern:
1. lokal oder in GitHub Actions bauen,
2. App auf Gerät installieren,
3. Login-Gate M2 durchführen,
4. danach Cache-/Performance-Gate M3 durchführen.

---

## 11. Warum der GitHub-Workflow nicht im PR gelandet ist

Die Datei

```text
.github/workflows/android-console-pocket.yml
```

wurde vorbereitet, konnte aber **nicht** in den Branch gepusht werden, weil die aktuelle
GitHub-Verbindung in Arena keine ausreichende `workflows`-Berechtigung hatte.

### Was trotzdem vorhanden ist
1. Die eigentliche Workflow-Datei liegt lokal im Workspace.
2. Ein anwendbarer Patch liegt versioniert im Projekt unter:

```text
gcloud-console-app/docs/patches/add-android-console-pocket-workflow.patch
```

### So wird der Workflow nachgezogen
```bash
git apply gcloud-console-app/docs/patches/add-android-console-pocket-workflow.patch
git add .github/workflows/android-console-pocket.yml
git commit -m "Add GitHub Actions workflow for Android app"
git push origin arena/01a08151-qoder-creator
```

---

## 12. Build- und Review-Anleitung

## Minimaler lokaler Build
```bash
cd gcloud-console-app
gradle wrapper --gradle-version 9.6
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## APK installieren
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Nützliche Folgekommandos
```bash
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

## Falls es an AGP/Kotlin/SDK scheitert
Dann bitte direkt `gcloud-console-app/docs/BUILD.md` lesen. Dort sind die Fallbacks dokumentiert,
z. B.:
- built-in Kotlin deaktivieren und `kotlin.android` aktivieren
- temporär auf API 36 zurückgehen
- Compose-/AGP-Abhängigkeiten gemeinsam anheben
- Configuration Cache ausschalten

---

## 13. Empfohlene Verifikationsreihenfolge

## Schritt 1 — echter Build
Erst prüfen, ob das Projekt lokal/CI sauber baut.

## Schritt 2 — Gate M2: Login
Verifikation auf einem **echten Gerät**:
- App starten
- Default `AUTO` testen
- Login-Seite aufrufen
- falls WebView blockiert → Verhalten des sicheren Fallbacks prüfen
- nach erfolgreichem Login: App komplett beenden und neu öffnen
- prüfen, ob Session erhalten bleibt

Vorlage dafür:
- `gcloud-console-app/docs/perf/m2-login-gate.md`

## Schritt 3 — Referenzmessung ohne Interceptor
- Interceptor aus
- Regelmodus `OFF`
- Ladeverhalten notieren

## Schritt 4 — Gate M3: Cache/Hit-Rate
- Interceptor an
- `LOG_ONLY`
- Requests, Bytes, Hit-Rate, offensichtliche UI-Defekte prüfen
- erst nach validem Verhalten Richtung `ENFORCE`

Vorlage dafür:
- `gcloud-console-app/docs/perf/README.md`

---

## 14. Abnahmestatus nach heutigem Stand

| Ziel | Status | Erläuterung |
|---|---|---|
| neue Android-App im Repo | erfüllt | unter `gcloud-console-app/` |
| Single-Activity-Grundidee | geliefert | im Code umgesetzt |
| keine automatischen extra Fenster | geliefert, praktisch noch zu testen | Design und Codepfade vorhanden |
| lokales UI-/Asset-Caching | geliefert | Cache-/Pipeline-Code vorhanden |
| Login funktioniert sicher | offen | hängt am echten Geräte-Gate M2 |
| Session überlebt Neustart | offen | Code vorgesehen, muss verifiziert werden |
| ≥ 90 % Cache-Hit-Rate statische Assets | offen | Gate M3 erforderlich |
| Warm FCP < 1,2 s | offen | echte Messung fehlt |
| Tests vorhanden | erfüllt | 8 JVM-Testklassen im Repo |
| CI-Workflow im PR | **nein** | nur als Patch vorbereitet wegen GitHub-Permissions |

---

## 15. Bekannte Einschränkungen / offene Risiken

1. **Google kann WebView-Login weiterhin blockieren.**
   Dann ist `CUSTOM_TABS` der robuste Pfad, aber ohne eigenen Request-Cache.

2. **Ohne echten Build sind API-/Versionsdetails noch nicht final bewiesen.**
   Das betrifft vor allem AGP-/Compose-/WebKit-Signaturen.

3. **Blocklisten können funktionale Seiteneffekte haben.**
   Daher Default `LOG_ONLY`.

4. **`blob:`-Downloads sind nativ nicht sauber greifbar.**
   Dafür ist aktuell nur eine Nutzerhinweis-Strategie vorgesehen.

5. **Performance-Ziele sind vorbereitet, aber noch nicht gemessen.**
   Es gibt Messvorlagen, aber noch keine echten Ergebnisdateien.

---

## 16. Wichtige Dateien für Reviewer

### Einstieg
- `gcloud-console-app/README.md`
- `gcloud-console-app/README-HANDOFF.md`
- `gcloud-console-app/PLAN.md`

### Architektur / Entscheidungen
- `gcloud-console-app/docs/ADR-001-webview-vs-alternativen.md`
- `gcloud-console-app/docs/ADR-002-native-cache-layer.md`

### Build / Betrieb
- `gcloud-console-app/docs/BUILD.md`
- `gcloud-console-app/docs/backlog.md`

### Messung / Gates
- `gcloud-console-app/docs/perf/README.md`
- `gcloud-console-app/docs/perf/m2-login-gate.md`

### Workflow-Nachzug
- `gcloud-console-app/docs/patches/add-android-console-pocket-workflow.patch`

---

## 17. Empfehlung für die nächsten 30–60 Minuten

Wenn jemand diesen PR jetzt übernimmt, sollte er oder sie genau das tun:

1. Patch für den GitHub-Workflow anwenden oder Workflow-Datei manuell anlegen.
2. Android-Teilprojekt lokal oder in CI bauen.
3. Falls der Build fehlschlägt: `docs/BUILD.md` Fallbacks abarbeiten.
4. Debug-APK auf echtes Gerät installieren.
5. Login-Gate M2 durchführen.
6. Erst danach Cache-/Performance-Gate M3 messen.
7. Ergebnisse unter `docs/perf/` ergänzen.

---

## 18. Abschließende ehrliche Bewertung

Für den aktuellen Sandbox-Rahmen ist das Ergebnis **substanziell und reviewbar**:
- Architektur ist dokumentiert,
- Code ist vorhanden,
- Kernlogik und Tests sind angelegt,
- der Branch und PR existieren,
- der fehlende Workflow ist als Patch vorbereitet.

Der **entscheidende Rest** ist jetzt reale Verifikation auf Build-/Geräte-Ebene.
Genau dafür sind die Doku, die Gates und die Patch-Datei vorbereitet.

Wenn du nur **eine** Sache als Nächstes tust, dann: **erst bauen, dann M2-Login testen.**
