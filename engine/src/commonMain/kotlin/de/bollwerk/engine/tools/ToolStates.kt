package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandValidator
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.view.GameView

/*
 * Reine UI-Logik der Werkzeuge (WP8): übersetzt Gesten in Welt-Koordinaten in Vorschau-Zustände und am
 * Ende in ein Command (kein Sim-State, keine Sim-Änderung). Läuft auf dem Sim-Thread bzw. mit einem
 * Snapshot-gestützten GameView. Erzeugte Commands tragen beliebigen Tick; der Aufrufer stempelt sie
 * (LocalInputSource).
 */

/** Aktives Werkzeug der Toolbar (Stil-Bibel §7: Materialien | Geräte | Zurück, Reparatur; dazu Abriss/Tür). */
sealed class ToolSelection {
    data object None : ToolSelection()
    /** Material-Index in `SimTables.materials`. */
    data class Material(val index: Int) : ToolSelection()
    /** Geräte-Index in `SimTables.devices`. */
    data class Device(val index: Int) : ToolSelection()
    data object Repair : ToolSelection()
    data object Delete : ToolSelection()
    data object Door : ToolSelection()
}

/**
 * Kontext eines Werkzeug-Aufrufs.
 * @property playerId lokaler Spieler.
 * @property pickRadiusM Fang-/Auswahlradius in Metern beim aktuellen Zoom (Prototyp: 26/34/16 px ÷ Maßstab).
 * @property validator derselbe Validator wie im Command-System (Ghost grün/rot, Grund); Standard ist der echte
 * [RulesValidator], damit Werkzeuge keine Regeln duplizieren.
 */
class ToolContext(
    val view: GameView,
    val playerId: Int,
    val pickRadiusM: Float,
    val validator: CommandValidator = RulesValidator,
)

/**
 * Vorschau-Balken beim Bauen (Stil-Bibel §7: gestrichelt grün/rot, Längen-/Kostenchip).
 * Koordinaten in Welt-Metern.
 */
data class GhostBeam(
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val materialId: Int,
    val valid: Boolean,
    /** Grund bei `valid == false`. */
    val reason: RejectReason?,
    val lengthM: Float,
    /** Kosten in ⚙. */
    val cost: Float,
    /** Knoten-Ref, an dem Ende B einrastet, oder −1. */
    val snapNodeRef: Long = -1,
    /** Balken-Ref, auf dem Ende B einrastet (Split), oder −1. */
    val snapBeamRef: Long = -1,
    /** Teilungsparameter (0..1 vom Ende A des Balkens) bei [snapBeamRef] >= 0, sonst 0. */
    val snapBeamT: Float = 0f,
    /** Ende B ist auf einen 15°-Winkel eingerastet (Hilfslinie am Start zeichnen). */
    val angleSnapped: Boolean = false,
    /** Ende B ist auf die Höchstlänge (6 m) eingerastet. */
    val lengthSnapped: Boolean = false,
    /**
     * Kettenanker noch nicht in der Sim (der vorige Balken wurde noch nicht ausgeführt): Ghost nur als Hinweis zeigen
     * (weder grün noch rot, `valid == false`, `reason == null`); Loslassen sendet nichts.
     */
    val pending: Boolean = false,
)

/** Vorschau-Gerät (Stil-Bibel: halbtransparent, gestrichelter Ring grün/rot; "nur über Erz", "Platz belegt" …). */
data class GhostDevice(
    /** Montagepunkt und Normale. */
    val x: Float,
    val y: Float,
    val nx: Float,
    val ny: Float,
    val typeId: Int,
    val valid: Boolean,
    val reason: RejectReason?,
    val beamRef: Long,
    val t: Float,
    val sideNegative: Boolean,
    val costMetal: Float,
    val costEnergy: Float,
)

/**
 * Flugbahn-Vorschau: [count] Punkte als (x, y)-Paare in [points] (Welt-Meter). Gleichheit nach Inhalt,
 * damit Compose-State-Vergleiche funktionieren.
 */
class Trajectory(val points: FloatArray, val count: Int) {
    init { require(count * 2 <= points.size) { "count exceeds points" } }

