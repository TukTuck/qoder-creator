# Console Pocket — Android-Wrapper für die Google Cloud Console

**Stand 2026-09-08: Plan + implementiertes Projektgerüst (M0–M6). Der Code wurde in dieser
Arbeitsumgebung nicht kompiliert — erster Build läuft lokal oder in CI.**

Eigenständiges Teilprojekt in diesem Repository, unabhängig vom Python-Projekt `qoder-creator` im Root.

## Was die App kann (soll)

`console.cloud.google.com` in einem eingebetteten Chromium (Android WebView) — mit einem **eigenen,
dauerhaften Cache für statische UI-Bausteine**, damit wiederholte Aufrufe fast ohne Netzwerk
auskommen. Und: **keine externen Fenster** — kein Browser-Handoff, kein zweiter Task, keine Popups;
selbst `target="_blank"` wird zu einem In-App-Tab.

## Zwei Engines (wichtig)

Google erlaubt die Konto-Anmeldung nicht in eingebetteten WebViews (`disallowed_useragent`).
Deshalb gibt es zwei Betriebsmodi, umschaltbar in den Einstellungen:

| Modus | Was passiert | Trade-off |
|---|---|---|
| `AUTO` (Default) | WebView mit vollem Cache; bei erkannter Login-Blockade automatischer Wechsel auf Custom Tabs | bestmögliche Ladezeit, Login-Risiko |
| `WEBVIEW` | erzwingt WebView mit Chrome-artigem User-Agent | maximaler Cache, Umgehung einer Google-Policy |
| `CUSTOM_TABS` | Console läuft in einem Chrome Custom Tab **im Task dieser App** (Back-Taste kehrt zurück) | Login funktioniert sicher, aber kein eigener Cache |

„Nur Login im Custom Tab, Rest im WebView" ist technisch nicht möglich: Chromes Cookie-Jar ist für
andere Apps nicht lesbar. Details: [`PLAN.md`](PLAN.md) Kapitel 6 und
[`docs/ADR-001`](docs/ADR-001-webview-vs-alternativen.md).

## Dokumente

| Datei | Inhalt |
|---|---|
| [`PLAN.md`](PLAN.md) | Hauptplan: Anforderungen, Realitätscheck, Architektur, 7 Beschleunigungs-Schichten, Login-Strategie, Keine-extra-Fenster-Policy, Messplan, Meilensteine, Risiken |
| [`docs/BUILD.md`](docs/BUILD.md) | Bauen, testen, installieren, **Troubleshooting/Fallbacks**, Messen |
| [`docs/ADR-001-webview-vs-alternativen.md`](docs/ADR-001-webview-vs-alternativen.md) | WebView vs. TWA/Custom Tabs/PWA/Proxy, inkl. Entscheidungs-Gate |
| [`docs/ADR-002-native-cache-layer.md`](docs/ADR-002-native-cache-layer.md) | Design der Cache-Schicht (Header-/CORS-Treue, Regeln, Fallstricke) |
| [`docs/backlog.md`](docs/backlog.md) | Umsetzungsstand, Aufgaben je Meilenstein, Testmatrix, Funktions-Checkliste |
| [`docs/web-rules.seed.json`](docs/web-rules.seed.json) | Regelwerk (Kopie in `app/src/main/assets/web-rules.json`) |

## Schnellstart

```bash
cd gcloud-console-app
gradle wrapper --gradle-version 9.6          # einmalig, Wrapper-JAR liegt nicht im Repo
./gradlew :app:testDebugUnitTest             # 8 Unit-Test-Klassen (Cache, Regeln, UA, Navigation)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Danach: Menü → **Cache & Messwerte** öffnen und die Hit-Rate beobachten. Erst wenn das Request-Log
ausgewertet ist, den Regel-Modus von `LOG_ONLY` auf `ENFORCE` stellen.

## Projektstruktur (Kern)

```
app/src/main/java/app/consolepocket/
├── ConsolePocketApp.kt        Application: Graph aufbauen, warmUp()
├── MainActivity.kt            DIE eine Activity (singleTask)
├── di/AppGraph.kt             manuelles DI (kein Hilt)
├── model/                     Engine-Modi, Console-URLs, URL-Klassifikation
├── state/AppPrefs.kt          DataStore: Einstellungen + Tab-Persistenz
├── web/                       WebView-Pool, Configurer, Clients, UA, Navigations-Policy
├── cache/                     Regelwerk, Disk-Cache, Fetcher, Pipeline, Header-Fabrik
├── downloads/                 In-App-Downloads (kein Browser-Handoff)
├── customtabs/                Custom-Tabs-Fallback inkl. mayLaunchUrl-Prewarm
├── metrics/CacheStats.kt      Zähler + Request-Log
└── ui/                        Compose: Browser, Settings, Downloads, Debug, Dialoge
```

## Was noch offen ist

Erster Build, Gate M2 (Login am echten Gerät), Gate M3 (Cache am echten Traffic), FCP/LCP-Anbindung
über `androidx.webkit`, Tablet/Foldable-Layouts, Long-Press-Kontextmenü, eigener Release-Keystore.
Siehe [`docs/backlog.md`](docs/backlog.md).

## Rechtliches

Inoffizielles Privat-Projekt. Kein Google-Produkt, keine Google-Marken im App-Namen/Icon,
keine Telemetrie, keine Datenweitergabe, `allowBackup=false`. Distribution per Sideload.
