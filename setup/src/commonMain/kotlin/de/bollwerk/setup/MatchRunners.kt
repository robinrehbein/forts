package de.bollwerk.setup

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.loop.ReplayMeta
import de.bollwerk.engine.loop.ReplayVerification
import de.bollwerk.engine.loop.ReplayVerifier
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnConfig
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.view.SnapshotExchange

/**
 * Einstieg für App (WP9) und Simrunner: aus `ContentDb` + [MatchSetup] ein spielbereiter [MatchRunner] (Session mit allen
 * Systemen, eingeschwungene Festungen, Snapshot-Austausch, lokale Eingabe, Replay-Aufnahme mit Content-Fingerabdruck).
 * Die Aufrufer sehen weder `GameSession` noch `SimStepper`.
 */
object MatchRunners {
    /**
     * Neue Partie.
     * @param localPlayer Spieler, dessen Sicht das HUD zeigt (im Hotseat folgt das HUD dem aktiven Spieler).
     * @param sources KI-Agenten, Skripte, Netzwerk (hinter der lokalen Eingabe).
     * @param record Replay aufzeichnen (`MatchRunner.toReplay`).
     * @param inputDelayTicks Verzögerung lokaler Eingaben (offline 0, Lockstep ~6).
     * @param hotseat HUD folgt dem aktiven Spieler; Standard: Zugmodus und nur Menschen am Gerät.
     * @param maxTicksPerAdvance Obergrenze der Ticks je `advance` bei Tempo 1 (skaliert mit dem Tempo); Überschuss wird verworfen.
     */
    fun create(
        db: ContentDb,
        setup: MatchSetup,
        config: SimConfig = SimConfig.DEFAULT,
        localPlayer: Int = 0,
        sources: List<CommandSource> = emptyList(),
        record: Boolean = true,
        inputDelayTicks: Int = 0,
        hotseat: Boolean = isHotseat(setup),
        exchange: SnapshotExchange = SnapshotExchange(),
        maxTicksPerAdvance: Int = 5,
    ): MatchRunner = MatchRunner(
        session = MatchBootstrap.createSession(db, setup, config),
        maxTicksPerAdvance = maxTicksPerAdvance,
        localPlayer = localPlayer,
        hotseat = hotseat,
        inputDelayTicks = inputDelayTicks,
        exchange = exchange,
        replayMeta = if (record) ReplayMeta(setup, config, db.version, db.fingerprint) else null,
        sources = sources,
    )

    /**
     * Wiedergabe eines Replays (ohne Aufnahme, aufgezeichnete Commands als Quelle); wirft bei inkompatiblem Replay.
     * Lokale Eingaben werden verworfen (`acceptInput = false`), und die Sim hält bei `replay.ticks` an
     * (`MatchRunner.reachedEnd`, `MatchRunner.endTick`).
     */
    fun play(db: ContentDb, replay: Replay, localPlayer: Int = 0, exchange: SnapshotExchange = SnapshotExchange()): MatchRunner =
        MatchRunner(
            session = MatchBootstrap.sessionForReplay(db, replay, listOf(ScriptedCommandSource(replay.commands))),
            localPlayer = localPlayer,
            hotseat = isHotseat(replay.setup),
            exchange = exchange,
            acceptInput = false,
            endTick = replay.ticks,
        )

    /** Prüft ein Replay Tick für Tick gegen seine Hash-Prüfpunkte (inkl. Versions- und Content-Prüfung). */
    fun verify(db: ContentDb, replay: Replay): ReplayVerification =
        ReplayVerifier.verify(replay, db.fingerprint) { MatchBootstrap.sessionForReplay(db, replay) }

    /** Hotseat = Zugmodus mit ausschließlich menschlichen Spielern. */
    fun isHotseat(setup: MatchSetup): Boolean =
        setup.turnMode == TurnMode.TURNS && setup.players.all { it.controller == Controller.HUMAN }

    /** Hotseat-Setup mit der Standard-Zuglänge (45 s). */
    fun hotseatSetup(
        seed: Long,
        mapId: String,
        turnTicks: Int = TurnConfig.DEFAULT_PLAY_TICKS,
        startMetal: Float = 400f,
        startEnergy: Float = 200f,
    ): MatchSetup = MatchSetup(
        seed = seed, mapId = mapId, players = listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)),
        turnMode = TurnMode.TURNS, turnTicks = turnTicks, startMetal = startMetal, startEnergy = startEnergy,
    )
}
