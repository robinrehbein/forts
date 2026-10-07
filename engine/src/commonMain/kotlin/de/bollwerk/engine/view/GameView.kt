package de.bollwerk.engine.view

import de.bollwerk.engine.sim.BeamView
import de.bollwerk.engine.sim.DeviceView
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.NodeView
import de.bollwerk.engine.sim.PlayerView
import de.bollwerk.engine.sim.ProjectileView
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.TurnView

/**
 * Nur-Lese-Sicht auf den [de.bollwerk.engine.sim.GameState] für KI, UI-Werkzeuge und Validator.
 * Änderungen am Spiel erfolgen ausschließlich über Commands. Nur auf dem Sim-Thread benutzen
 * (der Render-/UI-Thread liest `FrameSnapshot`s). Ein Cast auf `GameState` ist verboten.
 */
interface GameView {
    val tick: Long
    val wind: Float
    val result: GameResult
    val turn: TurnView
    val terrain: Terrain
    /** Karte (Bauzonen, Erz, Fundamente, Grenzen). */
    val map: MapSpec
    /** Content-Tabellen (Kosten, Reichweiten, Mündungsgeschwindigkeiten …). */
    val tables: SimTables
    val simConfig: SimConfig
    val nodeView: NodeView
    val beamView: BeamView
    val deviceView: DeviceView
    val projectileView: ProjectileView
    val playerCount: Int
    fun player(id: Int): PlayerView
}
