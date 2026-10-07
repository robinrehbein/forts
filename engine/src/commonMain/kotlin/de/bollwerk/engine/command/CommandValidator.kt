package de.bollwerk.engine.command

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.view.GameView

/**
 * Ablehnungsgründe. [displayKey] ist der Schlüssel der App-Ressource (`strings.xml`, DE/EN);
 * die Engine selbst enthält keine UI-Texte.
 */
enum class RejectReason(val displayKey: String) {
    NOT_ENOUGH_METAL("reject_not_enough_metal"),
    NOT_ENOUGH_ENERGY("reject_not_enough_energy"),
    TOO_LONG("reject_too_long"),
    TOO_SHORT("reject_too_short"),
    NOT_CONNECTED("reject_not_connected"),
    OUT_OF_BUILD_ZONE("reject_out_of_build_zone"),
    BLOCKED_BY_TERRAIN("reject_blocked_by_terrain"),
    DUPLICATE_BEAM("reject_duplicate_beam"),
    /** Platz belegt (Gerät zu nah an anderem Gerät, `DeviceProps.minSpacing`). */
    OCCUPIED("reject_occupied"),
    LOCKED_TECH("reject_locked_tech"),
    /** Ungültiges Ziel, auch: nicht-endliche Float-Werte. */
    INVALID_TARGET("reject_invalid_target"),
    /** Ref zeigt auf ein Objekt, das nicht mehr existiert (Slot inzwischen neu belegt oder frei). */
    STALE_TARGET("reject_stale_target"),
    NOT_OWNER("reject_not_owner"),
    NOT_YOUR_TURN("reject_not_your_turn"),
    RELOADING("reject_reloading"),
    STILL_BUILDING("reject_still_building"),
    /** Mine nur über eigenem Erz. */
    NEEDS_ORE("reject_needs_ore"),
    /** Gerät nur oben montierbar (Turbine). */
    TOP_MOUNT_ONLY("reject_top_mount_only"),
    /** Höchstens eines pro Spieler (Reaktor). */
    UNIQUE_LIMIT("reject_unique_limit"),
    /** Reaktor bzw. Balken mit Reaktor kann nicht abgerissen werden. */
    REACTOR_PROTECTED("reject_reactor_protected"),
    NOTHING_TO_UNDO("reject_nothing_to_undo"),
    /**
     * Letzter Bau kann (noch) nicht zurückgenommen werden: beschädigt oder brennend (der Eintrag bleibt). Abgelaufene oder
     * zerstörte Einträge fallen dagegen aus dem Journal und ergeben [NOTHING_TO_UNDO] bzw. den nächstälteren Eintrag.
     */
    UNDO_BLOCKED("reject_undo_blocked"),
    UNKNOWN_CONTENT("reject_unknown_content"),
    GAME_OVER("reject_game_over"),
}

/** Ergebnis der Prüfung/Anwendung eines Commands. */
sealed class CommandResult {
    data object Accepted : CommandResult()
    data class Rejected(val reason: RejectReason) : CommandResult()
}

/**
 * Prüft ein Command gegen den aktuellen Zustand, **ohne** ihn zu verändern. Arbeitet nur auf [GameView]
 * (inkl. `view.tables` und `view.map`), damit Ghost-Vorschau (Werkzeuge), KI und das Command-System
 * im Tick exakt dieselbe Wahrheit sehen. Das Command-System ruft ihn pro Command sequenziell gegen den
 * sich ändernden Zustand auf.
 */
fun interface CommandValidator {
    /** @return `null`, wenn gültig, sonst der Grund. */
    fun validate(view: GameView, cmd: Command): RejectReason?
}

/** WP0-Platzhalter: nur die Basis-Prüfungen aus [CommandChecks]. Die echten Regeln kommen mit WP3. */
object BasicValidator : CommandValidator {
    override fun validate(view: GameView, cmd: Command): RejectReason? = CommandChecks.precheck(view, cmd)
}

/**
 * Billige, zustandsarme Prüfungen, die [de.bollwerk.engine.loop.GameSession] **vor** dem Tick als Filter
 * anwendet und die jeder echte Validator zuerst aufrufen soll: Spielende, Spieler-ID, Zug, endliche Floats,
 * Content-Indizes und veraltete Refs.
 */
