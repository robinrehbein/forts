package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.ParticleBuffers
import de.bollwerk.renderapi.ParticleKind
import de.bollwerk.renderapi.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feuer (Glut-Halo additiv, Flammenzungen im Verlauf der fire-Token), Partikel (Rauch, Staub, Trümmer,
 * Funken, Glut, Explosions-Phasen, Mündungsfeuer), Projektile mit Schweif, Leuchtspuren und Flaggen.
 */
internal class EffectPainter(private val c: SceneContext, private val fx: FxState) {
    private val rng = RenderRng(9)
    private val tongue = FloatArray(32)
    private val flagSumX = FloatArray(FLAG_OWNERS)
    private val flagCnt = IntArray(FLAG_OWNERS)
    private val smokeHist = IntArray(SMOKE_KEYS)

    // ---------------------------------------------------------------------------------------------
    // Feuer
    // ---------------------------------------------------------------------------------------------

    /** Additiver Glut-Schein auf brennenden Balken (auch auf Nachbarn). */
    fun drawFireGlow(snap: FrameSnapshot) {
        for (i in 0 until snap.beamCount) {
            if ((snap.beamFlags[i] and BeamFlags.ALIVE) == 0) continue
            val fire = snap.beamFire01[i]
            if (fire <= 0f) continue
            val a = snap.beamA[i]; val b = snap.beamB[i]
            val mx = (c.ix[a] + c.ix[b]) * 0.5f
            val my = (c.iy[a] + c.iy[b]) * 0.5f
            val dx = c.ix[b] - c.ix[a]; val dy = c.iy[b] - c.iy[a]
            val l = sqrt(dx * dx + dy * dy)
            val r = 1.6f + l * 0.45f
            val f = fire * (0.8f + 0.2f * sin(c.time * 9f + snap.beamUid[i]))
            c.sink.glow(mx, my, r, SceneContext.a(Palette.FIRE_ORANGE, 0.55f * f))
        }
    }

    /** 3–6 animierte Flammenzungen je brennendem Balken; Wind legt sie um. */
    fun drawFlames(snap: FrameSnapshot) {
        val lean = SceneContext.clamp(snap.wind * 0.06f, -0.45f, 0.45f)
        val speed = if (c.reduced) 0.4f else 1f
        for (b in 0 until snap.beamCount) {
            if ((snap.beamFlags[b] and BeamFlags.ALIVE) == 0) continue
            val fire = snap.beamFire01[b]
            if (fire <= 0f) continue
            val a = snap.beamA[b]; val e = snap.beamB[b]
            val x0 = c.ix[a]; val y0 = c.iy[a]; val x1 = c.ix[e]; val y1 = c.iy[e]
            val dx = x1 - x0; val dy = y1 - y0
            val l = sqrt(dx * dx + dy * dy)
            val mat = snap.beamMaterial[b]
            val th = if (mat >= 0 && mat < c.matThick.size) c.matThick[mat] else 0.3f
            val uid = snap.beamUid[b]
            rng.reseed(uid)
            val n = (SceneContext.clamp(l * 0.9f, 3f, 6f) * minOf(1f, 0.35f + fire) + 0.5f).toInt()
            for (i in 0 until n) {
                val t = (i + 0.5f) / n + (rng.next() - 0.5f) * 0.12f
                val x = x0 + (x1 - x0) * t
                val y = y0 + (y1 - y0) * t - th * 0.2f
                val fl = sin(c.time * speed * (9f + rng.next() * 5f) + i * 1.9f + uid) * 0.5f + 0.5f
                val h = (0.4f + 1.15f * fire * (0.7f + 0.3f * rng.next())) * (0.8f + 0.35f * fl)
                val w = 0.42f + 0.34f * fire
                val shear = lean + sin(c.time * speed * 7f + i) * 0.08f
                flame(x, y, w, h, shear, (rng.next() - 0.5f) * w * 0.5f)
            }
        }
    }

