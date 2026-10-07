package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.StepContext

/**
 * Gemeinsame DERIVED-Daten der Physik-Systeme (PHYSICS, STRAIN_DAMAGE, TOPOLOGY, DEBRIS): Lösungs-Caches,
 * Liste der beweglichen Knoten und Union-Find-Puffer. Alles hier ist aus PERSISTENT-Feldern des [GameState]
 * ableitbar und wird in [rebuild] neu aufgebaut, sobald `state.topologyDirty` gesetzt ist **oder** die Caches
 * nicht zu diesem [GameState] gehören (z. B. ein anderer Zustand nach Restore oder in Tests mit mehreren Zuständen).
 *
 * **Bindung:** Die Welt hält **keine** Referenz auf den Zustand (kein Festhalten einer alten Partie, wenn ein
 * Stepper z. B. in einem ViewModel überlebt). Stattdessen trägt der Zustand eine Kennung des Caches
 * (`BeamPool.solveCacheOwner`/`solveCacheEpoch`, DERIVED), die [rebuild] setzt. Ein [PhysicsWorld] (und damit ein
 * Stepper aus `PhysicsSystems`) ist für **einen** Zustand gedacht: Wird derselbe Stepper abwechselnd mit zwei
 * Zuständen benutzt (KI-Vorausschau, Batch-Läufe), baut er bei jedem Wechsel neu auf. Das ist korrekt (der
 * Neuaufbau ist idempotent: er ändert PERSISTENT-Daten nur, wenn sie ohnehin veraltet sind), aber langsam; dafür
 * je Zustand ein eigenes `PhysicsSystems.all()` anlegen.
 *
 * Arrays wachsen nur in [rebuild] (nie im Substep-Hot-Path).
 */
class PhysicsWorld {
    /** Aufbau-Nummer des letzten [rebuild]; der Zustand trägt sie in `BeamPool.solveCacheEpoch`. */
    private var epoch: Int = 0

    /** DERIVED. XPBD-Compliance je Balken-ID: `restLen / EA`. */
    var compliance = FloatArray(0); private set
    /** DERIVED. Axiale Dämpfung je Balken-ID (Material `damping`). */
    var axialDamping = FloatArray(0); private set
    /** DERIVED. Nur-Zug je Balken-ID (Seil). */
    var tensionOnly = BooleanArray(0); private set

    /** DERIVED. Bewegliche (nicht verankerte, lebende) Knoten in aufsteigender ID. */
    var dynamicNodes = IntArray(0); private set
    var dynamicCount: Int = 0; private set

    private var parent = IntArray(0)
    private var rootAnchored = BooleanArray(0)

    /** Anzahl Topologie-Neuaufbauten (Diagnose/Tests). */
    var rebuilds: Int = 0; private set

    /** Muss vor dem nächsten Physikschritt neu aufgebaut werden? */
    fun needsRebuild(state: GameState): Boolean {
        val b = state.beams
        return state.topologyDirty || b.solveCacheOwner !== this || b.solveCacheEpoch != epoch
    }

    /** Baut neu auf, falls nötig. */
    fun ensure(state: GameState, ctx: StepContext) {
        if (needsRebuild(state)) rebuild(state, ctx)
    }

