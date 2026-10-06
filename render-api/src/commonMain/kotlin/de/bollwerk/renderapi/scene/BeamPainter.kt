package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.Palette
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Balken nach Material (Stil-Bibel §4/§5): Holz (2 Planken, Maserung, Astlöcher, AO), Metall (Doppel-T,
 * Nieten), Panzer (Platten, Bolzen, Rost), Seil (gedreht, mit Durchhang), Tür (Scharnier, Warnstreifen,
 * offen/geschlossen). Texturen folgen der Drehung (Zeichnung im Balken-Koordinatensystem), Schadensstufen,
 * Brand/Verkohlung, Brandspuren, Knarzen und die Dehnungsansicht.
 */
internal class BeamPainter(private val c: SceneContext, private val fx: FxState) {
    private val rng = RenderRng(1)
    private val heatGrad = intArrayOf(0, 0)
    private val fireGrad = IntArray(2)

    fun drawAll(snap: FrameSnapshot) {
        val minX = c.visMinX(2f); val maxX = c.visMaxX(2f); val minY = c.visMinY(2f); val maxY = c.visMaxY(2f)
        for (i in 0 until snap.beamCount) {
            if ((snap.beamFlags[i] and BeamFlags.ALIVE) == 0) continue
            val a = snap.beamA[i]; val b = snap.beamB[i]
            val x0 = c.ix[a]; val y0 = c.iy[a]; val x1 = c.ix[b]; val y1 = c.iy[b]
            // Culling gegen das Begrenzungsrechteck (Seil: Durchhang bis ~8 % der Länge)
            val lo = if (x0 < x1) x0 else x1; val hi = if (x0 < x1) x1 else x0
            val tp = if (y0 < y1) y0 else y1; val bt = if (y0 < y1) y1 else y0
            if (hi < minX || lo > maxX || bt + 1.5f < minY || tp - 1f > maxY) continue
            drawBeam(snap, i, x0, y0, x1, y1)
        }
    }

    /** Nach Beschädigungsstufe: 0 intakt, 1 Haarrisse/Dellen, 2 abgesplittert/verbogen, 3 kritisch. */
    private fun stageOf(hp01: Float): Int = if (hp01 < 0.15f) 3 else if (hp01 < 0.4f) 2 else if (hp01 < 0.7f) 1 else 0

    private fun drawBeam(snap: FrameSnapshot, i: Int, ax: Float, ay: Float, bx: Float, by: Float) {
        val mat = snap.beamMaterial[i]
        if (mat < 0 || mat >= c.matKind.size) return
        val mk = c.matKind[mat]
        val th = c.matThick[mat]
        val dx = bx - ax; val dy = by - ay
        val l = sqrt(dx * dx + dy * dy)
        if (l < 1e-4f) return
        if (mk == MatKind.ROPE) { drawRope(snap, i, ax, ay, bx, by, l, th); return }
        val sink = c.sink
        val px = c.px
        val uid = snap.beamUid[i]
        val stage = stageOf(snap.beamHp01[i])
        val ang = atan2(dy, dx)
        sink.save()
        sink.translate(ax, ay)
        sink.rotate(ang)
        val flags = snap.beamFlags[i]
        if (snap.beamLoad01[i] > 0.8f && (flags and BeamFlags.DEBRIS) == 0 && !c.reduced) {
            // Knarzen: leichtes Zittern ab 80 % Bruchlast
            sink.translate(sin(c.time * 71f + uid) * px * 0.8f, sin(c.time * 53f + uid * 3f) * px * 1.1f)
        }
        if (mk == MatKind.DOOR) {
            drawDoor(snap, i, l, th, ang, stage)
            sink.restore()
            return
        }
        drawBody(snap, i, mk, l, th, stage)
        sink.restore()
    }

    private fun texOf(mk: Int): Int = when (mk) {
        MatKind.WOOD -> c.texWood
        MatKind.METAL -> c.texMetal
        MatKind.ARMOUR -> c.texArmour
        else -> c.texDoor
    }

