# ADR-001 — Warum (embedded) WebView und nicht TWA, Custom Tabs, PWA oder nativer Client

- Status: **akzeptiert, am 2026-09-08 durch Auftraggeber-Entscheidung erweitert** (Zwei-Engine-Modell)
- Datum: 2026-09-08
- Kontext: Android-App, die die Google Cloud Console schnell und „ohne extra Fenster" verfügbar macht.

## Entscheidung

Wir bauen eine **Single-Activity-App mit Android WebView (Chromium)** und einer **nativen
Cache-Schicht** über `shouldInterceptRequest`. Keine Trusted Web Activity, kein nativer
Console-Client, kein lokaler HTTP-Proxy-Server.

**Update 2026-09-08 (Entscheidung des Auftraggebers: „O2 — policy-sicher"):** Custom Tabs sind
nicht mehr nur Fallback, sondern eine **gleichwertige, umschaltbare Engine**. Umgesetzt ist ein
Drei-Wege-Schalter (`AUTO` / `WEBVIEW` / `CUSTOM_TABS`, siehe `model/Engine.kt`):

- `AUTO` startet im WebView (voller Cache) und degradiert automatisch auf Custom Tabs, sobald die
  Google-Blockade erkannt wird (`UserAgentProvider.looksLikeOAuthBlock`).
- `CUSTOM_TABS` ist der dauerhafte policy-sichere Modus: Die Console läuft in einem Chrome Custom
  Tab **im Task dieser App** — die Back-Taste kehrt zur App zurück, es entsteht kein eigener
  Recents-Eintrag, aber es ist eine sichtbare Chrome-Fläche.
- Warum nicht „Login im Custom Tab, dann WebView": Custom Tabs teilen das Cookie-Jar von Chrome,
  auf das andere Apps keinen Zugriff haben (ohne Root). Ein Cookie-Transfer in den App-WebView ist
  damit ausgeschlossen — dieser Weg entfällt ersatzlos.

Konsequenz: Anforderung A2 (eigener UI-Cache) ist **nur** in den WebView-Modi erfüllbar; im
Custom-Tabs-Modus bleibt als Beschleunigung `warmup()` + `mayLaunchUrl()`.

## Alternativen im Vergleich

| Variante | Login möglich? | Eigener Cache/Kontrolle? | „Keine extra Fenster"? | Aufwand | Urteil |
|---|---|---|---|---|---|
| **A. WebView + native Cache-Schicht** (gewählt) | ⚠️ nur mit Chrome-artigem UA (Google-Policy!) | ✅✅ volle Kontrolle über jeden Sub-Request | ✅✅ alles in einer Activity | mittel–hoch | **Beste Passung** auf die Anforderungen, aber mit dem OAuth-Risiko |
| B. Chrome Custom Tabs | ✅✅ (nutzt Chromes Session, policy-konform) | ❌ kaum (nur `mayLaunchUrl`-Prewarm, kein Request-Zugriff) | ❌ sichtbare Chrome-Fläche über der App | sehr gering | Schnellster Weg zu „funktioniert + eingeloggt", aber kein eigener UI-Cache → Kernanforderung A2 nicht erfüllt. **Plan B** |
| C. TWA / Bubblewrap (PWA im Vollbild) | ✅ | ❌ (Chrome-Engine, kein Interceptor) | ✅ (Chrome läuft als eigener Task/Overlay) | gering | **Unmöglich**: TWA verlangt `assetlinks.json`/Digital Asset Links auf der Ziel-Domain — `console.cloud.google.com` gehört uns nicht. Zudem gibt es dort kein Web-App-Manifest für Voll-PWA |
| D. Eigenes UI gegen Google-Cloud-APIs | ✅ (OAuth nativ) | n/a | ✅ | sehr hoch | Riesenaufwand, nie vollständig (die Console hat hunderte Oberflächen), ToS-/Wartungsrisiko. Explizit Nicht-Ziel |
| E. Lokaler HTTP-Server in der App (z. B. NanoHTTPD) als Cache-Proxy, WebView zeigt `http://127.0.0.1:PORT` | ❌ Origin-Wechsel killt Cookies/CORS/SSO | ✅ | ✅ | hoch | Verworfen: anderer Origin ⇒ Google-Cookies, CORS, `Secure`-Cookies und SameSite brechen; HTTPS auf localhost zusätzlich problematisch. `shouldInterceptRequest` behält den **echten** Origin — klar überlegen |
| F. WebView + Service Worker / Cache API (Web-Standard) | ⚠️ | ❌ | ✅ | mittel | Verworfen: **Service Worker sind im Android WebView nicht brauchbar** (Registrierung läuft nicht zuverlässig, u. a. dokumentiert in Tauri-/Capacitor-Issues, Stand 2026 offen). Deshalb muss Caching nativ erfolgen |

## Begründung der Wahl

1. **Anforderung A2 (UI in der App speichern)** ist nur mit Zugriff auf einzelne Sub-Requests
   erfüllbar. Den gibt es ausschließlich in einem embedded WebView via `shouldInterceptRequest`.
2. **Anforderung A3 (keine extra Fenster)** ist mit Custom Tabs nicht erfüllbar.
3. Der WebView behält die **echte Origin** (`console.cloud.google.com`) → Cookies, CORS, CSP,
   SameSite, SSO-Redirects verhalten sich wie im Browser. Wir greifen nur in die *Auslieferung*
   von Ressourcen ein, nicht in die Identität der Seite.
4. Moderne WebView-Features sind verfügbar: `androidx.webkit` 1.16 (stabil seit Juli 2026) liefert
   `WebViewAssetLoader`, algorithmic Darkening, stabilisierte Async-Startup-APIs und
   `NavigationListener` mit **nativen FCP/LCP-Messwerten** — genau das, was wir für L0/L3/L5 und
   den Messplan brauchen.

## Belege (Recherche 2026-09-08)

- Google blockiert OAuth in embedded WebViews: Einführung 2016/2017 (`403 disallowed_useragent`),
  vollständige Blockade aller embedded WebViews ab **30.09.2021**, Policy-Ausweitung mit
  Nutzer-Warnung ab **24.07.2023**. Erkennung über den WebView-User-Agent; bekannter Workaround ist
  ein Chrome-artiger UA (grauer Bereich, kann jederzeit durch weitere Signale brechen).
- Service Worker im Android WebView nicht zuverlässig registrierbar (offene Issues in
  Tauri/Capacitor, 2024–2026); `WebViewAssetLoader` wird von Google als empfohlener Weg für lokale
  Inhalte über `https://appassets.androidplatform.net` genannt (secure context).
- Google Play verlangt seit **31.08.2026** `targetSdk ≥ 36` für neue Apps/Updates; Android 17
  (API 37) ist seit **16.06.2026** stabil. Aktuelle Toolchain: AGP 9.4.0 (Sept 2026) / Gradle 9.6 /
  JDK 17 / Compose BOM 2026.08.00 / `androidx.webkit:1.16.0` (minSdk 24).
- Die offizielle App **„Google Cloud"** (`com.google.android.apps.cloudconsole`) deckt nur einen
  Teil der Console ab (Billing-Überblick, Compute/App Engine, SSH, Cloud Shell, Incidents,
  Gemini Cloud Assist) und verweist für „alles Weitere" ausdrücklich auf die Web-Console — genau
  diese Lücke füllt dieses Projekt.

## Entscheidungs-Gate (Wann kippt diese Entscheidung?)

Falls in **M2** nachgewiesen wird, dass der Login auch mit Chrome-artigem UA nicht funktioniert
(z. B. weil Google Client-Hints/TLS-Fingerprinting auswertet), wird diese ADR neu geöffnet:

- **B1 (bevorzugt):** Hybrid — Custom Tab **ausschließlich** für `accounts.google.com`, danach
  WebView mit eigener Cache-Schicht. Kompromiss: ein sichtbares Chrome-Overlay beim Login
  (einmalig, danach nie wieder), dafür bleibt A2 erhalten. Zu klären: Cookie-Transfer zwischen
  Chrome-Jar und App-WebView (ggf. via „einmalig im WebView anmelden, nachdem das Konto in Chrome
  entsperrt wurde", oder Login per Gerätecode/`gcloud auth`-artigem Flow).
- **B2 (Fallback):** Reine Custom-Tabs-App mit `mayLaunchUrl`-Prewarm + Warm-Halten des Tabs.
  Verliert A2 weitgehend, erfüllt aber „funktioniert sofort".

Beide Varianten würden **vor** Investitionen in M3 (Cache-Kern) beschlossen — kein Code für die
Cache-Schicht, solange das Login-Gate nicht grün ist.