    fun x(i: Int): Float = points[i * 2]
    fun y(i: Int): Float = points[i * 2 + 1]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Trajectory || other.count != count) return false
        for (i in 0 until count * 2) if (points[i].toRawBits() != other.points[i].toRawBits()) return false
        return true
    }

    override fun hashCode(): Int {
        var h = count
        for (i in 0 until count * 2) h = h * 31 + points[i].toRawBits()
        return h
    }

    companion object {
        val EMPTY: Trajectory = Trajectory(FloatArray(0), 0)
    }
}

/** Zustände des Bauwerkzeugs. */
sealed class BuildToolState {
    data object Idle : BuildToolState()

    /** Erstes Ende gewählt: bestehender Knoten, Punkt auf Balken (Split) oder freie Position (Refs −1). */
    data class FirstNodeSelected(val nodeRef: Long, val beamRef: Long, val beamT: Float, val x: Float, val y: Float) : BuildToolState()

    /** Finger zieht; [ghost] zeigt Gültigkeit, Länge und Kosten. */
    data class Previewing(val from: FirstNodeSelected, val ghost: GhostBeam) : BuildToolState()
}

/** Zustände des Geräte-Werkzeugs. */
sealed class DeviceToolState {
    data object Idle : DeviceToolState()
    data class Previewing(val ghost: GhostDevice) : DeviceToolState()
}

/** Zustände des Zielwerkzeugs (Stil-Bibel §7: Waffe antippen, dann ziehen – Zugrichtung = Schussrichtung). */
sealed class AimToolState {
    data object Idle : AimToolState()

    /** Waffe gewählt, noch kein Zug. */
    data class WeaponSelected(val deviceRef: Long) : AimToolState()

    /** Zug läuft; [angle] Bogenmaß, [power] `minPower..maxPower`; [trajectory] aus `Ballistics.predict`. */
    data class Aiming(
        val deviceRef: Long,
        val angle: Float,
        val power: Float,
        val trajectory: Trajectory = Trajectory.EMPTY,
        val apexHeightM: Float = 0f,
        val windDriftM: Float = 0f,
    ) : AimToolState()
}

/** Bauwerkzeug (Materialien): Ziehen von Knoten/Balken/Fläche zu Knoten/Balken/Fläche → `PlaceBeam`. */
interface BuildTool {
    val state: BuildToolState
    /** Material-Index des aktiven Werkzeugs. */
    var material: Int
    fun onDown(worldX: Float, worldY: Float, ctx: ToolContext)
    fun onMove(worldX: Float, worldY: Float, ctx: ToolContext)
    /** @return zu sendendes Command oder `null`. */
    fun onUp(worldX: Float, worldY: Float, ctx: ToolContext): Command?
    fun cancel()
}

/** Geräte-Werkzeug: Ziehen über einen Balken zeigt [GhostDevice], Loslassen → `PlaceDevice`. */
interface DeviceTool {
    val state: DeviceToolState
    /** Geräte-Index des aktiven Werkzeugs. */
    var deviceType: Int
    fun onDown(worldX: Float, worldY: Float, ctx: ToolContext)
    fun onMove(worldX: Float, worldY: Float, ctx: ToolContext)
    fun onUp(worldX: Float, worldY: Float, ctx: ToolContext): Command?
    fun cancel()
}

/**
 * Tipp-Werkzeuge: [ToolSelection.Repair] → `RepairBeam`, [ToolSelection.Delete] → `DeleteDevice`/`DeleteBeam`,
 * [ToolSelection.Door] → `ToggleDoor`. "Zurück" ist ein Button und sendet direkt `Command.Undo`.
 */
interface TapTool {
    /** @return Command für den Tipp bei (worldX, worldY) mit Werkzeug [selection], oder `null`. */
    fun onTap(worldX: Float, worldY: Float, selection: ToolSelection, ctx: ToolContext): Command?
}

/** Zielwerkzeug: Antippen wählt Waffe, Ziehen setzt Winkel/Kraft, Loslassen liefert `SetAim`. */
interface AimTool {
    val state: AimToolState
    fun onDown(worldX: Float, worldY: Float, ctx: ToolContext)
    fun onMove(worldX: Float, worldY: Float, ctx: ToolContext)
    fun onUp(worldX: Float, worldY: Float, ctx: ToolContext): Command?
    fun cancel()
}
