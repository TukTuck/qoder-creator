# ADR-002 — Design der nativen Cache-Schicht (`RequestPipeline`)

- Status: **akzeptiert und umgesetzt** (Code liegt, noch nicht kompiliert/getestet)
- Datum: 2026-09-08
- Ziel: statische UI-Bestandteile der Google Cloud Console dauerhaft in der App halten, sodass
  wiederholte Aufrufe fast ohne Netzwerk auskommen — ohne die Seite zu brechen.

## 1. Grundsatzentscheidung

Wir cachen **nicht** das HTML der Console (dynamisch, personalisiert, projektbezogen), sondern die
**unveränderlichen Bausteine**: JS-Bundles, CSS, Fonts, Icons, WOFF2, Bilder, Web-Assets von
`www.gstatic.com`. Genau dort liegen die Megabytes und die Latenz.

Auslieferungspunkt ist `WebViewClient.shouldInterceptRequest(view, request)`, weil:
- der **echte Origin** erhalten bleibt (Cookies, CORS, CSP, SSO unverändert),
- wir Statuscode, Header und Body kontrollieren,
- keine zweite Netzschicht (lokaler Proxy-Server) nötig ist.

## 2. Pipeline

```
request ──► RuleEngine.classify(url, method, headers)
              │
              ├─ IGNORE      → return null            (WebView/Chromium macht es selbst)
              ├─ BLOCK       → 204 No Content, 0 Byte (Telemetrie, Logging, Feedback)
              ├─ PASSTHROUGH → return null            (POST/XHR, HTML-Dokumente, Auth-Domains)
              ├─ CACHE_FIRST → DiskAssetCache.get(key)
              │                  ├─ hit  → WebResourceResponse(200, headers, body)   [+ Metrics]
              │                  └─ miss → OkHttp.fetch() → in Cache schreiben → ausliefern
              └─ SWR         → Cache-Hit sofort ausliefern **und** Hintergrund-Refresh (WorkManager/Coroutine)
```

**Regel:** nur `GET`/`HEAD`, nur Sub-Resources (`request.isForMainFrame == false`),
niemals Requests mit Body-Abhängigkeit. `shouldInterceptRequest` liefert **kein** POST-Body →
POSTs grundsätzlich `PASSTHROUGH`.

## 3. Klassifikation (Regelwerk, nicht hartkodiert)

Regeln liegen in `docs/web-rules.seed.json` (Seed) und zur Laufzeit in einer vom Nutzer
beschreibbaren DataStore-Kopie, plus „gelernten" Regeln aus beobachtetem Traffic.

| Merkmal | Policy | TTL | Begründung |
|---|---|---|---|
| Pfad enthält Versions-/Hash-Segment (z. B. `/_/js/`, `/m=`, `.<hash>.js`, `.<hash>.css`, `.<hash>.woff2`) | `CACHE_FIRST` | 30 Tage | Inhalt ist per Hash unveränderlich — Server-`Cache-Control` darf ignoriert werden |
| `www.gstatic.com/**` (statische Assets) | `CACHE_FIRST` | 30 Tage | dito |
| `fonts.gstatic.com/**` | `CACHE_FIRST` (Toggle: durch lokale Fonts ersetzen) | 90 Tage | Fonts ändern sich nie |
| Bilder/Icons (`png|jpg|svg|webp|ico`) auf Google-Domains | `CACHE_FIRST` | 7 Tage | klein, häufig wiederholt |
| HTML-Dokumente (`isForMainFrame`) | `PASSTHROUGH` | – | personalisiert, muss frisch sein |
| `apis.google.com`, `accounts.google.com`, `*.googleapis.com` (API-Calls) | `PASSTHROUGH` | – | funktionskritisch/authentifiziert |
| `play.google.com/log`, `clientmetrics`, `csi`, `firelog`, `googletagmanager.com`, `google-analytics.com`, Survey-/Feedback-Beacons | `BLOCK` | – | Latenz & Traffic ohne Nutzwert |
| alles Unbekannte | `IGNORE` (Chromium-Cache) | – | konservativ: nichts kaputt machen |

Jede Regel hat eine **Priorität** und einen `enabled`-Schalter; globaler Modus
`OFF | LOG_ONLY | ENFORCE` (in M3 zuerst `LOG_ONLY`, um den echten Traffic zu sehen, bevor etwas
verworfen wird).

