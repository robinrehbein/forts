package de.bollwerk.setup

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.systems.StandardSystems

/**
 * Einstieg für App, Simrunner und Tests: baut aus `ContentDb` + [MatchSetup] den Anfangszustand.
 * Tabellen und Karte entstehen immer über diese Brücke, damit alle Teilnehmer identische Indizes haben.
 */
object MatchBootstrap {
    fun create(db: ContentDb, setup: MatchSetup, config: SimConfig = SimConfig.DEFAULT): GameState =
        MatchFactory.create(setup, SimTablesFactory.build(db, config), MapSpecFactory.build(db, setup.mapId), config)

    /**
     * Spielbereite Sitzung: Anfangszustand, **vollständige Systemliste** ([StandardSystems]: Physik, Regeln, Kampf) und
     * eingeschwungene Festungen (`PhysicsSystems.settle`, wie `buildScene` im Prototyp; ändert den Tick nicht).
     *
     * Die Session behält die billige Vorprüfung `BasicValidator`: Die Regeln prüft das Command-System **sequenziell**
     * im Tick (`RulesValidator`); eine Vorab-Prüfung gegen den Zustand vor dem Tick würde Commands ablehnen, die erst
     * durch ein früheres Command desselben Ticks gültig werden.
     *
     * Das Einschwingen ist **immer** an (kein Schalter): Das Replay enthält kein Merkmal dafür, ein Replay einer anderen
     * Variante würde ab Tick 0 stumm auseinanderlaufen. Wer den unveränderten Anfangszustand braucht, nimmt [create].
     *
     * **Zugmodus:** `MatchSetup.turnTicks == 0` heißt *unbegrenzte* Züge. Für Hotseat setzt der Aufrufer die Zuglänge
     * (App: `TurnConfig.DEFAULT_PLAY_TICKS` = 45 s); hier wird sie nicht ergänzt, damit Setup und Replay dasselbe sagen.
     */
    fun createSession(
        db: ContentDb,
        setup: MatchSetup,
        config: SimConfig = SimConfig.DEFAULT,
        sources: List<CommandSource> = emptyList(),
    ): GameSession = session(create(db, setup, config), sources)

    /** Wie [createSession], für ein Replay (siehe [forReplay]). */
    fun sessionForReplay(db: ContentDb, replay: Replay, sources: List<CommandSource> = emptyList()): GameSession =
        session(forReplay(db, replay), sources)

    private fun session(state: GameState, sources: List<CommandSource>): GameSession {
        PhysicsSystems.settle(state)
        return GameSession(state, StandardSystems.stepper(), sources = sources)
    }

    /**
     * Anfangszustand für ein Replay; wirft, wenn Content-Fingerabdruck oder Engine-Version nicht passen
     * (Commands enthalten Content-Indizes).
     */
    fun forReplay(db: ContentDb, replay: Replay): GameState {
        replay.incompatibility(db.fingerprint, GameInfo.ENGINE_VERSION)?.let { throw IllegalStateException("replay incompatible: $it") }
        return create(db, replay.setup, replay.config)
    }
}
