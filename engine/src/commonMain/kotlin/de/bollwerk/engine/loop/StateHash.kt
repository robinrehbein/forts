package de.bollwerk.engine.loop

import de.bollwerk.engine.sim.BeamRecord
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.Pool

/**
 * FNV-1a (64 Bit) über den gesamten PERSISTENT-Zustand (siehe Feld-Klassen in [Pool]) in fester
 * Index-Reihenfolge: Tick, alle RNG-Ströme, Wind, Ergebnis, Zug, alle Pools (inkl. Generationen und uids;
 * tote Slots nur Flags + Generation), Frei-/Warte-Listen und Spieler (inkl. Tech-Set und Zurück-Journal).
 * Floats gehen bitgenau ein (`toRawBits`). RENDER- und DERIVED-Felder (Interpolations-Startpunkte,
 * Maserung, `lambda`, Komponenten, Adjazenz, Massen, Lösungsreihenfolge, Gerätepositionen, Dehnung)
 * sind ausgenommen.
 */
object StateHash {
    private const val OFFSET: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME: Long = 0x100000001b3L

    fun of(state: GameState): Long {
        val h = Hasher()
        h.long(state.tick)
        for (r in state.rngStreams) h.long(r.state)
        h.float(state.wind)
        when (val r = state.result) {
            GameResult.Ongoing -> h.int(0)
            GameResult.Draw -> h.int(1)
            is GameResult.Winner -> { h.int(2); h.int(r.playerId); h.int(r.reason.ordinal) }
        }
        val turn = state.turn
        h.int(turn.mode.ordinal); h.int(turn.activePlayer)
        h.int(turn.turnNumber); h.int(turn.ticksLeft); h.int(turn.phase.ordinal)

        val n = state.nodes
        poolHeader(h, n)
        for (i in 0 until n.size) {
            if (!slot(h, n, i)) continue
            h.float(n.x[i]); h.float(n.y[i]); h.float(n.px[i]); h.float(n.py[i])
            h.float(n.invMass[i]); h.int(n.ownerOf[i])
            h.int(n.debrisTicks[i]); h.int(n.belowGroundTicks[i])
        }
        freeLists(h, n)

        val b = state.beams
        poolHeader(h, b)
        for (i in 0 until b.size) {
            if (!slot(h, b, i)) continue
            h.int(b.a[i]); h.int(b.b[i]); h.int(b.materialOf[i])
            h.float(b.restLen[i]); h.float(b.hpOf[i]); h.float(b.maxHpOf[i])
            h.float(b.fireOf[i]); h.float(b.fuelOf[i]); h.int(b.ownerOf[i])
            h.int(b.hitCooldownTicks[i]); h.int(b.doorTimerTicks[i])
        }
        freeLists(h, b)

        val d = state.devices
        poolHeader(h, d)
        for (i in 0 until d.size) {
            if (!slot(h, d, i)) continue
            h.int(d.typeOf[i]); h.int(d.beamId[i]); h.float(d.tOf[i])
            h.float(d.hpOf[i]); h.float(d.maxHpOf[i]); h.int(d.ownerOf[i])
            h.float(d.aimAngle[i]); h.float(d.power[i]); h.int(d.reloadTicksOf[i])
            h.int(d.buildTicks[i]); h.int(d.burstLeft[i]); h.int(d.burstTicks[i])
            h.int(d.beamTicksLeft[i])
        }
        freeLists(h, d)

        val p = state.projectiles
        poolHeader(h, p)
        for (i in 0 until p.size) {
            if (!slot(h, p, i)) continue
            h.float(p.x[i]); h.float(p.y[i]); h.float(p.px[i]); h.float(p.py[i])
            h.float(p.vx[i]); h.float(p.vy[i])
            h.int(p.kindOf[i]); h.int(p.ownerOf[i]); h.int(p.ttl[i]); h.int(p.ageTicks[i])
            h.int(p.sourceDevice[i]); h.long(p.ignoreBeamRef[i]); h.int(p.pierceLeft[i])
        }
        freeLists(h, p)

        for (pl in state.players) {
            h.int(pl.id); h.float(pl.metal); h.float(pl.energy)
            h.float(pl.metalCap); h.float(pl.energyCap)
            h.int(pl.techUnlocked.wordCount)
            for (w in 0 until pl.techUnlocked.wordCount) h.long(pl.techUnlocked.word(w))
            h.int(pl.reactorDeviceId); h.int(if (pl.alive) 1 else 0); h.int(pl.facing)
            val j = pl.undoJournal
            h.int(j.size)
            for (k in 0 until j.size) {
                val e = j[k]
                h.int(e.kind.ordinal); h.long(e.tick); h.long(e.ref); h.float(e.metal); h.float(e.energy)
                h.int(e.newNodeRefs.size); for (r in e.newNodeRefs) h.long(r)
                h.int(e.splits.size)
                for (s in e.splits) {
                    h.long(s.nodeRef); h.long(s.halfARef); h.long(s.halfBRef)
                    beamRecord(h, s.original)
                    h.int(s.devices.size)
                    for (dm in s.devices) { h.long(dm.deviceRef); h.float(dm.t) }
                }
            }
        }
        return h.value
    }

    /** Hash als 16-stelliger Hex-String (für Logs und Simrunner). */
    fun hex(hash: Long): String = hash.toULong().toString(16).padStart(16, '0')

    private fun poolHeader(h: Hasher, pool: Pool) {
        h.int(pool.size)
        h.int(pool.nextUid)
    }

    /** Hasht die Pool-Basisfelder des Slots; @return lebt der Slot (dann folgen die Nutzdaten). */
    private fun slot(h: Hasher, pool: Pool, i: Int): Boolean {
        h.int(pool.flags[i])
        h.int(pool.genOf[i])
        if (!pool.isAlive(i)) return false
        h.int(pool.uidOf[i])
        h.long(pool.allocTick[i])
        return true
    }

    private fun beamRecord(h: Hasher, r: BeamRecord) {
        h.long(r.nodeARef); h.long(r.nodeBRef); h.int(r.material); h.int(r.owner)
        h.float(r.restLen); h.float(r.hp); h.float(r.maxHp); h.float(r.fire); h.float(r.fuel)
        h.int(r.flags); h.float(r.texOffset)
    }

    private fun freeLists(h: Hasher, pool: Pool) {
        val f = pool.freeListSnapshot()
        h.int(f.size)
        for (v in f) h.int(v)
        val pend = pool.pendingSnapshot()
        h.int(pend.size)
        for (v in pend) h.int(v)
    }

    /** Inkrementeller FNV-1a-Hasher (byteweise, little-endian). */
    class Hasher {
        var value: Long = OFFSET
            private set

        fun byte(b: Int) {
            value = (value xor (b.toLong() and 0xff)) * PRIME
        }

        fun int(v: Int) {
            byte(v); byte(v ushr 8); byte(v ushr 16); byte(v ushr 24)
        }

        fun long(v: Long) {
            int(v.toInt()); int((v ushr 32).toInt())
        }

        fun float(v: Float) = int(v.toRawBits())
    }
}