    private fun periodOf(mk: Int): Float = when (mk) {
        MatKind.WOOD -> ProceduralTextures.WOOD_LEN.toFloat()
        MatKind.METAL -> ProceduralTextures.METAL_LEN.toFloat()
        MatKind.ARMOUR -> ProceduralTextures.ARMOUR_LEN.toFloat()
        else -> ProceduralTextures.DOOR_LEN.toFloat()
    }

    /** Textur kachelnd entlang x ∈ [x0, x1] (Balkenkoordinaten), Phase aus [off] (Maserungs-Versatz je Balken). */
    private fun strip(tex: Int, period: Float, off: Float, x0: Float, x1: Float, y: Float, h: Float) {
        if (tex < 0 || x1 <= x0) return
        val sink = c.sink
        val eps = c.px * 0.5f
        var pos = x0
        var guard = 0
        while (pos < x1 - 1e-5f && guard < 16) {
            val u = SceneContext.fract((pos + off) / period)
            var seg = (1f - u) * period
            if (seg < 1e-4f) seg = period
            val last = pos + seg >= x1
            if (last) seg = x1 - pos
            sink.imageRegion(tex, u, 0f, u + seg / period, 1f, pos, y, if (last) seg else seg + eps, h)
            pos += seg
            guard++
        }
    }

    private fun drawBody(snap: FrameSnapshot, i: Int, mk: Int, l: Float, th: Float, stage: Int) {
        val sink = c.sink
        val px = c.px
        val uid = snap.beamUid[i]
        val off = snap.beamTexOffset[i]
        val flags = snap.beamFlags[i]
        val t2 = th * 0.5f
        val wood = mk == MatKind.WOOD
        val jagA = (flags and BeamFlags.JAG_A) != 0
        val jagB = (flags and BeamFlags.JAG_B) != 0
        val tex = texOf(mk)
        val per = periodOf(mk)
        val bow = if (!wood && stage >= 2) th * 0.55f else 0f
        // Körper (bei Splitterenden um die Zacken verkürzt, die als Polygon darübergelegt werden)
        val xa = if (wood && jagA) 0.08f else 0f
        val xb = if (wood && jagB) l - 0.08f else l

        val fl = fx.beamFlash[i]
        if (bow > 0f) {
            // Auflagen (Abdunklung, Dehnung, Treffer-Blitz, rote Warnkontur) laufen mit dem Körper mit
            val tint = if (stage >= 2) Palette.withAlpha(Palette.SOOT, 0.322f) else 0
            val strain = if (c.strainView) SceneContext.withAlpha(SceneContext.heat(snap.beamLoad01[i] * 1.6f), 0.82f) else 0
            val flash = if (fl > 0f) SceneContext.a(Palette.WHITE, fl * 0.55f) else 0
            val pulse = if (stage == 3) SceneContext.a(Palette.CRITICAL, 0.35f + 0.55f * (if (c.reduced) 0.6f else 0.5f + 0.5f * sin(c.time * 4.2f + uid))) else 0
            drawBent(tex, per, off, l, th, bow, tint, strain, flash, pulse)
        } else {
            sink.fillRect(xa - px, -t2 - px, (xb - xa) + 2f * px, th + 2f * px, SceneContext.OUTLINE_BEAM)
            strip(tex, per, off, xa, xb, -t2, th)
        }

        // Splitter-/Scherenden (Bruchkanten)
        if (jagA) drawJag(l = l, th = th, atB = false, wood = wood, uid = uid, mk = mk)
        if (jagB) drawJag(l = l, th = th, atB = true, wood = wood, uid = uid, mk = mk)

        // Schäden (Stil-Bibel §5)
        if (stage >= 1) drawDamage(uid, l, th, stage, wood, bow)
        if (stage >= 2 && wood) drawNotches(uid, l, th)

        // Brandspuren
        val sc = fx.scorchCount[i]
        if (sc > 0 && c.texScorch >= 0) {
            for (k in 0 until sc) {
                val r = fx.scorchR[i * 3 + k]
                val cx = fx.scorchT[i * 3 + k] * l
                sink.image(c.texScorch, cx - r, -t2 - th * 0.2f + bendY(cx, l, bow), 2f * r, th * 1.4f, 0.85f)
            }
        }
        // Brand / Verkohlung
        val fire = snap.beamFire01[i]
        val fuel = snap.beamFuel01[i]
        if (wood && (fire > 0f || fuel < 1f)) drawCharring(uid, l, th, fire, fuel)

        if (bow <= 0f) {
            if (c.strainView) {
                sink.fillRect(0f, -t2, l, th, SceneContext.withAlpha(SceneContext.heat(snap.beamLoad01[i] * 1.6f), 0.82f))
            }
            if (fl > 0f) sink.fillRect(0f, -t2, l, th, SceneContext.a(Palette.WHITE, fl * 0.55f))
        }

        // Lichtkante oben (1 px), Schatten unten
        if (bow <= 0f) {
            sink.fillRect(0f, -t2, l, px, if (wood) Palette.withAlpha(Palette.CREAM, 0.38f) else Palette.withAlpha(Palette.STEEL_WHITE, 0.322f))
            sink.fillRect(0f, t2 - px, l, px, Palette.withAlpha(Palette.BLACK, 0.251f))
        }
        if (stage == 3 && bow <= 0f) {
            val pu = if (c.reduced) 0.6f else 0.5f + 0.5f * sin(c.time * 4.2f + uid)
            sink.strokeRect(0f, -t2, l, th, px * 2.2f, SceneContext.a(Palette.CRITICAL, 0.35f + 0.55f * pu))
        }
    }

