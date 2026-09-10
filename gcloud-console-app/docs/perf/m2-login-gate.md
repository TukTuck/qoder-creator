# Gate M2 — Login-Fähigkeit (bitte ausfüllen)

> Dieses Gate entscheidet, ob der WebView-Pfad mit eigenem Cache tragfähig ist oder ob die App
> dauerhaft im Custom-Tabs-Modus (`CUSTOM_TABS`) betrieben wird. Solange das hier nicht grün ist,
> lohnt keine weitere Optimierung der Cache-Schicht (siehe `ADR-001`, „Entscheidungs-Gate").

## Versuchsaufbau

| Punkt | Wert |
|---|---|
| Datum |  |
| Gerät / Android-Version |  |
| Chromium/WebView-Version (Settings → Engine-Info) |  |
| App-Version / Commit |  |
| Engine-Modus | `AUTO` / `WEBVIEW` / `CUSTOM_TABS` |
| User-Agent im WebView (chrome://inspect → Network → Request-Headers) |  |

Prüfung: Enthält der gesendete UA noch `; wv`, `Build/…` oder `Version/4.0`? → falls ja, ist
`UserAgentProvider.chromeLike()` nicht wirksam (dann greift die Blockade garantiert).

## Ergebnisse

| Test | Erwartung | Ergebnis |
|---|---|---|
| `console.cloud.google.com` lädt im WebView | Console-Startseite oder Login-Redirect |  |
| Google-Anmeldung im WebView-Modus | Kontenauswahl → Passwort → 2FA |  |
| Fehler `403 disallowed_useragent` sichtbar? | nein |  |
| Policy-Warnseite („eingebettete WebViews") sichtbar? | nein |  |
| 2FA per Code (SMS/Authenticator) | funktioniert |  |
| 2FA per Passkey/WebAuthn | funktioniert **oder** bekannte WebView-Grenze (dann Passwort/Code nutzen) |  |
| App-Kill → Neustart | ohne erneuten Login in der Console |  |
| Flugmodus → Neustart | gecachte Assets werden geliefert, Hinweis erscheint |  |
| Konto wechseln / abmelden | funktioniert |  |
| Auto-Degradation | bei Blockade erscheint der Dialog „Sicheren Modus aktivieren" und der Custom Tab öffnet sich im App-Task |  |
| Custom-Tabs-Modus | Login funktioniert ohne Tricks, Back-Taste kehrt in die App zurück |  |

## Entscheidung

- [ ] **Grün:** WebView-Modus trägt → weiter mit Gate M3 (Cache am echten Traffic)
- [ ] **Rot:** dauerhaft `CUSTOM_TABS` als Default setzen (`AppPrefs.engineMode`) und PLAN Kapitel 5
      auf „Prewarm statt Cache" umschreiben
- [ ] **Teilweise:** (z. B. Login nur mit Passwort, kein Passkey) → Einschränkung dokumentieren und
      in der App als Hinweistext aufnehmen

## Notizen / Logs

```
<Logcat-Auszug, Screenshots-Dateinamen, beobachtete Redirect-Kette>
```
