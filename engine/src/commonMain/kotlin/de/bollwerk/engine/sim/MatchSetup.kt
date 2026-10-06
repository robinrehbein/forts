package de.bollwerk.engine.sim

import de.bollwerk.engine.math.FloatMath
import kotlinx.serialization.Serializable

/** Wer einen Spieler steuert. */
@Serializable
enum class Controller { HUMAN, AI }

/** Ein Spieler im Gefecht-Setup; [difficulty] = Name von `de.bollwerk.ai.Difficulty` bei KI. */
@Serializable
data class PlayerSetup(val controller: Controller, val difficulty: String? = null)

/**
 * Alles, was eine Partie vor Tick 0 festlegt (Gefecht-Setup, Mockup 2). Steht im `Replay`, damit ein
 * Replay ohne weitere Angaben bitgleich nachgespielt werden kann.
 */
@Serializable
data class MatchSetup(
    val seed: Long,
    val mapId: String,
    val players: List<PlayerSetup>,
    val turnMode: TurnMode = TurnMode.REALTIME,
    /** Zuglänge im Zugmodus in Ticks (0 = unbegrenzt). */
    val turnTicks: Int = 0,
    /** Startressourcen (Stil-Bibel, Normal: 400 ⚙ / 200 ⚡); die App setzt sie je Schwierigkeit. */
    val startMetal: Float = 400f,
    val startEnergy: Float = 200f,
    /** Überschreibt die Startfestung der Karte für alle Spieler (Bauvorlagen-Schlüssel) oder null. */
    val startFortBlueprint: String? = null,
)

/**
 * Baut den Anfangszustand einer Partie: Fundamente, Startfestungen (inkl. Reaktor), Startressourcen,
 * Zugmodus und Startwind. Einziger Weg, wie Session, Simrunner, Tests und KI eine Partie anlegen.
 */
object MatchFactory {
    fun create(setup: MatchSetup, tables: SimTables, map: MapSpec, config: SimConfig = SimConfig.DEFAULT): GameState {
        require(setup.players.size == map.playerCount) {
            "setup has ${setup.players.size} players, map '${map.id}' needs ${map.playerCount}"
        }
        val state = GameState(setup.seed, tables, map, config, map.playerCount)
        for (p in state.players) {
            p.metal = FloatMath.min(setup.startMetal, p.metalCap)
            p.energy = FloatMath.min(setup.startEnergy, p.energyCap)
            p.facing = facingOf(map, p.id)
        }

        val turn = state.turnState
        turn.mode = setup.turnMode
        if (setup.turnMode == TurnMode.TURNS) {
            turn.activePlayer = 0
            turn.turnNumber = 1
            turn.ticksLeft = setup.turnTicks
            turn.phase = TurnPhase.PLAY
        }

        state.wind = if (map.windMax > map.windMin) state.rngWind.nextFloat(map.windMin, map.windMax) else map.windMin

        val nodes = state.nodes
        for (f in map.foundations) nodes.alloc(f.x, f.y, f.owner, anchored = true)

        for (fort in map.startForts) {
            val key = setup.startFortBlueprint ?: fort.blueprintKey
            val bp = tables.blueprint(key) ?: throw IllegalArgumentException("unknown blueprint '$key'")
            placeBlueprint(state, bp, fort.owner, fort.originX, map.baseY[fort.owner], fort.mirror)
        }
        state.rebuildDerived()
        return state
    }

    /**
     * Setzt eine Bauvorlage ohne Kosten und ohne Bauzeit. Verankerte Vorlagen-Knoten verschmelzen mit einem
     * eigenen Knoten im Abstand `SimConfig.nodeMergeRadius` (z. B. einem Fundament).
     * @return Geräte-Slots in Vorlagen-Reihenfolge.
     */
    fun placeBlueprint(state: GameState, bp: BlueprintProps, owner: Int, originX: Float, originY: Float, mirror: Boolean): IntArray {
        val tables = state.tables
        val nodes = state.nodes
        val r2 = state.config.nodeMergeRadius * state.config.nodeMergeRadius
        val nodeIds = IntArray(bp.nodes.size)
        for ((i, n) in bp.nodes.withIndex()) {
            val wx = if (mirror) originX - n.x else originX + n.x
            val wy = originY + n.y
            var found = -1
            for (j in 0 until nodes.size) {
                if (!nodes.isAlive(j) || nodes.ownerOf[j] != owner) continue
                val dx = nodes.x[j] - wx; val dy = nodes.y[j] - wy
                if (dx * dx + dy * dy <= r2) { found = j; break }
            }
            nodeIds[i] = if (found >= 0) found else nodes.alloc(wx, wy, owner, anchored = n.anchored)
        }
        val beamIds = IntArray(bp.beams.size)
        for ((i, b) in bp.beams.withIndex()) {
            val mat = tables.materials[b.material]
            val na = nodeIds[b.a]; val nb = nodeIds[b.b]
            val dx = nodes.x[nb] - nodes.x[na]; val dy = nodes.y[nb] - nodes.y[na]
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            beamIds[i] = state.beams.alloc(na, nb, b.material, len * mat.restLengthFactor, mat.hp, owner)
        }
        val devIds = IntArray(bp.devices.size)
        for ((i, d) in bp.devices.withIndex()) {
            val props = tables.devices[d.type]
            var aim = 0f
            var power = state.config.maxPower
            if (props.weapon >= 0) {
                val w = tables.weapons[props.weapon]
                aim = if (mirror) FloatMath.PI - w.defaultAimRad else w.defaultAimRad
                power = w.defaultPower
            }
            val id = state.devices.alloc(
                type = d.type, beam = beamIds[d.beam], t = d.t, maxHp = props.hp, owner = owner,
                buildTicks = 0, sideNegative = d.sideNegative != mirror, aimAngle = aim, power = power,
            )
            if (props.role == DeviceRole.REACTOR) state.players[owner].reactorDeviceId = id
            devIds[i] = id
        }
        state.topologyDirty = true
        return devIds
    }

    /** +1 = Spieler schaut nach rechts (Basis links), −1 = nach links. */
    private fun facingOf(map: MapSpec, owner: Int): Int {
        for (z in map.buildZones) if (z.owner == owner) return if ((z.x0 + z.x1) * 0.5f > map.width * 0.5f) -1 else 1
        return if (owner % 2 == 0) 1 else -1
    }
}