    /** Durchbiegung (Meter, negativ = nach oben) an der Stelle [x] eines [l] langen, um [bow] verbogenen Balkens. */
    private fun bendY(x: Float, l: Float, bow: Float): Float {
        if (bow <= 0f || l <= 0f) return 0f
        val u = x / l
        return -bow * 4f * u * (1f - u)
    }

    /** Verbogener Metall-/Panzerbalken: kurze gedrehte Segmente entlang einer Parabel. */
    private fun drawBent(tex: Int, per: Float, off: Float, l: Float, th: Float, bow: Float, tint: Int, strain: Int, flash: Int, pulse: Int) {
        val sink = c.sink
        val px = c.px
        val t2 = th * 0.5f
        val n = 6
        var x0 = 0f
        var y0 = 0f
        for (k in 1..n) {
            val x1 = l * k / n
            val u = x1 / l
            val y1 = -bow * 4f * u * (1f - u)
            val dx = x1 - x0; val dy = y1 - y0
            val sl = sqrt(dx * dx + dy * dy)
            sink.save()
            sink.translate(x0, y0)
            sink.rotate(atan2(dy, dx))
            sink.fillRect(-px - (if (k > 1) 0.02f else 0f), -t2 - px, sl + 2f * px + 0.04f, th + 2f * px, SceneContext.OUTLINE_BEAM)
            strip(tex, per, off + x0, 0f, sl + 0.02f, -t2, th)
            if (tint != 0) sink.fillRect(0f, -t2, sl, th, tint)
            if (strain != 0) sink.fillRect(0f, -t2, sl, th, strain)
            if (flash != 0) sink.fillRect(0f, -t2, sl, th, flash)
            if (pulse != 0) {
                // rote Warnkontur entlang der verbogenen Kanten
                val w = px * 2.2f
                sink.line(0f, -t2, sl, -t2, w, pulse, true)
                sink.line(0f, t2, sl, t2, w, pulse, true)
                if (k == 1) sink.line(0f, -t2, 0f, t2, w, pulse, true)
                if (k == n) sink.line(sl, -t2, sl, t2, w, pulse, true)
            }
            sink.restore()
            x0 = x1; y0 = y1
        }
    }

