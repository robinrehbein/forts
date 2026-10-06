package de.bollwerk.engine.view

import de.bollwerk.engine.command.RejectReason

/** Ursache eines Balkenbruchs (Darstellung unterscheidet Splitter-Bruch und stilles Zerfallen). */
enum class BreakCause {
    /** Überlast (Dehnung). */
    STRAIN,
    /** Treffer/Explosion. */
    DAMAGE,
    /** Verbrannt (TP oder Brennstoff aufgebraucht). */
    FIRE,
    /** Trümmer-Zerfall, Kill-Grenzen, unter dem Boden (still, nur Staub). */
    DECAY,
    /** Vom Spieler abgerissen. */
    DELETED,
}

/** Art des getroffenen Ziels bei [FxEvent.Hit]. */
enum class HitTarget { BEAM, DEVICE, TERRAIN }

/**
 * Effekt-Ereignisse aus der Simulation für Partikel, Audio, Kamera-Shake und HUD-Toasts.
 * Sie sind **kein** Sim-State (gehen nicht in den Hash ein): Systeme schreiben sie in
 * [de.bollwerk.engine.sim.StepContext.fx], die `GameSession` sammelt sie nach jedem Tick im [FxBuffer],
 * der [SnapshotBuilder] übergibt sie dem nächsten [FrameSnapshot].
 *
 * Objekte werden über **uids** referenziert (`PoolView.uid`, nie wiederverwendet), nicht über Slots.
 * Positionen in Welt-Metern; `NaN` = keine Position. [tick] = Sim-Tick des Ereignisses (Reihenfolge, Alter).
 */
sealed class FxEvent {
    abstract val tick: Long
    abstract val x: Float
    abstract val y: Float

    /**
     * Explosion mit [radius] (m) und [damage] im Zentrum; [weaponId] = Waffen-Index oder −1.
     * [hitMaterialId] = Material des getroffenen Balkens (Trümmerstücke), −1 Gelände, −2 Luft/Gerät.
     * [hitBeamUid]/[hitBeamT] = getroffener Balken und Stelle 0..1 (Brandspur-Decal) oder −1.
     */
    data class Explosion(
        override val tick: Long, override val x: Float, override val y: Float,
        val radius: Float, val damage: Float, val weaponId: Int,
        val hitMaterialId: Int, val incendiary: Boolean, val hitBeamUid: Int, val hitBeamT: Float,
    ) : FxEvent()

    /** Balken gebrochen an Stelle [t] (0..1 von Ende A); Splitter/Funken je nach [materialId]. */
    data class BeamBroken(
        override val tick: Long, override val x: Float, override val y: Float,
        val beamUid: Int, val materialId: Int, val t: Float, val cause: BreakCause,
    ) : FxEvent()

    /** Balken beim Bauen geteilt: [beamUid] ersetzt durch [halfAUid] und [halfBUid] (Maserung fortsetzen). */
    data class BeamSplit(
        override val tick: Long, override val x: Float, override val y: Float,
        val beamUid: Int, val halfAUid: Int, val halfBUid: Int,
    ) : FxEvent()

    /** Waffe hat gefeuert (Mündungsfeuer, Rückstoß, Sound); Position = Mündung, [angle] in Bogenmaß. */
    data class Fired(
        override val tick: Long, override val x: Float, override val y: Float,
        val deviceUid: Int, val weaponId: Int, val angle: Float,
    ) : FxEvent()

    /** Hitscan-Leuchtspur (MG, Scharfschütze) von (x, y) nach ([x1], [y1]). */
    data class Tracer(
        override val tick: Long, override val x: Float, override val y: Float,
        val x1: Float, val y1: Float, val weaponId: Int,
    ) : FxEvent()

    /** Laserstrahl in diesem Tick von (x, y) nach ([x1], [y1]). */
    data class LaserBeam(
        override val tick: Long, override val x: Float, override val y: Float,
        val x1: Float, val y1: Float, val deviceUid: Int,
    ) : FxEvent()

    /** Treffer-Blitz auf Balken/Gerät (Prototyp `flash`) oder Einschlag im Gelände. */
    data class Hit(
        override val tick: Long, override val x: Float, override val y: Float,
        val target: HitTarget, val targetUid: Int, val damage: Float, val weaponId: Int,
    ) : FxEvent()

    /** Holzbalken hat Feuer gefangen. */
    data class Ignited(override val tick: Long, override val x: Float, override val y: Float, val beamUid: Int) : FxEvent()

    /** Gerät zerstört. */
    data class DeviceDestroyed(
        override val tick: Long, override val x: Float, override val y: Float,
        val deviceUid: Int, val deviceTypeId: Int,
    ) : FxEvent()

    /** Gerät platziert (Metall-Klonk). */
    data class DevicePlaced(
        override val tick: Long, override val x: Float, override val y: Float,
        val deviceUid: Int, val deviceTypeId: Int,
    ) : FxEvent()

    /** Trümmer schlagen auf dem Boden auf (Staub, dumpfer Schlag); [speed] in m/s. */
    data class DebrisLanded(override val tick: Long, override val x: Float, override val y: Float, val speed: Float) : FxEvent()

    /** Balken gesetzt (Bau-Sound, Staub). */
    data class BeamPlaced(
        override val tick: Long, override val x: Float, override val y: Float,
        val beamUid: Int, val materialId: Int,
    ) : FxEvent()

    /** Balken vollständig repariert. */
    data class BeamRepaired(override val tick: Long, override val x: Float, override val y: Float, val beamUid: Int) : FxEvent()

    /** Tür geöffnet/geschlossen (Puff, Sound). */
    data class DoorToggled(
        override val tick: Long, override val x: Float, override val y: Float,
        val beamUid: Int, val open: Boolean,
    ) : FxEvent()

    /** Tech gewonnen ([unlocked]) oder verloren (HUD-Toast). */
    data class TechChanged(
        override val tick: Long, override val x: Float, override val y: Float,
        val playerId: Int, val techId: Int, val unlocked: Boolean,
    ) : FxEvent()

    /** Command abgelehnt (roter Ghost, Ressourcen-Chip blinkt, Toast); Position NaN, wenn unbekannt. */
    data class CommandRejected(
        override val tick: Long, override val x: Float, override val y: Float,
        val playerId: Int, val reason: RejectReason,
    ) : FxEvent()

    /** Reaktor von [playerId] zerstört (Siegsequenz). */
    data class ReactorDestroyed(override val tick: Long, override val x: Float, override val y: Float, val playerId: Int) : FxEvent()
}

/**
 * Sammelt Fx-Ereignisse über mehrere Ticks (Sim-Thread), bis der nächste Snapshot sie abholt; so gehen bei
 * mehreren Ticks pro Frame keine Explosionen/Sounds verloren. Bei mehr als [capacity] Ereignissen
 * (z. B. headless ohne Snapshots) fallen die ältesten heraus.
 */
class FxBuffer(val capacity: Int = 2048) {
    private val events = ArrayDeque<FxEvent>()

    val size: Int get() = events.size

    fun addAll(list: List<FxEvent>) {
        for (i in list.indices) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(list[i])
        }
    }

    /** Hängt alle Ereignisse in Reihenfolge an [out] an und leert den Puffer. */
    fun drainTo(out: MutableList<FxEvent>) {
        while (events.isNotEmpty()) out.add(events.removeFirst())
    }

    fun clear() = events.clear()
}
