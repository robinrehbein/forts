package de.bollwerk.engine.sim

/** Flag-Bits für [NodePool.flags] (Bit 0 = [Pool.ALIVE]). */
object NodeFlags {
    const val ALIVE: Int = Pool.ALIVE
    /** Fest im Fundament verankert (invMass = 0). */
    const val ANCHORED: Int = 1 shl 1
    /** Gehört zu einer Komponente ohne Anker (Trümmer). */
    const val DEBRIS: Int = 1 shl 2
    /** Liegt aktuell auf dem Gelände. */
    const val GROUNDED: Int = 1 shl 3
}

/** Ansicht ohne Schreibzugriff (für KI/UI über [de.bollwerk.engine.view.GameView]). */
interface NodeView : PoolView {
    fun x(id: Int): Float
    fun y(id: Int): Float
    /** Geschwindigkeit in m/s (aus Verlet `(x − px) / Substep-Dauer`). */
    fun vx(id: Int): Float
    fun vy(id: Int): Float
    fun owner(id: Int): Int
    fun flags(id: Int): Int
    fun isAnchored(id: Int): Boolean
    /** Anzahl angeschlossener Balken (DERIVED, gültig nach [NodePool.rebuildAdjacency]). */
    fun beamCount(id: Int): Int
    /** [k]-ter angeschlossener Balken (`0 until beamCount(id)`), aufsteigend nach Balken-ID. */
    fun adjacentBeam(id: Int, k: Int): Int
}

/**
 * Knoten (Gelenke) als Structure-of-Arrays. Verlet-Zustand: aktuelle Position ([x], [y]) und
 * vorherige Substep-Position ([px], [py]); die Geschwindigkeit ist implizit `(x − px) / substepDt`.
 */
class NodePool(initialCapacity: Int = 256) : Pool(initialCapacity), NodeView {
    /** PERSISTENT. */
    var x = FloatArray(initialCapacity); private set
    /** PERSISTENT. */
    var y = FloatArray(initialCapacity); private set
    /** PERSISTENT. Position des vorherigen Substeps (Verlet). */
    var px = FloatArray(initialCapacity); private set
    /** PERSISTENT. */
    var py = FloatArray(initialCapacity); private set
    /** PERSISTENT. Inverse Masse (0 = verankert/unbeweglich). */
    var invMass = FloatArray(initialCapacity); private set
    /** DERIVED. Masse in kg (aus Balken/Geräten aufsummiert, siehe Physik-System). */
    var mass = FloatArray(initialCapacity); private set
    /** PERSISTENT. Besitzer (Spieler-ID, −1 = neutral). */
    var ownerOf = IntArray(initialCapacity); private set
    /** DERIVED. Komponenten-ID aus der Union-Find-Konnektivität. */
    var component = IntArray(initialCapacity); private set
    /** PERSISTENT. Ticks seit der Knoten Trümmer wurde (Prototyp `age`, Zerfall nach `DebrisConfig.decayAfterTicks`). */
    var debrisTicks = IntArray(initialCapacity); private set
    /** PERSISTENT. Ticks unter der Geländeoberfläche (Prototyp `below`, Entfernen nach `DebrisConfig.belowGroundTicks`). */
    var belowGroundTicks = IntArray(initialCapacity); private set
    /** RENDER. Geschwindigkeit (m/s) im Vortick, für `FxEvent.DebrisLanded` (Prototyp `spd`). */
    var lastSpeed = FloatArray(initialCapacity); private set
    /** RENDER. Position zu Beginn des laufenden Ticks (Interpolations-Startpunkt), setzt `GameState.beginTick()`. */
    var tickX = FloatArray(initialCapacity); private set
    var tickY = FloatArray(initialCapacity); private set

    /** DERIVED. Adjazenz im CSR-Format: Balken von Knoten i liegen in `adjBeam[adjStart[i] until adjStart[i+1]]`. */
    var adjStart = IntArray(initialCapacity + 1); private set
    var adjBeam = IntArray(0); private set
    /** DERIVED. Anzahl angeschlossener lebender Balken. */
    var beamCountOf = IntArray(initialCapacity); private set

