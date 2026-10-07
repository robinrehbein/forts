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

## Golden-Replays (Regressions-Absicherung des ausgelieferten Spiels)

Echte Partien mit dem echten Content (`ClasspathContent`) und `MatchBootstrap`, aufgezeichnet über
`MatchRunners.create(db, setup, record = true)`, liegen als Replay-JSON im Repo (Setup, alle angenommenen Commands,
StateHash-Prüfpunkt alle 60 Ticks, Content-Fingerabdruck, `ENGINE_VERSION`):

| Golden | Modul / Ort | Inhalt |
|---|---|---|
| `schlucht_all_commands` | `setup/src/jvmTest/resources/goldens/` | Skriptpartie auf Schlucht: `PlaceBeam` (auch Teilung über `aBeamRef`/`bBeamRef`), `Undo` der Teilung, `DeleteBeam`, `DeleteDevice`, `RepairBeam`, `ToggleDoor`, `PlaceDevice` der ganzen Tech-Kette (Waffenkammer, Upgrade-Zentrum, Fabrik mit echter Bauzeit), `SetAim` + `Fire` für alle sechs Waffen (MG, Scharfschütze, Mörser, Kanone, Brandrakete, Laser), am Ende `Surrender` |
| `schlucht_hotseat_endturn` | `setup/src/jvmTest/resources/goldens/` | Zugmodus (Hotseat) mit `EndTurn`, Zugwechsel, Aufgabe im vierten Zug |
| `ai_hard_schlucht`, `ai_hard_huegel` | `ai/src/jvmTest/resources/goldens/` | KI (HARD) gegen KI (HARD) bis zum zerstörten Reaktor |

Je Golden prüfen die Tests zweierlei: (1) die Aufnahme spielt über `MatchRunners.verify` Tick für Tick bitgleich ab
(fängt Änderungen an Sim, Regeln und Content); (2) erneutes Aufzeichnen mit dem Skript bzw. der KI ergibt exakt dieselbe
Datei (fängt geändertes KI-/Skriptverhalten, das ein reines Command-Replay nicht bemerkt).

**Wenn ein Golden-Test fehlschlägt**, nennt die Meldung den ersten abweichenden Tick (`First diverging tick: …`) und den
Befehl zum Neuerzeugen. Eine Änderung an Content-Dateien oder `GameInfo.ENGINE_VERSION` meldet `…not compatible with this
build… regenerate goldens`. Ungewollt = Determinismus- bzw. Verhaltens-Regression, Code korrigieren. Gewollt = Goldens
neu erzeugen (bei geändertem Sim-Verhalten vorher `GameInfo.ENGINE_VERSION` erhöhen; bei KI-Änderungen erst, wenn diese eingespielt sind):

```bash
# Spiel-Goldens (setup) und KI-Goldens (ai) neu aufnehmen. --tests und --rerun gelten in Gradle nur für den Task direkt davor,
# deshalb je Task wiederholen; --rerun, weil Gradle die Umgebungsvariable nicht als Eingabe kennt (sonst bliebe ein
# UP-TO-DATE-/FROM-CACHE-Task still stehen und schriebe nichts)
BOLLWERK_REGEN_GOLDENS=1 ./gradlew :setup:jvmTest --tests '*Golden*' --rerun :ai:jvmTest --tests '*Golden*' --rerun
git diff --stat setup/src/jvmTest/resources/goldens ai/src/jvmTest/resources/goldens   # Änderung prüfen, mit Begründung committen
```

Statt der Umgebungsvariable wirkt auch `-Dbollwerk.regenGoldens=true` an der Test-JVM (z. B. in einer IDE-Run-Konfiguration;
Gradle reicht `-D` der Kommandozeile nicht an den Test-Prozess durch). Ohne die Variable überschreibt kein Test jemals Dateien.
Neu aufgenommen wird nur, was besteht: Vor dem Schreiben prüft jeder Test, dass die frische Aufnahme sich selbst abspielt und
das Golden hält, was sein Name sagt (alle Befehlsarten und Waffen bzw. ein zerstörter Reaktor). Die KI-Goldens wählen dabei
den ersten Seed aus `101..112`, dessen Partie binnen 9000 Ticks mit einem Sieg endet; der gewählte Seed steht im Replay.
Das Hotseat-Golden nutzt die ausgelieferte Zuglänge (45 s). Die Meldungstests (`GoldenFailureMessageTest`, KI-Tamper-Test)
verfälschen selbst aufgezeichnete Kurzpartien und hängen deshalb nicht am Zustand der eingecheckten Goldens.
Die Goldens werden mit `:setup:jvmTest` bzw. `:ai:jvmTest` (und damit in der CI) bei jedem Lauf geprüft.
