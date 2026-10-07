package de.bollwerk.renderapi.scene

import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.Palette
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geräte in kanonischen Formen (Stil-Bibel §4): Reaktor, Mine, Windturbine, Werkstatt, Waffenkammer,
 * Upgrade-Zentrum, Fabrik und die sechs Waffen. Alle stehen mit Montagefüßen **auf** dem Balken, 1 dp
 * dunkle Kontur, Licht von oben links; Rohre drehen mit dem Zielwinkel, Rückstoß/Drehung/Puls aus [FxState].
 * Lage aus `DeviceGeometry.mountAt` mit **interpolierten** Knoten.
 */
internal class DevicePainter(private val c: SceneContext, private val fx: FxState, private val terrain: de.bollwerk.engine.sim.Terrain) {
    private val geo = FloatArray(DeviceGeometry.SIZE)
    private val rng = RenderRng(3)
    private val g2 = IntArray(2)

    /** Mündungs-/Strahlursprung je Geräte-Slot (für den Laserstrahl). */
    var muzzleX = FloatArray(0)
    var muzzleY = FloatArray(0)

    /** Trefferzentrum und -radius je Geräte-Slot (Auswahlrahmen); `radius == 0` = nicht gezeichnet. */
    var centerX = FloatArray(0)
    var centerY = FloatArray(0)
    var radius = FloatArray(0)

    /** Oberkante des Gerätekörpers (Reaktor: Kühlrippen) und Normale der Montagefläche je Slot (Flagge). */
    var topX = FloatArray(0)
    var topY = FloatArray(0)
    var normalX = FloatArray(0)
    var normalY = FloatArray(0)
    /** Montagepunkt (Fußpunkt auf dem Balken) je Slot, aus den interpolierten Knoten (Fahnen weichen Geräten aus). */
    var mountX = FloatArray(0)
    var mountY = FloatArray(0)
    /** Wurde der Slot in diesem Frame berechnet (lebt, Balken lebt)? */
    var drawn = BooleanArray(0)

    fun drawAll(snap: FrameSnapshot) {
        if (muzzleX.size < snap.deviceCount) {
            val n = maxOf(snap.deviceCount, 32)
            muzzleX = FloatArray(n); muzzleY = FloatArray(n); centerX = FloatArray(n); centerY = FloatArray(n); radius = FloatArray(n)
            topX = FloatArray(n); topY = FloatArray(n); normalX = FloatArray(n); normalY = FloatArray(n); drawn = BooleanArray(n)
            mountX = FloatArray(n); mountY = FloatArray(n)
        }
        for (i in 0 until snap.deviceCount) { radius[i] = 0f; drawn[i] = false }
        val minX = c.visMinX(4f); val maxX = c.visMaxX(4f); val minY = c.visMinY(6f); val maxY = c.visMaxY(4f)
        for (i in 0 until snap.deviceCount) {
            if ((snap.deviceFlags[i] and DeviceFlags.ALIVE) == 0) continue
            val type = snap.deviceType[i]
            if (type < 0 || type >= c.tables.devices.size) continue
            val beam = snap.deviceBeam[i]
            if (beam < 0 || beam >= snap.beamCount || (snap.beamFlags[beam] and BeamFlags.ALIVE) == 0) continue
            val props = c.tables.devices[type]
            val a = snap.beamA[beam]; val b = snap.beamB[beam]
            val mat = snap.beamMaterial[beam]
            val thick = if (mat >= 0 && mat < c.matThick.size) c.matThick[mat] else 0.3f
            DeviceGeometry.mountAt(
                c.ix[a], c.iy[a], c.ix[b], c.iy[b], snap.deviceT[i],
                (snap.deviceFlags[i] and DeviceFlags.SIDE_NEG) != 0,
                thick, props.mountOffset, props.pivotOffset, props.barrelLength, snap.deviceAim[i], geo,
            )
            muzzleX[i] = geo[DeviceGeometry.MUZZLE_X]; muzzleY[i] = geo[DeviceGeometry.MUZZLE_Y]
            val cx = geo[DeviceGeometry.CX]; val cy = geo[DeviceGeometry.CY]
            centerX[i] = cx; centerY[i] = cy; radius[i] = props.hitRadius
            val nx = geo[DeviceGeometry.NX]; val ny = geo[DeviceGeometry.NY]
            normalX[i] = nx; normalY[i] = ny
            mountX[i] = geo[DeviceGeometry.X]; mountY[i] = geo[DeviceGeometry.Y]
            topX[i] = geo[DeviceGeometry.X] + nx * REACTOR_TOP; topY[i] = geo[DeviceGeometry.Y] + ny * REACTOR_TOP
            drawn[i] = true
            if (cx < minX || cx > maxX || cy < minY || cy > maxY) continue
            drawDevice(snap, i, props.hitRadius, c.devKind[type], props.pivotOffset, props.barrelLength)
        }
    }

    private companion object {
        /** Höhe der Reaktor-Kühlrippen über dem Montagepunkt (Gerätekoordinaten, siehe [reactor]). */
        const val REACTOR_TOP = 2.62f
    }

