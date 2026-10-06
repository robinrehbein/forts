package de.bollwerk.setup

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.SimConfig

/**
 * Einstieg für App, Simrunner und Tests: baut aus `ContentDb` + [MatchSetup] den Anfangszustand.
 * Tabellen und Karte entstehen immer über diese Brücke, damit alle Teilnehmer identische Indizes haben.
 */
object MatchBootstrap {
    fun create(db: ContentDb, setup: MatchSetup, config: SimConfig = SimConfig.DEFAULT): GameState =
        MatchFactory.create(setup, SimTablesFactory.build(db, config), MapSpecFactory.build(db, setup.mapId), config)

    /**
     * Anfangszustand für ein Replay; wirft, wenn Content-Fingerabdruck oder Engine-Version nicht passen
     * (Commands enthalten Content-Indizes).
     */
    fun forReplay(db: ContentDb, replay: Replay): GameState {
        replay.incompatibility(db.fingerprint, GameInfo.ENGINE_VERSION)?.let { throw IllegalStateException("replay incompatible: $it") }
        return create(db, replay.setup, replay.config)
    }
}
