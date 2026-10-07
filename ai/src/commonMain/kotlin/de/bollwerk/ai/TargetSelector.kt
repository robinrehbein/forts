package de.bollwerk.ai

import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/** Ausgewähltes Ziel einer Waffe samt Schusslösung. */
class TargetChoice {
    var targetId: Int = -1
    var value: Float = 0f
    var score: Float = 0f
    val solution: AimSolution = AimSolution()

    fun reset() { targetId = -1; value = 0f; score = 0f; solution.reset() }
}

/**
 * Zielwahl der KI: Bewertung `Wert × Freiliegen / Entfernung` je gegnerischem Gerät.
 * - Wert nach Rolle ([valueOf]): Reaktor 10, Waffen 6, Minen 4, Techgebäude 3, Sonstiges 2. Hat der Gegner keine Waffe
 *   mehr, zählt der Reaktor [FINISH_REACTOR_BONUS]-fach; ab Minute 4 steigt sein Wert ohnehin langsam bis zum selben
 *   Faktor (Endspiel-Druck gegen endlose Waffen-Duelle). Der Zuschlag gilt nur für Bahnen, die den Reaktor mindestens
 *   per Splash erreichen ([REACTOR_BONUS_MIN_EXPOSURE]); in Panzerung zu graben lohnt gegen 50 %/s Reparatur nicht.
 * - Freiliegen: Hindernisfreiheit der tatsächlichen Schussbahn ([AimController]: abgetastete Bogen-/Linienbahn gegen
 *   Balken und Geräte), 0..1.
 * - Entfernung: `1 + d / 40 m` im Nenner.
 * Vorauswahl: die [AiTuning.candidateTargets] Geräte mit der besten Bewertung `Wert / Entfernung` werden genau
 * bewertet (Rechenzeit). Rauschen auf die Bewertung ([AiTuning.targetNoise]) senkt die Entscheidungsqualität (Leicht).
 */
class TargetSelector(private val aim: AimController) {
    private var ids = IntArray(32)
    private var pre = FloatArray(32)
    private val scratch = AimSolution()
    private val geo = FloatArray(DeviceGeometry.SIZE)

    /**
     * Bestes Ziel für Waffe [deviceId]. @return `false`, wenn kein Ziel im Bereich lösbar ist.
     */
    fun choose(
        view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps, tuning: AiTuning, rng: AiRng,
        out: TargetChoice,
    ): Boolean {
        out.reset()
        val d = view.deviceView
        val beams = view.beamView
        val tables = view.tables
        DeviceGeometry.mount(view, deviceId, geo)
        val px = geo[DeviceGeometry.X]; val py = geo[DeviceGeometry.Y]
        // Endspiel: Hat der Gegner keine Waffe mehr, zählt vor allem der Reaktor
        var enemyWeapons = 0
        for (i in 0 until d.size) {
            if (!d.isAlive(i)) continue
            val owner = d.owner(i)
            val ty = d.type(i)
            if (owner != me && owner >= 0 && ty >= 0 && ty < tables.devices.size && tables.devices[ty].role == DeviceRole.WEAPON) enemyWeapons++
        }
        // Endspiel-Druck: Je länger die Partie, desto mehr zählt der Reaktor (sonst können sich zwei KIs endlos
        // gegenseitig die wieder aufgebauten Waffen abschießen)
        val late = FloatMath.clamp((view.tick - LATE_GAME_START_TICKS).toFloat() / LATE_GAME_RAMP_TICKS, 0f, 1f)
        val pressure = 1f + (LATE_GAME_REACTOR_BONUS - 1f) * late
        val reactorBonus = FloatMath.max(pressure, if (enemyWeapons == 0) FINISH_REACTOR_BONUS else 1f)
        // Belagerung: Ab [SIEGE_START_TICKS] lohnt auch das Graben zum Reaktor (die Deckung davor wird gebündelt zerlegt)
        // (nur Waffen mit Explosion: Hitscan-Treffer in der Deckung heilt die Reparatur sofort)
        val siege = if (weapon.splashRadius > 0f) siegeFactor(view.tick) else 0f
        val digBonus = 1f + (SIEGE_REACTOR_BONUS - 1f) * siege
        var n = 0
        for (i in 0 until d.size) {
            if (!d.isAlive(i)) continue
            val owner = d.owner(i)
            if (owner == me || owner < 0) continue
            val ty = d.type(i)
            if (ty < 0 || ty >= tables.devices.size) continue
            val b = d.beam(i)
            if (b < 0 || !beams.isAlive(b) || (beams.flags(b) and BeamFlags.DEBRIS) != 0) continue
            DeviceGeometry.mount(view, i, geo)
            val dx = geo[DeviceGeometry.CX] - px; val dy = geo[DeviceGeometry.CY] - py
            val dist = sqrt(dx * dx + dy * dy)
            if (weapon.maxRange > 0f && dist > weapon.maxRange + RANGE_SLACK) continue
            if (n == ids.size) { ids = ids.copyOf(n * 2); pre = pre.copyOf(n * 2) }
            ids[n] = i
            val role = tables.devices[ty].role
            pre[n] = valueOf(role) * (if (role == DeviceRole.REACTOR) FloatMath.max(reactorBonus, digBonus) else 1f) / distanceFactor(dist)
            n++
        }
        if (n == 0) return false
        // Teil-Selektionssortierung der besten Kandidaten (stabil: bei Gleichstand die kleinere Slot-ID)
        val limit = if (tuning.candidateTargets < n) tuning.candidateTargets else n
        for (k in 0 until limit) {
            var best = k
            for (m in k + 1 until n) if (pre[m] > pre[best]) best = m
            if (best != k) {
                val ti = ids[k]; ids[k] = ids[best]; ids[best] = ti
                val tp = pre[k]; pre[k] = pre[best]; pre[best] = tp
            }
        }
        for (k in 0 until limit) {
            // Ohne Rauschen ist `pre` (Freiliegen 1) eine obere Schranke der Bewertung: Die Liste ist absteigend sortiert,
            // also kann kein weiterer Kandidat das gefundene Ziel noch schlagen (Rechenzeit; kleiner Zuschlag, weil die
            // Vorauswahl ab dem Montagepunkt statt ab dem Drehpunkt misst).
            if (tuning.targetNoise <= 0f && out.targetId >= 0 && out.score >= pre[k] * BOUND_SLACK) break
            val t = ids[k]
            if (!aim.evaluate(view, me, deviceId, props, weapon, t, tuning, scratch)) continue
            val role = tables.devices[d.type(t)].role
            // Reaktor-Zuschlag nur, wenn der Schuss ihn auch erreicht (Splash/frei): Graben in Panzerung ist zwecklos
            var exposure = scratch.exposure
            var rb = reactorBonus
            if (role == DeviceRole.REACTOR && exposure < REACTOR_BONUS_MIN_EXPOSURE) {
                // Belagerung: Einschlag in der gegnerischen Deckung vor dem Reaktor zählt als Graben
                if (siege > 0f && digsTowards(view, t, scratch)) { exposure = FloatMath.max(exposure, AimController.DIGGING); rb = digBonus } else rb = 1f
            }
            val value = valueOf(role) * (if (role == DeviceRole.REACTOR) rb else 1f)
            var score = score(value, exposure, scratch.distance)
            if (tuning.targetNoise > 0f) {
                score *= 1f + tuning.targetNoise * rng.gaussian()
                if (score < 0f) score = 0f
            }
            if (out.targetId < 0 || score > out.score) {
                out.targetId = t
                out.value = value
                out.score = score
                out.solution.copyFrom(scratch)
            }
        }
        return out.targetId >= 0
    }

