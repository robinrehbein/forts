# Bollwerk – Projektregeln für alle Agenten

Bollwerk ist ein Forts-artiges 2D-Physik-Festungsspiel als **native Android-App (Kotlin)** im Querformat.
Zwei Festungen aus Holz-/Metallbalken, physikalische Statik (biegen, brechen, einstürzen), Metall/Energie-Wirtschaft, Techbaum, manuell gezielte Waffen, Feuer, Reparatur. Sieg: gegnerischen Reaktor zerstören.
V1: Singleplayer gegen KI (Leicht/Normal/Schwer) und Hotseat. Online-Multiplayer (Lockstep) kommt später, deshalb ist die Simulation **ab Tag 1 deterministisch**. iOS ist in ~12 Monaten geplant, deshalb sind die Kernmodule **Kotlin Multiplatform** (`commonMain`, vorerst nur JVM-Target).

## Verbindliche Design-Quellen
- `docs/design/STILBIBEL.md` – Maßstab, **alle Zahlen** (Kosten, TP, Wirtschaft, Waffen), Palette, Typo, Bauteile, Schadens-/Feuer-/Explosionsstufen, HUD, Zielgeste. Bei Widersprüchen gewinnt die Stil-Bibel.
- `docs/design/mockups/*.webp` – freigegebene Screens (1 Hauptmenü … 9 Effekte) und Spielplatz-Screenshots.
- `docs/design/prototype/physik-spielplatz.html` – lauffähiger JS-Prototyp. **Referenz für Physik-Gefühl, Konstanten, Rendering-Details und Audio.** Bei Portierung nach Kotlin Konstanten und Verhalten übernehmen, nicht neu erfinden. Bekannter Fund: Die axiale Dämpfung hatte dort früher ein invertiertes Vorzeichen; Dämpfung muss Energie immer reduzieren (Test!).

## Module (Basis-Package `de.bollwerk`)
| Modul | Typ | Inhalt | darf abhängen von |
|---|---|---|---|
| `:engine` | KMP (jvm) | Simulation, Regeln, Commands, Loop, Snapshot, Tools (reine UI-Logik) | nur kotlin-stdlib, kotlinx-serialization |
| `:content` | KMP (jvm) | Content-Datenklassen, JSON unter `content/src/commonMain/resources/content/`, Loader, Validator, Fingerprint | kotlinx-serialization |
| `:setup` | KMP (jvm) | Brücke Content → Engine: `SimTablesFactory`, `MapSpecFactory`, `MatchBootstrap` (Besitz: WP1/WP5) | engine, content |
| `:ai` | KMP (jvm) | Gegner-KI | engine, content |
| `:render-api` | KMP (jvm) | Camera, DrawList, GameRenderer-Interface, ParticleSystem, Palette, OverlayState | engine |
| `:render-android` | Android lib | CanvasRenderer, GameSurfaceView, RenderThread, TextureFactory, Audio | render-api, engine |
| `:app` | Android app | Compose-Menüs/HUD, InputController, Activity, AppGraph | alle |
| `:tools:simrunner` | JVM app | Headless-CLI: Szenarien, Hash, Profiling, KI vs KI, PNG-Render | engine, content, setup, ai, render-api |

`:engine`, `:content`, `:setup`, `:ai`, `:render-api` haben **keine Android- und keine JVM-only-APIs in commonMain** (kein java.*, kein System.currentTimeMillis, kein Thread). Ressourcen-Laden über ein `expect/actual` oder einen übergebenen Text-Provider.