    /** Gezackte Bruchkante: Holz = Splitter, Metall = abgescherte, verbogene Lasche. */
    private fun drawJag(l: Float, th: Float, atB: Boolean, wood: Boolean, uid: Int, mk: Int) {
        val t2 = th * 0.5f
        val dir = if (atB) 1f else -1f
        val x = if (atB) l else 0f
        val p = c.poly
        val r = rng
        r.reseed(uid * 31 + (if (atB) 7 else 3))
        var n = 0
        if (wood) {
            val steps = 5
            // Basis auf der Balkenkante (leicht nach innen), Spitzen nach außen
            val bx = x - dir * 0.08f
            p[0] = bx; p[1] = -t2; n = 1
            for (k in 0..steps) {
                val yy = -t2 + th * k / steps
                val sp = (if (k % 2 == 1) 0.05f else 0.18f + r.next() * 0.22f) * (if (k == 0 || k == steps) 0.6f else 1f)
                p[n * 2] = x + dir * sp; p[n * 2 + 1] = yy; n++
            }
            p[n * 2] = bx; p[n * 2 + 1] = t2; n++
            c.polyOutlined(n, Palette.WOOD_WARM, SceneContext.OUTLINE_BEAM, c.px * 1.6f)
            // helle Faserspitzen
            var k = 1
            while (k < steps) {
                c.sink.line(bx, -t2 + th * k / steps, x + dir * 0.12f, -t2 + th * k / steps, c.px * 1.2f, Palette.withAlpha(Palette.SPARK, 0.502f), false)
                k += 2
            }
        } else {
            p[0] = x; p[1] = -t2
            p[2] = x + dir * th * 0.45f; p[3] = -t2 * 0.2f
            p[4] = x + dir * th * 0.05f; p[5] = t2 * 0.4f
            p[6] = x + dir * th * 0.55f; p[7] = t2
            p[8] = x; p[9] = t2
            c.polyOutlined(5, if (mk == MatKind.ARMOUR) Palette.STEEL_MID else Palette.STEEL_LIGHT, SceneContext.OUTLINE_BEAM, c.px * 1.6f)
            c.sink.line(x, -t2 + c.px, x + dir * th * 0.45f, -t2 * 0.2f, c.px, Palette.withAlpha(Palette.WHITE, 0.4f), false)
        }
    }

    /** Abgesplitterte Kanten / fehlendes Stück (Holz, TP < 40 %): dunkle Keile an den Kanten. */
    private fun drawNotches(uid: Int, l: Float, th: Float) {
        val t2 = th * 0.5f
        val r = rng
        r.reseed(uid * 7 + 3)
        val p = c.poly2
        val nb = 2 + r.below(2)
        for (k in 0 until nb) {
            val x = (0.15f + r.next() * 0.7f) * l
            val w = 0.1f + r.next() * 0.16f
            val d = th * (0.2f + r.next() * 0.2f)
            p[0] = x - w; p[1] = -t2; p[2] = x - w * 0.2f; p[3] = -t2 + d; p[4] = x + w; p[5] = -t2
            c.sink.fillPolygon(p, 3, NOTCH)
        }
        val x = (0.25f + r.next() * 0.5f) * l
        val w = 0.18f + r.next() * 0.16f
        val d = th * 0.42f
        p[0] = x + w; p[1] = t2; p[2] = x + w * 0.6f; p[3] = t2 - d; p[4] = x - w * 0.5f; p[5] = t2 - d * 0.8f; p[6] = x - w; p[7] = t2
        c.sink.fillPolygon(p, 4, NOTCH)
    }

