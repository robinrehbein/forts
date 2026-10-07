package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.GameRenderer
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.ParticleKind
import de.bollwerk.renderapi.ParticleSystem
import de.bollwerk.renderapi.Palette
import de.bollwerk.renderapi.RenderTarget
import de.bollwerk.renderapi.TextAlign

/**
 * Einstellungen des Szenen-Renderers.
 * @property reducedMotion weniger Bewegung (Shake ×0,25, kein Blitz, ruhigere Flammen/Pulse); Partikelgrenze setzt das
 *   [de.bollwerk.renderapi.PooledParticleSystem] selbst (260).
 * @property hudTopInsetDp Höhe der HUD-Leiste oben in dp (Lupe/Chips weichen ihr aus).
 */
class SceneConfig(
    val reducedMotion: Boolean = false,
    val texts: SceneTexts = SceneTexts(),
    val hudTopInsetDp: Float = 76f,
    val seed: Int = 1,
)

/**
 * Plattformneutraler Szenen-Renderer: zeichnet einen [FrameSnapshot] im freigegebenen Stil (Stil-Bibel §4–6) nur
 * über [DrawSink]/[RenderTarget]; Android (Canvas) und der Simrunner (java.awt) implementieren nur die Senke.
 *
 * **Reihenfolge:** Himmel+Berge (Ebene [SceneLayers.SKY]) → Wolken → Gelände (Ebene [SceneLayers.TERRAIN]) →
 * Erz-Glühen, Decals, Fundamente → Balken → Feuer-Schein → Knoten → Geräte → Fahnen → Flammen → Projektile →
 * Partikel → Overlay (Welt) → Chips/Lupe (Bildschirm).
 *
 * **Zeit und Fx:** Animationen laufen auf der Sim-Zeit `(tick + alpha) / 60`; die Frame-Dauer für Partikel ergibt
 * sich aus deren Differenz, oder die Plattform setzt [frameDtSeconds] (reale Frame-Zeit). Der Renderer ruft
 * `particles.onFx` **genau einmal je neuer `snap.seq`** auf und schreibt `particles.update` selbst fort (die
 * Plattform darf beides nicht zusätzlich tun). Kamera-Shake (aus Explosionen) bleibt reine Darstellung und wird
 * nicht in die [Camera] geschrieben ([shakeXpx]/[shakeYpx] zur Info).
 *
 * **Allokation:** Im Dauerbetrieb entstehen keine Objekte (feste Puffer, gecachte Strings).
 */
class SceneRenderer(val config: SceneConfig = SceneConfig()) : GameRenderer {
    private val c = SceneContext()
    private val fx = FxState(config.reducedMotion)
    private val rng = RenderRng(config.seed * 7919 + 1)
    private val loupeGeo = FloatArray(4)

    private var info: WorldInfo? = null
    private var bg: BackgroundPainter? = null
    private var terrain: TerrainPainter? = null
    private var beams: BeamPainter? = null
    private var joints: JointPainter? = null
    private var devices: DevicePainter? = null
    private var effects: EffectPainter? = null
    private var overlays: OverlayPainter? = null

    private var boundSink: DrawSink? = null
    private var boundDensity = -1f
    private var imageHandles = IntArray(16)
    private var imageCount = 0
    private var boundParticles: ParticleSystem? = null

    private var lastSeq = Long.MIN_VALUE
    private var lastTime = Float.NaN
    private var matColors = IntArray(0)

    /** Reale Frame-Dauer in s (von der Plattform je Frame gesetzt); NaN = aus der Sim-Zeit ableiten. */
    var frameDtSeconds: Float = Float.NaN

    /** Letzter angewendeter Shake in Pixeln (nur Darstellung). */
    var shakeXpx: Float = 0f; private set
    var shakeYpx: Float = 0f; private set

    /** Zuletzt verarbeitete Snapshot-Nummer (Tests, Diagnose). */
    val lastProcessedSeq: Long get() = lastSeq