## Determinismus-Regeln (nicht verhandelbar, gelten für engine und ai)
1. Fester Tick `1/60 s`, keine Wall-Clock im Sim-Pfad. Alle Eingriffe sind serialisierbare `Command(tick, playerId, …)`.
2. Eigener RNG (`SplitMix64`) im `GameState`; KI nutzt einen abgeleiteten eigenen Stream. Kein `kotlin.random.Random`, kein `Math.random`.
3. Nur `Float` im Sim (kein Mischen mit Double). Trigonometrie nur über `FastTrig` (Tabellen); erlaubt sind +, −, ×, ÷, `sqrt`. **Kein `fma`/`mulAdd`** und keine Bibliotheksfunktionen, die intern FMA nutzen könnten (iOS/Kotlin-Native). `GoldenHashTest` wacht darüber.
4. Keine Iteration über `HashMap`/`HashSet` im Sim-Pfad. Structure-of-Arrays-Pools mit Int-IDs, Schleifen in fester Index-Reihenfolge.
5. `StateHash` (FNV-1a über Positionen, TP, Ressourcen, Tick) muss für gleichen Seed + gleiche Commands bitgleich sein. Dafür gibt es Tests.
6. Rendering, Partikel, Audio und Kamera-Shake sind **nicht** Teil des Sim-States.
7. Objekte über Ticks hinweg nur per **Ref** (`PoolView.ref`/`resolve`: Generation + Slot) oder **uid** festhalten, nie per nackter Slot-ID. Pools geben Slots verzögert frei (`release` → `endTick`); Systeme überspringen `isNew(i)`.
8. Zufall je System aus seinem Strom (`GameState.rngWind/rngFire/rngDebris/rngWeapon`, KI: `RngStreams.ai(id)`).
9. Pool-Felder sind als PERSISTENT (gehasht), RENDER oder DERIVED markiert; neue Felder ebenso markieren und PERSISTENT-Felder in `StateHash` aufnehmen.

## Threads und Datenfluss
- **Sim-Thread** besitzt `GameState`, `GameSession`, Werkzeuge (lesen `GameView`) und KI. Systemreihenfolge fest über `SystemSlot`.
- **UI-Thread** schickt Eingaben nur über `LocalInputSource.push` (thread-sicher, `CommandInbox`).
- **Render-Thread** liest nur `FrameSnapshot`s aus `SnapshotExchange` (Dreifachpuffer); Fx einmal je neuer `seq` verarbeiten.
- Partie anlegen nur über `MatchBootstrap` (`:setup`) bzw. `MatchFactory`; Replays tragen `MatchSetup`, `SimConfig`, Content-Fingerprint und `ENGINE_VERSION`.

## Physik-Kern (Kurzfassung)
Verlet + XPBD-Abstandsconstraints: 4 Substeps, 6 Gauss-Seidel-Iterationen, Compliance pro Material, Seil nur Zug. Dehnung → Schaden → Bruch, danach Union-Find-Konnektivität: Komponenten ohne Anker werden Trümmer. Swept-Capsule-Kollision für Projektile (kein Tunneling), Explosionen = radialer Verlet-Impuls + Schaden mit Falloff. Feuer nur auf Holz, Ausbreitung über gemeinsame Knoten, windabhängig.

## Toolchain und Befehle
- JDK 21 im Container, Kotlin-JVM-Target 17. Gradle Wrapper (8.14.x), AGP 8.7.x, Kotlin 2.1.x, Compose BOM, kotlinx-serialization-json. Versionen zentral in `gradle/libs.versions.toml`.
- Android SDK: `/opt/android-sdk` (`local.properties` mit `sdk.dir`, nicht eingecheckt). minSdk 26, target/compileSdk 35, nur Querformat.
- Schnelle Checks, **nur das eigene Modul** bauen, um parallele Agenten nicht zu blockieren:
  - `./gradlew :engine:jvmTest` (analog `:content:jvmTest`, `:setup:jvmTest`, `:ai:jvmTest`, `:render-api:jvmTest`)
  - `./gradlew :render-android:assembleDebug`, `./gradlew :app:assembleDebug`
  - `./gradlew :tools:simrunner:run --args="..."`
- Tests: kotlin-test (+ JUnit5-Runner auf JVM). Jede neue Logik bekommt Tests. Kein Test wird übersprungen oder deaktiviert.

## Zusammenarbeit paralleler Agenten
- Jeder Agent schreibt **nur in die Dateien/Verzeichnisse seines Arbeitspakets**. Öffentliche Verträge (Interfaces/Datenklassen aus Welle 0) nur erweitern, nicht brechen; nötige Vertragsänderungen klein halten und im Ergebnisbericht nennen.
- Wenn ein fremdes Modul gerade nicht kompiliert, nicht dort reparieren: kurz warten und erneut bauen, sonst im Bericht melden.
- Keine Commits, keine Pushes – das macht der Orchestrator nach jeder Welle.
- UI-Texte: Deutsch und Englisch (`strings.xml` / `values-en`), Deutsch ist Standard.
- Keine Original-Assets oder Namen von Forts/EarthWork Games. Arbeitstitel "Bollwerk" steht in einer Konstante.
