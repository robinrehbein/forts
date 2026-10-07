package de.bollwerk.engine.combat

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec

/**
 * Broadphase für Strahl-/Projektil-Tests: gleichmäßiges Raster über die Kill-Grenzen der Karte
 * (`SimConfig.combat.broadphaseCellSize`), CSR-Layout (`start`/`items`), je Tick neu aufgebaut.
 *
 * Jeder lebende Balken wird in alle Zellen seiner um [margin] (größte halbe Balkendicke + größter Projektilradius)
 * vergrößerten Achsen-Box eingetragen. Ein Punkt, der näher als `halbe Dicke + Radius` an einem Balken liegt, liegt
 * damit immer in einer Zelle, die diesen Balken enthält; Abfragen müssen nur die Zellen der Mittellinie besuchen.
 * Positionen außerhalb des Rasters werden auf die Randzellen geklemmt (konservativ).
 *
 * DERIVED (nicht gehasht): reine Funktion der Knotenpositionen und Balken zum Aufbauzeitpunkt. Innerhalb einer Zelle
 * stehen die Balken in aufsteigender ID (feste Reihenfolge). Allokationsfrei im eingeschwungenen Zustand
 * (Arrays wachsen nur bei neuen Höchstständen).
 */
internal class BeamGrid {
    private var cell: Float = 2f
    private var inv: Float = 0.5f
    private var minX: Float = 0f
    private var minY: Float = 0f
    var cols: Int = 1; private set
    var rows: Int = 1; private set

    /** Beginn der Zelle c in [items] (`start[c] until start[c + 1]`). */
    var start: IntArray = IntArray(2); private set
    var items: IntArray = IntArray(64); private set
    private var cursor: IntArray = IntArray(1)
    private var rx0 = IntArray(0)
    private var rx1 = IntArray(0)
    private var ry0 = IntArray(0)
    private var ry1 = IntArray(0)

    /** Besuchsmarke je Balken (Duplikate über mehrere Zellen), siehe [nextStamp]. */
    var mark: IntArray = IntArray(0); private set
    private var stamp: Int = 0

    /** Vergrößerung der Balken-Boxen in m. */
    var margin: Float = 0.5f; private set

    /** Anzahl Balken-Slots zum Aufbauzeitpunkt (nur diese sind eingetragen). */
    var builtSize: Int = 0; private set

    private var configuredMap: MapSpec? = null
    private var configuredCell: Float = -1f

    private fun configure(state: GameState) {
        val map = state.map
        val c = state.config.combat.broadphaseCellSize
        if (map === configuredMap && c == configuredCell) return
        configuredMap = map
        configuredCell = c
        cell = if (c > 0.1f) c else 0.1f
        inv = 1f / cell
        minX = map.killMinX
        minY = map.killMinY
        cols = ((map.killMaxX - map.killMinX) * inv).toInt() + 2
        rows = ((map.killMaxY - map.killMinY) * inv).toInt() + 2
        if (cols < 1) cols = 1
        if (rows < 1) rows = 1
        val cells = cols * rows
        start = IntArray(cells + 1)
        cursor = IntArray(cells)
        var maxHalf = 0f
        val mats = state.tables.materials
        for (i in mats.indices) if (mats[i].thickness * 0.5f > maxHalf) maxHalf = mats[i].thickness * 0.5f
        var maxRad = 0f
        val ws = state.tables.weapons
        for (i in ws.indices) if (ws[i].projectileRadius > maxRad) maxRad = ws[i].projectileRadius
        margin = maxHalf + maxRad + 0.05f
    }

    fun cellX(x: Float): Int {
        val f = (x - minX) * inv
        if (!(f >= 0f)) return 0
        val i = f.toInt()
        return if (i >= cols) cols - 1 else i
    }

    fun cellY(y: Float): Int {
        val f = (y - minY) * inv
        if (!(f >= 0f)) return 0
        val i = f.toInt()
        return if (i >= rows) rows - 1 else i
    }

    /** Länge einer Zelle in m (Abfragen zerlegen lange Strahlen in Stücke dieser Länge). */
    val cellSize: Float get() = cell

    /** Neue Besuchsmarke für eine Abfrage. */
    fun nextStamp(): Int {
        stamp++
        if (stamp == Int.MAX_VALUE) {
            mark.fill(0)
            stamp = 1
        }
        return stamp
    }

    /** Zustand, für den das Raster gebaut wurde (null = ungültig). Nur Identität, kein Inhalt. */
    private var builtState: GameState? = null

    /** `BeamPool.nextUid` beim Aufbau: ändert sich bei jedem neuen Balken (Bruchhälften, wiederbelegte Slots). */
    private var builtUid: Int = -1

    /** Verwirft das Raster (Systeme rufen das zu Beginn ihres Schritts). */
    fun invalidate() {
        builtState = null
    }

    /**
     * Stellt sicher, dass das Raster zum Zustand passt: baut neu auf, wenn es verworfen wurde, für einen anderen
     * Zustand gebaut ist oder seit dem Aufbau Balken entstanden sind. Gestorbene Balken prüfen Abfragen selbst
     * (`isAlive`); Knoten bewegen sich innerhalb von WEAPONS/PROJECTILES nicht (Impulse ändern nur `px/py`).
     */
    fun ensure(state: GameState) {
        if (builtState === state && builtUid == state.beams.nextUid) return
        build(state)
    }

    /** Baut das Raster aus allen lebenden Balken neu auf. */
    fun build(state: GameState) {
        configure(state)
        val beams = state.beams
        val nodes = state.nodes
        val n = beams.size
        if (rx0.size < beams.capacity) {
            val cap = beams.capacity
            rx0 = IntArray(cap); rx1 = IntArray(cap); ry0 = IntArray(cap); ry1 = IntArray(cap)
            mark = IntArray(cap)
            stamp = 0
        }
        val cells = cols * rows
        val st = start
        for (c in 0..cells) st[c] = 0
        val m = margin
        var total = 0
        for (j in 0 until n) {
            if (!beams.isAlive(j)) { rx0[j] = 1; rx1[j] = 0; continue }
            val a = beams.a[j]; val b = beams.b[j]
            val ax = nodes.x[a]; val ay = nodes.y[a]; val bx = nodes.x[b]; val by = nodes.y[b]
            val x0 = cellX(FloatMath.min(ax, bx) - m); val x1 = cellX(FloatMath.max(ax, bx) + m)
            val y0 = cellY(FloatMath.min(ay, by) - m); val y1 = cellY(FloatMath.max(ay, by) + m)
            rx0[j] = x0; rx1[j] = x1; ry0[j] = y0; ry1[j] = y1
            for (cy in y0..y1) {
                val row = cy * cols
                for (cx in x0..x1) st[row + cx + 1]++
            }
            total += (x1 - x0 + 1) * (y1 - y0 + 1)
        }
        for (c in 1..cells) st[c] += st[c - 1]
        if (items.size < total) items = IntArray(if (total > items.size * 2) total else items.size * 2)
        val cur = cursor
        for (c in 0 until cells) cur[c] = st[c]
        val it = items
        for (j in 0 until n) {
            val x0 = rx0[j]; val x1 = rx1[j]
            if (x0 > x1) continue
            for (cy in ry0[j]..ry1[j]) {
                val row = cy * cols
                for (cx in x0..x1) it[cur[row + cx]++] = j
            }
        }
        builtSize = n
        builtState = state
        builtUid = beams.nextUid
    }
}