    private var adjCursor = IntArray(0)

    /** Dauer eines Substeps in s (für [vx]/[vy]); setzt der [GameState]. */
    var substepDt: Float = 1f / 240f

    /** Legt einen ruhenden Knoten an. */
    fun alloc(x: Float, y: Float, owner: Int, anchored: Boolean = false): Int {
        val id = allocSlot()
        this.x[id] = x; this.y[id] = y
        px[id] = x; py[id] = y
        tickX[id] = x; tickY[id] = y
        mass[id] = 0f
        invMass[id] = if (anchored) 0f else 1f
        ownerOf[id] = owner
        component[id] = id
        debrisTicks[id] = 0; belowGroundTicks[id] = 0; lastSpeed[id] = 0f
        beamCountOf[id] = 0
        if (anchored) flags[id] = flags[id] or NodeFlags.ANCHORED
        return id
    }

    override fun resize(newCapacity: Int) {
        x = x.copyOf(newCapacity); y = y.copyOf(newCapacity)
        px = px.copyOf(newCapacity); py = py.copyOf(newCapacity)
        invMass = invMass.copyOf(newCapacity); mass = mass.copyOf(newCapacity)
        ownerOf = ownerOf.copyOf(newCapacity); component = component.copyOf(newCapacity)
        debrisTicks = debrisTicks.copyOf(newCapacity); belowGroundTicks = belowGroundTicks.copyOf(newCapacity)
        lastSpeed = lastSpeed.copyOf(newCapacity)
        tickX = tickX.copyOf(newCapacity); tickY = tickY.copyOf(newCapacity)
        adjStart = adjStart.copyOf(newCapacity + 1)
        beamCountOf = beamCountOf.copyOf(newCapacity)
    }

    /**
     * Baut die Adjazenz (CSR) aus allen lebenden Balken neu auf, in fester Reihenfolge (Knoten-ID, dann
     * Balken-ID). Aufruf durch das Topologie-System, wenn `GameState.topologyDirty`, und nach Restore.
     */
    fun rebuildAdjacency(beams: BeamPool) {
        val n = size
        for (i in 0 until n) beamCountOf[i] = 0
        var total = 0
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j)) continue
            beamCountOf[beams.a[j]]++
            beamCountOf[beams.b[j]]++
            total += 2
        }
        // mit Reserve wachsen: bei Einstürzen kommt pro Bruch ein Balken hinzu (keine Allokation je Neuaufbau)
        if (adjBeam.size < total) adjBeam = IntArray(if (total > adjBeam.size * 2) total else adjBeam.size * 2)
        var acc = 0
        for (i in 0 until n) { adjStart[i] = acc; acc += beamCountOf[i] }
        adjStart[n] = acc
        if (adjCursor.size < n) adjCursor = IntArray(capacity)
        val cursor = adjCursor
        for (i in 0 until n) cursor[i] = adjStart[i]
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j)) continue
            val a = beams.a[j]; val b = beams.b[j]
            adjBeam[cursor[a]++] = j
            adjBeam[cursor[b]++] = j
        }
    }

    override fun isAnchored(id: Int): Boolean = (flags[id] and NodeFlags.ANCHORED) != 0

    override fun x(id: Int): Float = x[id]
    override fun y(id: Int): Float = y[id]
    override fun vx(id: Int): Float = (x[id] - px[id]) / substepDt
    override fun vy(id: Int): Float = (y[id] - py[id]) / substepDt
    override fun owner(id: Int): Int = ownerOf[id]
    override fun flags(id: Int): Int = flags[id]
    override fun beamCount(id: Int): Int = beamCountOf[id]
    override fun adjacentBeam(id: Int, k: Int): Int = adjBeam[adjStart[id] + k]
}