    /**
     * Additiv (WP7): Meldet, dass die Fx der Snapshot-Nummer [seq] bereits verarbeitet wurden (nach `bind` aufrufen).
     * Eine Plattform, die den Renderer neu aufbaut (geänderte Einstellungen, Speicherdruck), setzt damit die zuletzt
     * gezeichnete `seq`, damit der neue Renderer die Fx des aktuellen, unveränderten Snapshots nicht noch einmal
     * abspielt (Explosion, Shake, Blitz, Decals).
     */
    fun markSeqProcessed(seq: Long) { lastSeq = seq }

    /** Schlüssel der zuletzt gezeichneten statischen Ebenen (Tests). */
    var lastLayerKey: Long = 0L; private set

    /** Anzahl Frames, in denen die statischen Ebenen `beginLayer` gemeldet haben. */
    var layerBeginCount: Int = 0; private set

    // ---------------------------------------------------------------------------------------------
    // Lebenszyklus
    // ---------------------------------------------------------------------------------------------

    override fun bind(tables: SimTables, map: MapSpec, target: RenderTarget) {
        release()
        c.tables = tables
        c.map = map
        c.reduced = config.reducedMotion
        val nm = tables.materials.size
        c.matKind = IntArray(nm) { val m = tables.materials[it]; MatKind.of(m.key, m.tensionOnly, m.isDoor, m.flammable) }
        c.matThick = FloatArray(nm) { tables.materials[it].thickness }
        c.devKind = IntArray(tables.devices.size) { val d = tables.devices[it]; DevKind.of(d.key, d.role, d.weapon >= 0) }
        c.weaponKind = IntArray(tables.weapons.size) { DevKind.of(tables.weapons[it].key, de.bollwerk.engine.sim.DeviceRole.WEAPON, true) }
        matColors = IntArray(nm) {
            when (c.matKind[it]) {
                MatKind.WOOD -> Palette.WOOD
                MatKind.METAL -> Palette.METAL
                MatKind.ARMOUR -> Palette.ARMOR
                MatKind.ROPE -> Palette.ROPE
                else -> Palette.DOOR
            }
        }
        val wi = WorldInfo(map)
        info = wi
        bg = BackgroundPainter(c, wi)
        terrain = TerrainPainter(c, wi)
        beams = BeamPainter(c, fx)
        joints = JointPainter(c)
        val dev = DevicePainter(c, fx, map.terrain)
        devices = dev
        effects = EffectPainter(c, fx)
        overlays = OverlayPainter(c, dev, config.texts)
        c.sink = target.sink
        target.sink.invalidateLayers()
        registerTextures(target)
        fx.reset()
        lastSeq = Long.MIN_VALUE
        lastTime = Float.NaN
        boundParticles = null
    }

    override fun release() {
        val s = boundSink
        if (s != null) {
            for (i in 0 until imageCount) s.releaseImage(imageHandles[i])
            s.invalidateLayers()
        }
        imageCount = 0
        boundSink = null
        boundDensity = -1f
        c.texWood = -1; c.texMetal = -1; c.texArmour = -1; c.texDoor = -1; c.texGrit = -1; c.texScorch = -1
        c.texJointWood = -1; c.texJointMetal = -1; c.texJointAnchor = -1
        for (i in 0 until 4) c.texCloud[i] = -1
    }

    private fun reg(sink: DrawSink, spec: de.bollwerk.renderapi.ImageSpec): Int {
        val h = sink.registerImage(spec)
        if (imageCount == imageHandles.size) imageHandles = imageHandles.copyOf(imageCount * 2)
        imageHandles[imageCount++] = h
        return h
    }

