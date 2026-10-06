package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.BreakCause
import kotlin.math.sqrt

/**
 * [de.bollwerk.engine.sim.SystemSlot.STRAIN_DAMAGE] (Prototyp: Abschnitt "Dehnung & Schaden" in `physicsTick`).
 * Nach den Substeps: Dehnung `(len − rest) / rest` (DERIVED `strainOf`), Knarzen (RENDER `creaking`, über
 * `SimConfig.creakRatio` der Grenze, nicht für Trümmer), Trümmer-Sperrzeit herunterzählen, Überlastschaden
 * `hp −= Überschuss · strainDamage · maxHp · dt` und Bruch bei `hp ≤ 0` (zufällige Stelle, [BreakCause.STRAIN]).
 * Grenzen aus `MaterialProps.tensionLimit`/`compressionLimit`; Druck bei `tensionOnly` (Seil) sowie Grenzen
 * `≤ 0` oder `+∞` gelten als unbegrenzt (kein Knarzen, kein Schaden).
 */
class StrainDamageSystem : SimSystem {
    /** Größtes Dehnungsverhältnis |Dehnung| / Grenze nicht-Trümmer-Balken im letzten Tick (Diagnose/HUD). */
    var maxStrainRatio: Float = 0f
        private set

    override fun step(state: GameState, ctx: StepContext) {
        val cfg = state.config
        val mats = state.tables.materials
        val beams = state.beams
        val nodes = state.nodes
        val dt = cfg.dt
        val creak = cfg.creakRatio
        var maxR = 0f
        val n = beams.size
        for (j in 0 until n) {
            if (!beams.isAlive(j)) continue
            val ia = beams.a[j]; val ib = beams.b[j]
            val dx = nodes.x[ib] - nodes.x[ia]
            val dy = nodes.y[ib] - nodes.y[ia]
            val len = sqrt(dx * dx + dy * dy)
            val rest = beams.restLen[j]
            val st = if (rest > 0f) (len - rest) / rest else 0f
            beams.strainOf[j] = st
            val mat = mats[beams.materialOf[j]]
            val abs = if (st < 0f) -st else st
            // Unbegrenzt (kein Knarzen, kein Schaden): Druck bei Nur-Zug-Material (Seil, Prototyp `comp: Infinity`)
            // sowie jede Grenze ≤ 0 oder +∞ (Content speichert "keine Grenze" teils als 0).
            val lim = if (st >= 0f) mat.tensionLimit else if (mat.tensionOnly) Float.POSITIVE_INFINITY else mat.compressionLimit
            val limited = lim > 0f && lim != Float.POSITIVE_INFINITY
            val ratio = if (limited) abs / lim else 0f
            val debris = (beams.flags[j] and BeamFlags.DEBRIS) != 0
            beams.creaking[j] = ratio > creak && !debris
            if (!debris && ratio > maxR) maxR = ratio
            if (beams.hitCooldownTicks[j] > 0) beams.hitCooldownTicks[j]--
            if (beams.isNew(j)) continue
            val ex = if (limited) abs - lim else 0f
            if (ex > 0f) {
                beams.hpOf[j] -= ex * cfg.strainDamage * beams.maxHpOf[j] * dt
                if (beams.hpOf[j] <= 0f) {
                    BeamBreaker.breakBeam(state, ctx, j, BeamBreaker.randomT(state), BreakCause.STRAIN)
                }
            }
        }
        maxStrainRatio = maxR
    }
}