    private fun drawDamage(uid: Int, l: Float, th: Float, stage: Int, wood: Boolean, bow: Float) {
        val sink = c.sink
        val px = c.px
        val t2 = th * 0.5f
        val r = rng
        r.reseed(uid * 7 + 3)
        if (stage >= 2 && bow <= 0f) sink.fillRect(0f, -t2, l, th, Palette.withAlpha(Palette.SOOT, 0.322f))
        if (wood) {
            // feine dunkle Haarrisse entlang der Maserung
            val n = if (stage == 1) 3 else 5
            val w = maxOf(px * 1.3f, 0.02f)
            for (k in 0 until n) {
                var x = r.next() * l * 0.8f
                var y = (r.next() - 0.5f) * th * 0.7f
                val len = 0.5f + r.next() * 1.3f
                for (s in 0 until 6) {
                    val nx = x + len / 6f
                    val ny = y + (r.next() - 0.5f) * th * 0.06f
                    sink.line(x, y, nx, ny, w, Palette.withAlpha(Palette.CHAR, 0.851f), false)
                    x = nx; y = ny
                }
            }
        } else {
            // Dellen und Kratzer
            val nd = if (stage == 1) 2 else 4
            for (k in 0 until nd) {
                val x = (0.15f + r.next() * 0.7f) * l
                val rr = th * (0.25f + r.next() * 0.15f)
                val y = (r.next() - 0.5f) * th * 0.3f + bendY(x, l, bow)
                sink.fillCircle(x, y, rr, Palette.withAlpha(Palette.BLACK, 0.22f))
                sink.fillCircle(x - rr * 0.25f, y - rr * 0.25f, rr * 0.45f, Palette.withAlpha(Palette.WHITE, 0.122f))
            }
            for (k in 0 until 5) {
                val x = r.next() * l
                val y = (r.next() - 0.5f) * th * 0.8f + bendY(x, l, bow)
                sink.line(x, y, x + 0.1f + r.next() * 0.25f, y + (r.next() - 0.5f) * 0.06f, px, Palette.withAlpha(Palette.STEEL_WHITE, 0.451f), false)
            }
        }
    }

