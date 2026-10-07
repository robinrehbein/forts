package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.Palette
import kotlin.math.atan2

/**
 * Knoten/Gelenke (Stil-Bibel §4): 0,42 m, Holz = Eisenlasche mit 3 Bolzen (dreht mit dem dicksten Balken),
 * Metall = sechseckiges Knotenblech mit Mittelbolzen, Fundament = Ankerbolzenkopf; leichter Schlagschatten
 * (2 dp, 30 %, ungedreht nach unten rechts, vom Maler vor dem Sprite gezeichnet). Nie größer als 1,4 × der dickste angeschlossene Balken. Reine Seil-Enden bekommen keinen Knoten.
 */
internal class JointPainter(private val c: SceneContext) {
    private var thick = FloatArray(0)
    private var metal = BooleanArray(0)
    private var ang = FloatArray(0)

    fun drawAll(snap: FrameSnapshot) {
        val n = snap.nodeCount
        if (thick.size < n) { thick = FloatArray(maxOf(n, thick.size * 2, 64)); metal = BooleanArray(thick.size); ang = FloatArray(thick.size) }
        for (i in 0 until n) { thick[i] = 0f; metal[i] = false; ang[i] = 0f }
        for (b in 0 until snap.beamCount) {
            if ((snap.beamFlags[b] and BeamFlags.ALIVE) == 0) continue
            val mat = snap.beamMaterial[b]
            if (mat < 0 || mat >= c.matKind.size) continue
            val th = c.matThick[mat]
            val mk = c.matKind[mat]
            val isMetal = mk == MatKind.METAL || mk == MatKind.ARMOUR || mk == MatKind.DOOR
            val a = snap.beamA[b]; val e = snap.beamB[b]
            val dx = c.ix[e] - c.ix[a]; val dy = c.iy[e] - c.iy[a]
            val an = atan2(dy, dx)
            if (isMetal) { metal[a] = true; metal[e] = true }
            if (th > thick[a]) { thick[a] = th; ang[a] = an }
            if (th > thick[e]) { thick[e] = th; ang[e] = an }
        }
        val sink = c.sink
        val minX = c.visMinX(1f); val maxX = c.visMaxX(1f); val minY = c.visMinY(1f); val maxY = c.visMaxY(1f)
        // Pass 0: Schlagschatten aller Knoten (liegt über den Balken, unter allen Knoten-Sprites), Pass 1: Sprites
        for (pass in 0 until 2) {
            for (i in 0 until n) {
                val f = snap.nodeFlags[i]
                if ((f and NodeFlags.ALIVE) == 0) continue
                val anchored = (f and NodeFlags.ANCHORED) != 0
                val mt = thick[i]
                if (mt == 0f && !anchored) continue
                if (mt < 0.2f && !anchored) continue // reine Seil-Enden
                val x = c.ix[i]; val y = c.iy[i]
                if (x < minX || x > maxX || y < minY || y > maxY) continue
                val spr = if (anchored) c.texJointAnchor else if (metal[i]) c.texJointMetal else c.texJointWood
                if (spr < 0) continue
                var sz = c.jointWorld
                if (mt in 0.001f..0.2999f && !anchored) sz *= maxOf(0.75f, mt * 1.4f / 0.42f)
                val rotated = !anchored && !metal[i]
                if (pass == 0) {
                    shadow(x, y, sz / c.jointWorld, anchored, metal[i], ang[i])
                } else if (rotated) {
                    sink.save()
                    sink.translate(x, y)
                    sink.rotate(ang[i])
                    sink.image(spr, -sz / 2f, -sz / 2f, sz, sz)
                    sink.restore()
                } else {
                    sink.image(spr, x - sz / 2f, y - sz / 2f, sz, sz)
                }
            }
        }
    }

    /** Schlagschatten (Stil-Bibel §4: 2 dp nach unten rechts, 30 %); [k] skaliert dünne Knoten. */
    private fun shadow(x: Float, y: Float, k: Float, anchored: Boolean, isMetal: Boolean, angle: Float) {
        val sink = c.sink
        val off = 2f * c.dp
        val col = Palette.withAlpha(Palette.BLACK, 0.3f)
        val sx = x + off; val sy = y + off
        if (anchored) {
            sink.fillCircle(sx, sy, ANCHOR_R * k, col)
        } else if (isMetal) {
            val r = HEX_R * k
            val ap = r * 0.8660254f
            val p = c.poly2
            p[0] = sx; p[1] = sy - r
            p[2] = sx + ap; p[3] = sy - r * 0.5f
            p[4] = sx + ap; p[5] = sy + r * 0.5f
            p[6] = sx; p[7] = sy + r
            p[8] = sx - ap; p[9] = sy + r * 0.5f
            p[10] = sx - ap; p[11] = sy - r * 0.5f
            sink.fillPolygon(p, 6, col)
        } else {
            sink.save()
            sink.translate(sx, sy)
            sink.rotate(angle)
            val n = c.roundRectPoly(-WOOD_W * k, -WOOD_H * k, 2f * WOOD_W * k, 2f * WOOD_H * k, 0.05f * k)
            sink.fillPolygon(c.poly, n, col)
            sink.restore()
        }
    }

    private companion object {
        // Maße der Sprites (ProceduralTextures.jointWood/jointMetal/jointAnchor) in Metern
        const val WOOD_W = ProceduralTextures.JOINT_D * 0.5f
        const val WOOD_H = 0.16f
        const val HEX_R = ProceduralTextures.JOINT_D * 0.5f
        const val ANCHOR_R = ProceduralTextures.JOINT_D * 0.5f
    }
}