    private fun drawDevice(snap: FrameSnapshot, i: Int, rad: Float, kind: Int, pivot: Float, barrel: Float) {
        val sink = c.sink
        val mx = geo[DeviceGeometry.X]; val my = geo[DeviceGeometry.Y]
        val nx = geo[DeviceGeometry.NX]; val ny = geo[DeviceGeometry.NY]
        val fr = atan2(nx, -ny)
        val team = Palette.team(snap.deviceOwner[i])
        val hp = snap.deviceHp01[i].coerceIn(0f, 1f)
        val build = snap.deviceBuild01[i]
        val uid = snap.deviceUid[i]
        sink.save()
        sink.translate(mx, my)
        sink.rotate(fr)
        val building = build < 0.999f && (snap.deviceFlags[i] and DeviceFlags.BUILDING) != 0
        if (building) {
            sink.save()
            val hgt = 3.8f
            sink.clipRect(-3f, -build * hgt, 6f, build * hgt + 0.3f)
        }
        drawKind(kind, uid, team, hp, fx.devSpin[i], fx.devPhase[i], snap.deviceAim[i], fx.devRecoil[i], pivot, barrel)
        if (building) {
            sink.restore()
            // Bau-Fortschritt: Scanlinie und gestricheltes Gerüst
            val hgt = 3.8f
            val y = -build * hgt
            sink.fillRect(-1.4f, y - c.px, 2.8f, c.px * 2.2f, SceneContext.a(Palette.RUST, 0.95f))
            val wx = 1.3f
            sink.dashedLine(-wx, 0f, -wx, -hgt, c.px * 1.6f, SceneContext.a(Palette.WHITE, 0.5f), 0.12f, 0.1f)
            sink.dashedLine(wx, 0f, wx, -hgt, c.px * 1.6f, SceneContext.a(Palette.WHITE, 0.5f), 0.12f, 0.1f)
        }
        sink.restore()

        // Bohrgestänge der Mine zum Erz (Weltkoordinaten)
        if (kind == DevKind.MINE && !building) {
            val gy = terrain.heightAt(mx) + 0.45f
            if (gy - my < 6f && gy > my + 0.2f) {
                sink.line(mx, my + 0.05f, mx, gy, 0.14f, Palette.STEEL, false)
                sink.dashedLine(mx, my + 0.05f, mx, gy, 0.05f, Palette.BOLT, 0.1f, 0.1f)
            }
        }
        // Laserstrahl
        if ((snap.deviceFlags[i] and DeviceFlags.FIRING_BEAM) != 0) laserBeam(snap, i)
        val fl = fx.devFlash[i]
        val cx = geo[DeviceGeometry.CX]; val cy = geo[DeviceGeometry.CY]
        if (fl > 0f) sink.glow(cx, cy, rad * 1.4f, SceneContext.a(Palette.GLOW_PEACH, fl * 0.6f))
        if (hp < 0.999f && kind != DevKind.REACTOR) {
            val bw = 1.2f
            val bx = cx - bw / 2f
            val by = cy - rad - (if (kind == DevKind.TURBINE) 1.6f else 0.45f)
            sink.fillRect(bx - 0.05f, by - 0.05f, bw + 0.1f, 0.2f, Palette.withAlpha(Palette.INK, 0.8f))
            sink.fillRect(bx, by, bw * hp, 0.1f, if (hp > 0.5f) Palette.OK else if (hp > 0.25f) Palette.HAZARD else Palette.INVALID)
        }
    }

    private fun drawKind(kind: Int, uid: Int, team: Int, hp: Float, spin: Float, phase: Float, aim: Float, recoil: Float, pivot: Float, barrel: Float) {
        when (kind) {
            DevKind.REACTOR -> reactor(uid, team, hp, phase)
            DevKind.MINE -> mine(spin, team)
            DevKind.TURBINE -> turbine(spin, team)
            DevKind.WORKSHOP -> workshop(team)
            DevKind.ARMOURY -> armoury(team)
            DevKind.UPGRADE -> upgradeCenter(team)
            DevKind.FACTORY -> factory(team)
            else -> weapon(aim, recoil, kind, team, pivot, barrel)
        }
    }

    /**
     * Vorschau-Gerät beim Platzieren (Montagepunkt [x],[y] mit Normale [nx],[ny]): das echte Aussehen, darüber
     * ein grüner/roter Farbschleier und gestrichelter Ring (Stil-Bibel §7).
     */
    fun drawGhost(typeId: Int, x: Float, y: Float, nx: Float, ny: Float, valid: Boolean, localPlayer: Int) {
        if (typeId < 0 || typeId >= c.tables.devices.size) return
        val props = c.tables.devices[typeId]
        val kind = c.devKind[typeId]
        val sink = c.sink
        val aim = if (localPlayer == 0) 0.87f else (PI.toFloat() - 0.87f)
        geo[DeviceGeometry.NX] = nx; geo[DeviceGeometry.NY] = ny
        sink.save()
        sink.translate(x, y)
        sink.rotate(atan2(nx, -ny))
        drawKind(kind, 1, Palette.team(localPlayer), 1f, c.time, c.time * 2.6f, aim, 0f, props.pivotOffset, props.barrelLength)
        sink.restore()
        val cx = x + nx * props.mountOffset
        val cy = y + ny * props.mountOffset
        val col = if (valid) Palette.OK else Palette.INVALID
        sink.fillCircle(cx, cy, props.hitRadius + 0.2f, SceneContext.a(col, 0.22f))
        ringDashed(cx, cy, props.hitRadius + 0.25f, c.dp * 2.4f, col)
    }

    /** Gestrichelter Kreis aus kurzen Linien (kein Pfad-Primitiv in der Senke). */
    fun ringDashed(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        val n = 28
        var k = 0
        while (k < n) { // while statt `step`: keine IntProgression je Aufruf (kein Objekt im Frame)
            val a0 = k.toFloat() / n * SceneContext.TAU
            val a1 = (k + 1f) / n * SceneContext.TAU
            c.sink.line(cx + cos(a0) * r, cy + sin(a0) * r, cx + cos(a1) * r, cy + sin(a1) * r, width, color, true)
            k += 2
        }
    }

    private fun laserBeam(snap: FrameSnapshot, i: Int) {
        val sink = c.sink
        val x0 = geo[DeviceGeometry.MUZZLE_X]; val y0 = geo[DeviceGeometry.MUZZLE_Y]
        val x1 = snap.deviceLaserEndX[i]; val y1 = snap.deviceLaserEndY[i]
        val flick = if (c.reduced) 1f else 0.85f + 0.15f * sin(c.time * 60f + i)
        sink.line(x0, y0, x1, y1, 0.34f, SceneContext.a(Palette.ENERGY, 0.22f * flick), true)
        sink.line(x0, y0, x1, y1, 0.16f, SceneContext.a(Palette.ENERGY, 0.6f * flick), true)
        sink.line(x0, y0, x1, y1, 0.06f, Palette.ENERGY_HI, true)
        sink.glow(x1, y1, 0.9f, SceneContext.a(Palette.ENERGY, 0.7f * flick))
        sink.glow(x0, y0, 0.6f, SceneContext.a(Palette.ENERGY, 0.6f * flick))
    }