    /** Erzeugt alle prozeduralen Texturen für die Pixeldichte des Ziels und registriert sie bei der Senke. */
    private fun registerTextures(target: RenderTarget) {
        val sink = target.sink
        val tpm = ProceduralTextures.tpmFor(target.density)
        c.tpm = tpm
        fun thick(kind: Int, def: Float): Float {
            for (i in c.matKind.indices) if (c.matKind[i] == kind) return c.matThick[i]
            return def
        }
        c.texWood = reg(sink, ProceduralTextures.wood(tpm, thick(MatKind.WOOD, 0.32f)))
        c.texMetal = reg(sink, ProceduralTextures.metal(tpm, thick(MatKind.METAL, 0.26f)))
        c.texArmour = reg(sink, ProceduralTextures.armour(tpm, thick(MatKind.ARMOUR, 0.42f)))
        c.texDoor = reg(sink, ProceduralTextures.door(tpm, thick(MatKind.DOOR, 0.40f)))
        c.texGrit = reg(sink, ProceduralTextures.grit())
        c.texScorch = reg(sink, ProceduralTextures.scorch())
        c.texJointWood = reg(sink, ProceduralTextures.jointWood(tpm))
        c.texJointMetal = reg(sink, ProceduralTextures.jointMetal(tpm))
        c.texJointAnchor = reg(sink, ProceduralTextures.jointAnchor(tpm))
        for (i in 0 until 4) c.texCloud[i] = reg(sink, ProceduralTextures.cloud(i))
        c.jointWorld = ProceduralTextures.jointSize(tpm).toFloat() / tpm
        boundSink = sink
        boundDensity = target.density
    }

    // ---------------------------------------------------------------------------------------------
    // Frame
    // ---------------------------------------------------------------------------------------------