    /** Eine Flammenzunge aus drei ineinanderliegenden Formen (außen rot, Mitte orange, innen gelb). */
    private fun flame(x: Float, y: Float, w: Float, h: Float, shear: Float, bend: Float) {
        tongueShape(x, y, w, h, shear, bend)
        c.sink.fillPolygon(tongue, 8, Palette.FIRE_OUTER)
        tongueShape(x, y, w * 0.72f, h * 0.72f, shear, bend * 0.8f)
        c.sink.fillPolygon(tongue, 8, Palette.FIRE_MID)
        tongueShape(x, y, w * 0.42f, h * 0.42f, shear, bend * 0.5f)
        c.sink.fillPolygon(tongue, 8, Palette.FIRE_INNER)
    }

    private fun tongueShape(x: Float, y: Float, w: Float, h: Float, shear: Float, bend: Float) {
        val t = tongue
        // Basis links, linke Wölbung, linke Flanke, Spitze, rechte Flanke, rechte Wölbung, Basis rechts, Bodenbogen
        pts(t, 0, x, y, -w * 0.5f, 0f, shear)
        pts(t, 1, x, y, -w * 0.58f, -h * 0.25f, shear)
        pts(t, 2, x, y, -w * 0.28f + bend, -h * 0.62f, shear)
        pts(t, 3, x, y, bend * 1.3f, -h, shear)
        pts(t, 4, x, y, w * 0.3f + bend, -h * 0.64f, shear)
        pts(t, 5, x, y, w * 0.58f, -h * 0.25f, shear)
        pts(t, 6, x, y, w * 0.5f, 0f, shear)
        pts(t, 7, x, y, 0f, h * 0.07f, shear)
    }

    private fun pts(t: FloatArray, i: Int, x: Float, y: Float, dx: Float, dy: Float, shear: Float) {
        t[i * 2] = x + dx - shear * dy
        t[i * 2 + 1] = y + dy
    }

    // ---------------------------------------------------------------------------------------------
    // Flaggen
    // ---------------------------------------------------------------------------------------------

    /**
     * Je Spieler eine Fahne an der **Vorderkante der Festung** (zur Kartenmitte, wie im freigegebenen Mockup
     * `3-spiel-bauen`): Die Stange steht auf dem obersten Knoten der vordersten Knotenspalte, an dem mindestens zwei
     * tragende Balken hängen (Trümmer und vorgeschobene Einzel-Balken zählen nicht). Knoten, auf denen ein Gerät
     * steht, werden übersprungen; findet sich in der Spalte keiner, wird die Spalte schrittweise verbreitert.
     *
     * **Stabil:** Die gewählte Knoten-uid merkt sich [FxState.flagUid]; die Fahne bleibt dort, solange der Knoten taugt
     * (lebt, kein Trümmer, ≥ 2 tragende Balken, kein Gerät darauf und höchstens [FLAG_KEEP] m hinter der Vorderkante).
     * Nur wenn er das nicht mehr tut, wird neu gewählt (Wackeln und Einsturz lassen sie nicht von Frame zu Frame
     * springen). Ohne tragenden Knoten gibt es keine Fahne. Gerätelagen stammen aus dem [DevicePainter] (interpolierte
     * Knoten des aktuellen Frames, nicht aus den Tick-Daten des Snapshots).
     */
    fun drawFlags(snap: FrameSnapshot, devices: DevicePainter) {
        for (o in 0 until FLAG_OWNERS) { flagSumX[o] = 0f; flagCnt[o] = 0 }
        val nodeCount = snap.nodeCount
        for (i in 0 until nodeCount) {
            if (!flagEligible(snap, i)) continue
            val o = snap.nodeOwner[i]
            flagSumX[o] += c.ix[i]; flagCnt[o]++
        }
        for (o in 0 until FLAG_OWNERS) {
            if (flagCnt[o] == 0) { fx.flagUid[o] = -1; continue }
            // Vorderkante = zur Kartenmitte hin (die Festung liegt auf der eigenen Seite)
            val left = flagSumX[o] / flagCnt[o] < c.map.width * 0.5f
            var front = if (left) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
            for (i in 0 until nodeCount) {
                if (snap.nodeOwner[i] != o || !flagEligible(snap, i)) continue
                val x = c.ix[i]
                if (if (left) x > front else x < front) front = x
            }
            var pick = -1
            val keep = fx.flagUid[o]
            if (keep >= 0) {
                for (i in 0 until nodeCount) {
                    if (snap.nodeUid[i] != keep) continue
                    if (snap.nodeOwner[i] == o && flagEligible(snap, i) && abs(c.ix[i] - front) <= FLAG_KEEP && !deviceOnNode(snap, devices, c.ix[i], c.iy[i])) pick = i
                    break
                }
            }
            if (pick < 0) {
                var band = FLAG_COL
                while (pick < 0 && band <= FLAG_COL * 5.01f) {
                    var topY = Float.POSITIVE_INFINITY
                    for (i in 0 until nodeCount) {
                        if (snap.nodeOwner[i] != o || !flagEligible(snap, i)) continue
                        val x = c.ix[i]; val y = c.iy[i]
                        if (abs(x - front) <= band && y < topY && !deviceOnNode(snap, devices, x, y)) { topY = y; pick = i }
                    }
                    band += FLAG_COL
                }
                fx.flagUid[o] = if (pick >= 0) snap.nodeUid[pick] else -1
            }
            if (pick < 0) continue
            val x = c.ix[pick]; val y = c.iy[pick]
            drawFlag(x, y - 0.2f, Palette.team(o), o * 2.1f, clothDir(snap, devices, x, y))
        }
    }

