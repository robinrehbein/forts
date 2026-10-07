package de.bollwerk.ai

import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.loop.CommandRecorder
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.setup.MatchBootstrap

data class DoorEvent(val tick: Long, val player: Int, val beamRef: Long, val openAfter: Boolean)

/**
 * Testbank: echte Partie aus dem Content mit KI-Spielern (null = passiver Spieler ohne Eingaben).
 * @param agentFactory baut die KI eines Spielers (Standard: [AiFactory.create] mit der Standard-Bauvorlage).
 */
class AiMatch(
    val seed: Long,
    difficulties: List<Difficulty?>,
    map: String = "schlucht",
    turnMode: TurnMode = TurnMode.REALTIME,
    turnTicks: Int = 0,
    agentFactory: (playerId: Int, difficulty: Difficulty, tables: SimTables, seed: Long) -> StandardAi =
        { p, d, t, s -> AiFactory.create(p, d, t, seed = s) as StandardAi },
) {
    val setup = MatchSetup(
        seed, map,
        difficulties.map { if (it == null) PlayerSetup(Controller.HUMAN) else PlayerSetup(Controller.AI, it.name) },
        turnMode = turnMode, turnTicks = turnTicks,
    )
    val ais: List<StandardAi?>
    val session: GameSession
    val state: GameState get() = session.state

    /** Angenommene/abgelehnte Commands je Spieler. */
    val accepted = IntArray(difficulties.size)
    val rejected = IntArray(difficulties.size)
    val rejectReasons = ArrayList<String>()
    /** Alle angenommenen Commands in Ausführungsreihenfolge (für die Wiedergabe ohne KI). */
    val acceptedCommands = ArrayList<Command>()
    /** Angenommene Tür-Commands: (Tick, Spieler, Balken-Ref, danach offen?). */
    val doorEvents = ArrayList<DoorEvent>()
    private val openBefore = HashSet<Long>()

    /** Ballistische Schüsse je Spieler und Explosionen (Tick, x, y, Material). */
    val ballisticShots = IntArray(difficulties.size)
    val explosions = ArrayList<FxEvent.Explosion>()
    /** Spieler-Zuordnung der Schüsse in Reihenfolge (für "Treffer innerhalb K Schüssen"). */
    val shotLog = ArrayList<Pair<Long, Int>>()

    init {
        val tables = MatchBootstrap.create(db, setup).tables
        ais = difficulties.mapIndexed { p, d -> d?.let { agentFactory(p, it, tables, seed) } }
        session = MatchBootstrap.createSession(db, setup, sources = ais.filterNotNull())
        session.recorder = CommandRecorder { t, cmd, result ->
            if (result is CommandResult.Rejected) {
                rejected[cmd.playerId]++
                if (rejectReasons.size < 20) rejectReasons.add("t=$t $cmd -> ${result.reason}")
            } else {
                accepted[cmd.playerId]++
                acceptedCommands.add(cmd)
                // Zustand vor dem Tick entscheidet (im selben Tick kann das Waffen-System die Tür wieder öffnen)
                if (cmd is Command.ToggleDoor) doorEvents.add(DoorEvent(t, cmd.playerId, cmd.beamRef, cmd.beamRef !in openBefore))
            }
        }
    }

    /** Läuft bis [maxTicks] oder Spielende. @return ausgeführte Ticks. */
    fun run(maxTicks: Long, stopAtResult: Boolean = true, onTick: (GameState) -> Unit = {}): Long {
        var n = 0L
        while (n < maxTicks) {
            if (stopAtResult && state.result != GameResult.Ongoing) break
            openBefore.clear()
            val b = state.beams
            for (j in 0 until b.size) if (b.isAlive(j) && (b.flags(j) and BeamFlags.DOOR_OPEN) != 0) openBefore.add(b.ref(j))
            check(session.tick()) { "session not ready" }
            for (e in session.ctx.fx) when (e) {
                is FxEvent.Fired -> {
                    val w = state.tables.weapons[e.weaponId]
                    if (w.mode == WeaponMode.BALLISTIC) {
                        val owner = ownerOfDeviceUid(e.deviceUid)
                        if (owner >= 0) { ballisticShots[owner]++; shotLog.add(state.tick to owner) }
                    }
                }
                is FxEvent.Explosion -> explosions.add(e)
                else -> {}
            }
            session.fxBuffer.clear()
            onTick(state)
            n++
        }
        return n
    }

    private fun ownerOfDeviceUid(uid: Int): Int {
        val d = state.devices
        for (i in 0 until d.size) if (d.uid(i) == uid && d.isAlive(i)) return d.owner(i)
        return -1
    }

    fun ownBeams(p: Int): Int {
        var n = 0
        val b = state.beams
        for (j in 0 until b.size) if (b.isAlive(j) && b.owner(j) == p) n++
        return n
    }

    fun ownDevicesOfRole(p: Int, role: de.bollwerk.engine.sim.DeviceRole): Int {
        var n = 0
        val d = state.devices
        for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == p && state.tables.devices[d.type(i)].role == role) n++
        return n
    }

    companion object {
        val db: ContentDb by lazy { ClasspathContent.load() }
    }
}