## 4. Header-Treue — die häufigste Fehlerquelle

Ein Interceptor, der Header „vereinfacht", erzeugt schwer debuggbare Defekte (weiße Seite,
kaputte Charts, CORS-Fehler in der Konsole). Pflichtregeln:

1. **Statuscode & Reason-Phrase** über `WebResourceResponse(statusCode, reasonPhrase, headers, stream)`
   zurückgeben (nicht den 3-Arg-Konstruktor — der liefert implizit 200).
2. **`Content-Type` exakt übernehmen** (inkl. Charset).
3. **`Content-Encoding` nicht anfassen**: wenn wir `Accept-Encoding: gzip, br` weiterreichen, liegt
   der Body komprimiert vor und **muss komprimiert** zurückgegeben werden — sonst dekodiert Chromium
   doppelt/falsch. Alternative (einfacher, aber größer): OkHttp den Body dekodieren lassen und
   `Content-Encoding` + `Content-Length` aus unseren Antwort-Headern **entfernen**.
   → Entscheidung: Variante B (dekodiert speichern, Encoding-Header entfernen), weil deutlich
   einfacher zu testen; optionale Brotli-/Gzip-Kompression auf der Platte kann später folgen.
4. **CORS-Header replizieren**: `Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials`,
   `Access-Control-Expose-Headers`, `Timing-Allow-Origin`. Ohne sie schlagen `fetch()`/XHR der
   Console gegen gecachte Cross-Origin-Assets fehl.
5. **`Vary` respektieren**: Cache-Key = URL + relevante `Vary`-Header-Werte (z. B. `Accept-Encoding`,
   `Origin`). Sonst liefern wir einem anderen Kontext die falsche Variante.
6. **Keine Hop-by-Hop-Header** zurückgeben (`Connection`, `Transfer-Encoding`, `Keep-Alive`, …).
7. **`ETag`/`Last-Modified` mitspeichern** → ermöglicht später bedingte Revalidierung (`If-None-Match`)
   und sauberes SWR.
8. `Cache-Control`/`Expires` für den WebView-Cache neutralisieren (z. B. `max-age` passend zu unserer
   TTL), damit Chromium nicht zusätzlich verwirft — wir sind die einzige Cache-Instanz.

## 5. Speicher

- Ort: `context.cacheDir/webcache` (app-privat, von Android bei Bedarf verwerfbar) **plus**
  langlebige Kopie unter `context.filesDir/webcache` für die „immutable"-Klasse, damit
  Speicherdruck-Clearing nicht den Hauptgewinn vernichtet.
- Größe: default 256 MB (Settings: 64/128/256/512 MB), LRU-Eviction, Journal-Datei mit
  Metadaten (URL, Header, Größe, Zeitstempel, TTL-Klasse, Hash).
- Index im RAM (klein: nur Schlüssel → Offset/Metadaten), damit `shouldInterceptRequest`
  **nur schnelle Disk-Reads** macht.
- Integrität: SHA-256 des Bodys beim Schreiben; Korruptionsfund → Eintrag verwerfen + neu laden.
- `android:allowBackup="false"`; „Cache leeren" in den Settings; Export der Cache-Statistik im Debug-Screen.

**Entscheidung bei der Umsetzung (2026-09-08):** Es wurde ein **eigener** `DiskAssetCache`
implementiert (`cache/DiskAssetCache.kt`: Journal + LRU + TTL + Integritätsprüfung), weil der
OkHttp-Cache an HTTP-Semantik gekoppelt ist und unsere Regel „Server-`Cache-Control` bei gehashten
Assets ignorieren" sonst ausbremst. OkHttp wird nur noch als Transport für Cache-Misses genutzt
(`cache/HttpAssetFetcher.kt`).

*Ursprüngliche Überlegung (zur Nachvollziehbarkeit):* OkHttp `Cache` (fertig, robust, Journal, LRU) als Speicher-Backend
verwenden und unsere Logik als OkHttp-Interceptor + `Request`-Rekonstruktion davor schalten —
weniger Eigenbau. Gegenprobe in M3: eigener `DiskLruCache`, falls OkHttp-Cache zu sehr an
HTTP-Semantik (z. B. `Cache-Control`) gekoppelt ist und unsere „Server-Header ignorieren"-Regel
ausbremst. **Tendenz: OkHttp-Cache mit eigenem `Interceptor`, der Antworten gezielt
„fresh" markiert (Response-Header-Rewrite vor dem Cachen).**