    /** Knoten, auf dem eine Fahne stehen darf: lebt, kein Trümmer, Spieler-Knoten, mindestens zwei tragende Balken. */
    private fun flagEligible(snap: FrameSnapshot, i: Int): Boolean {
        val f = snap.nodeFlags[i]
        if ((f and NodeFlags.ALIVE) == 0 || (f and NodeFlags.DEBRIS) != 0) return false
        val o = snap.nodeOwner[i]
        return o >= 0 && o < FLAG_OWNERS && c.solidBeams[i] >= 2
    }

    /** Wehrichtung des Tuchs: mit dem Wind, außer ein Gerät (Turbine, MG) stünde im Weg; dann nach der anderen Seite. */
    private fun clothDir(snap: FrameSnapshot, devices: DevicePainter, x: Float, y: Float): Float {
        val d = if (c.wind >= 0f) 1f else -1f
        val n = minOf(snap.deviceCount, devices.drawn.size)
        for (k in 0 until n) {
            if (!devices.drawn[k]) continue
            val dx = (devices.mountX[k] - x) * d
            if (dx > 0f && dx < CLOTH_CLEAR && abs(devices.mountY[k] - y) < CLOTH_BAND) return -d
        }
        return d
    }

    /** Steht ein (in diesem Frame gezeichnetes) Gerät auf dem Knoten bei ([x], [y])? */
    private fun deviceOnNode(snap: FrameSnapshot, devices: DevicePainter, x: Float, y: Float): Boolean {
        val n = minOf(snap.deviceCount, devices.drawn.size)
        for (k in 0 until n) {
            if (!devices.drawn[k]) continue
            val dx = devices.mountX[k] - x; val dy = devices.mountY[k] - y
            if (dx * dx + dy * dy < DEVICE_ON_NODE * DEVICE_ON_NODE) return true
        }
        return false
    }

    private fun drawFlag(x: Float, y: Float, team: Int, ph: Float, dir: Float) {
        if (x < c.visMinX(2f) || x > c.visMaxX(2f)) return
        val sink = c.sink
        val top = y - 2.6f
        val w = c.wind
        val len = 1.25f
        val hgt = 0.78f
        val flap = (0.12f + minOf(0.2f, kotlin.math.abs(w) * 0.03f)) * (if (c.reduced) 0.4f else 1f)
        // Fußplatte auf dem Knoten, dann die Stange
        sink.fillRect(x - 0.14f, y - 0.04f, 0.28f, 0.07f, Palette.STEEL_MID)
        sink.strokeRect(x - 0.14f, y - 0.04f, 0.28f, 0.07f, c.px, SceneContext.OUTLINE)
        sink.line(x, y, x, top - 0.1f, 0.1f, SceneContext.OUTLINE, true)
        sink.line(x, y, x, top - 0.1f, 0.05f, Palette.STEEL_PALE, true)
        sink.fillCircle(x, top - 0.12f, 0.07f, Palette.HAZARD)
        val n = 8
        val p = c.poly
        var k = 0
        for (i in 0..n) {
            val u = i.toFloat() / n
            p[k * 2] = x + dir * u * len
            p[k * 2 + 1] = top + sin(c.time * 6f - u * 4f + ph) * flap * u
            k++
        }
        for (i in n downTo 0) {
            val u = i.toFloat() / n
            p[k * 2] = x + dir * u * len
            p[k * 2 + 1] = top + hgt + sin(c.time * 6f - u * 4f + ph + 0.3f) * flap * u
            k++
        }
        c.polyOutlined(k, team)
        sink.fillRect(x + dir * 0.28f - 0.04f, top + 0.2f, 0.08f, 0.38f, Palette.withAlpha(Palette.WHITE, 0.851f))
    }

