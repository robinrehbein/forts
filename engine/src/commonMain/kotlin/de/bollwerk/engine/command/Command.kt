package de.bollwerk.engine.command

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Jeder Eingriff in die Simulation ist ein serialisierbares Command (Determinismus-Regel 1).
 * Replays und später Lockstep-Netzwerk übertragen ausschließlich diese Objekte.
 *
 * - Content-Referenzen (`materialId`, `deviceTypeId`) sind **Indizes** in `ContentDb` / `SimTables`.
 * - Objekt-Referenzen (`*Ref`) sind stabile Pool-Refs (`PoolView.ref`: Generation + Slot), nie nackte
 *   Slot-IDs; −1 = keine. Veraltete Refs lehnt der Validator mit `RejectReason.STALE_TARGET` ab.
 * - Floats müssen endlich sein (sonst `INVALID_TARGET`); `power` und `t` werden beim Anwenden geklemmt
 *   ([CommandChecks.sanitize]).
 *
 * JSON-Diskriminator: `"type"`.
 */
@Serializable
sealed class Command {
    /** Tick, in dem das Command wirkt. */
    abstract val tick: Long
    /** Ausführender Spieler. */
    abstract val playerId: Int

    /** Kopie mit anderem [tick] (z. B. verspätet angewendete Commands, damit Replays exakt bleiben). */
    abstract fun withTick(tick: Long): Command

    /**
     * Balken setzen. Jedes Ende wird in dieser Priorität aufgelöst:
     * 1. bestehender Knoten (`*NodeRef >= 0`),
     * 2. Punkt auf einem bestehenden Balken (`*BeamRef >= 0`, Parameter `*BeamT` 0..1 von dessen Ende A):
     *    der Balken wird dort geteilt (Hälften mit Ruhelänge `rest·t` bzw. `rest·(1−t)`, TP/Feuer/Brennstoff
     *    und Maserungs-Versatz übernommen, Geräte darauf mit umgerechnetem `t` umgezogen, `FxEvent.BeamSplit`),
     * 3. freie Weltposition (`*X`/`*Y`): liegt ein eigener Knoten näher als `SimConfig.nodeMergeRadius`
     *    (auch einer, der im selben Tick von einem früheren Command angelegt wurde), wird er benutzt, sonst
     *    entsteht ein neuer Knoten.
     */
    @Serializable
    @SerialName("placeBeam")
    data class PlaceBeam(
        override val tick: Long,
        override val playerId: Int,
        val aNodeRef: Long = -1,
        val aBeamRef: Long = -1,
        val aBeamT: Float = 0f,
        val aX: Float = 0f,
        val aY: Float = 0f,
        val bNodeRef: Long = -1,
        val bBeamRef: Long = -1,
        val bBeamT: Float = 0f,
        val bX: Float = 0f,
        val bY: Float = 0f,
        val materialId: Int,
    ) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /**
     * Eigenen Balken abreißen: Rückerstattung `deleteRefund · Kosten · TP-Anteil` (Prototyp). Brennt der Balken,
     * wird stattdessen nur das Feuer gelöscht. Balken mit Reaktor: `REACTOR_PROTECTED`.
     */
    @Serializable
    @SerialName("deleteBeam")
    data class DeleteBeam(override val tick: Long, override val playerId: Int, val beamRef: Long) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Eigenes Gerät abreißen (Rückerstattung `deleteRefund ·` Baukosten); Reaktor: `REACTOR_PROTECTED`. */
    @Serializable
    @SerialName("deleteDevice")
    data class DeleteDevice(override val tick: Long, override val playerId: Int, val deviceRef: Long) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Reparatur starten (Rate/Kosten siehe `RepairConfig`, pausiert solange der Balken brennt). */
    @Serializable
    @SerialName("repairBeam")
    data class RepairBeam(override val tick: Long, override val playerId: Int, val beamRef: Long) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Gerät auf Balken [beamRef] an Parameter [t] (0..1) platzieren; [sideNegative] = andere Balkenseite. */
    @Serializable
    @SerialName("placeDevice")
    data class PlaceDevice(
        override val tick: Long,
        override val playerId: Int,
        val deviceTypeId: Int,
        val beamRef: Long,
        val t: Float,
        val sideNegative: Boolean = false,
    ) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Tür (Balken aus Tür-Material) öffnen/schließen; vom Spieler geöffnet = fixiert (`DOOR_PINNED`). */
    @Serializable
    @SerialName("toggleDoor")
    data class ToggleDoor(override val tick: Long, override val playerId: Int, val beamRef: Long) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Zielwinkel (Bogenmaß, 0 = rechts, positiv = nach oben) und Kraft (`minPower..maxPower`) einer Waffe setzen. */
    @Serializable
    @SerialName("setAim")
    data class SetAim(
        override val tick: Long,
        override val playerId: Int,
        val deviceRef: Long,
        val angle: Float,
        val power: Float,
    ) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Waffe abfeuern (mit aktuell gesetztem Zielwinkel/Kraft). */
    @Serializable
    @SerialName("fire")
    data class Fire(override val tick: Long, override val playerId: Int, val deviceRef: Long) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Letzten Bau-Vorgang zurücknehmen (Regeln: `UndoJournal`). */
    @Serializable
    @SerialName("undo")
    data class Undo(override val tick: Long, override val playerId: Int) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Zug beenden (Hotseat). */
    @Serializable
    @SerialName("endTurn")
    data class EndTurn(override val tick: Long, override val playerId: Int) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }

    /** Aufgeben. */
    @Serializable
    @SerialName("surrender")
    data class Surrender(override val tick: Long, override val playerId: Int) : Command() {
        override fun withTick(tick: Long): Command = copy(tick = tick)
    }
}

/**
 * Gemeinsame JSON-Konfiguration für Commands und Replays. Unbekannte Felder führen zu einem Fehler, damit
 * ein Replay aus einer inkompatiblen Version nicht still falsch abgespielt wird; NaN/Unendlich sind verboten.
 */
val CommandJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = false
    allowSpecialFloatingPointValues = false
}
