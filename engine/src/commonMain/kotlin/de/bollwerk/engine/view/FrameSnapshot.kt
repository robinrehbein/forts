package de.bollwerk.engine.view

import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase

/**
 * Kopie dessen, was Renderer und HUD brauchen – entkoppelt Sim- und Render-Thread. Arrays sind
 * **pool-indiziert** (Index = Knoten-/Balken-/Geräte-Slot, Länge ≥ count), tote Slots haben
 * `flags and ALIVE == 0`. Render-Caches (Maserung, Decals) werden über die `*Uid`-Arrays verknüpft, nicht
 * über Slots: Ändert sich die uid eines Slots, ist es ein neues Objekt.
 *
 * **Interpolation:** Jeder Snapshot enthält beide Endpunkte des letzten Ticks (`*Prev*` = Position zu
 * Tickbeginn, `node*`/`proj*` = Tickende). Der Renderer zeichnet `lerp(prev, cur, alpha)` mit `alpha` aus dem
 * `GameLoop` – unabhängig davon, wie oft der Puffer wiederverwendet wird.
 *
 * **Threading:** Instanzen werden über `SnapshotExchange` (Dreifachpuffer) übergeben. Der Sim-Thread schreibt
 * (inkl. [fx]), der Render-Thread **liest nur**. Ein Snapshot mit neuer [seq] ist frisch; seine [fx] verarbeitet
 * der Renderer genau einmal.
 */
class FrameSnapshot {
    /** Laufende Nummer (vom [SnapshotBuilder] je Build erhöht). */
    var seq: Long = 0L
    /** Tick, aus dem der Snapshot stammt. */
    var tick: Long = 0L
    var wind: Float = 0f
    var result: GameResult = GameResult.Ongoing
    var turnMode: TurnMode = TurnMode.REALTIME
    var turnActivePlayer: Int = -1
    var turnNumber: Int = 0
    var turnTicksLeft: Int = 0
    var turnPhase: TurnPhase = TurnPhase.PLAY

    // ---- Knoten ----
    var nodeCount: Int = 0
    var nodeX = FloatArray(0)
    var nodeY = FloatArray(0)
    /** Position zu Beginn des letzten Ticks (Interpolations-Start). */
    var nodePrevX = FloatArray(0)
    var nodePrevY = FloatArray(0)
    var nodeFlags = IntArray(0)
    var nodeOwner = IntArray(0)
    var nodeUid = IntArray(0)

    // ---- Balken ----
    var beamCount: Int = 0
    /** Knoten-Slot an Ende A/B. */
    var beamA = IntArray(0)
    var beamB = IntArray(0)
    var beamMaterial = IntArray(0)
    var beamUid = IntArray(0)
    /** Ruhelänge in m (Seil-Durchhang ∝ Länge, max. 8 %). */
    var beamRestLen = FloatArray(0)
    /** TP-Anteil 0..1. */
    var beamHp01 = FloatArray(0)
    /** Brandstärke 0..1. */
    var beamFire01 = FloatArray(0)
    /** Brennstoff 0..1 (niedrig = verkohlt). */
    var beamFuel01 = FloatArray(0)
    /** |Dehnung| relativ zur Material-Grenzdehnung (Knarzen ab 0,8). */
    var beamLoad01 = FloatArray(0)
    /** Holzmaserungs-Versatz in m. */
    var beamTexOffset = FloatArray(0)
    /** `BeamFlags` (DOOR_OPEN, JAG_A/B, DOOR_HINGE_B, REPAIRING, DEBRIS …). */
    var beamFlags = IntArray(0)
    var beamOwner = IntArray(0)

    // ---- Geräte ----
    var deviceCount: Int = 0
    var deviceType = IntArray(0)
    var deviceUid = IntArray(0)
    var deviceBeam = IntArray(0)
    var deviceT = FloatArray(0)
    var deviceHp01 = FloatArray(0)
    var deviceMaxHp = FloatArray(0)
    var deviceOwner = IntArray(0)
    var deviceAim = FloatArray(0)
    var devicePower = FloatArray(0)
    /** Nachlade-Fortschritt 0..1 (1 = bereit / keine Waffe). */
    var deviceReload01 = FloatArray(0)
    /** Bau-Fortschritt 0..1 (1 = fertig). */
    var deviceBuild01 = FloatArray(0)
    /** Montagepunkt und Normale am Tickende (für Zwischenframes `DeviceGeometry.mountAt` mit interpolierten Knoten). */
    var deviceX = FloatArray(0)
    var deviceY = FloatArray(0)
    var deviceNX = FloatArray(0)
    var deviceNY = FloatArray(0)
    /** Laser-Endpunkt (gültig bei `DeviceFlags.FIRING_BEAM`). */
    var deviceLaserEndX = FloatArray(0)
    var deviceLaserEndY = FloatArray(0)
    var deviceFlags = IntArray(0)

    // ---- Projektile ----
    var projectileCount: Int = 0
    var projX = FloatArray(0)
    var projY = FloatArray(0)
    /** Position zu Beginn des letzten Ticks. */
    var projPrevX = FloatArray(0)
    var projPrevY = FloatArray(0)
    var projVx = FloatArray(0)
    var projVy = FloatArray(0)
    var projKind = IntArray(0)
    var projOwner = IntArray(0)
    var projUid = IntArray(0)
    var projFlags = IntArray(0)

    /** Effekt-Ereignisse seit dem letzten gelesenen Snapshot (nach [FxEvent.tick] sortierbar). Render-Thread: nur lesen. */
    val fx: MutableList<FxEvent> = ArrayList()

    /**
     * Intern (Sim-Thread): Dieser Puffer wurde veröffentlicht, aber nie gelesen; der nächste Build behält
     * seine [fx], statt sie zu verwerfen. Gesetzt von `SnapshotExchange`.
     */
    var carryFx: Boolean = false

    /** HUD-Werte (vom [SnapshotBuilder] befüllt, wenn ein lokaler Spieler gesetzt ist). */
    var hud: HudModel = HudModel.EMPTY
}