    override fun render(
        target: RenderTarget,
        snap: FrameSnapshot,
        alpha: Float,
        camera: Camera,
        overlay: OverlayState,
        particles: ParticleSystem,
    ) {
        val wi = info ?: error("SceneRenderer.render before bind")
        val sink = target.sink
        if (sink !== boundSink || target.density != boundDensity) {
            // andere Senke oder Pixeldichte: Texturen neu erzeugen
            val old = boundSink
            if (old != null) for (i in 0 until imageCount) old.releaseImage(imageHandles[i])
            imageCount = 0
            registerTextures(target)
        }
        c.sink = sink
        if (particles !== boundParticles) {
            particles.clear() // Reste einer früheren Partie (wiederverwendetes System) verwerfen
            particles.bindWorld(matColors, c.matKind, c.weaponKind, c.map.terrain)
            boundParticles = particles
        }
        val a = alpha.coerceIn(0f, 1f)
        c.alpha = a
        c.wind = snap.wind
        c.strainView = overlay.strainView
        c.localPlayer = overlay.localPlayer
        val time = (snap.tick + a) * (1f / 60f)
        c.time = time

        // Knoten interpolieren
        c.ensureNodes(snap.nodeCount)
        val ix = c.ix; val iy = c.iy
        for (i in 0 until snap.nodeCount) {
            ix[i] = snap.nodePrevX[i] + (snap.nodeX[i] - snap.nodePrevX[i]) * a
            iy[i] = snap.nodePrevY[i] + (snap.nodeY[i] - snap.nodePrevY[i]) * a
        }

        // Zeitschritt dieses Frames. Der Zeitsprung zurück (Replay-Neustart, neue Partie ohne `bind`) setzt den
        // visuellen Zustand immer zurück, egal woher die Frame-Dauer stammt.
        var dt = 0f
        var simDelta = 0f
        if (!lastTime.isNaN()) {
            simDelta = time - lastTime
            if (simDelta < -1f) { particles.clear(); fx.reset(); lastSeq = Long.MIN_VALUE; simDelta = 0f }
        }
        if (!frameDtSeconds.isNaN()) dt = frameDtSeconds.coerceIn(0f, 0.1f)
        else if (simDelta > 0f) dt = if (simDelta > 0.1f) 0.1f else simDelta
        lastTime = time

        fx.sync(snap)
        if (snap.seq != lastSeq) {
            lastSeq = snap.seq
            val list = snap.fx
            for (i in 0 until list.size) {
                val e = list[i]
                particles.onFx(e, snap.wind)
                fx.onEvent(e, snap, c.map, c.weaponKind)
            }
        }
        if (dt > 0f) {
            particles.update(dt, snap.wind)
            fx.update(dt, snap, c.devKind)
            emitAmbient(snap, particles, dt)
        }

        // Kamera → Bildschirmabbildung
        val vw = target.widthPx.toFloat()
        val vh = target.heightPx.toFloat()
        val scale = camera.scale
        val density = target.density
        val shk = fx.shakeDp * density
        if (shk > 0f) { shakeXpx = (rng.next() * 2f - 1f) * shk; shakeYpx = (rng.next() * 2f - 1f) * shk } else { shakeXpx = 0f; shakeYpx = 0f }
        val ox0 = camera.worldToScreenX(0f)
        val oy0 = camera.worldToScreenY(0f)
        c.camX = camera.centerX; c.camY = camera.centerY

        val key = layerKey(camera, vw, vh, density, wi)
        lastLayerKey = key

        // 1. Himmel (ohne Shake)
        c.setView(vw, vh, density, scale, ox0, oy0)
        val bgp = bg!!
        if (sink.beginLayer(SceneLayers.SKY, key, target.widthPx, target.heightPx, 0f, 0f)) {
            bgp.drawSky()
            sink.endLayer(SceneLayers.SKY)
            layerBeginCount++
        }
        bgp.drawClouds(fx.cloudPhase)

        // 2. Gelände (Ebene, um den Shake verschoben)
        val tp = terrain!!
        if (sink.beginLayer(SceneLayers.TERRAIN, key xor 0x5DEECE66DL, target.widthPx, target.heightPx, shakeXpx, shakeYpx)) {
            sink.save()
            sink.translate(ox0, oy0)
            sink.scale(scale, scale)
            tp.drawStatic()
            sink.restore()
            sink.endLayer(SceneLayers.TERRAIN)
            layerBeginCount++
        }

        // 3. Welt
        c.setView(vw, vh, density, scale, ox0 + shakeXpx, oy0 + shakeYpx)
        drawWorld(snap, overlay, particles, true)

        // 4. Bildschirm-Overlays
        drawScreenOverlays(snap, overlay, camera, vw, vh, density, scale, ox0 + shakeXpx, oy0 + shakeYpx, particles)
        if (fx.screenFlash > 0f && !config.reducedMotion) sink.fillRect(0f, 0f, vw, vh, SceneContext.a(Palette.WHITE, fx.screenFlash))
    }

    /** Welt-Zeichnung unter der aktuellen Sicht [SceneContext.s]/[SceneContext.ox]/[SceneContext.oy]. */
    private fun drawWorld(snap: FrameSnapshot, overlay: OverlayState, particles: ParticleSystem, withOverlay: Boolean) {
        val sink = c.sink
        sink.save()
        sink.translate(c.ox, c.oy)
        sink.scale(c.s, c.s)
        terrain!!.drawOreGlow()
        drawGroundDecals()
        terrain!!.drawFoundations(snap)
        beams!!.drawAll(snap)
        val ef = effects!!
        ef.drawFireGlow(snap)
        joints!!.drawAll(snap)
        devices!!.drawAll(snap)
        ef.drawFlags(snap, devices!!)
        ef.drawFlames(snap)
        ef.drawProjectiles(snap, c.alpha)
        ef.drawTracers()
        ef.drawParticles(particles.buffers)
        val ov = overlays!!
        if (withOverlay && ov.hasWorldOverlay(overlay)) ov.drawWorld(snap, overlay)
        sink.restore()
    }

    private fun drawGroundDecals() {
        val tex = c.texScorch
        if (tex < 0) return
        for (i in 0 until fx.decalCount) {
            val r = fx.decalR[i]
            c.sink.image(tex, fx.decalX[i] - r, fx.decalY[i] - r * 0.32f, 2f * r, r * 0.7f, 0.8f)
        }
    }