    /**
     * Topologie-Neuaufbau (Prototyp `purge` + `computeMasses` + `connectivity`), feste Reihenfolge:
     * 1. Geräte auf toten Balken sterben.
     * 2. Unverankerte Knoten ohne lebenden Balken werden freigegeben.
     * 3. Knoten-Adjazenz (CSR) neu.
     * 4. Union-Find über alle lebenden Balken (Balken-ID-Reihenfolge); Komponenten ohne Anker → [NodeFlags.DEBRIS]
     *    bzw. [BeamFlags.DEBRIS]; neu zu Trümmern gewordene Knoten starten mit `debrisTicks = 0`.
     * 5. Geräte auf Trümmer-Balken sterben.
     * 6. Massen: je Balken `0,5 · rest · Dichte` an beide Knoten, je Gerät `Masse · (1 − t)` an A und `Masse · t` an B,
     *    mindestens `SimConfig.minNodeMass`; `invMass = 0` für Anker.
     * 7. Lösungsreihenfolge (aufsteigende Balken-ID) und Material-Caches.
     */
    fun rebuild(state: GameState, ctx: StepContext) {
        rebuilds++
        val nodes = state.nodes
        val beams = state.beams
        val devices = state.devices
        val mats = state.tables.materials
        val devProps = state.tables.devices

        // 1. Geräte auf toten Balken
        val dn = devices.size
        for (d in 0 until dn) {
            if (devices.isAlive(d) && !beams.isAlive(devices.beamId[d])) DeviceKiller.kill(state, ctx, d)
        }

        // 2. Verwaiste Knoten (Zählung über lebende Balken)
        nodes.rebuildAdjacency(beams)
        var purged = false
        val nn = nodes.size
        for (i in 0 until nn) {
            if (nodes.isAlive(i) && !nodes.isAnchored(i) && nodes.beamCountOf[i] == 0) {
                nodes.release(i)
                purged = true
            }
        }
        // 3. Adjazenz (nach dem Freigeben unverändert, da verwaiste Knoten keine Balken haben)
        if (purged) nodes.rebuildAdjacency(beams)

        // 4. Union-Find
        if (parent.size < nodes.capacity) {
            parent = IntArray(nodes.capacity)
            rootAnchored = BooleanArray(nodes.capacity)
        }
        val par = parent
        for (i in 0 until nn) { par[i] = i; rootAnchored[i] = false }
        val bn = beams.size
        for (j in 0 until bn) {
            if (!beams.isAlive(j)) continue
            val ra = find(beams.a[j]); val rb = find(beams.b[j])
            if (ra != rb) par[ra] = rb
        }
        for (i in 0 until nn) if (nodes.isAlive(i) && nodes.isAnchored(i)) rootAnchored[find(i)] = true
        val nflags = nodes.flags
        for (i in 0 until nn) {
            if (!nodes.isAlive(i)) continue
            val r = find(i)
            nodes.component[i] = r
            val debris = !rootAnchored[r]
            val was = (nflags[i] and NodeFlags.DEBRIS) != 0
            if (debris) {
                if (!was) { nodes.debrisTicks[i] = 0; nodes.belowGroundTicks[i] = 0 }
                nflags[i] = nflags[i] or NodeFlags.DEBRIS
            } else {
                nflags[i] = nflags[i] and (NodeFlags.DEBRIS or NodeFlags.GROUNDED).inv()
            }
        }
        val bflags = beams.flags
        for (j in 0 until bn) {
            if (!beams.isAlive(j)) continue
            bflags[j] = if ((nflags[beams.a[j]] and NodeFlags.DEBRIS) != 0) bflags[j] or BeamFlags.DEBRIS
            else bflags[j] and BeamFlags.DEBRIS.inv()
        }

        // 5. Geräte auf Trümmern sterben
        for (d in 0 until dn) {
            if (devices.isAlive(d) && (bflags[devices.beamId[d]] and BeamFlags.DEBRIS) != 0) DeviceKiller.kill(state, ctx, d)
        }

        // 6. Massen
        val mass = nodes.mass
        for (i in 0 until nn) mass[i] = 0f
        for (j in 0 until bn) {
            if (!beams.isAlive(j)) continue
            val m = 0.5f * beams.restLen[j] * mats[beams.materialOf[j]].density
            mass[beams.a[j]] += m
            mass[beams.b[j]] += m
        }
        for (d in 0 until dn) {
            if (!devices.isAlive(d)) continue
            val j = devices.beamId[d]
            if (!beams.isAlive(j)) continue
            val dm = devProps[devices.typeOf[d]].mass
            val t = devices.tOf[d]
            mass[beams.a[j]] += dm * (1f - t)
            mass[beams.b[j]] += dm * t
        }
        val minMass = state.config.minNodeMass
        val inv = nodes.invMass
        if (dynamicNodes.size < nodes.capacity) dynamicNodes = IntArray(nodes.capacity)
        var dc = 0
        for (i in 0 until nn) {
            if (!nodes.isAlive(i)) continue
            if (mass[i] < minMass) mass[i] = minMass
            if (nodes.isAnchored(i)) {
                inv[i] = 0f
            } else {
                inv[i] = 1f / mass[i]
                dynamicNodes[dc++] = i
            }
        }
        dynamicCount = dc

        // 7. Lösungsreihenfolge + Material-Caches
        if (compliance.size < beams.capacity) {
            compliance = FloatArray(beams.capacity)
            axialDamping = FloatArray(beams.capacity)
            tensionOnly = BooleanArray(beams.capacity)
        }
        val order = beams.solveOrder
        var sc = 0
        for (j in 0 until bn) {
            if (!beams.isAlive(j)) continue
            val mat = mats[beams.materialOf[j]]
            compliance[j] = beams.restLen[j] / mat.stiffness
            axialDamping[j] = mat.damping
            tensionOnly[j] = mat.tensionOnly
            if (inv[beams.a[j]] + inv[beams.b[j]] > 0f) order[sc++] = j
        }
        beams.solveCount = sc

        epoch++
        beams.solveCacheOwner = this
        beams.solveCacheEpoch = epoch
        state.topologyDirty = false
    }

    private fun find(i0: Int): Int {
        val par = parent
        var i = i0
        while (par[i] != i) {
            par[i] = par[par[i]]
            i = par[i]
        }
        return i
    }
}
