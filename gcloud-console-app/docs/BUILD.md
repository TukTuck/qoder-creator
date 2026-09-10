# Bauen, Testen, Installieren

## 0. Ehrlicher Hinweis zum aktuellen Stand

Der Code in `app/` wurde **in dieser Arbeitsumgebung nicht kompiliert** — die Sandbox hat kein JDK
und kein Android-SDK. Syntax, APIs und Abhängigkeiten sind sorgfältig gegen den Stand 09/2026
ausgewählt (AGP 9.4 / Gradle 9.6 / JDK 17 / compileSdk 37 / Compose BOM 2026.08.00 /
`androidx.webkit:1.16.0`), aber der **erste Build läuft bei dir oder in CI**. Rechne mit einer
kurzen Runde „Versions-/API-Feinschliff" (siehe Abschnitt 5, Fallbacks).

## 1. Voraussetzungen

| Tool | Version |
|---|---|
| JDK | 17 (Temurin empfohlen) |
| Gradle | 9.6 (via Wrapper, siehe unten) |
| Android Gradle Plugin | 9.4.0 |
| Android SDK Platform | 37 (Android 17) |
| SDK Build-Tools | 36.0.0 |
| Android Studio | aktuelle Stable (alternativ `cmdline-tools` + `sdkmanager`) |

SDK-Komponenten installiert Gradle beim ersten Build selbst, sofern die Lizenzen akzeptiert sind:

```bash
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-37" "build-tools;36.0.0" "platform-tools"
```

## 2. Gradle-Wrapper erzeugen (einmalig)

`gradle/wrapper/gradle-wrapper.jar` ist ein Binary und liegt bewusst nicht im Repo.
Entweder mit Android Studio öffnen (erzeugt den Wrapper automatisch) oder:

```bash
cd gcloud-console-app
gradle wrapper --gradle-version 9.6      # benötigt ein lokal installiertes Gradle
./gradlew --version
```

## 3. Bauen & installieren

```bash
cd gcloud-console-app

./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:assembleRelease           # mit Debug-Signatur, direkt installierbar (Sideload)
./gradlew :app:testDebugUnitTest         # Unit-Tests: Regeln, Cache, UA, Navigation, Header
./gradlew :app:lintDebug
```

Release mit eigenem Keystore (nicht ins Repo legen!):

```properties
# gcloud-console-app/keystore.properties   (steht in .gitignore)
storeFile=/pfad/zu/console-pocket.jks
storePassword=***
keyAlias=console-pocket
keyPassword=***
```

Dazu in `app/build.gradle.kts` ein `signingConfigs { create("release") { ... } }`-Block ergänzt und
`signingConfig = signingConfigs.getByName("release")` gesetzt.

## 4. CI

`.github/workflows/android-console-pocket.yml` (im Repo-Root, mit Pfad-Filter auf
`gcloud-console-app/**`) baut bei jedem Push: Unit-Tests → Lint → Debug-APK → Release-APK →
Artefakt-Upload. Der Workflow erzeugt den Wrapper selbst, das Repo bleibt binary-frei.

## 5. Troubleshooting & Fallbacks

### 5.1 Compose-Compiler / „built-in Kotlin" (AGP 9)

AGP 9.x aktiviert **built-in Kotlin** standardmäßig, deshalb wird das Plugin
`org.jetbrains.kotlin.android` hier nicht angewendet. Falls dein Setup damit Probleme meldet:

1. In `gradle.properties`: `android.builtInKotlin=false`
2. In `app/build.gradle.kts` im `plugins {}`-Block: `alias(libs.plugins.kotlin.android)` aktivieren
   (Alias ist im Version Catalog vorbereitet) und `kotlin { jvmToolchain(17) }` ergänzen.

### 5.2 compileSdk 37 nicht verfügbar

Fallback auf API 36 (von Google Play weiterhin akzeptiert, da targetSdk ≥ 36 verlangt wird):

```kotlin
compileSdk = 36
targetSdk = 36
```
plus AGP auf 9.0.x und `tools:targetApi="36"` im Manifest.

### 5.3 Abhängigkeits-Versionen

Die Versionen in `gradle/libs.versions.toml` sind der Stand 09/2026. Falls eine Version nicht
auflösbar ist:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | head -50
./gradlew --refresh-dependencies :app:assembleDebug
```
Android Studio → „Upgrade Assistant" hebt AGP/Kotlin/Compose-Stack gemeinsam an.

### 5.4 Configuration Cache

Bei seltsamen Fehlern im Build (Plugin-Kompatibilität) kurz abschalten:
`org.gradle.configuration-cache=false` in `gradle.properties`.

### 5.5 App startet, aber Console bleibt leer

1. Debug-Build installieren (Remote-Inspection ist nur dort aktiv).
2. Chrome am PC öffnen → `chrome://inspect/#devices` → WebView der App inspecten → Tab „Network":
   Zeigt unsere `X-Console-Pocket-Cache: hit|miss`-Header.
3. In der App: Menü → „Cache & Messwerte" → Interceptor **aus** schalten (Referenzmessung).
   Funktioniert die Seite dann, liegt es an der Pipeline → Regel-Modus auf `OFF` stellen und
   die Regeln in `assets/web-rules.json` schrittweise wieder aktivieren.

### 5.6 Login schlägt fehl (`disallowed_useragent`)

Erwartbarer Fall, siehe `PLAN.md` Kapitel 6: Die App zeigt dann automatisch den Dialog
„Sicheren Modus aktivieren" und wechselt auf Chrome Custom Tabs (läuft im Task der App, Back-Taste
kehrt zurück). Dauerhaft umstellbar unter Einstellungen → Engine.

## 6. Messen (Messplan aus PLAN Kapitel 9)

| Was | Wie |
|---|---|
| Hit-Rate, Bytes, Request-Zahl | In-App: Menü → „Cache & Messwerte" |
| Seitenladezeit | dito (`lastPageLoadMs`), zusätzlich `stats.lastPageLoadMs` |
| Netzwerk-Waterfall der Console | `chrome://inspect` (Debug-Build) |
| Kaltstart | Perfetto/`androidx.tracing`, System-Trace „Activity Start"/„Choreographer#doFrame" |
| Referenz ohne eigenen Cache | Interceptor aus + Regel-Modus `OFF` → gleiche Messung |

Messergebnisse bitte als `docs/perf/messungen-<JJJJ-MM-TT>.md` ablegen (Ordner ist in `.gitignore`
für Traces, Markdown bleibt versioniert).