    private fun drawScreenOverlays(
        snap: FrameSnapshot, o: OverlayState, camera: Camera, vw: Float, vh: Float, density: Float, scale: Float,
        ox: Float, oy: Float, particles: ParticleSystem,
    ) {
        val ov = overlays!!
        val sink = c.sink
        val top = config.hudTopInsetDp * density
        val loupe = o.loupe
        val g = o.ghost
        if (g != null) {
            val sx = g.bx * scale + ox
            val sy = g.by * scale + oy
            if (loupe != null) {
                val lg = loupeGeo
                ov.loupeGeometry(loupe.screenX, loupe.screenY, loupe.radiusDp, loupe.offsetDp, top, lg)
                val x = loupe.screenX + (if (lg[3] > 0f) -150f else 26f) * density
                ov.ghostChip(x, loupe.screenY + 54f * density, g.lengthM, g.cost, g.valid, config.texts.reason(g.reason), TextAlign.LEFT, top)
            } else {
                ov.ghostChip(sx + 34f * density, sy + 30f * density, g.lengthM, g.cost, g.valid, config.texts.reason(g.reason), TextAlign.LEFT, top)
            }
        }
        val gd = o.ghostDevice
        if (gd != null && gd.typeId >= 0 && gd.typeId < c.tables.devices.size) {
            val sx = gd.x * scale + ox
            val sy = gd.y * scale + oy - 90f * density
            ov.deviceChip(sx, sy, gd.costMetal, gd.costEnergy, gd.valid, config.texts.reason(gd.reason), top)
        }
        val tr = o.trajectory
        if (tr != null && tr.count >= 3) {
            // Scheitel: höchster Punkt der Bahn
            var ai = 0
            for (i in 1 until tr.count) if (tr.y(i) < tr.y(ai)) ai = i
            val h = tr.y(0) - tr.y(ai)
            if (h > 0.5f) ov.apexLabel(tr.x(ai) * scale + ox, tr.y(ai) * scale + oy, (h + 0.5f).toInt(), top)
        }
        if (loupe != null) drawLoupe(snap, o, loupe, vw, vh, density, scale, particles, top)
    }

    /** 2×-Lupe: die Welt wird um den Fingerpunkt vergrößert erneut in einen kreisförmigen Clip gezeichnet. */
    private fun drawLoupe(
        snap: FrameSnapshot, o: OverlayState, l: de.bollwerk.renderapi.Loupe, vw: Float, vh: Float, density: Float,
        scale: Float, particles: ParticleSystem, top: Float,
    ) {
        val sink = c.sink
        val ov = overlays!!
        val lg = loupeGeo
        ov.loupeGeometry(l.screenX, l.screenY, l.radiusDp, l.offsetDp, top, lg)
        val cx = lg[0]; val cy = lg[1]; val r = lg[2]
        ov.loupeBackdrop(l.screenX, l.screenY, cx, cy, r, lg[3])
        sink.save()
        sink.clipCircle(cx, cy, r)
        sink.fillRect(cx - r, cy - r, 2f * r, 2f * r, Palette.OUTLINE)
        // Himmel hinter der Lupe: Abendverlauf (ohne Berge)
        sky4[0] = Palette.SKY_1; sky4[1] = Palette.SKY_2; sky4[2] = Palette.SKY_3; sky4[3] = Palette.SKY_4
        sink.gradientRect(cx - r, cy - r, 2f * r, 2f * r, 0f, cy - r, 0f, cy + r, sky4, SKY4_STOPS)
        val s2 = scale * l.magnification
        val savedOx = c.ox; val savedOy = c.oy
        c.setView(vw, vh, density, s2, cx - l.worldX * s2, cy - l.worldY * s2)
        c.cullL = cx - r; c.cullR = cx + r; c.cullT = cy - r; c.cullB = cy + r
        // Gelände direkt (ohne Ebenen-Cache)
        sink.save()
        sink.translate(c.ox, c.oy)
        sink.scale(c.s, c.s)
        terrain!!.drawStatic()
        sink.restore()
        drawWorld(snap, o, particles, true)
        ov.loupeCrosshair(cx, cy, r)
        sink.restore()
        ov.loupeFrame(cx, cy, r)
        // Sicht für den Rest des Frames zurücksetzen
        c.setView(vw, vh, density, scale, savedOx, savedOy)
    }