    private fun drawCharring(uid: Int, l: Float, th: Float, fire: Float, fuel: Float) {
        val sink = c.sink
        val t2 = th * 0.5f
        val ch = SceneContext.clamp((1f - fuel) * 1.25f, 0f, 0.9f)
        if (ch > 0f) sink.fillRect(0f, -t2, l, th, SceneContext.a(Palette.SOOT, ch))
        if (fire > 0f) {
            val fl = fire * (0.75f + 0.25f * sin(c.time * 13f + uid))
            fireGrad[0] = SceneContext.a(Palette.FIRE_ORANGE, 0.65f * fl); fireGrad[1] = SceneContext.a(Palette.FIRE_MID, 0f)
            sink.gradientRect(0f, -t2, l, th, 0f, t2, 0f, -t2, fireGrad, SceneContext.STOPS2)
            if (fuel < 0.7f) {
                // glühende Risse im verkohlten Holz
                val r = rng
                r.reseed(uid * 13)
                val pu = 0.55f + 0.45f * sin(c.time * 5f + uid)
                val col = SceneContext.a(SceneContext.lerp(Palette.FIRE_OUTER, Palette.FIRE_ORANGE, pu), 0.9f * pu * (1f - fuel))
                val n = 2 + (l * 1.3f).toInt()
                val w = maxOf(1.4f * c.px, 0.03f)
                for (k in 0 until n) {
                    var x = r.next() * l
                    var y = (r.next() - 0.5f) * th * 0.6f
                    for (s in 0 until 3) {
                        val nx = x + 0.12f + r.next() * 0.25f
                        val ny = SceneContext.clamp(y + (r.next() - 0.5f) * th * 0.5f, -th * 0.45f, th * 0.45f)
                        sink.line(x, y, nx, ny, w, col, false)
                        x = nx; y = ny
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Seil
    // -------------------------------------------------------------------------------------------
    private fun drawRope(snap: FrameSnapshot, i: Int, ax: Float, ay: Float, bx: Float, by: Float, l: Float, th: Float) {
        val sink = c.sink
        val px = c.px
        val rest = snap.beamRestLen[i]
        val slack = if (rest > l) sqrt(3f * l * (rest - l) / 8f) else 0f
        val sag = minOf(l * 0.08f, l * 0.025f + slack)
        val qx = (ax + bx) * 0.5f
        val qy = (ay + by) * 0.5f + 2f * sag
        val n = 14
        val fire = snap.beamFire01[i]
        val body = if (c.strainView) SceneContext.heat(snap.beamLoad01[i] * 1.6f) else if (fire > 0f) Palette.FIRE_ORANGE else Palette.ROPE
        // Außenkontur, Fasern
        var x0 = ax; var y0 = ay
        for (k in 1..n) {
            val t = k.toFloat() / n
            val u = 1f - t
            val x1 = u * u * ax + 2f * u * t * qx + t * t * bx
            val y1 = u * u * ay + 2f * u * t * qy + t * t * by
            sink.line(x0, y0, x1, y1, th + 2.4f * px, Palette.ROPE_OUTLINE, true)
            x0 = x1; y0 = y1
        }
        x0 = ax; y0 = ay
        for (k in 1..n) {
            val t = k.toFloat() / n
            val u = 1f - t
            val x1 = u * u * ax + 2f * u * t * qx + t * t * bx
            val y1 = u * u * ay + 2f * u * t * qy + t * t * by
            sink.line(x0, y0, x1, y1, th, body, true)
            x0 = x1; y0 = y1
        }
        if (!c.strainView) {
            // gedrehte Faser: diagonale Schraffur
            val cnt = maxOf(6, (l / (th * 1.4f) + 0.5f).toInt())
            val w = maxOf(px, th * 0.22f)
            for (k in 1 until cnt) {
                val t = k.toFloat() / cnt
                val u = 1f - t
                val x = u * u * ax + 2f * u * t * qx + t * t * bx
                val y = u * u * ay + 2f * u * t * qy + t * t * by
                val tx = 2f * u * (qx - ax) + 2f * t * (bx - qx)
                val ty = 2f * u * (qy - ay) + 2f * t * (by - qy)
                val tl = sqrt(tx * tx + ty * ty).let { if (it < 1e-6f) 1f else it }
                val ux = tx / tl; val uy = ty / tl
                sink.line(x - uy * th * 0.45f - ux * th * 0.35f, y + ux * th * 0.45f - uy * th * 0.35f,
                    x + uy * th * 0.45f + ux * th * 0.35f, y - ux * th * 0.45f + uy * th * 0.35f, w, Palette.withAlpha(Palette.ROPE_DARK, 0.851f), false)
            }
        }
        val fl = fx.beamFlash[i]
        if (fl > 0f) {
            x0 = ax; y0 = ay
            for (k in 1..n) {
                val t = k.toFloat() / n
                val u = 1f - t
                val x1 = u * u * ax + 2f * u * t * qx + t * t * bx
                val y1 = u * u * ay + 2f * u * t * qy + t * t * by
                sink.line(x0, y0, x1, y1, th, SceneContext.a(Palette.WHITE, fl * 0.6f), true)
                x0 = x1; y0 = y1
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Tür
    // -------------------------------------------------------------------------------------------
    private fun drawDoor(snap: FrameSnapshot, i: Int, l: Float, th: Float, ang: Float, stage: Int) {
        val sink = c.sink
        val px = c.px
        val flags = snap.beamFlags[i]
        val uid = snap.beamUid[i]
        val open = (flags and BeamFlags.DOOR_OPEN) != 0
        val hingeAtB = (flags and BeamFlags.DOOR_HINGE_B) != 0
        val t2 = th * 0.5f
        if (hingeAtB) { sink.translate(l, 0f); sink.rotate(PI.toFloat()) }
        if (!open) {
            sink.fillRect(-px, -t2 - px, l + 2f * px, th + 2f * px, SceneContext.OUTLINE_BEAM)
            strip(c.texDoor, ProceduralTextures.DOOR_LEN.toFloat(), snap.beamTexOffset[i], 0f, l, -t2, th)
            if (stage >= 1) drawDamage(uid, l, th, stage, false, 0f)
            val sc = fx.scorchCount[i]
            if (sc > 0 && c.texScorch >= 0) for (k in 0 until sc) {
                val r = fx.scorchR[i * 3 + k]
                val cx = (if (hingeAtB) 1f - fx.scorchT[i * 3 + k] else fx.scorchT[i * 3 + k]) * l
                sink.image(c.texScorch, cx - r, -t2, 2f * r, th, 0.85f)
            }
            if (c.strainView) sink.fillRect(0f, -t2, l, th, SceneContext.withAlpha(SceneContext.heat(snap.beamLoad01[i] * 1.6f), 0.8f))
            val fl = fx.beamFlash[i]
            if (fl > 0f) sink.fillRect(0f, -t2, l, th, SceneContext.a(Palette.WHITE, fl * 0.5f))
            if (stage == 3) {
                val pu = if (c.reduced) 0.6f else 0.5f + 0.5f * sin(c.time * 4.2f + uid)
                sink.strokeRect(0f, -t2, l, th, px * 2.2f, SceneContext.a(Palette.CRITICAL, 0.35f + 0.55f * pu))
            }
        } else {
            // Öffnung dunkel mit Rahmen
            sink.fillRect(0f, -th * 0.32f, l, th * 0.64f, Palette.withAlpha(Palette.INK, 0.878f))
            sink.fillRect(0f, -th * 0.4f, l, th * 0.08f, Palette.STEEL_SHADE)
            sink.fillRect(0f, th * 0.32f, l, th * 0.08f, Palette.STEEL_SHADE)
            val out = if (snap.beamOwner[i] == 0) 1f else -1f
            val a2 = ang + (if (hingeAtB) PI.toFloat() else 0f)
            val s1 = a2 + 1.745f
            val s2 = a2 - 1.745f
            val sgn = if (cos(s1) * out > cos(s2) * out) 1f else -1f
            sink.save()
            sink.rotate(sgn * 1.745f)
            val pl = l * 0.92f
            val tt = th * 0.5f
            sink.fillRect(-px, -tt * 0.5f - px, pl + 2f * px, tt + 2f * px, SceneContext.OUTLINE_BEAM)
            sink.save()
            sink.scale(1f, 0.5f)
            strip(c.texDoor, ProceduralTextures.DOOR_LEN.toFloat(), snap.beamTexOffset[i], 0f, pl, -t2, th)
            sink.restore()
            sink.restore()
        }
        // Scharnier: zwei Bänder + Bolzen
        for (k in 0 until 2) {
            val hx = if (k == 0) 0.32f else 0.9f
            sink.fillRect(hx - 0.1f - px, -th * 0.62f - px, 0.2f + 2f * px, th * 1.24f + 2f * px, SceneContext.OUTLINE_BEAM)
            heatGrad[0] = Palette.STEEL_WHITE; heatGrad[1] = Palette.STEEL_MID
            sink.gradientRect(hx - 0.1f, -th * 0.62f, 0.2f, th * 1.24f, 0f, -th * 0.62f, 0f, th * 0.62f, heatGrad, SceneContext.STOPS2)
            sink.fillCircle(hx, 0f, 0.045f, Palette.STEEL_DARK)
        }
        if (!open) {
            sink.fillRect(l - 0.55f - px, -0.05f - px, 0.3f + 2f * px, 0.1f + 2f * px, SceneContext.OUTLINE_BEAM)
            sink.fillRect(l - 0.55f, -0.05f, 0.3f, 0.1f, Palette.STEEL_PALE)
        }
    }

    private companion object {
        val NOTCH: Int = Palette.OUTLINE_BEAM
    }
}