object CommandChecks {
    fun precheck(view: GameView, cmd: Command): RejectReason? {
        if (view.result != GameResult.Ongoing) return RejectReason.GAME_OVER
        if (cmd.playerId < 0 || cmd.playerId >= view.playerCount) return RejectReason.INVALID_TARGET
        val turn = view.turn
        if (turn.mode == TurnMode.TURNS && cmd !is Command.Surrender) {
            if (turn.activePlayer != cmd.playerId || turn.phase != TurnPhase.PLAY) return RejectReason.NOT_YOUR_TURN
        }
        if (!floatsFinite(cmd)) return RejectReason.INVALID_TARGET
        val t = view.tables
        return when (cmd) {
            is Command.PlaceBeam -> when {
                cmd.materialId !in t.materials.indices -> RejectReason.UNKNOWN_CONTENT
                stale(view.nodeView.resolve(cmd.aNodeRef), cmd.aNodeRef) -> RejectReason.STALE_TARGET
                stale(view.nodeView.resolve(cmd.bNodeRef), cmd.bNodeRef) -> RejectReason.STALE_TARGET
                stale(view.beamView.resolve(cmd.aBeamRef), cmd.aBeamRef) -> RejectReason.STALE_TARGET
                stale(view.beamView.resolve(cmd.bBeamRef), cmd.bBeamRef) -> RejectReason.STALE_TARGET
                else -> null
            }
            is Command.DeleteBeam -> beamRef(view, cmd.beamRef)
            is Command.RepairBeam -> beamRef(view, cmd.beamRef)
            is Command.ToggleDoor -> beamRef(view, cmd.beamRef)
            is Command.PlaceDevice ->
                if (cmd.deviceTypeId !in t.devices.indices) RejectReason.UNKNOWN_CONTENT else beamRef(view, cmd.beamRef)
            is Command.DeleteDevice -> deviceRef(view, cmd.deviceRef)
            is Command.SetAim -> deviceRef(view, cmd.deviceRef)
            is Command.Fire -> deviceRef(view, cmd.deviceRef)
            is Command.Undo, is Command.EndTurn, is Command.Surrender -> null
        }
    }

    /** Klemmt Kraft auf `minPower..maxPower` und Balken-Parameter auf 0..1 (nach [precheck] anwenden). */
    fun sanitize(cmd: Command, config: SimConfig): Command = when (cmd) {
        is Command.SetAim -> {
            val p = FloatMath.clamp(cmd.power, config.minPower, config.maxPower)
            if (p == cmd.power) cmd else cmd.copy(power = p)
        }
        is Command.PlaceDevice -> {
            val tt = FloatMath.clamp(cmd.t, 0f, 1f)
            if (tt == cmd.t) cmd else cmd.copy(t = tt)
        }
        is Command.PlaceBeam -> {
            val ta = FloatMath.clamp(cmd.aBeamT, 0f, 1f)
            val tb = FloatMath.clamp(cmd.bBeamT, 0f, 1f)
            if (ta == cmd.aBeamT && tb == cmd.bBeamT) cmd else cmd.copy(aBeamT = ta, bBeamT = tb)
        }
        else -> cmd
    }

    /** Sind alle Float-Felder endlich? */
    fun floatsFinite(cmd: Command): Boolean = when (cmd) {
        is Command.PlaceBeam -> fin(cmd.aX) && fin(cmd.aY) && fin(cmd.bX) && fin(cmd.bY) && fin(cmd.aBeamT) && fin(cmd.bBeamT)
        is Command.PlaceDevice -> fin(cmd.t)
        is Command.SetAim -> fin(cmd.angle) && fin(cmd.power)
        else -> true
    }

    private fun fin(v: Float): Boolean = v.isFinite()

    private fun stale(resolved: Int, ref: Long): Boolean = ref >= 0L && resolved < 0

    private fun beamRef(view: GameView, ref: Long): RejectReason? = when {
        ref < 0L -> RejectReason.INVALID_TARGET
        view.beamView.resolve(ref) < 0 -> RejectReason.STALE_TARGET
        else -> null
    }

    private fun deviceRef(view: GameView, ref: Long): RejectReason? = when {
        ref < 0L -> RejectReason.INVALID_TARGET
        view.deviceView.resolve(ref) < 0 -> RejectReason.STALE_TARGET
        else -> null
    }
}