    private val sky4 = IntArray(4)
    private val SKY4_STOPS = floatArrayOf(0f, 0.4f, 0.75f, 1f)

    private fun layerKey(camera: Camera, vw: Float, vh: Float, density: Float, wi: WorldInfo): Long {
        var h = wi.mapHash.toLong()
        h = h * 1099511628211L + camera.centerX.toRawBits()
        h = h * 1099511628211L + camera.centerY.toRawBits()
        h = h * 1099511628211L + camera.scale.toRawBits()
        h = h * 1099511628211L + vw.toRawBits()
        h = h * 1099511628211L + vh.toRawBits()
        h = h * 1099511628211L + density.toRawBits()
        return h
    }

    /**
     * Dauer-Effekte aus dem Snapshot (nicht aus Fx-Ereignissen): Rauchsäule und aufsteigende Funken über
     * brennenden Balken, Rauchspur hinter Geschossen. Rate pro Sekunde × [dt] als Wahrscheinlichkeit.
     */
    private fun emitAmbient(snap: FrameSnapshot, p: ParticleSystem, dt: Float) {
        val red = if (config.reducedMotion) 0.5f else 1f
        val ix = c.ix; val iy = c.iy
        for (i in 0 until snap.beamCount) {
            if ((snap.beamFlags[i] and BeamFlags.ALIVE) == 0) continue
            val fire = snap.beamFire01[i]
            if (fire <= 0f) continue
            val a = snap.beamA[i]; val b = snap.beamB[i]
            val t = rng.next()
            val x = ix[a] + (ix[b] - ix[a]) * t
            val y = iy[a] + (iy[b] - iy[a]) * t
            if (rng.next() < 2.6f * fire * dt * red) {
                // Rauchsäule beginnt über den Flammenspitzen und kippt mit dem Wind (Partikel-Update)
                p.emit(ParticleKind.SMOKE, x, y - 1.1f - rng.next() * 0.5f, rng.range(-0.3f, 0.3f), rng.range(-1.2f, -0.5f), rng.range(2.2f, 3.4f), rng.range(0.45f, 0.8f))
            }
            if (rng.next() < 4f * fire * dt * red) {
                p.emit(ParticleKind.EMBER, x, y - 0.3f, rng.range(-0.8f, 0.8f), rng.range(-3.5f, -1.2f), rng.range(0.7f, 1.4f), rng.range(0.03f, 0.06f))
            }
        }
        val prob = minOf(1f, 0.8f * dt * 60f * red)
        for (i in 0 until snap.projectileCount) {
            if ((snap.projFlags[i] and 1) == 0) continue
            if (rng.next() > prob) continue
            val x = snap.projPrevX[i] + (snap.projX[i] - snap.projPrevX[i]) * c.alpha
            val y = snap.projPrevY[i] + (snap.projY[i] - snap.projPrevY[i]) * c.alpha
            val kind = snap.projKind[i]
            val dk = if (kind >= 0 && kind < c.weaponKind.size) c.weaponKind[kind] else DevKind.CANNON
            p.emit(ParticleKind.SMOKE, x, y, rng.range(-0.2f, 0.2f), rng.range(-0.2f, 0.2f), rng.range(0.5f, 0.9f), if (dk == DevKind.CANNON) 0.16f else 0.22f)
        }
    }
}