    /**
     * Gräbt die Bahn [sol] zum Gerät [targetId]: Einschlag auf einem gegnerischen Balken (Freiliegen > 0) höchstens
     * [SIEGE_DIG_DISTANCE] vom Gerät entfernt (die Deckung direkt vor dem Reaktor, z. B. die Torwand)?
     */
    fun digsTowards(view: GameView, targetId: Int, sol: AimSolution): Boolean {
        if (!(sol.exposure > 0f)) return false
        DeviceGeometry.mount(view, targetId, geo)
        val dx = sol.impactX - geo[DeviceGeometry.CX]; val dy = sol.impactY - geo[DeviceGeometry.CY]
        return dx * dx + dy * dy <= SIEGE_DIG_DISTANCE * SIEGE_DIG_DISTANCE
    }

    companion object {
        private const val RANGE_SLACK = 4f
        private const val DISTANCE_SCALE = 40f
        private const val BOUND_SLACK = 1.05f
        /** Faktor auf den Reaktor-Wert, wenn der Gegner keine Waffe mehr hat. */
        const val FINISH_REACTOR_BONUS: Float = 3f
        /** Ab hier (4 min) steigt der Reaktor-Wert linear über [LATE_GAME_RAMP_TICKS] bis zum Faktor [LATE_GAME_REACTOR_BONUS]. */
        const val LATE_GAME_START_TICKS: Long = 4L * 60L * 60L
        const val LATE_GAME_RAMP_TICKS: Float = 3f * 60f * 60f
        const val LATE_GAME_REACTOR_BONUS: Float = 3f
        /** Der Reaktor-Zuschlag gilt nur für Schüsse, deren Einschlag ihn mindestens per Splash erreicht. */
        const val REACTOR_BONUS_MIN_EXPOSURE: Float = 0.5f

        /**
         * Belagerung gegen Patt: Ab hier (5 min) steigt der Reaktor-Wert auch für grabende Bahnen (Einschlag in der
         * Deckung höchstens [SIEGE_DIG_DISTANCE] vor dem Reaktor, siehe [digsTowards]) linear über [SIEGE_RAMP_TICKS] bis zum Faktor
         * [SIEGE_REACTOR_BONUS]. Zwei vollständig gebaute Festungen schießen sich sonst endlos die wieder aufgebauten
         * Waffen ab, während der gepanzerte Reaktor nie in Reichweite kommt.
         */
        const val SIEGE_START_TICKS: Long = 5L * 60L * 60L
        const val SIEGE_RAMP_TICKS: Float = 1f * 60f * 60f
        const val SIEGE_REACTOR_BONUS: Float = 3f
        /** Einschläge bis zu diesem Abstand vom Reaktor (m) graben auf ihn zu (Torwand ~7,5 m vor dem Reaktor). */
        const val SIEGE_DIG_DISTANCE: Float = 7f

        /** 0..1: Fortschritt der Belagerung (0 vor [SIEGE_START_TICKS]). */
        fun siegeFactor(tick: Long): Float = FloatMath.clamp((tick - SIEGE_START_TICKS).toFloat() / SIEGE_RAMP_TICKS, 0f, 1f)

        /** Zielwert nach Rolle (Reaktor 10, Waffen 6, Minen 4, Tech 3, sonst 2). */
        fun valueOf(role: DeviceRole): Float = when (role) {
            DeviceRole.REACTOR -> 10f
            DeviceRole.WEAPON -> 6f
            DeviceRole.MINE -> 4f
            DeviceRole.TECH -> 3f
            else -> 2f
        }

        fun distanceFactor(dist: Float): Float = 1f + dist / DISTANCE_SCALE

        /** `Wert × Freiliegen / Entfernungsfaktor`. */
        fun score(value: Float, exposure: Float, dist: Float): Float = value * exposure / distanceFactor(dist)
    }
}
