package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.Palette
import de.bollwerk.renderapi.TextAlign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Überlagerungen aus [OverlayState] (Stil-Bibel §7). Weltteil ([drawWorld], in Metern): Ghost-Balken grün/rot
 * gestrichelt, Snap-Ringe, Auswahlrahmen, Flugbahn (Punkte werden kleiner) mit Einschlag-Fadenkreuz und
 * Splash-Radius, Ghost-Gerät. Bildschirmteil ([drawScreen], in Pixeln): Längen-/Kosten-Chip bzw. Grund-Text,
 * Scheitelhöhe und die 2×-Lupe (zeichnet die Welt erneut in einen kreisförmigen Clip). Gezeichnet wird nur,
 * was im Zustand vorhanden ist; ohne Overlay entstehen keine Aufrufe.
 */
internal class OverlayPainter(
    private val c: SceneContext,
    private val devices: DevicePainter,
    private val texts: SceneTexts,
) {
    private var lastLenKey = Int.MIN_VALUE
    private var lastLenText = ""
    private var lastCostKey = Int.MIN_VALUE
    private var lastCostText = ""
    private var lastApex = Int.MIN_VALUE
    private var lastApexText = ""
    private var lastDevMetalKey = Int.MIN_VALUE
    private var lastDevMetalText = ""
    private var lastDevEnergyKey = Int.MIN_VALUE
    private var lastDevEnergyText = ""

    // Textbreiten: der Sink misst, der Maler merkt sich das Ergebnis je Text-Instanz, Größe und Stärke (kleiner
    // Ringpuffer; die Texte sind gecachte Instanzen, daher kostet ein Frame keine Messung und keine Allokation).
    private val wText = arrayOfNulls<String>(WIDTH_CACHE)
    private val wSize = FloatArray(WIDTH_CACHE)
    private val wValue = FloatArray(WIDTH_CACHE)
    private var wNext = 0
    private var wSink: Any? = null

    private fun textWidth(text: String, size: Float): Float {
        if (wSink !== c.sink) { wSink = c.sink; for (k in 0 until WIDTH_CACHE) wText[k] = null }
        for (k in 0 until WIDTH_CACHE) if (wText[k] === text && wSize[k] == size) return wValue[k]
        val w = c.sink.measureText(text, size, true)
        val k = wNext
        wNext = if (k + 1 == WIDTH_CACHE) 0 else k + 1
        wText[k] = text; wSize[k] = size; wValue[k] = w
        return w
    }

    // Auswahl-Refs (Generation << 32 | Slot): Der Snapshot trägt keine Generation, deshalb merkt sich der Maler beim
    // ersten Sehen einer Ref die uid des Slots; wechselt sie oder stirbt das Objekt, ist die Auswahl veraltet
    // (CLAUDE.md Regel 7) und wird nicht mehr gezeichnet, auch wenn der Slot neu belegt wird.
    private var selBeamRef = -1L
    private var selBeamUid = 0
    private var selDevRef = -1L
    private var selDevUid = 0

    /** Slot des ausgewählten Balkens oder −1 (keine Auswahl, tot oder durch ein neues Objekt ersetzt). */
    fun selectedBeamSlot(snap: FrameSnapshot, ref: Long): Int {
        if (ref < 0L) { selBeamRef = -1L; return -1 }
        val slot = ref.toInt()
        val inRange = slot >= 0 && slot < snap.beamCount
        val alive = inRange && (snap.beamFlags[slot] and BeamFlags.ALIVE) != 0
        if (ref != selBeamRef) { selBeamRef = ref; selBeamUid = if (alive) snap.beamUid[slot] else DEAD_UID }
        return if (alive && snap.beamUid[slot] == selBeamUid) slot else -1
    }

    /** Slot des ausgewählten Geräts oder −1 (siehe [selectedBeamSlot]). */
    fun selectedDeviceSlot(snap: FrameSnapshot, ref: Long): Int {
        if (ref < 0L) { selDevRef = -1L; return -1 }
        val slot = ref.toInt()
        val inRange = slot >= 0 && slot < snap.deviceCount
        val alive = inRange && (snap.deviceFlags[slot] and DeviceFlags.ALIVE) != 0
        if (ref != selDevRef) { selDevRef = ref; selDevUid = if (alive) snap.deviceUid[slot] else DEAD_UID }
        return if (alive && snap.deviceUid[slot] == selDevUid) slot else -1
    }

    fun hasWorldOverlay(o: OverlayState): Boolean =
        o.ghost != null || o.ghostDevice != null || o.snaps.isNotEmpty() || o.trajectory != null || o.selectedDeviceRef >= 0 || o.selectedBeamRef >= 0

    // ---------------------------------------------------------------------------------------------
    // Weltteil
    // ---------------------------------------------------------------------------------------------

    fun drawWorld(snap: FrameSnapshot, o: OverlayState) {
        val sink = c.sink
        val dp = c.dp
        val t = c.time
        // ausgewählter Balken
        if (o.selectedBeamRef >= 0) {
            val slot = selectedBeamSlot(snap, o.selectedBeamRef)
            if (slot >= 0) {
                val a = snap.beamA[slot]; val b = snap.beamB[slot]
                sink.dashedLine(c.ix[a], c.iy[a], c.ix[b], c.iy[b], 2f * dp, Palette.withAlpha(Palette.CREAM, 0.902f), 6f * dp, 4f * dp)
            }
        }
        // ausgewähltes Gerät: blau gestrichelter Rahmen mit weißen Ecken
        if (o.selectedDeviceRef >= 0) {
            val slot = selectedDeviceSlot(snap, o.selectedDeviceRef)
            if (slot >= 0 && devices.radius[slot] > 0f) selectionFrame(devices.centerX[slot], devices.centerY[slot], devices.radius[slot])
        }
        // Flugbahn
        val tr = o.trajectory
        if (tr != null && tr.count >= 2) trajectory(snap, o, tr.count)
        // Ghost-Balken
        val g = o.ghost
        if (g != null) {
            val col = if (g.valid) Palette.OK else Palette.INVALID
            val mat = g.materialId
            val th = if (mat >= 0 && mat < c.matThick.size) c.matThick[mat] else 0.3f
            val dx = g.bx - g.ax; val dy = g.by - g.ay
            val l = sqrt(dx * dx + dy * dy)
            if (l > 1e-3f) {
                sink.save()
                sink.translate(g.ax, g.ay)
                sink.rotate(kotlin.math.atan2(dy, dx))
                sink.fillRect(0f, -th / 2f, l, th, SceneContext.a(col, 0.32f))
                val w = 2.4f * dp
                val d = 8f * dp
                val gp = 5f * dp
                sink.dashedLine(0f, -th / 2f, l, -th / 2f, w, col, d, gp)
                sink.dashedLine(0f, th / 2f, l, th / 2f, w, col, d, gp)
                sink.dashedLine(0f, -th / 2f, 0f, th / 2f, w, col, d, gp)
                sink.dashedLine(l, -th / 2f, l, th / 2f, w, col, d, gp)
                sink.restore()
            }
            sink.fillCircle(g.ax, g.ay, 4f * dp, col)
            val snapped = g.snapNodeRef >= 0 || g.snapBeamRef >= 0
            snapRing(g.bx, g.by, col, snapped, t)
        }
        // Snap-Markierungen (Knoten, an denen eingerastet werden kann)
        for (i in 0 until o.snaps.size) {
            val s = o.snaps[i]
            snapRing(s.x, s.y, if (o.ghost?.valid == false) Palette.INVALID else Palette.OK, s.active, t)
        }
        // Ghost-Gerät
        val gd = o.ghostDevice
        if (gd != null) devices.drawGhost(gd.typeId, gd.x, gd.y, gd.nx, gd.ny, gd.valid, o.localPlayer)
    }

    private fun snapRing(x: Float, y: Float, col: Int, snapped: Boolean, t: Float) {
        val sink = c.sink
        val dp = c.dp
        val pu = 0.5f + 0.5f * sin(t * 7f)
        sink.strokeCircle(x, y, (if (snapped) 15f else 10f) * dp, 3f * dp, col)
        if (snapped) {
            sink.strokeCircle(x, y, (22f + 5f * pu) * dp, 2f * dp, SceneContext.a(col, 0.25f + 0.4f * pu))
            sink.fillCircle(x, y, 15f * dp, SceneContext.a(col, 0.25f))
        }
    }

    private fun selectionFrame(cx: Float, cy: Float, rad: Float) {
        val sink = c.sink
        val dp = c.dp
        val r = rad + 0.45f
        val x0 = cx - r; val y0 = cy - r - 0.1f; val w = 2f * r
        val blue = SceneContext.a(Palette.TEAM_BLUE, 0.95f)
        val wd = 2.4f * dp
        val d = 7f * dp; val g = 5f * dp
        sink.dashedLine(x0, y0, x0 + w, y0, wd, blue, d, g)
        sink.dashedLine(x0 + w, y0, x0 + w, y0 + w, wd, blue, d, g)
        sink.dashedLine(x0 + w, y0 + w, x0, y0 + w, wd, blue, d, g)
        sink.dashedLine(x0, y0 + w, x0, y0, wd, blue, d, g)
        val cs = 0.32f
        val cw = 3f * dp
        for (k in 0 until 4) {
            val sx = if (k % 2 == 1) 1f else 0f
            val sy = if (k >= 2) 1f else 0f
            val X = x0 + sx * w; val Y = y0 + sy * w
            val dx = if (sx > 0f) -1f else 1f
            val dy = if (sy > 0f) -1f else 1f
            sink.line(X + dx * cs, Y, X, Y, cw, Palette.WHITE, true)
            sink.line(X, Y, X, Y + dy * cs, cw, Palette.WHITE, true)
        }
    }

    /**
     * Flugbahn: Punkte in gleichen Abständen, zum Ende kleiner und von weiß nach Warngelb; Einschlag-Fadenkreuz.
     * FX1: Trifft die Bahn zuerst die eigene Festung ([de.bollwerk.engine.tools.Trajectory.blockedOwn]), sind alle Punkte rot
     * (Stil-Bibel: rot = ungültig) und am Einschlag steht eine Warnmarke statt des Fadenkreuzes.
     */
    private fun trajectory(snap: FrameSnapshot, o: OverlayState, count: Int) {
        val tr = o.trajectory ?: return
        val blocked = tr.blockedOwn
        val sink = c.sink
        val dp = c.dp
        val step = maxOf(0.75f, 15f * dp)
        // Gesamtlänge
        var total = 0f
        for (i in 1 until count) {
            val dx = tr.x(i) - tr.x(i - 1); val dy = tr.y(i) - tr.y(i - 1)
            total += sqrt(dx * dx + dy * dy)
        }
        val hasHit = !o.impactX.isNaN() && !o.impactY.isNaN()
        val n = maxOf(1, (total / step).toInt())
        var acc = 0f
        var k = 1
        for (i in 1 until count) {
            val x0 = tr.x(i - 1); val y0 = tr.y(i - 1)
            val x1 = tr.x(i); val y1 = tr.y(i)
            val dx = x1 - x0; val dy = y1 - y0
            val seg = sqrt(dx * dx + dy * dy)
            if (seg <= 0f) continue
            var pos = step - acc
            while (pos <= seg && k < n + (if (hasHit) 0 else 1)) {
                val f = pos / seg
                val x = x0 + dx * f; val y = y0 + dy * f
                val u = k.toFloat() / n
                val rr = maxOf(1.6f * dp, (SceneContext.lerp(5.2f, 2.4f, u)) * dp)
                sink.fillCircle(x + 0.6f * dp, y + 0.9f * dp, rr + 0.6f * dp, Palette.withAlpha(Palette.INK, 0.549f))
                val dot = if (blocked) Palette.INVALID else if (u < 0.6f) Palette.WHITE else if (u < 0.8f) Palette.CREAM else Palette.HAZARD
                sink.fillCircle(x, y, rr, dot)
                k++
                pos += step
            }
            acc = seg - (pos - step)
            if (acc >= step) acc = 0f
        }
        if (hasHit) {
            if (blocked) blockedImpact(snap, o) else impact(snap, o)
        }
    }

    /**
     * Warnmarke am Einschlag in der eigenen Festung: roter Splash-Ring, roter Kreis mit Kreuz und darüber ein rotes
     * Warndreieck mit Ausrufezeichen (Stil-Bibel: rot = ungültig). Allokationsfrei.
     */
    private fun blockedImpact(snap: FrameSnapshot, o: OverlayState) {
        val sink = c.sink
        val dp = c.dp
        val hx = o.impactX; val hy = o.impactY
        val splash = splashOfSelected(snap, o)
        if (splash > 0f) devices.ringDashed(hx, hy, splash, 2f * dp, SceneContext.a(Palette.INVALID, 0.9f))
        val R = 11f * dp
        val pu = 1f + (if (c.reduced) 0f else 0.08f * sin(c.time * 8f))
        sink.strokeCircle(hx, hy, R * pu, 5f * dp, Palette.withAlpha(Palette.INK, 0.6f))
        sink.strokeCircle(hx, hy, R * pu, 3f * dp, Palette.INVALID)
        val k = R * 0.55f
        val w = 3f * dp
        sink.line(hx - k, hy - k, hx + k, hy + k, w, Palette.INVALID, true)
        sink.line(hx - k, hy + k, hx + k, hy - k, w, Palette.INVALID, true)
        // Warndreieck über dem Einschlag
        val ts = 13f * dp
        val tx = hx; val ty = hy - R * 1.6f - ts
        val p = c.poly2
        p[0] = tx; p[1] = ty - ts
        p[2] = tx + ts * 1.1f; p[3] = ty + ts * 0.8f
        p[4] = tx - ts * 1.1f; p[5] = ty + ts * 0.8f
        c.polyOutlined2(3, Palette.INVALID, Palette.withAlpha(Palette.INK, 0.8f), 2f * dp)
        sink.line(tx, ty - ts * 0.45f, tx, ty + ts * 0.2f, 2.6f * dp, Palette.WHITE, true)
        sink.fillCircle(tx, ty + ts * 0.52f, 1.6f * dp, Palette.WHITE)
    }

    /** Splash-Radius der gewählten Waffe (0 ohne Auswahl/Splash). */
    private fun splashOfSelected(snap: FrameSnapshot, o: OverlayState): Float {
        if (o.selectedDeviceRef < 0) return 0f
        val slot = selectedDeviceSlot(snap, o.selectedDeviceRef)
        if (slot < 0) return 0f
        val type = snap.deviceType[slot]
        if (type < 0 || type >= c.tables.devices.size) return 0f
        val w = c.tables.devices[type].weapon
        return if (w >= 0 && w < c.tables.weapons.size) c.tables.weapons[w].splashRadius else 0f
    }

    private fun impact(snap: FrameSnapshot, o: OverlayState) {
        val sink = c.sink
        val dp = c.dp
        val hx = o.impactX; val hy = o.impactY
        // Splash-Radius der gewählten Waffe
        var splash = 0f
        if (o.selectedDeviceRef >= 0) {
            val slot = selectedDeviceSlot(snap, o.selectedDeviceRef)
            if (slot >= 0) {
                val type = snap.deviceType[slot]
                if (type >= 0 && type < c.tables.devices.size) {
                    val w = c.tables.devices[type].weapon
                    if (w >= 0 && w < c.tables.weapons.size) splash = c.tables.weapons[w].splashRadius
                }
            }
        }
        if (splash > 0f) devices.ringDashed(hx, hy, splash, 2f * dp, SceneContext.a(Palette.RUST, 0.9f))
        val R = 14f * dp
        val pu = 1f + (if (c.reduced) 0f else 0.06f * sin(c.time * 6f))
        sink.strokeCircle(hx, hy, R * pu, 5f * dp, Palette.withAlpha(Palette.INK, 0.6f))
        sink.strokeCircle(hx, hy, R * pu, 3f * dp, Palette.HAZARD)
        val w = 3f * dp
        sink.line(hx - R * 1.9f, hy, hx - R * 0.45f, hy, w, Palette.HAZARD, true)
        sink.line(hx + R * 0.45f, hy, hx + R * 1.9f, hy, w, Palette.HAZARD, true)
        sink.line(hx, hy - R * 1.9f, hx, hy - R * 0.45f, w, Palette.HAZARD, true)
        sink.line(hx, hy + R * 0.45f, hx, hy + R * 1.9f, w, Palette.HAZARD, true)
        sink.fillCircle(hx, hy, 3f * dp, Palette.HAZARD)
    }

    // ---------------------------------------------------------------------------------------------
    // Bildschirmteil
    // ---------------------------------------------------------------------------------------------

    private fun roundedPanel(x: Float, y: Float, w: Float, h: Float, border: Int) {
        val sink = c.sink
        val d = c.density
        val r = 9f * d
        val shadow = c.roundRectPolyScreen(x + 1f * d, y + 3f * d, w, h, r)
        sink.fillPolygon(c.poly, shadow, Palette.withAlpha(Palette.INK, 0.451f))
        val n = c.roundRectPolyScreen(x, y, w, h, r)
        sink.fillPolygon(c.poly, n, Palette.PANEL)
        c.polyStroke(c.poly, n, border, 2f * d)
    }

    private fun gearIcon(cx: Float, cy: Float, r: Float, color: Int) {
        val n = c.gearPolyScreen(cx, cy, r, 8)
        c.sink.fillPolygon(c.poly, n, color)
        c.sink.fillCircle(cx, cy, r * 0.34f, Palette.DARK)
    }

    private fun boltIcon(cx: Float, cy: Float, h: Float, color: Int) {
        val p = c.poly2
        val s = h / 2f
        p[0] = cx + 0.15f * s; p[1] = cy - s
        p[2] = cx - 0.55f * s; p[3] = cy + 0.1f * s
        p[4] = cx - 0.05f * s; p[5] = cy + 0.1f * s
        p[6] = cx - 0.2f * s; p[7] = cy + s
        p[8] = cx + 0.6f * s; p[9] = cy - 0.15f * s
        p[10] = cx + 0.1f * s; p[11] = cy - 0.15f * s
        c.sink.fillPolygon(p, 6, color)
    }

    /** Längen-/Kosten-Chip neben dem Zielknoten (grün) bzw. Grund-Text (rot). */
    fun ghostChip(x: Float, y: Float, length: Float, cost: Float, valid: Boolean, reason: String?, align: TextAlign, topInsetPx: Float) {
        val sink = c.sink
        val d = c.density
        val h = 38f * d
        val padX = 14f * d
        if (valid) {
            val lk = (length * 10f + 0.5f).toInt()
            if (lk != lastLenKey) { lastLenKey = lk; lastLenText = texts.length(length) }
            val ck = (cost + 0.5f).toInt()
            if (ck != lastCostKey) { lastCostKey = ck; lastCostText = texts.cost(cost) }
            val fs = 21f * d
            val w1 = textWidth(lastLenText, fs)
            val w2 = textWidth(lastCostText, fs)
            val total = padX * 2f + w1 + 12f * d + w2 + 6f * d + 18f * d
            val px0 = placeX(x, total, align)
            val py0 = (y - h / 2f).coerceIn(topInsetPx, maxOf(topInsetPx, c.viewH - h - 8f * d))
            roundedPanel(px0, py0, total, h, Palette.OK)
            val base = py0 + h / 2f + fs * 0.35f
            sink.text(lastLenText, px0 + padX, base, fs, Palette.TEXT, TextAlign.LEFT, true)
            sink.text(lastCostText, px0 + padX + w1 + 12f * d, base, fs, Palette.TEXT, TextAlign.LEFT, true)
            gearIcon(px0 + padX + w1 + 12f * d + w2 + 6f * d + 8f * d, py0 + h / 2f, 8.5f * d, Palette.TEXT)
        } else {
            val text = reason ?: ""
            val fs = 17f * d
            val total = padX * 2f + textWidth(text, fs)
            val px0 = placeX(x, total, align)
            val py0 = (y - h / 2f).coerceIn(topInsetPx, maxOf(topInsetPx, c.viewH - h - 8f * d))
            roundedPanel(px0, py0, total, h, Palette.INVALID)
            sink.text(text, px0 + padX, py0 + h / 2f + fs * 0.35f, fs, Palette.INVALID_TEXT, TextAlign.LEFT, true)
        }
    }

    /** Kosten-Chip eines Ghost-Geräts: ⚙ Metall (und ⚡ Energie) bzw. Grund. */
    fun deviceChip(x: Float, y: Float, metal: Float, energy: Float, valid: Boolean, reason: String?, topInsetPx: Float) {
        val sink = c.sink
        val d = c.density
        val h = 38f * d
        val padX = 14f * d
        val fs = 19f * d
        if (valid) {
            val mk = (metal + 0.5f).toInt()
            if (mk != lastDevMetalKey) { lastDevMetalKey = mk; lastDevMetalText = texts.cost(metal) }
            val mt = lastDevMetalText
            var et = ""
            if (energy > 0f) {
                val ek = (energy + 0.5f).toInt()
                if (ek != lastDevEnergyKey) { lastDevEnergyKey = ek; lastDevEnergyText = texts.cost(energy) }
                et = lastDevEnergyText
            }
            val w1 = textWidth(mt, fs)
            val w2 = if (energy > 0f) textWidth(et, fs) else 0f
            val total = padX * 2f + w1 + 6f * d + 18f * d + (if (energy > 0f) 10f * d + w2 + 4f * d + 12f * d else 0f)
            val px0 = placeX(x, total, TextAlign.CENTER)
            val py0 = (y - h / 2f).coerceIn(topInsetPx, maxOf(topInsetPx, c.viewH - h - 8f * d))
            roundedPanel(px0, py0, total, h, Palette.OK)
            val base = py0 + h / 2f + fs * 0.35f
            var cx = px0 + padX
            sink.text(mt, cx, base, fs, Palette.TEXT, TextAlign.LEFT, true)
            cx += w1 + 6f * d
            gearIcon(cx + 8f * d, py0 + h / 2f, 8.5f * d, Palette.TEXT)
            cx += 18f * d
            if (energy > 0f) {
                cx += 10f * d
                sink.text(et, cx, base, fs, Palette.ENERGY, TextAlign.LEFT, true)
                cx += w2 + 4f * d
                boltIcon(cx + 5f * d, py0 + h / 2f, 17f * d, Palette.ENERGY)
            }
        } else {
            val text = reason ?: ""
            val f2 = 17f * d
            val total = padX * 2f + textWidth(text, f2)
            val px0 = placeX(x, total, TextAlign.CENTER)
            val py0 = (y - h / 2f).coerceIn(topInsetPx, maxOf(topInsetPx, c.viewH - h - 8f * d))
            roundedPanel(px0, py0, total, h, Palette.INVALID)
            sink.text(text, px0 + padX, py0 + h / 2f + f2 * 0.35f, f2, Palette.INVALID_TEXT, TextAlign.LEFT, true)
        }
    }

    private fun placeX(x: Float, w: Float, align: TextAlign): Float {
        val d = c.density
        val left = when (align) { TextAlign.LEFT -> x; TextAlign.CENTER -> x - w / 2f; TextAlign.RIGHT -> x - w }
        return left.coerceIn(8f * d, maxOf(8f * d, c.viewW - w - 8f * d))
    }

    /** Beschriftung "SCHEITEL 24 m" am Flugbahn-Scheitel (Bildschirmkoordinaten des Scheitels). */
    fun apexLabel(sx: Float, sy: Float, heightM: Int, topInsetPx: Float) {
        val sink = c.sink
        val d = c.density
        if (heightM != lastApex) { lastApex = heightM; lastApexText = texts.apex(heightM) }
        val fs = 16f * d
        val w = textWidth(lastApexText, fs) + 16f * d
        val x = (sx - w / 2f).coerceIn(8f * d, maxOf(8f * d, c.viewW - w - 8f * d))
        val y = (sy - 22f * d - 14f * d).coerceIn(topInsetPx, c.viewH)
        val n = c.roundRectPolyScreen(x, y, w, 24f * d, 6f * d)
        sink.fillPolygon(c.poly, n, Palette.withAlpha(Palette.INK, 0.549f))
        sink.text(lastApexText, x + w / 2f, y + 17f * d, fs, Palette.TEXT, TextAlign.CENTER, true)
    }

    /**
     * Lupe: Geometrie aus [loupe] (Kreis [radiusDp] über dem Finger, bei Platzmangel seitlich). Liefert
     * Mittelpunkt/Radius in [out] = (cx, cy, r). Der Aufrufer zeichnet die Welt im Clip.
     */
    fun loupeGeometry(screenX: Float, screenY: Float, radiusDp: Float, offsetDp: Float, topInsetPx: Float, out: FloatArray) {
        val d = c.density
        val r = radiusDp * d
        var cx = screenX
        var cy = screenY - offsetDp * d - r
        if (cy - r < topInsetPx - 6f * d) {
            val side = if (screenX < c.viewW / 2f) 1f else -1f
            cx = screenX + side * (r + offsetDp * d)
            cy = (screenY - 40f * d).coerceIn(topInsetPx + r, c.viewH - r - 10f * d)
            out[3] = side
        } else out[3] = 0f
        out[0] = cx; out[1] = cy; out[2] = r
    }

    /** Zeiger-Dreieck von der Lupe zum Finger, Schlagschatten und Lupenrahmen (vor/nach dem Welt-Clip). */
    fun loupeBackdrop(fingerX: Float, fingerY: Float, cx: Float, cy: Float, r: Float, side: Float) {
        val sink = c.sink
        val d = c.density
        val p = c.poly2
        if (side == 0f) {
            p[0] = cx - 16f * d; p[1] = cy + r - 4f * d; p[2] = cx + 16f * d; p[3] = cy + r - 4f * d; p[4] = fingerX; p[5] = fingerY - 18f * d
        } else {
            p[0] = cx - side * (r - 4f * d); p[1] = cy - 16f * d; p[2] = cx - side * (r - 4f * d); p[3] = cy + 16f * d; p[4] = fingerX + side * 18f * d; p[5] = fingerY
        }
        sink.fillPolygon(p, 3, Palette.withAlpha(Palette.TEXT, 0.902f))
        sink.fillCircle(cx + 2f * d, cy + 5f * d, r + 5f * d, Palette.withAlpha(Palette.BLACK, 0.451f))
    }

    fun loupeFrame(cx: Float, cy: Float, r: Float) {
        val sink = c.sink
        val d = c.density
        sink.strokeCircle(cx, cy, r, 5f * d, Palette.TEXT)
        sink.strokeCircle(cx, cy, r + 3f * d, 1.5f * d, Palette.DARK)
        sink.strokeCircle(cx, cy, r - 2.5f * d, 1.5f * d, Palette.DARK)
    }

    fun loupeCrosshair(cx: Float, cy: Float, r: Float) {
        val sink = c.sink
        val d = c.density
        val col = Palette.withAlpha(Palette.WHITE, 0.851f)
        sink.line(cx - r, cy, cx - 6f * d, cy, d, col, false)
        sink.line(cx + 6f * d, cy, cx + r, cy, d, col, false)
        sink.line(cx, cy - r, cx, cy - 6f * d, d, col, false)
        sink.line(cx, cy + 6f * d, cx, cy + r, d, col, false)
        // Badge "2×"
        val fs = 12f * d
        val w = 28f * d
        val bx = cx + r * 0.62f
        val by = cy - r * 1.02f
        val n = c.roundRectPolyScreen(bx, by, w, 20f * d, 6f * d)
        sink.fillPolygon(c.poly, n, Palette.PANEL)
        sink.text("2×", bx + w / 2f, by + 14.5f * d, fs, Palette.TEXT, TextAlign.CENTER, true)
    }

    private companion object {
        const val DEAD_UID = Int.MIN_VALUE
        const val WIDTH_CACHE = 12
    }
}
