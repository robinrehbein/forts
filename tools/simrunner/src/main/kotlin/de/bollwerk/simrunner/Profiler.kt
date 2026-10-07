package de.bollwerk.simrunner

import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot

/** Wachsende Reihe von Nanosekunden-Messwerten. */
class NanoSeries(initial: Int = 4096) {
    private var data = LongArray(initial)
    var size: Int = 0; private set
    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun sum(): Long { var s = 0L; for (i in 0 until size) s += data[i]; return s }
    fun max(): Long { var m = 0L; for (i in 0 until size) if (data[i] > m) m = data[i]; return m }
    /** p-Quantil (0..1, nearest-rank) oder 0 ohne Werte. */
    fun quantile(p: Double): Long {
        if (size == 0) return 0L
        val c = data.copyOf(size); c.sort()
        val idx = (kotlin.math.ceil(p * size).toInt() - 1).coerceIn(0, size - 1)
        return c[idx]
    }
}

/** Statistik je Slot in Millisekunden pro Tick. */
data class SlotStat(val slot: SystemSlot?, val label: String, val avgMs: Double, val p95Ms: Double, val maxMs: Double, val samples: Int)

/**
 * Misst die Laufzeit je [SystemSlot] (ein Tick = ein Messwert je Slot) und den ganzen Tick. Die Messung
 * umhüllt nur die Systeme (`SimStepper` bleibt unverändert); sie beeinflusst die Simulation nicht.
 */
class Profiler {
    private val perSlot = Array(SystemSlot.entries.size) { NanoSeries() }
    private val installed = BooleanArray(SystemSlot.entries.size)
    val wholeTick = NanoSeries()

    fun wrap(slot: SystemSlot, inner: SimSystem): SimSystem {
        installed[slot.ordinal] = true
        val series = perSlot[slot.ordinal]
        return SimSystem { state, ctx ->
            val t0 = System.nanoTime()
            inner.step(state, ctx)
            series.add(System.nanoTime() - t0)
        }
    }

    /** Eine Zeile je installiertem Slot (in Slot-Reihenfolge), zuletzt die Gesamtzeit je Tick. */
    fun stats(): List<SlotStat> {
        val out = ArrayList<SlotStat>()
        for (slot in SystemSlot.entries) {
            if (!installed[slot.ordinal]) continue
            out.add(stat(slot, slot.name, perSlot[slot.ordinal]))
        }
        out.add(stat(null, "TICK (gesamt)", wholeTick))
        return out
    }

    private fun stat(slot: SystemSlot?, label: String, s: NanoSeries): SlotStat {
        val n = s.size
        return SlotStat(slot, label, if (n == 0) 0.0 else s.sum() / 1e6 / n, s.quantile(0.95) / 1e6, s.max() / 1e6, n)
    }
}
