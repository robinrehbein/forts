package de.bollwerk.app.tutorial

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.rules.BeamPlanner
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.GameView
import kotlin.math.abs
import kotlin.math.sqrt

/** Punkt in Welt-Metern (y wächst nach unten). */
data class WorldPoint(val x: Float, val y: Float)

/**
 * Orte in der Spielwelt, auf die der Coach-Mark zeigt. Entstehen einmal je Tutorial-Partie aus dem eingeschwungenen
 * Anfangszustand ([find]); die Festung des Spielers ist verankert, die Punkte bleiben also stabil.
 *
 * @property beamA @property beamB Knotenpaar der eigenen Festung, zwischen dem ein neuer Balken gültig ist (Schritt 1).
 * @property mineSpot Punkt über dem eigenen Erzvorkommen, an dem ein Tipp die Mine setzt (Schritt 2); [ore] liegt darunter im Boden.
 * @property facing +1 = der Spieler schießt nach rechts, −1 nach links (Richtung der gezeigten Zielgeste).
 */
data class TutorialTargets(
    val beamA: WorldPoint,
    val beamB: WorldPoint,
    val ore: WorldPoint,
    val mineSpot: WorldPoint,
    val facing: Int = 1,
) {
    companion object {
        /** Abstand des Tipp-Punkts über dem Erz (m): über dem Balken, damit die Mine oben montiert wird. */
        const val MINE_SPOT_RISE = 1.1f

        private const val MIN_LEN = 2.5f
        private const val MAX_LEN = 5.2f
        private const val IDEAL_LEN = 4f

        /**
         * Sucht die Ziele für Spieler [player] im Zustand [view]. Das Knotenpaar ist ein **gültiger** Balken laut echtem
         * Validator (Holz, Metall reicht, nicht doppelt, Gelände frei), wenig kreuzend und möglichst hoch gelegen.
         * `null`, wenn die Karte kein Erz für [player] hat oder keine Balken-Kandidaten bleiben.
         */
        fun find(view: GameView, player: Int, woodMaterial: Int): TutorialTargets? {
            val ore = view.map.ores.firstOrNull { it.owner == player } ?: return null
            val ground = view.terrain.heightAt(ore.x)
            val pair = findBeamPair(view, player, woodMaterial) ?: return null
            val facing = view.player(player).facing
            return TutorialTargets(
                beamA = pair.first, beamB = pair.second,
                ore = WorldPoint(ore.x, ground), mineSpot = WorldPoint(ore.x, ground - MINE_SPOT_RISE),
                facing = if (facing < 0) -1 else 1,
            )
        }

        private fun findBeamPair(view: GameView, player: Int, wood: Int): Pair<WorldPoint, WorldPoint>? {
            val nodes = view.nodeView
            val beams = view.beamView
            val own = ArrayList<Int>()
            for (i in 0 until nodes.size) {
                if (nodes.isAlive(i) && nodes.owner(i) == player && (nodes.flags(i) and NodeFlags.DEBRIS) == 0) own.add(i)
            }
            var best: Pair<Int, Int>? = null
            var bestScore = Float.MAX_VALUE
            for (ia in own.indices) for (ib in ia + 1 until own.size) {
                val a = own[ia]
                val b = own[ib]
                val ax = nodes.x(a); val ay = nodes.y(a); val bx = nodes.x(b); val by = nodes.y(b)
                val len = dist(ax, ay, bx, by)
                if (len < MIN_LEN || len > MAX_LEN) continue
                if (BeamPlanner.beamBetween(view, a, b)) continue
                val cmd = Command.PlaceBeam(view.tick, player, aNodeRef = nodes.ref(a), bNodeRef = nodes.ref(b), materialId = wood)
                if (RulesValidator.validate(view, cmd) != null) continue
                var crossings = 0
                for (k in 0 until beams.size) {
                    if (!beams.isAlive(k) || (beams.flags(k) and BeamFlags.DEBRIS) != 0) continue
                    val na = beams.nodeA(k); val nb = beams.nodeB(k)
                    if (na == a || na == b || nb == a || nb == b) continue
                    if (segmentsCross(ax, ay, bx, by, nodes.x(na), nodes.y(na), nodes.x(nb), nodes.y(nb))) crossings++
                }
                // Hoch gelegen (kleines y) und nahe an der Idealgröße; Kreuzungen wiegen am schwersten
                val score = crossings * 1000f + abs(len - IDEAL_LEN) + (ay + by) * 0.5f
                if (score < bestScore) { bestScore = score; best = a to b }
            }
            val (a, b) = best ?: return null
            // Linker Knoten zuerst (stabile Reihenfolge für Anzeige und Tests)
            val pa = WorldPoint(nodes.x(a), nodes.y(a))
            val pb = WorldPoint(nodes.x(b), nodes.y(b))
            return if (pa.x <= pb.x) pa to pb else pb to pa
        }

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            return sqrt(dx * dx + dy * dy)
        }

        /** Schneiden sich die Strecken echt (nicht nur an Endpunkten)? */
        private fun segmentsCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
            fun orient(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
            val d1 = orient(ax, ay, bx, by, cx, cy)
            val d2 = orient(ax, ay, bx, by, dx, dy)
            val d3 = orient(cx, cy, dx, dy, ax, ay)
            val d4 = orient(cx, cy, dx, dy, bx, by)
            return ((d1 > 1e-4f && d2 < -1e-4f) || (d1 < -1e-4f && d2 > 1e-4f)) &&
                ((d3 > 1e-4f && d4 < -1e-4f) || (d3 < -1e-4f && d4 > 1e-4f))
        }
    }
}