## 6. Threading & Performance

- `shouldInterceptRequest` läuft auf einem WebView-Netzwerk-Thread. **Keine** langen Blockaden:
  kein Bitmap-Decoding, keine synchronen Retries, Timeouts kurz (connect 5 s / read 15 s).
- Gemeinsamer OkHttp-Client (ein Connection-Pool, HTTP/2, `Dispatcher` mit angepassten Limits).
- Cache-Reads über gepufferte Streams; Antwort-Body als `InputStream` direkt an
  `WebResourceResponse` durchreichen (nicht erst vollständig in `ByteArray` laden, außer bei
  kleinen Assets < 256 KB).
- Hintergrund-Refresh (SWR) und Prefetch laufen auf `Dispatchers.IO` bzw. `WorkManager`
  (Constraints: WLAN/ungemessert, Gerät geladen) — niemals auf dem UI-Thread.

## 7. Lernen & Prewarm (L4)

- Die Pipeline zählt pro URL: Häufigkeit, Bytes, Dauer, Trefferquote.
- „Top-N (nach Bytes × Häufigkeit)" wird nach jedem Seitenbesuch in eine Prefetch-Liste geschrieben.
- `PrewarmWorker` lädt diese Liste bei App-Start (parallel zur WebView-Initialisierung) und
  gelegentlich im Hintergrund → der Cache ist warm, **bevor** der Nutzer klickt. Auch nützlich nach
  einem Console-Deploy, das neue Asset-Hashes einführt.
- Kein Prefetch von authentifizierten API-Antworten (Frische/Datenschutz).

## 7.1 Umsetzungsstand (Klassen)

| Baustein | Klasse |
|---|---|
| Regelmodell + Klassifikation | `cache/Rules.kt` (`RuleSet`, `Rule`, `MatchSpec`, `Policy`, `RuleEngine`) |
| Glob-Matching | `cache/GlobMatcher.kt` |
| JSON-Seed-Parser | `cache/RuleSetParser.kt` (+ `assets/web-rules.json`) |
| Disk-Cache | `cache/DiskAssetCache.kt` |
| Netzwerk (Miss) | `cache/HttpAssetFetcher.kt` |
| Header-Treue | `cache/ResponseFactory.kt` |
| Orchestrierung | `cache/RequestPipeline.kt` |
| Android-Glue | `cache/WebResourceResponses.kt`, `web/ConsoleWebViewClient.kt` |
| Metriken | `metrics/CacheStats.kt`, `ui/debug/DebugScreen.kt` |
| Tests | `app/src/test/java/app/consolepocket/cache/*Test.kt`, `.../web/*Test.kt` |

## 8. Testbarkeit

- **Unit-Tests (Kern!):** `RuleEngine` (Klassifikation), Header-Mapping, Vary/CORS-Fälle, TTL,
  Eviction — mit `MockWebServer`.
- **Golden-Files:** aufgezeichnete Antwort-Header echter Console-Assets (anonymisiert) als
  Testfixture, damit Header-Rewrites regressionsfrei bleiben.
- **Debug-Screen:** Live-Log `URL | policy | cache/net/block | ms | bytes`, Hit-Rate-Zähler,
  Regel-Editor, „Interceptor aus"-Schalter.
- **Funktions-Checkliste** (muss nach jedem Cache-Release grün sein): Login, Projektwechsel,
  Compute-Liste, GKE, Logs-Explorer (große Tabellen), Billing (Charts!), IAM, BigQuery-Editor,
  CSV/PDF-Export, Datei-Upload, Dark Mode, Suche/Omni-Box.

## 9. Verworfen

- **HTML-Offline-Paket der Console im APK bundlen**: rechtlich fragwürdig, 50+ MB, sofort veraltet,
  Hash-Mismatch bricht die Seite. Stattdessen: Skeleton-Shell (L3) + gelernter Cache (L4).
- **Lokaler HTTP-Proxy-Server**: Origin-Wechsel zerstört Cookies/CORS/SSO (siehe ADR-001, Variante E).
- **JS-Injection zur Cache-Steuerung** (`evaluateJavascript`, eigene Fetch-Patches): Eingriff in
  Google-Code, bricht bei jedem Deploy, sicherheitstechnisch unerwünscht.
- **Service Worker**: im Android WebView nicht nutzbar (ADR-001, Variante F).