    // ------------------------------------------------------------------------------------------
    // Reaktor
    // ------------------------------------------------------------------------------------------
    private fun reactor(uid: Int, team: Int, hp: Float, phase: Float) {
        val sink = c.sink
        c.feet(0.82f)
        val x0 = -1.1f; val y0 = -2.36f; val w = 2.2f; val h = 2.2f
        // Kühlrippen oben
        for (k in 0 until 7) {
            val x = -0.93f + k * 0.28f
            c.boxV(x, y0 - 0.26f, 0.17f, 0.3f, Palette.STEEL_HI, Palette.STEEL_MID)
        }
        c.roundRectGrad(x0, y0, w, h, 0.16f, Palette.STEEL_BODY, Palette.STEEL_SHADE, Palette.STEEL)
        sink.fillRect(x0 + 0.1f, y0 + 0.05f, w - 0.2f, 0.04f, Palette.withAlpha(Palette.WHITE, 0.141f))
        c.hazard(x0 + 0.06f, -0.62f, w - 0.12f, 0.42f, 0.2f)
        for (k in 0 until 4) {
            val bx = if (k % 2 == 0) x0 + 0.14f else x0 + w - 0.14f
            val by = if (k < 2) y0 + 0.16f else -0.74f
            sink.fillCircle(bx, by, 0.04f, SceneContext.BOLT)
        }
        // Sichtfenster mit pulsierendem cyan Kern und Kreuzstreben
        val cy = -1.42f
        val r = 0.62f
        val pulse = if (c.reduced) 0.85f else 0.7f + 0.3f * sin(phase)
        c.disc(0f, cy, r + 0.12f, Palette.STEEL_LIGHT)
        sink.strokeCircle(0f, cy, r + 0.06f, 0.06f, Palette.withAlpha(Palette.STEEL_PALE, 0.4f))
        sink.fillCircle(0f, cy, r, Palette.CORE_BG)
        // Kern aus konzentrischen Scheiben (der Prototyp nutzt einen weichen Radialverlauf): Das Pulsieren moduliert die
        // Helligkeit zwischen 73 % und 100 %, nicht die Deckkraft bis nahe Null, damit der Kern cyan leuchtet.
        val pa = 0.55f + 0.45f * pulse
        sink.fillCircle(0f, cy, r * 0.97f, SceneContext.a(Palette.ENERGY_DEEP, 0.9f))
        sink.fillCircle(0f, cy, r * 0.8f, SceneContext.a(Palette.ENERGY_MID, 0.92f * pa))
        sink.fillCircle(-0.04f, cy - 0.04f, r * 0.52f, SceneContext.a(Palette.ENERGY, 0.97f * pa))
        sink.fillCircle(-0.1f, cy - 0.1f, r * 0.24f, SceneContext.a(Palette.ENERGY_HI, pa))
        sink.fillRect(-r, cy - 0.055f, 2f * r, 0.11f, Palette.STEEL)
        sink.fillRect(-0.055f, cy - r, 0.11f, 2f * r, Palette.STEEL)
        sink.fillRect(-r, cy - 0.055f, 2f * r, 0.025f, Palette.withAlpha(Palette.GLASS, 0.349f))
        sink.fillRect(-0.055f, cy - r, 0.025f, 2f * r, Palette.withAlpha(Palette.GLASS, 0.349f))
        sink.fillCircle(0f, cy, 0.1f, Palette.BOLT)
        sink.fillCircle(-0.25f, cy - 0.3f, 0.09f, Palette.withAlpha(Palette.WHITE, 0.349f))
        sink.glow(0f, cy, 1.7f, SceneContext.a(Palette.ENERGY, 0.55f * pulse))
        // Teamfarben-Lämpchen
        val lp = if (c.reduced) 0.8f else 0.6f + 0.4f * sin(c.time * 3f + team)
        c.disc(-0.82f, y0 + 0.32f, 0.1f, team)
        sink.glow(-0.82f, y0 + 0.32f, 0.3f, SceneContext.a(team, 0.5f * lp))
        sink.fillCircle(-0.85f, y0 + 0.29f, 0.03f, Palette.withAlpha(Palette.WHITE, 0.702f))
        if (hp < 0.999f) {
            sink.fillRect(x0, y0, w, h, SceneContext.a(Palette.SOOT, 0.5f * (1f - hp)))
            val rr = rng
            rr.reseed(uid)
            val cnt = kotlin.math.ceil((1f - hp) * 6f).toInt()
            for (k in 0 until cnt) {
                var x = (rr.next() - 0.5f) * r
                var y = cy + (rr.next() - 0.5f) * r
                for (q in 0 until 3) {
                    val nx = x + (rr.next() - 0.5f) * 0.3f
                    val ny = y + (rr.next() - 0.5f) * 0.3f
                    sink.line(x, y, nx, ny, 0.025f, Palette.withAlpha(Palette.ENERGY_HI, 0.749f), false)
                    x = nx; y = ny
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Mine
    // ------------------------------------------------------------------------------------------
    private fun mine(spin: Float, team: Int) {
        val sink = c.sink
        c.feet(0.7f)
        val top = -2.45f
        // Gitter-Bohrturm: Beine
        sink.line(-0.72f, -0.14f, -0.12f, top, 0.14f, Palette.OUTLINE, true)
        sink.line(0.72f, -0.14f, 0.12f, top, 0.14f, Palette.OUTLINE, true)
        sink.line(-0.72f, -0.14f, -0.12f, top, 0.09f, Palette.WOOD, true)
        sink.line(0.72f, -0.14f, 0.12f, top, 0.09f, Palette.WOOD, true)
        for (k in 0 until 4) {
            val y1 = -0.3f - k * 0.52f
            val y2 = y1 - 0.52f
            val w1 = 0.72f - (0.6f * (-y1 - 0.14f) / 2.3f)
            val w2 = 0.72f - (0.6f * (-y2 - 0.14f) / 2.3f)
            sink.line(-w1, y1, w2, y2, 0.045f, Palette.WOOD_FRAME, false)
            sink.line(w1, y1, -w2, y2, 0.045f, Palette.WOOD_FRAME, false)
            sink.line(-w2, y2, w2, y2, 0.045f, Palette.WOOD_FRAME, false)
        }
        // Rad oben (dreht)
        sink.save()
        sink.translate(0f, top - 0.02f)
        c.disc(0f, 0f, 0.3f, Palette.STEEL)
        sink.strokeCircle(0f, 0f, 0.24f, 0.05f, Palette.STEEL_PALE)
        for (k in 0 until 6) {
            val a = spin + k / 6f * SceneContext.TAU
            sink.line(0f, 0f, cos(a) * 0.24f, sin(a) * 0.24f, 0.035f, Palette.STEEL_PALE, false)
        }
        sink.fillCircle(0f, 0f, 0.07f, Palette.RUST)
        sink.restore()
        // Förderband zur Seite + Motorblock
        val p = c.poly
        p[0] = 0.45f; p[1] = -0.42f; p[2] = 1.35f; p[3] = -0.95f; p[4] = 1.42f; p[5] = -0.84f; p[6] = 0.52f; p[7] = -0.3f
        c.polyOutlined(4, Palette.STEEL)
        for (k in 0 until 3) {
            val u = SceneContext.fract(c.time * 0.45f + k / 3f)
            sink.fillCircle(SceneContext.lerp(0.55f, 1.32f, u), SceneContext.lerp(-0.48f, -0.98f, u) - 0.07f, 0.06f, Palette.ENERGY)
        }
        sink.line(1.15f, -0.14f, 1.35f, -0.95f, 0.05f, Palette.STEEL_MID, false)
        sink.line(1.55f, -0.14f, 1.38f, -0.9f, 0.05f, Palette.STEEL_MID, false)
        p[0] = 1.1f; p[1] = -0.14f; p[2] = 1.7f; p[3] = -0.14f; p[4] = 1.5f; p[5] = -0.42f; p[6] = 1.25f; p[7] = -0.38f
        c.polyOutlined(4, Palette.STEEL_LIGHT)
        c.roundRectGrad(-0.5f, -0.78f, 1f, 0.64f, 0.06f, Palette.RUST_LIGHT, Palette.RUST, Palette.RUST_DARK)
        sink.fillRect(-0.36f, -0.66f, 0.3f, 0.2f, Palette.STEEL_DEEP)
        sink.fillRect(-0.33f, -0.63f, 0.24f, 0.14f, Palette.LAMP)
        sink.fillRect(0.08f, -0.66f, 0.3f, 0.08f, team)
        sink.fillRect(-0.46f, -0.76f, 0.92f, 0.03f, Palette.withAlpha(Palette.WHITE, 0.251f))
    }

    // ------------------------------------------------------------------------------------------
    // Windturbine
    // ------------------------------------------------------------------------------------------
    private fun turbine(spin: Float, team: Int) {
        val sink = c.sink
        c.feet(0.42f)
        val p = c.poly
        p[0] = -0.42f; p[1] = -0.14f; p[2] = 0.42f; p[3] = -0.14f; p[4] = 0.3f; p[5] = -0.36f; p[6] = -0.3f; p[7] = -0.36f
        c.polyOutlined(4, Palette.STEEL_SHADE)
        // Mast: schlankes Trapez, links hell → rechts dunkel
        p[0] = -0.12f; p[1] = -0.36f; p[2] = 0.12f; p[3] = -0.36f; p[4] = 0.055f; p[5] = -3.35f; p[6] = -0.055f; p[7] = -3.35f
        c.polyOutlined(4, Palette.STEEL_PALE)
        p[0] = -0.12f; p[1] = -0.36f; p[2] = -0.02f; p[3] = -0.36f; p[4] = -0.02f; p[5] = -3.35f; p[6] = -0.055f; p[7] = -3.35f
        sink.fillPolygon(p, 4, Palette.STEEL_WHITE)
        sink.fillRect(-0.1f, -0.9f, 0.2f, 0.1f, team)
        sink.save()
        sink.translate(0f, -3.4f)
        c.roundRect(-0.2f, -0.13f, 0.48f, 0.26f, 0.1f, Palette.STEEL_HI)
        for (k in 0 until 3) {
            sink.save()
            sink.rotate(spin + k * 2.0944f)
            // Blatt: schmale Tropfenform, Rost-Spitze
            val q = c.poly
            q[0] = 0.08f; q[1] = -0.05f
            q[2] = 0.5f; q[3] = -0.11f
            q[4] = 1.0f; q[5] = -0.115f
            q[6] = 1.62f; q[7] = -0.035f
            q[8] = 1.62f; q[9] = 0.03f
            q[10] = 1.0f; q[11] = 0.06f
            q[12] = 0.5f; q[13] = 0.07f
            q[14] = 0.08f; q[15] = 0.07f
            sink.fillPolygon(q, 8, Palette.STEEL_WHITE)
            // Rost-Spitze
            q[0] = 1.2f; q[1] = -0.1f; q[2] = 1.62f; q[3] = -0.035f; q[4] = 1.62f; q[5] = 0.03f; q[6] = 1.2f; q[7] = 0.045f
            sink.fillPolygon(q, 4, Palette.RUST)
            q[0] = 0.08f; q[1] = -0.05f
            q[2] = 0.5f; q[3] = -0.11f
            q[4] = 1.0f; q[5] = -0.115f
            q[6] = 1.62f; q[7] = -0.035f
            q[8] = 1.62f; q[9] = 0.03f
            q[10] = 1.0f; q[11] = 0.06f
            q[12] = 0.5f; q[13] = 0.07f
            q[14] = 0.08f; q[15] = 0.07f
            c.polyStroke(q, 8, SceneContext.OUTLINE, c.px * 1.2f)
            sink.restore()
        }
        c.disc(0f, 0f, 0.11f, Palette.STEEL)
        sink.restore()
    }

    // ------------------------------------------------------------------------------------------
    // Techgebäude
    // ------------------------------------------------------------------------------------------
    private fun smokePuffs(x: Float, y: Float, n: Int, seed: Int) {
        val wind = c.wind
        for (k in 0 until n) {
            val u = SceneContext.fract(c.time * 0.28f + k.toFloat() / n + seed * 0.37f)
            val rr = 0.12f + u * 0.32f
            c.sink.fillCircle(x + wind * u * 0.12f - u * 0.1f, y - u * 1.5f, rr, SceneContext.a(Palette.SMOKE_YOUNG, (1f - u) * 0.55f))
        }
    }

    private fun workshop(team: Int) {
        val sink = c.sink
        c.feet(0.8f)
        // Körper (dunkler Stahl) + Dach in Rost
        c.boxV(-0.95f, -1.5f, 1.9f, 1.36f, Palette.STEEL_MID, Palette.STEEL)
        sink.fillRect(-0.95f, -1.5f, 1.9f, 0.04f, Palette.withAlpha(Palette.WHITE, 0.2f))
        val p = c.poly
        p[0] = -1.12f; p[1] = -1.48f; p[2] = 1.12f; p[3] = -1.48f; p[4] = 0.82f; p[5] = -1.98f; p[6] = -0.82f; p[7] = -1.98f
        c.polyOutlined(4, Palette.RUST)
        for (k in 1 until 4) sink.line(-1.0f + k * 0.5f - 0.2f, -1.46f, -0.8f + k * 0.4f - 0.2f, -1.96f, c.px * 1.4f, Palette.withAlpha(Palette.GLOW_PEACH, 0.4f), false)
        sink.fillRect(-1.12f, -1.5f, 2.24f, 0.06f, Palette.RUST_DARK)
        // Schornstein
        c.box(0.42f, -2.34f, 0.24f, 0.42f, Palette.STEEL_SHADE)
        smokePuffs(0.54f, -2.36f, 2, 1)
        // Zahnrad-Schild
        val ng = c.gearPoly(-0.4f, -0.86f, 0.4f, 9, c.time * 0.0f)
        c.polyOutlined(ng, Palette.STEEL_HI)
        sink.fillCircle(-0.4f, -0.86f, 0.16f, Palette.DARK)
        sink.fillCircle(-0.4f, -0.86f, 0.07f, Palette.STEEL_HI)
        // gelb beleuchtetes Fenster
        sink.fillRect(0.18f, -1.3f, 0.62f, 0.52f, Palette.DARK)
        sink.fillRect(0.22f, -1.26f, 0.54f, 0.44f, Palette.LAMP)
        sink.fillRect(0.48f, -1.26f, 0.04f, 0.44f, Palette.LAMP_FRAME)
        sink.fillRect(0.22f, -1.06f, 0.54f, 0.04f, Palette.LAMP_FRAME)
        sink.strokeRect(0.18f, -1.3f, 0.62f, 0.52f, c.px * 1.2f, SceneContext.OUTLINE)
        sink.glow(0.5f, -1.04f, 1.1f, SceneContext.a(Palette.LAMP, 0.5f))
        // Werkbank / Team
        sink.fillRect(0.18f, -0.62f, 0.62f, 0.4f, Palette.STEEL_SHADE)
        sink.fillRect(0.2f, -0.6f, 0.58f, 0.08f, team)
    }

    private fun armoury(team: Int) {
        val sink = c.sink
        c.feet(0.85f)
        val p = c.poly
        // Bunker
        p[0] = -1.0f; p[1] = -0.14f; p[2] = 1.0f; p[3] = -0.14f; p[4] = 1.0f; p[5] = -1.22f; p[6] = 0.74f; p[7] = -1.5f; p[8] = -0.74f; p[9] = -1.5f; p[10] = -1.0f; p[11] = -1.22f
        c.polyOutlined(6, Palette.STEEL_SHADE)
        sink.fillRect(-0.7f, -1.48f, 1.4f, 0.05f, Palette.withAlpha(Palette.WHITE, 0.18f))
        sink.fillRect(-0.98f, -1.2f, 1.96f, 0.05f, Palette.withAlpha(Palette.WHITE, 0.141f))
        // Tor mit Warnstreifen
        c.box(-0.5f, -1.04f, 1.0f, 0.9f, Palette.STEEL_DEEP)
        c.hazard(-0.5f, -1.06f, 1.0f, 0.2f, 0.2f)
        for (k in 0 until 4) sink.fillRect(-0.46f, -0.78f + k * 0.15f, 0.92f, 0.035f, Palette.STEEL_SHADE)
        // Munition seitlich
        for (k in 0 until 2) {
            val x = if (k == 0) -0.78f else 0.78f
            sink.fillRect(x - 0.07f, -0.7f, 0.14f, 0.42f, Palette.BRASS)
            val q = c.poly2
            q[0] = x - 0.07f; q[1] = -0.7f; q[2] = x + 0.07f; q[3] = -0.7f; q[4] = x; q[5] = -0.92f
            sink.fillPolygon(q, 3, Palette.WOOD_WARM)
            sink.strokeRect(x - 0.07f, -0.7f, 0.14f, 0.42f, c.px, SceneContext.OUTLINE)
        }
        // Fadenkreuz-Emblem
        sink.strokeCircle(0f, -1.3f, 0.17f, 0.04f, Palette.HAZARD)
        sink.line(-0.25f, -1.3f, 0.25f, -1.3f, 0.03f, Palette.HAZARD, false)
        sink.line(0f, -1.15f, 0f, -1.45f, 0.03f, Palette.HAZARD, false)
        // Mast mit Teamwimpel
        sink.line(0.62f, -1.5f, 0.62f, -2.5f, 0.1f, SceneContext.OUTLINE, true)
        sink.line(0.62f, -1.5f, 0.62f, -2.5f, 0.05f, Palette.STEEL_PALE, true)
        val w = c.wind
        val flap = sin(c.time * 6f) * 0.06f
        val dir = if (w >= 0f) 1f else -1f
        val q = c.poly2
        q[0] = 0.62f; q[1] = -2.5f; q[2] = 0.62f + dir * 0.6f; q[3] = -2.4f + flap; q[4] = 0.62f; q[5] = -2.24f
        c.polyOutlined2(3, team)
        sink.fillRect(-0.55f, -0.2f, 1.1f, 0.06f, SceneContext.a(team, 0.9f))
    }

    private fun upgradeCenter(team: Int) {
        val sink = c.sink
        c.feet(0.9f)
        // Unterbau und Kabine
        c.boxV(-0.98f, -0.92f, 1.2f, 0.78f, Palette.STEEL_MID, Palette.STEEL)
        c.boxV(0.22f, -0.7f, 0.74f, 0.56f, Palette.STEEL_SHADE, Palette.STEEL_DARK)
        sink.fillRect(0.3f, -0.6f, 0.58f, 0.18f, Palette.DARK)
        sink.fillRect(0.34f, -0.57f, 0.5f, 0.12f, SceneContext.a(Palette.ENERGY, 0.55f))
        sink.fillRect(-0.9f, -0.82f, 0.5f, 0.07f, team)
        // Pfeil-Schild
        c.box(-0.9f, -1.9f, 0.62f, 0.9f, Palette.STEEL)
        val a = c.poly2
        a[0] = -0.59f; a[1] = -1.78f; a[2] = -0.4f; a[3] = -1.5f; a[4] = -0.51f; a[5] = -1.5f; a[6] = -0.51f; a[7] = -1.14f; a[8] = -0.67f; a[9] = -1.14f; a[10] = -0.67f; a[11] = -1.5f; a[12] = -0.78f; a[13] = -1.5f
        sink.fillPolygon(a, 7, Palette.HAZARD)
        // Kran: Mast, Ausleger, Haken
        c.box(0.5f, -2.3f, 0.1f, 1.62f, Palette.RUST)
        c.box(0.0f, -2.34f, 1.12f, 0.1f, Palette.RUST)
        sink.line(0.98f, -2.24f, 0.98f, -1.78f, 0.03f, Palette.STEEL_PALE, false)
        c.box(0.9f, -1.8f, 0.16f, 0.12f, Palette.STEEL_HI)
        sink.line(0.5f, -2.3f, 0.12f, -2.24f, 0.04f, Palette.OUTLINE, false)
    }

    private fun factory(team: Int) {
        val sink = c.sink
        c.feet(1.1f)
        c.boxV(-1.25f, -1.22f, 2.5f, 1.08f, Palette.STEEL_MID, Palette.STEEL)
        // Schornsteine
        c.box(0.55f, -2.5f, 0.26f, 1.3f, Palette.STEEL_SHADE)
        c.box(0.95f, -2.2f, 0.22f, 1.0f, Palette.STEEL_SHADE)
        sink.fillRect(0.52f, -2.52f, 0.32f, 0.07f, Palette.DARK)
        sink.fillRect(0.92f, -2.22f, 0.28f, 0.07f, Palette.DARK)
        smokePuffs(0.68f, -2.55f, 3, 2)
        smokePuffs(1.06f, -2.26f, 2, 3)
        // Sägezahndach
        val p = c.poly
        var n = 0
        p[0] = -1.25f; p[1] = -1.22f; n = 1
        for (k in 0 until 4) {
            val x0 = -1.25f + k * 0.5f
            p[n * 2] = x0; p[n * 2 + 1] = -1.7f; n++
            p[n * 2] = x0 + 0.5f; p[n * 2 + 1] = -1.22f; n++
        }
        c.polyOutlined(n, Palette.STEEL)
        for (k in 0 until 4) sink.line(-1.25f + k * 0.5f + 0.04f, -1.66f, -1.25f + k * 0.5f + 0.44f, -1.26f, c.px * 1.2f, Palette.withAlpha(Palette.WHITE, 0.2f), false)
        // beleuchtete Fenster
        for (k in 0 until 3) {
            val x = -1.05f + k * 0.5f
            sink.fillRect(x, -0.98f, 0.36f, 0.3f, Palette.DARK)
            sink.fillRect(x + 0.03f, -0.95f, 0.3f, 0.24f, Palette.FIRE_INNER)
        }
        sink.fillRect(0.58f, -1.0f, 0.5f, 0.34f, Palette.RUST)
        sink.strokeRect(0.58f, -1.0f, 0.5f, 0.34f, c.px, SceneContext.OUTLINE)
        sink.glow(-0.4f, -0.8f, 1.5f, SceneContext.a(Palette.FIRE_INNER, 0.4f))
        // Förderband mit Rollen
        c.box(-1.22f, -0.42f, 2.44f, 0.16f, Palette.STEEL_DARK)
        for (k in 0 until 9) {
            val x = -1.1f + SceneContext.fract(c.time * 0.3f + k / 9f) * 2.2f
            sink.fillCircle(x, -0.34f, 0.04f, Palette.BOLT)
        }
        sink.fillRect(-1.2f, -0.2f, 2.4f, 0.05f, SceneContext.a(team, 0.9f))
    }

    // ------------------------------------------------------------------------------------------
    // Waffen
    // ------------------------------------------------------------------------------------------
    private fun weapon(aim: Float, recoil: Float, kind: Int, team: Int, piv: Float, L: Float) {
        val sink = c.sink
        val fr = atan2(geo[DeviceGeometry.NX], -geo[DeviceGeometry.NY])
        val rec = recoil * recoil
        // Unterbau
        when (kind) {
            DevKind.MORTAR -> {
                c.feet(0.5f)
                val p = c.poly
                p[0] = -0.62f; p[1] = -0.14f; p[2] = 0.62f; p[3] = -0.14f; p[4] = 0.46f; p[5] = -0.32f; p[6] = -0.46f; p[7] = -0.32f
                c.polyOutlined(4, Palette.STEEL_SHADE)
                sink.save(); sink.translate(0f, -0.34f); sink.scale(1f, 0.2f)
                sink.fillCircle(0f, 0f, 0.5f, Palette.STEEL_LIGHT)
                sink.restore()
                sink.save(); sink.translate(0f, -0.34f); sink.scale(1f, 0.2f)
                sink.strokeCircle(0f, 0f, 0.5f, c.px * 6f, SceneContext.OUTLINE)
                sink.restore()
                sink.fillRect(-0.46f, -0.27f, 0.92f, 0.05f, team)
                p[0] = -0.22f; p[1] = -0.38f; p[2] = -0.12f; p[3] = -piv - 0.1f; p[4] = 0.12f; p[5] = -piv - 0.1f; p[6] = 0.22f; p[7] = -0.38f
                c.polyOutlined(4, Palette.STEEL)
            }
            DevKind.CANNON -> {
                c.feet(0.62f, Palette.WOOD_DEEP)
                val p = c.poly
                p[0] = -0.85f; p[1] = -0.14f; p[2] = 0.6f; p[3] = -0.14f; p[4] = 0.42f; p[5] = -0.62f; p[6] = -0.3f; p[7] = -0.82f; p[8] = -0.85f; p[9] = -0.42f
                c.polyOutlined(5, Palette.WOOD_WARM)
                p[0] = -0.85f; p[1] = -0.14f; p[2] = 0.6f; p[3] = -0.14f; p[4] = 0.5f; p[5] = -0.34f; p[6] = -0.85f; p[7] = -0.34f
                sink.fillPolygon(p, 4, Palette.WOOD_DARK)
                sink.fillRect(-0.5f, -0.5f, 0.9f, 0.07f, Palette.STEEL)
            }
            DevKind.MG, DevKind.SNIPER -> {
                // Dreibein
                val w = if (kind == DevKind.SNIPER) 0.55f else 0.5f
                sink.line(-w, -0.02f, 0f, -piv + 0.1f, 0.1f, SceneContext.OUTLINE, true)
                sink.line(w, -0.02f, 0f, -piv + 0.1f, 0.1f, SceneContext.OUTLINE, true)
                sink.line(0.08f, -0.02f, 0f, -piv + 0.1f, 0.1f, SceneContext.OUTLINE, true)
                sink.line(-w, -0.02f, 0f, -piv + 0.1f, 0.055f, Palette.STEEL_MID, true)
                sink.line(w, -0.02f, 0f, -piv + 0.1f, 0.055f, Palette.STEEL_MID, true)
                sink.line(0.08f, -0.02f, 0f, -piv + 0.1f, 0.055f, Palette.STEEL_MID, true)
                sink.fillRect(-0.07f, -piv + 0.08f, 0.14f, 0.1f, team)
            }
            DevKind.ROCKET -> {
                // A-Gestell
                sink.line(-0.55f, -0.02f, 0.05f, -piv + 0.05f, 0.1f, SceneContext.OUTLINE, true)
                sink.line(0.55f, -0.02f, 0.05f, -piv + 0.05f, 0.1f, SceneContext.OUTLINE, true)
                sink.line(-0.55f, -0.02f, 0.05f, -piv + 0.05f, 0.055f, Palette.STEEL_HI, true)
                sink.line(0.55f, -0.02f, 0.05f, -piv + 0.05f, 0.055f, Palette.STEEL_HI, true)
                c.box(-0.2f, -0.2f, 0.4f, 0.18f, Palette.STEEL_SHADE)
                sink.fillRect(-0.18f, -0.14f, 0.36f, 0.05f, team)
            }
            else -> {
                // Laser: Drehteller
                c.feet(0.45f)
                val p = c.poly
                p[0] = -0.55f; p[1] = -0.14f; p[2] = 0.55f; p[3] = -0.14f; p[4] = 0.4f; p[5] = -0.34f; p[6] = -0.4f; p[7] = -0.34f
                c.polyOutlined(4, Palette.STEEL_SHADE)
                sink.fillRect(-0.45f, -0.3f, 0.9f, 0.05f, Palette.ENERGY)
                c.box(-0.12f, -piv + 0.12f, 0.24f, piv - 0.46f, Palette.STEEL)
            }
        }
        // Rohr dreht mit dem Zielwinkel (Zugrichtung = Schussrichtung)
        val ar = aim
        sink.save()
        sink.translate(0f, -piv)
        sink.rotate(-ar - fr)
        if (cos(ar) < 0f) sink.scale(1f, -1f)
        val rk = when (kind) { DevKind.CANNON -> 0.38f; DevKind.MG -> 0.12f; DevKind.SNIPER -> 0.18f; else -> 0.26f }
        sink.translate(-rec * rk, 0f)
        when (kind) {
            DevKind.MORTAR -> {
                val p = c.poly
                p[0] = -0.3f; p[1] = -0.25f; p[2] = L; p[3] = -0.28f; p[4] = L; p[5] = 0.28f; p[6] = -0.3f; p[7] = 0.25f; p[8] = -0.44f; p[9] = 0.1f; p[10] = -0.44f; p[11] = -0.1f
                c.polyOutlined(6, Palette.STEEL_MID)
                sink.fillRect(-0.3f, -0.25f, L + 0.3f, 0.12f, Palette.STEEL_HI)
                sink.fillRect(-0.3f, 0.12f, L + 0.3f, 0.14f, Palette.STEEL_DARK)
                sink.fillRect(0.36f, -0.29f, 0.14f, 0.58f, Palette.RUST)
                sink.fillRect(0.36f, -0.29f, 0.14f, 0.06f, Palette.withAlpha(Palette.GLOW_PEACH, 0.349f))
                c.box(L - 0.14f, -0.32f, 0.18f, 0.64f, Palette.STEEL_SHADE)
                sink.save(); sink.translate(L + 0.04f, 0f); sink.scale(0.3f, 1f)
                sink.fillCircle(0f, 0f, 0.22f, Palette.INK)
                sink.restore()
                c.disc(0f, 0f, 0.13f, Palette.STEEL)
            }
            DevKind.CANNON -> {
                val p = c.poly
                p[0] = -0.55f; p[1] = -0.22f; p[2] = L; p[3] = -0.15f; p[4] = L; p[5] = 0.15f; p[6] = -0.55f; p[7] = 0.22f; p[8] = -0.77f; p[9] = 0f
                c.polyOutlined(5, Palette.STEEL_MID)
                sink.fillRect(-0.55f, -0.2f, L + 0.55f, 0.1f, Palette.STEEL_HI)
                sink.fillRect(-0.55f, 0.1f, L + 0.55f, 0.11f, Palette.STEEL_DARK)
                for (k in 0 until 3) {
                    val x = if (k == 0) 0.15f else if (k == 1) 1.0f else L - 0.12f
                    c.box(x, -0.21f + x * 0.03f, 0.1f, 0.42f - x * 0.06f, Palette.STEEL_SHADE)
                }
                sink.save(); sink.translate(L + 0.02f, 0f); sink.scale(0.4f, 1f)
                sink.fillCircle(0f, 0f, 0.1f, Palette.INK)
                sink.restore()
                sink.fillRect(-0.4f, -0.17f, L, 0.035f, Palette.withAlpha(Palette.WHITE, 0.2f))
            }
            DevKind.MG -> {
                c.roundRect(-0.32f, -0.15f, 0.66f, 0.3f, 0.05f, Palette.STEEL_BODY)
                c.box(0.3f, -0.045f, L - 0.3f, 0.09f, Palette.STEEL)
                c.box(0.38f, -0.08f, 0.3f, 0.16f, Palette.STEEL_MID)
                sink.fillRect(-0.22f, 0.15f, 0.26f, 0.2f, Palette.OLIVE)
                for (k in 0 until 5) sink.fillRect(0.04f + k * 0.055f, 0.14f + k * 0.03f, 0.035f, 0.09f, Palette.BRASS)
                sink.fillRect(-0.12f, -0.27f, 0.2f, 0.12f, Palette.STEEL)
            }
            DevKind.SNIPER -> {
                // Schaft (Holz), Gehäuse, langer Lauf, Zielfernrohr mit cyan Linse
                val p = c.poly
                p[0] = -0.75f; p[1] = -0.05f; p[2] = -0.1f; p[3] = -0.1f; p[4] = -0.1f; p[5] = 0.12f; p[6] = -0.7f; p[7] = 0.2f
                c.polyOutlined(4, Palette.WOOD_WARM)
                c.box(-0.12f, -0.1f, 0.5f, 0.2f, Palette.STEEL_SHADE)
                c.box(0.36f, -0.035f, L - 0.36f, 0.07f, Palette.STEEL)
                c.box(L - 0.22f, -0.06f, 0.2f, 0.12f, Palette.STEEL_MID)
                c.box(0.02f, -0.27f, 0.62f, 0.12f, Palette.STEEL_DARK)
                sink.fillRect(0.2f, -0.15f, 0.04f, 0.06f, Palette.STEEL_MID)
                sink.fillCircle(0.64f, -0.21f, 0.075f, Palette.ENERGY_LIGHT)
                sink.glow(0.64f, -0.21f, 0.3f, SceneContext.a(Palette.ENERGY, 0.5f))
            }
            DevKind.ROCKET -> {
                // 3er-Werfer: drei Rohre, rote Spitzen, gelbes Band
                for (k in 0 until 3) {
                    val y = (k - 1) * 0.2f
                    c.box(-0.2f, y - 0.09f, L + 0.2f, 0.18f, Palette.STEEL_SHADE)
                    sink.fillRect(-0.2f, y - 0.09f, L + 0.2f, 0.05f, Palette.STEEL_LIGHT)
                    val t = c.poly2
                    t[0] = L; t[1] = y - 0.07f; t[2] = L + 0.26f; t[3] = y; t[4] = L; t[5] = y + 0.07f
                    c.polyOutlined2(3, Palette.TEAM_RED)
                }
                sink.fillRect(0.5f, -0.3f, 0.1f, 0.6f, Palette.HAZARD)
                sink.strokeRect(0.5f, -0.3f, 0.1f, 0.6f, c.px, SceneContext.OUTLINE)
            }
            else -> {
                // Laser: Gehäuse mit Linse
                c.roundRectGrad(-0.4f, -0.22f, 1.0f, 0.44f, 0.12f, Palette.BOLT, Palette.STEEL_BODY, Palette.STEEL)
                sink.fillRect(-0.3f, -0.12f, 0.6f, 0.1f, Palette.withAlpha(Palette.ENERGY, 0.4f))
                c.box(0.55f, -0.18f, L - 0.55f, 0.36f, Palette.STEEL_SHADE)
                sink.fillCircle(L - 0.04f, 0f, 0.2f, Palette.CORE_BG)
                sink.fillCircle(L - 0.04f, 0f, 0.16f, Palette.ENERGY)
                sink.fillCircle(L - 0.09f, -0.05f, 0.07f, Palette.ENERGY_HI)
                sink.glow(L - 0.04f, 0f, 0.55f, SceneContext.a(Palette.ENERGY, 0.55f))
            }
        }
        sink.restore()
        if (kind == DevKind.CANNON) {
            // Speichenrad vor dem Rohr
            sink.save()
            sink.translate(-0.05f, -0.42f)
            c.disc(0f, 0f, 0.4f, Palette.WOOD_BLACK)
            sink.fillCircle(0f, 0f, 0.33f, Palette.WOOD_WARM)
            sink.fillCircle(0f, 0f, 0.26f, Palette.STEEL_DARK)
            for (k in 0 until 8) {
                val a = k / 8f * SceneContext.TAU
                sink.line(0f, 0f, cos(a) * 0.27f, sin(a) * 0.27f, 0.06f, Palette.WOOD, false)
            }
            c.disc(0f, 0f, 0.08f, Palette.BOLT)
            sink.restore()
        }
    }
}
