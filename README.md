# Bollwerk

Forts-artiges 2D-Physik-Festungsspiel als **native Android-App (Kotlin)** im Querformat:
Festungen aus Holz-/Metallbalken mit echter Statik (biegen, brechen, einstürzen), Metall/Energie-Wirtschaft,
Techbaum, manuell gezielte Waffen, Feuer und Reparatur. Sieg: gegnerischen Reaktor zerstören.
V1: Singleplayer gegen KI (Leicht/Normal/Schwer) und Hotseat. Die Simulation ist ab Tag 1 deterministisch
(später Lockstep-Multiplayer), die Kernmodule sind Kotlin Multiplatform (iOS folgt).

Projektregeln für alle Mitwirkenden (auch Agenten): [`CLAUDE.md`](CLAUDE.md).
Design (verbindlich): [`docs/design/`](docs/design/) – vor allem die [Stil-Bibel](docs/design/STILBIBEL.md),
die Mockups und der [JS-Prototyp](docs/design/prototype/physik-spielplatz.html).

## Module

| Modul | Typ | Inhalt |
|---|---|---|
| `:engine` | KMP (jvm) | Simulation (SoA-Pools, `SimStepper`), Commands, `GameLoop`/`GameSession`, `StateHash`, `FrameSnapshot`, Werkzeug-Zustände, `FastTrig`, `SplitMix64` |
| `:content` | KMP (jvm) | Content-Datenklassen, JSON unter `content/src/commonMain/resources/content/`, `ContentLoader`, `ContentDb` |
| `:setup` | KMP (jvm) | Brücke Content → Engine: `SimTablesFactory`, `MapSpecFactory`, `MatchBootstrap` |
| `:ai` | KMP (jvm) | Gegner-KI (`AiAgent`, `Difficulty`) |
| `:render-api` | KMP (jvm) | `Camera`, `GameRenderer`, `OverlayState`, `Palette`, `ParticleSystem` |
| `:render-android` | Android-Lib | `GameSurfaceView`/`RenderThread`, `RenderSession` und `CanvasDrawSink` (Canvas-Renderer), Texturen, Audio (`SfxSynth`, `SpatialMixer`) und `Haptics`, `DebugOverlay` |
| `:app` | Android-App | Activity, Compose-Menüs/HUD, Eingabe (`de.bollwerk.app`) |
| `:tools:simrunner` | JVM-App | Headless-CLI: Szenarien, Hash, Profiling, KI vs. KI, PNG-Render |

Abhängigkeiten: `engine` ← `render-api` ← `render-android` ← `app`; `content` + `engine` ← `setup`, `ai` ← `app`, `simrunner`.

## Bauen und Testen

Voraussetzungen: JDK 17+ (gebaut wird mit JDK 21, Bytecode-Ziel 17), Android SDK 35
(`local.properties` mit `sdk.dir=…`, wird nicht eingecheckt).

```bash
# Schnelle Tests eines Moduls
./gradlew :engine:jvmTest
./gradlew :content:jvmTest :setup:jvmTest :ai:jvmTest :render-api:jvmTest

# Android-Unit-Tests (prüfen u. a. die Paparazzi-HUD-Goldens in :app bei jedem Lauf) und Simrunner-Tests
./gradlew :app:testDebugUnitTest :render-android:testDebugUnitTest :tools:simrunner:test

# Paparazzi-Goldens nach gewollter HUD-Änderung neu aufnehmen (Bilder prüfen und mit einchecken)
./gradlew :app:recordPaparazziDebug

# Headless-Simulation
./gradlew :tools:simrunner:run --args="--ticks 600 --hash"

# Android
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # auf verbundenes Gerät
./gradlew :app:lintDebug
./gradlew :app:assembleRelease        # R8 (Shrinking); noch nicht signiert, Signing-Konfiguration fehlt
```

Versionen stehen zentral in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (Job `jvm`: KMP-Modul- und Simrunner-Tests samt Smoke-Run; danach Job `android`: Debug-APK, Android-Unit-Tests inkl. Paparazzi-Goldens, Lint, Release-Build mit R8, APK als Artefakt).
