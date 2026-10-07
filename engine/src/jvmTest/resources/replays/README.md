# Golden-Replays

Drei skriptete Partien (`build_fire_duel`, `fire_spread`, `hotseat_turns`) mit allen angenommenen Commands und einem
StateHash alle 60 Ticks. `GoldenReplayTest` spielt sie gegen frisch angelegte Sessions ab; die erste Abweichung wird mit
Tick (Prüfpunkt oder abgelehntes Command) gemeldet.

## Fehlschlag
- Unbeabsichtigt: Determinismus-Fehler suchen (Systemreihenfolge, Float-Mischung, HashMap-Iteration, FMA).
- Absichtlich (Sim-Verhalten geändert): `GameInfo.ENGINE_VERSION` erhöhen, dann neu erzeugen:

```
GOLDEN_REGEN=1 ./gradlew :engine:jvmTest --tests '*GoldenReplayTest*' --rerun
git diff engine/src/jvmTest/resources/replays   # prüfen, mit Begründung committen
```

Die Skripte stehen in `engine/src/jvmTest/kotlin/de/bollwerk/engine/golden/GoldenScenarios.kt`.
