package de.bollwerk.engine.sim

/** Lesender Zugriff auf einen Spieler. */
interface PlayerView {
    val id: Int
    val metal: Float
    val energy: Float
    val metalCap: Float
    val energyCap: Float
    val metalRate: Float
    val energyRate: Float
    val reactorDeviceId: Int
    val alive: Boolean
    /** +1 = Basis links, schießt nach rechts; −1 = Basis rechts. */
    val facing: Int
    /** Aktuell besessene Techs. */
    val techs: TechSetView
    /** Einträge im Zurück-Journal (UI: "Zurück" aktiv, wenn > 0). */
    val undoCount: Int
    fun hasTech(techIndex: Int): Boolean
    /** Neuester Zurück-Eintrag oder null (für Validator/Werkzeuge; additiv, WP3). */
    fun undoTop(): UndoEntry? = null
    /** Zurück-Eintrag [index] (0 = ältester) oder null (additiv, WP3). */
    fun undoAt(index: Int): UndoEntry? = null
}

/**
 * Wirtschaft und Fortschritt eines Spielers (PERSISTENT und gehasht, außer den DERIVED-Raten). Raten sind pro Sekunde und werden
 * vom Wirtschafts-System je Tick neu berechnet (Mine +6 ⚙/s, Turbine +6 ⚡/s × Höhenfaktor, Reaktor +2 ⚡/s).
 */
class PlayerState(
    override val id: Int,
    override var metal: Float,
    override var energy: Float,
    override var metalCap: Float,
    override var energyCap: Float,
    undoDepth: Int = 60,
) : PlayerView {
    override var metalRate: Float = 0f
    override var energyRate: Float = 0f
    /**
     * Besessene Techs (Indizes in `ContentDb.techs`). Vom Tech-System (`SystemSlot.TECH`) **jeden Tick neu
     * berechnet**: Tech T ist gesetzt ⇔ der Spieler hat ein lebendes, fertiges Gerät mit `grantsTech == T`.
     */
    val techUnlocked: TechSet = TechSet()
    /** Geräte-ID des Reaktors (−1 = noch keiner). */
    override var reactorDeviceId: Int = -1
    override var alive: Boolean = true
    override var facing: Int = 1
    /** Zurück-Journal (siehe [UndoJournal]). */
    val undoJournal: UndoJournal = UndoJournal(undoDepth)

    override val techs: TechSetView get() = techUnlocked
    override val undoCount: Int get() = undoJournal.size
    override fun undoTop(): UndoEntry? = undoJournal.peek()
    override fun undoAt(index: Int): UndoEntry? = if (index in 0 until undoJournal.size) undoJournal[index] else null
    override fun hasTech(techIndex: Int): Boolean = techIndex in techUnlocked
}

/** Art eines Zurück-Eintrags. */
enum class UndoKind { BEAM, DEVICE }

/** Gespeicherte Daten eines durch Split ersetzten Balkens (zum Wiedervereinigen bei Zurück). */
data class BeamRecord(
    val nodeARef: Long,
    val nodeBRef: Long,
    val material: Int,
    val owner: Int,
    val restLen: Float,
    val hp: Float,
    val maxHp: Float,
    val fire: Float,
    val fuel: Float,
    val flags: Int,
    val texOffset: Float,
    /** Ref des ursprünglichen Balkens (WP3, additiv): Zurück ordnet damit ältere Journal-Einträge dem neu angelegten Balken zu. */
    val ref: Long = PoolView.NO_REF,
)

/** Gerät, das beim Split auf eine Hälfte umgezogen ist, mit seinem ursprünglichen Parameter [t]. */
data class DeviceRemap(val deviceRef: Long, val t: Float)

/**
 * Ein Split eines bestehenden Balkens beim Bauen (Ende auf Balkenmitte): neuer Knoten [nodeRef], Hälften
 * [halfARef] (A-Seite) und [halfBRef], ursprünglicher Balken [original].
 */
data class SplitRecord(
    val nodeRef: Long,
    val halfARef: Long,
    val halfBRef: Long,
    val original: BeamRecord,
    val devices: List<DeviceRemap>,
)

/**
 * Ein Bau-Vorgang im Zurück-Journal.
 * @property ref neuer Balken ([UndoKind.BEAM]) oder neues Gerät ([UndoKind.DEVICE]).
 * @property newNodeRefs durch den Bau neu angelegte freie Knoten (nicht Split-Knoten).
 * @property metal tatsächlich bezahlte Kosten; @property energy dito.
 */
data class UndoEntry(
    val kind: UndoKind,
    val tick: Long,
    val ref: Long,
    val metal: Float,
    val energy: Float,
    val newNodeRefs: List<Long> = emptyList(),
    val splits: List<SplitRecord> = emptyList(),
)

/**
 * Begrenztes Zurück-Journal je Spieler (Prototyp: 60 Einträge, ältester fällt heraus). PERSISTENT und gehasht.
 *
 * Regeln für `Command.Undo` (umgesetzt vom Command-System, WP3):
 * - Nimmt den **neuesten noch erfüllbaren** Eintrag. Einträge, die nie mehr zurückgenommen werden können, weil sie
 *   abgelaufen sind (`tick − entry.tick > SimConfig.undoWindowTicks`, 0 = unbegrenzt) oder ihr Balken/Gerät bzw. eine
 *   Split-Hälfte zerstört wurde, fallen **jeden Tick aus dem Journal** (WP3 `UndoRules.prune`) und blockieren ältere
 *   nicht; `RulesValidator`/`canUndo` rechnen dasselbe schreibgeschützt nach, die Antwort ist also zwischen den Ticks
 *   dieselbe wie im Command-System. Ohne erfüllbaren Eintrag: `RejectReason.NOTHING_TO_UNDO`.
 * - Ist der oberste erfüllbare Eintrag beschädigt (TP < Original), brennt oder hat einen Split-/Neuknoten verloren:
 *   `RejectReason.UNDO_BLOCKED`, der Eintrag bleibt.
 * - Entfernt die erzeugten Objekte in umgekehrter Reihenfolge, **vereinigt Split-Balken wieder** zum
 *   Originalbalken (Daten aus [BeamRecord], neue uid) und setzt umgezogene Geräte auf ihr altes `t` zurück.
 * - Erstattet `undoRefund ×` ([UndoEntry.metal], [UndoEntry.energy]).
 */
class UndoJournal(val capacity: Int) {
    private val entries = ArrayList<UndoEntry>(capacity)

    val size: Int get() = entries.size

    fun push(e: UndoEntry) {
        if (capacity <= 0) return
        if (entries.size == capacity) entries.removeAt(0)
        entries.add(e)
    }

    fun peek(): UndoEntry? = entries.lastOrNull()

    fun pop(): UndoEntry? = if (entries.isEmpty()) null else entries.removeAt(entries.size - 1)

    /** Eintrag [i] (0 = ältester). */
    operator fun get(i: Int): UndoEntry = entries[i]

    fun clear() = entries.clear()
}