    // ---------------------------------------------------------------------------------------------
    // Projektile
    // ---------------------------------------------------------------------------------------------

    fun drawProjectiles(snap: FrameSnapshot, alpha: Float) {
        val sink = c.sink
        val px = c.px
        for (i in 0 until snap.projectileCount) {
            if ((snap.projFlags[i] and 1) == 0) continue
            val x = snap.projPrevX[i] + (snap.projX[i] - snap.projPrevX[i]) * alpha
            val y = snap.projPrevY[i] + (snap.projY[i] - snap.projPrevY[i]) * alpha
            if (x < c.visMinX(3f) || x > c.visMaxX(3f) || y < c.visMinY(3f) || y > c.visMaxY(3f)) continue
            val vx = snap.projVx[i]; val vy = snap.projVy[i]
            val sp = sqrt(vx * vx + vy * vy)
            val ux = if (sp > 1e-3f) vx / sp else 1f
            val uy = if (sp > 1e-3f) vy / sp else 0f
            val kind = snap.projKind[i]
            val dk = if (kind >= 0 && kind < c.weaponKind.size) c.weaponKind[kind] else DevKind.CANNON
            val inc = (snap.projFlags[i] and 2) != 0 || dk == DevKind.ROCKET
            // Schweif: abklingende Linie gegen die Flugrichtung
            val trail = SceneContext.clamp(sp * 0.05f, 0.4f, 2.8f)
            val steps = 5
            for (k in 0 until steps) {
                val f0 = k.toFloat() / steps
                val f1 = (k + 1f) / steps
                val a = (1f - f0) * 0.5f
                val col = if (inc) SceneContext.a(Palette.FIRE_ORANGE, a) else SceneContext.a(Palette.STEEL_PALE, a * 0.55f)
                sink.line(x - ux * trail * f0, y - uy * trail * f0, x - ux * trail * f1, y - uy * trail * f1, (if (dk == DevKind.MORTAR) 0.22f else 0.16f) * (1f - f0 * 0.6f), col, true)
            }
            sink.save()
            sink.translate(x, y)
            sink.rotate(kotlin.math.atan2(uy, ux))
            when (dk) {
                DevKind.MORTAR -> {
                    sink.save(); sink.scale(1f, 0.59f)
                    sink.fillCircle(0f, 0f, 0.34f, if (inc) Palette.FIRE_OUTER else Palette.STEEL_SHADE)
                    sink.strokeCircle(0f, 0f, 0.34f, px * 1.4f / 0.59f, SceneContext.OUTLINE)
                    sink.restore()
                    sink.fillRect(-0.12f, -0.2f, 0.07f, 0.4f, Palette.HAZARD)
                    sink.fillRect(-0.2f, -0.13f, 0.3f, 0.05f, Palette.withAlpha(Palette.WHITE, 0.4f))
                }
                DevKind.ROCKET -> {
                    val q = c.poly2
                    q[0] = -0.3f; q[1] = -0.06f; q[2] = 0.25f; q[3] = -0.08f; q[4] = 0.4f; q[5] = 0f; q[6] = 0.25f; q[7] = 0.08f; q[8] = -0.3f; q[9] = 0.06f
                    c.polyOutlined2(5, Palette.STEEL_HI)
                    q[0] = 0.25f; q[1] = -0.08f; q[2] = 0.4f; q[3] = 0f; q[4] = 0.25f; q[5] = 0.08f
                    sink.fillPolygon(q, 3, Palette.TEAM_RED)
                    // Abgasflamme
                    q[0] = -0.3f; q[1] = -0.05f; q[2] = -0.75f - 0.15f * sin(c.time * 40f); q[3] = 0f; q[4] = -0.3f; q[5] = 0.05f
                    sink.fillPolygon(q, 3, Palette.FIRE_INNER)
                    sink.glow(-0.3f, 0f, 0.7f, SceneContext.a(Palette.FIRE_ORANGE, 0.6f))
                }
                else -> {
                    c.disc(0f, 0f, 0.17f, Palette.STEEL_DARK)
                    sink.fillCircle(-0.05f, -0.06f, 0.05f, Palette.withAlpha(Palette.WHITE, 0.451f))
                }
            }
            sink.restore()
        }
    }

    /** Hitscan-Leuchtspuren (MG, Scharfschütze). */
    fun drawTracers() {
        val sink = c.sink
        for (i in 0 until fx.trCount) {
            val a = 1f - fx.trAge[i] / FxState.TRACER_LIFE
            if (a <= 0f) continue
            sink.line(fx.trX0[i], fx.trY0[i], fx.trX1[i], fx.trY1[i], 0.07f, SceneContext.a(Palette.CREAM, a), true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Partikel
    // ---------------------------------------------------------------------------------------------

    fun drawParticles(b: ParticleBuffers) {
        val n = b.count
        if (n == 0) return
        val sink = c.sink
        val px = c.px
        val minX = c.visMinX(4f); val maxX = c.visMaxX(4f); val minY = c.visMinY(6f); val maxY = c.visMaxY(4f)
        // 1. Rauch (jung hell, alt dunkler und durchsichtig), Staub. Abdeckung begrenzt: höchstens MAX_SMOKE Wolken.
        // Die gezeichnete Teilmenge richtet sich nach einem festen Schlüssel je Partikel (seed), nicht nach der
        // Pufferposition: Das Partikelsystem tauscht beim Entfernen das letzte Partikel in die Lücke, ein
        // Index-Raster würde bei jedem Absterben umspringen (Flackern). Gezählt wird nur, was im Bild liegt.
        var smokeVisible = 0
        smokeHist.fill(0)
        for (i in 0 until n) {
            if (b.kind[i] != K_SMOKE) continue
            val x = b.x[i]; val y = b.y[i]
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            smokeHist[smokeKey(b.seed[i])]++
            smokeVisible++
        }
        var smokeLimit = SMOKE_KEYS // Schlüssel < smokeLimit werden gezeichnet
        if (smokeVisible > MAX_SMOKE) {
            var cum = 0
            smokeLimit = 0
            while (smokeLimit < SMOKE_KEYS && cum + smokeHist[smokeLimit] <= MAX_SMOKE) { cum += smokeHist[smokeLimit]; smokeLimit++ }
            if (smokeLimit == 0) smokeLimit = 1 // entartet (viele gleiche seeds): wenigstens einen Schlüssel zeigen
        }
        for (i in 0 until n) {
            val k = b.kind[i]
            val x = b.x[i]; val y = b.y[i]
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            if (k == K_SMOKE) {
                if (smokeKey(b.seed[i]) >= smokeLimit) continue
                val u = b.age[i] / b.life[i]
                // wächst mit dem Alter (Wind treibt die Wolke im Partikelsystem), Radius begrenzt
                val r = minOf(SMOKE_RMAX, b.size[i] * SMOKE_SCALE * (0.55f + u * 1.25f))
                val a = smokeAlpha(u)
                val col = SceneContext.lerp(Palette.SMOKE_YOUNG or Palette.BLACK, Palette.SMOKE_OLD or Palette.BLACK, minOf(1f, u * 2f))
                sink.fillCircle(x, y, r, SceneContext.a(col, a))
                sink.fillCircle(x - r * 0.22f, y - r * 0.28f, r * 0.58f, SceneContext.a(Palette.SMOKE_HI, a * 0.4f))
            } else if (k == K_DUST) {
                val u = b.age[i] / b.life[i]
                val r = b.size[i] * (0.6f + u * 1.3f)
                val a = (1f - u) * 0.7f
                sink.fillCircle(x, y + r * 0.12f, r, SceneContext.a(Palette.DUST_DARK, a))
                sink.fillCircle(x - r * 0.18f, y - r * 0.2f, r * 0.72f, SceneContext.a(Palette.DUST_LIGHT, a))
            }
        }
        // 2. Trümmer
        for (i in 0 until n) {
            val k = b.kind[i]
            if (k != K_CHUNK && k != K_SPLINTER && k != K_DEBRIS) continue
            val x = b.x[i]; val y = b.y[i]
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            chunk(b, i, k)
        }
        // 3. Glut, Funken
        for (i in 0 until n) {
            val k = b.kind[i]
            val x = b.x[i]; val y = b.y[i]
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            if (k == K_EMBER) {
                val u = b.age[i] / b.life[i]
                val s = b.size[i] * (1f - u * 0.5f) * (0.7f + 0.3f * sin(c.time * 30f + b.seed[i]))
                val col = if (u < 0.5f) Palette.SPARK else Palette.FIRE_MID
                sink.fillRect(x - s, y - s, 2f * s, 2f * s, SceneContext.a(col, 1f - u))
            } else if (k == K_SPARK) {
                val u = b.age[i] / b.life[i]
                sink.line(x, y, x - b.vx[i] * 0.03f, y - b.vy[i] * 0.03f, maxOf(0.05f, 1.6f * px), SceneContext.a(Palette.SPARK, 1f - u), true)
            }
        }
        // 4. Explosions-Phasen und Mündungsfeuer
        for (i in 0 until n) {
            val k = b.kind[i]
            if (k == K_FLASH) flash(b, i)
            else if (k == K_FIREBALL) fireball(b, i)
            else if (k == K_SHOCK) shock(b, i)
            else if (k == K_MUZZLE) muzzle(b, i)
        }
    }

    /**
     * Rauch-Deckkraft über die Lebenszeit [u] (0..1): jung 20 % (Palette), steigt bis [SMOKE_PEAK] (alter Rauch ist
     * dicht und dunkel, Mockups `spielplatz-brand`/`3-spiel-bauen`) und klingt erst im letzten Viertel aus.
     */
    internal fun smokeAlpha(u: Float): Float = when {
        u < SMOKE_RISE -> SceneContext.lerp(0.2f, SMOKE_PEAK, u / SMOKE_RISE)
        u < 1f - SMOKE_FADE -> SMOKE_PEAK
        else -> { val t = SceneContext.clamp((1f - u) / SMOKE_FADE, 0f, 1f); SMOKE_PEAK * t * sqrt(t) }
    }

    /** Stabiler Schlüssel 0..[SMOKE_KEYS]-1 eines Rauchpartikels aus seinem seed (Bit-Mischer). */
    internal fun smokeKey(seed: Int): Int {
        var h = seed * -0x61c88647
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        return (h ushr 24) and (SMOKE_KEYS - 1)
    }

    /**
     * Explosions-Blitz als **örtlicher** radialer Schein um den Einschlagpunkt (statt einer Bildschirm-Aufhellung):
     * Radius auf [BURST_MAX_M] und [BURST_SCREEN_FRACTION] der kleineren Bildschirmseite begrenzt, klingt in 0,25 s ab.
     */
    fun drawBurst() {
        val v = fx.burst
        if (v <= 0f) return
        val r = burstRadius(fx.burstR)
        if (r <= 0f) return
        c.sink.glow(fx.burstX, fx.burstY, r, SceneContext.a(Palette.CREAM, 0.6f * v))
    }

    /** Begrenzter Radius (m) des örtlichen Blitzes für den Wunschradius [wanted]. */
    internal fun burstRadius(wanted: Float): Float = minOf(wanted, BURST_MAX_M, burstLimit(BURST_SCREEN_FRACTION))

    private fun chunk(b: ParticleBuffers, i: Int, k: Int) {
        val sink = c.sink
        val life = b.life[i]
        val u = b.age[i] / life
        val a = minOf(1f, (1f - u) * 3f)
        val size = b.size[i]
        val rot = b.rot[i]
        val cr = cos(rot); val sr = sin(rot)
        val col = SceneContext.a(b.color[i], a)
        val r = rng
        r.reseed(b.seed[i])
        val p = c.poly2
        val x = b.x[i]; val y = b.y[i]
        if (k == K_SPLINTER) {
            // schmaler Span mit zugespitzten Enden
            val lx = size * 3.2f; val ly = size * 0.45f
            fun put(idx: Int, px: Float, py: Float) { p[idx * 2] = x + px * cr - py * sr; p[idx * 2 + 1] = y + px * sr + py * cr }
            put(0, -lx, 0f); put(1, -lx * 0.3f, -ly); put(2, lx, -ly * 0.2f); put(3, lx * 0.2f, ly)
            c.polyOutlined2(4, col, SceneContext.a(Palette.SOOT, a * 0.8f), c.px * 1.1f)
            return
        }
        val nPts = 4 + (b.seed[i] % 3)
        val wide = if ((b.seed[i] and 1) == 1) 1.6f else 1.2f
        val s = if (k == K_DEBRIS) size * 0.8f else size
        for (q in 0 until nPts) {
            val ang = q.toFloat() / nPts * SceneContext.TAU + r.next() * 0.5f
            val rr = 0.55f + r.next() * 0.45f
            val lx = cos(ang) * rr * wide * s
            val ly = sin(ang) * rr * 0.7f * s
            p[q * 2] = x + lx * cr - ly * sr
            p[q * 2 + 1] = y + lx * sr + ly * cr
        }
        c.polyOutlined2(nPts, col, SceneContext.a(Palette.SOOT, a * 0.8f), c.px * 1.2f)
    }

    /** Obergrenze (m) für Blitz und Feuerball: nie mehr als ein Bruchteil der kleineren Bildschirmseite. */
    internal fun burstLimit(fraction: Float): Float = fraction * minOf(c.viewW, c.viewH) * c.px

    private fun flash(b: ParticleBuffers, i: Int) {
        val sink = c.sink
        val x = b.x[i]; val y = b.y[i]; val r = minOf(b.size[i], FLASH_MAX_M, burstLimit(FLASH_SCREEN_FRACTION))
        val p = c.poly
        val n = 24
        for (q in 0 until n) {
            val a = q.toFloat() / n * SceneContext.TAU + x
            val rr = if (q % 2 == 1) r * 0.32f else r * (0.8f + 0.2f * sin(q * 7.1f))
            p[q * 2] = x + cos(a) * rr
            p[q * 2 + 1] = y + sin(a) * rr
        }
        sink.fillPolygon(p, n, Palette.WHITE)
        sink.glow(x, y, r * 0.8f, Palette.withAlpha(Palette.CREAM, 0.7f))
    }

    private fun fireball(b: ParticleBuffers, i: Int) {
        val sink = c.sink
        val u = b.age[i] / b.life[i]
        val r = minOf(b.size[i] * (0.4f + 0.65f * sqrt(u)), FIREBALL_MAX_M, burstLimit(FIREBALL_SCREEN_FRACTION))
        val al = if (u < 0.45f) 1f else (1f - u) / 0.55f
        val x = b.x[i]; val y = b.y[i]
        val s0 = (b.seed[i] and 0xFFFF) / 65536f * 6f
        sink.glow(x, y, minOf(r * 2.2f, burstLimit(0.5f)), SceneContext.a(Palette.FIRE_ORANGE, 0.45f * al))
        for (layer in 0 until 4) {
            val sc = when (layer) { 0 -> 1f; 1 -> 0.78f; 2 -> 0.52f; else -> 0.26f }
            val col = when (layer) { 0 -> Palette.FIRE_OUTER; 1 -> Palette.FIRE_MID; 2 -> Palette.FIRE_INNER; else -> Palette.CREAM }
            val cc = SceneContext.a(col, al)
            for (q in 0 until 5) {
                val a = s0 * 3f + q * 1.257f + u * 0.8f
                val d = r * 0.38f * sc
                sink.fillCircle(x + cos(a) * d, y + sin(a) * d * 0.9f, r * 0.62f * sc, cc)
            }
            sink.fillCircle(x, y, r * 0.7f * sc, cc)
        }
    }

    private fun shock(b: ParticleBuffers, i: Int) {
        val sink = c.sink
        val u = b.age[i] / b.life[i]
        val inv = 1f - u
        val r = b.size[i] * (0.15f + 0.85f * (1f - inv * inv))
        val x = b.x[i]; val y = b.y[i]
        sink.fillCircle(x, y, r, SceneContext.a(Palette.CREAM, 0.07f * inv))
        sink.strokeCircle(x, y, r, maxOf(1.5f * c.px, 0.22f * inv), SceneContext.a(Palette.CREAM, inv * 0.85f))
    }

    private fun muzzle(b: ParticleBuffers, i: Int) {
        val sink = c.sink
        val u = b.age[i] / b.life[i]
        val m = 1f - u
        val s = b.size[i] * (0.7f + 0.3f * m)
        val dx = b.vx[i]; val dy = b.vy[i]
        val x = b.x[i]; val y = b.y[i]
        val p = c.poly2
        // Stern entlang der Rohrrichtung
        fun put(idx: Int, lx: Float, ly: Float) { p[idx * 2] = x + lx * dx - ly * dy; p[idx * 2 + 1] = y + lx * dy + ly * dx }
        put(0, 0f, -s * 0.22f); put(1, s * 0.45f, -s * 0.3f); put(2, s * 0.3f, -s * 0.08f); put(3, s * 1.05f, 0f)
        put(4, s * 0.3f, s * 0.08f); put(5, s * 0.45f, s * 0.3f); put(6, 0f, s * 0.22f)
        sink.glow(x + dx * s * 0.4f, y + dy * s * 0.4f, s * 0.9f, SceneContext.a(Palette.FIRE_INNER, m))
        sink.fillPolygon(p, 7, SceneContext.a(Palette.CREAM, m))
    }

    internal companion object {
        /** Spieler, für die Fahnen gezeichnet werden. */
        const val FLAG_OWNERS = 4
        /** Breite (m) der "vordersten Knotenspalte", aus der der oberste Knoten die Fahne trägt. */
        const val FLAG_COL = 0.7f
        /** Die Fahne bleibt auf ihrem Knoten, solange der höchstens so viele m hinter der Vorderkante liegt. */
        const val FLAG_KEEP = 2.5f
        /** Ein Gerät, dessen Fußpunkt näher als so viele m am Knoten liegt, "steht" darauf. */
        const val DEVICE_ON_NODE = 0.7f
        /** Tuch weht zur anderen Seite, wenn ein Gerät weniger als [CLOTH_CLEAR] m in Wehrichtung und weniger als [CLOTH_BAND] m über/unter dem Knoten steht. */
        const val CLOTH_CLEAR = 1.7f
        const val CLOTH_BAND = 3.5f
        /** Obergrenze gleichzeitig gezeichneter Rauchwolken (Abdeckung begrenzen) und Anzahl der Schlüssel-Klassen. */
        const val MAX_SMOKE = 70
        const val SMOKE_KEYS = 256
        /** Rauchgröße relativ zur Partikelgröße und absolute Obergrenze des Radius (m). */
        const val SMOKE_SCALE = 0.75f
        const val SMOKE_RMAX = 1.1f
        const val SMOKE_PEAK = 0.8f
        /** Anteil der Lebenszeit bis zur Höchstdichte und Anteil am Ende, in dem der Rauch ausklingt. */
        const val SMOKE_RISE = 0.5f
        const val SMOKE_FADE = 0.25f
        /** Blitz-Stern: Radius höchstens so viele m und so viel der kleineren Bildschirmseite. */
        const val FLASH_MAX_M = 3.2f
        const val FLASH_SCREEN_FRACTION = 0.3f
        const val FIREBALL_MAX_M = 4.2f
        const val FIREBALL_SCREEN_FRACTION = 0.42f
        /** Treffer-Blitz (örtlich, additiv) am Explosionspunkt: Radius ≤ 5 m und ≤ 35 % der kleineren Seite. */
        const val BURST_MAX_M = 5f
        const val BURST_SCREEN_FRACTION = 0.35f
        val K_SPARK = ParticleKind.SPARK.ordinal
        val K_EMBER = ParticleKind.EMBER.ordinal
        val K_SMOKE = ParticleKind.SMOKE.ordinal
        val K_DUST = ParticleKind.DUST.ordinal
        val K_CHUNK = ParticleKind.CHUNK.ordinal
        val K_FLASH = ParticleKind.FLASH.ordinal
        val K_FIREBALL = ParticleKind.FIREBALL.ordinal
        val K_SHOCK = ParticleKind.SHOCKWAVE.ordinal
        val K_MUZZLE = ParticleKind.MUZZLE_FLASH.ordinal
        val K_SPLINTER = ParticleKind.SPLINTER.ordinal
        val K_DEBRIS = ParticleKind.DEBRIS.ordinal
    }
}
