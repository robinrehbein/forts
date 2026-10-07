package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.Palette
import de.bollwerk.renderapi.ParticleBuffers
import de.bollwerk.renderapi.ParticleKind
import de.bollwerk.renderapi.PooledParticleSystem
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP14a (Render-Feinschliff nach Sichtprüfung): Nebel nur in der Kluft, eine Sonne, Fundamente, Rauch, Fahne,
 * örtlicher Blitz, Lupe mit echter Welt. Die Tests prüfen Geometrie und Grenzen über die aufzeichnende Senke.
 */
class PolishTest {
    private class Harness(val scene: SyntheticScene, val w: Int = 1280, val h: Int = 576, val d: Float = 1f) {
        val sink = RecordingDrawSink().also { it.recordPolygons = true }
        val target = TestTarget(w, h, d, sink)
        val renderer = SceneRenderer()
        val camera = Camera(w.toFloat(), h.toFloat(), d).also { it.centerX = 60f; it.centerY = 28f; it.setZoom(0.45f) }
        val particles = PooledParticleSystem(seed = 5)
        init { renderer.bind(scene.tables, scene.map, target) }
        fun frame(overlay: OverlayState = OverlayState.NONE, alpha: Float = 0f) {
            renderer.render(target, scene.snap, alpha, camera, overlay, particles)
        }
    }

    /** Kontext + Terrain-Maler ohne Renderer, zum direkten Aufruf einzelner Zeichenschritte. */
    private class TerrainRig(val scene: SyntheticScene, val map: MapSpec = scene.map) {
        val sink = RecordingDrawSink().also { it.recordPolygons = true }
        val c = SceneContext().also {
            it.sink = sink; it.tables = scene.tables; it.map = map
            it.setView(1280f, 576f, 1f, 24f, 640f - 60f * 24f, 288f - 28f * 24f)
        }
        val info = WorldInfo(map)
        val painter = TerrainPainter(c, info)
    }

    // ---- (1) Nebel nur im Luftraum der Kluft ----

    @Test
    fun canyonFogFillsOnlyTheCanyonAirAndNeverTheWalls() {
        val rig = TerrainRig(SyntheticScene())
        rig.painter.drawCanyonFog()
        val terrain = rig.scene.map.terrain
        val fog = rig.sink.polygons
        assertTrue(fog.size >= 12, "stacked depth layers: ${fog.size}")
        assertEquals(0, rig.sink.gradientCalls, "no rectangular gradient may be used for the fog")
        for (poly in fog) {
            val xy = poly.xy
            for (k in 0 until xy.size / 2) {
                val x = xy[k * 2]; val y = xy[k * 2 + 1]
                assertTrue(x >= 40f - 1e-3f && x <= 80f + 1e-3f, "vertex x=$x outside the canyon span 40..80")
                // innerhalb der Kluft = über der Geländekontur (y nach unten: Luft hat y <= Geländehöhe)
                assertTrue(y <= terrain.heightAt(x) + 1e-3f, "vertex ($x,$y) lies inside the rock wall (surface ${terrain.heightAt(x)})")
            }
        }
    }

    @Test
    fun canyonFogGetsDenserWithDepthAndKeepsTheFloorCovered() {
        val rig = TerrainRig(SyntheticScene())
        var prev = -1f
        for (i in 0..20) { val a = rig.painter.fogAlpha(i / 20f); assertTrue(a >= prev, "monotonic"); prev = a }
        assertTrue(rig.painter.fogAlpha(1f) in 0.8f..0.9f)
        assertEquals(0f, rig.painter.fogAlpha(0f))
        rig.painter.drawCanyonFog()
        // Die tiefste Schicht reicht von Wand zu Wand (Kluftboden), die oberste ist die breiteste
        fun width(p: RecordingDrawSink.PolyRec): Float {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (k in 0 until p.xy.size / 2) { lo = min(lo, p.xy[k * 2]); hi = maxOf(hi, p.xy[k * 2]) }
            return hi - lo
        }
        val first = rig.sink.polygons.first(); val last = rig.sink.polygons.last()
        assertTrue(width(first) > width(last), "layers narrow with the canyon")
        assertTrue(width(last) >= 72.5f - 47.5f - 1e-3f, "deepest layer spans the floor: ${width(last)}")
        // Alpha je Schicht klein (Summe ergibt den Verlauf), nie ein deckender Block
        for (p in rig.sink.polygons) assertTrue((p.color ushr 24) < 100, "single layer alpha ${p.color ushr 24}")
    }

    @Test
    fun fullRenderUsesNoRectangularFogGradient() {
        val h = Harness(SyntheticScene().both())
        h.frame()
        for (g in h.sink.gradientColors) for (col in g) {
            val rgb = col and 0xFFFFFF
            assertFalse(rgb == (Palette.FOG_PLUM and 0xFFFFFF) || rgb == (Palette.FOG_PLUM_DEEP and 0xFFFFFF), "fog gradient rect")
        }
        assertEquals(0, h.sink.depth)
    }

    @Test
    fun canyonFogFillsEveryBasinOfAMultiBasinCanyon() {
        // zwei Becken (Boden y=52 bei x 46..55 und y=56 bei x 65..74), getrennt durch einen Felsrücken (y=40, x 58..62)
        val sc = SyntheticScene()
        val xs = floatArrayOf(-40f, 40f, 46f, 55f, 58f, 62f, 65f, 74f, 80f, 160f)
        val ys = floatArrayOf(34f, 34f, 52f, 52f, 40f, 40f, 56f, 56f, 34f, 34f)
        val m = sc.map
        val map = MapSpec(m.id, m.width, m.height, Terrain.fromPolyline(xs, ys), m.buildZones, m.ores, m.foundations, m.startForts, m.baseY, m.windMin, m.windMax, m.killMinX, m.killMaxX, m.killMinY, m.killMaxY)
        val rig = TerrainRig(sc, map)
        assertTrue(rig.info.hasValley)
        rig.painter.drawCanyonFog()
        // Schicht-Linie (erster Punkt) in [yLo, yHi] mit mindestens einem Punkt im Becken [x0, x1]
        fun hasLayerIn(x0: Float, x1: Float, yLo: Float, yHi: Float): Boolean = rig.sink.polygons.any { p ->
            p.xy[1] in yLo..yHi && (0 until p.xy.size / 2).any { k -> p.xy[k * 2] in x0..x1 }
        }
        assertTrue(hasLayerIn(65f, 74f, 54f, 56f), "deepest basin is fogged")
        assertTrue(hasLayerIn(46f, 55f, 42f, 51f), "the shallower basin is fogged below the rock spine top (y=40), not only the deepest one")
        for (p in rig.sink.polygons) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (k in 0 until p.xy.size / 2) {
                lo = min(lo, p.xy[k * 2]); hi = maxOf(hi, p.xy[k * 2])
                assertTrue(p.xy[k * 2 + 1] <= map.terrain.heightAt(p.xy[k * 2]) + 1e-3f, "vertex inside rock")
            }
            // eine Schicht unterhalb der Felsrücken-Oberkante darf den Rücken nicht überspannen
            if (p.xy[1] > 41f) assertTrue(hi <= 58.2f || lo >= 61.8f, "a layer below the spine top must not span the spine ($lo..$hi)")
        }
    }

    // ---- (2) eine Sonne ----

    @Test
    fun exactlyOneSunDiscWithOneHaloLowOnTheHorizon() {
        val h = Harness(SyntheticScene().both())
        h.frame()
        val sun = h.sink.circles.filter { it.color == Palette.SUN }
        assertEquals(1, sun.size, "one sun disc")
        assertTrue(h.sink.circles.none { it.color == Palette.SUN_CORE || it.color == Palette.CREAM && it.r > 15f }, "no second disc")
        val halos = h.sink.glowColors.indices.filter { (h.sink.glowColors[it] and 0xFFFFFF) == (Palette.SUN_CORE and 0xFFFFFF) }
        assertEquals(1, halos.size, "one halo")
        // tief am Horizont: Mittelpunkt höchstens um einen Radius über der Horizontlinie
        val bg = BackgroundPainter(SceneContext().also { it.setView(1280f, 576f, 1f, 10.8f, 0f, 0f); it.camX = 60f; it.camY = 28f }, WorldInfo(h.scene.map))
        val hy = bg.horizonY()
        val s = sun[0]
        assertTrue(s.y > hy - s.r * 0.8f && s.y < hy + s.r * 0.2f, "sun center ${s.y} vs horizon $hy radius ${s.r}")
        // der Halo ist ein Schein (Alpha < 0,5), keine zweite deckende Scheibe
        assertTrue((h.sink.glowColors[halos[0]] ushr 24) < 128)
    }

    // ---- (3) Fundamente ----

    private fun footingScene(anchoredSolid: Boolean, ropeAnchor: Boolean): SyntheticScene {
        val sc = SyntheticScene()
        if (anchoredSolid) {
            val a = sc.node(24f, 34f, 0, true); val b = sc.node(27f, 34f, 0)
            sc.beam(a, b, SyntheticScene.WOOD)
        }
        if (ropeAnchor) {
            val a = sc.node(20f, 34f, 0, true); val b = sc.node(22f, 33f, 0)
            sc.beam(a, b, SyntheticScene.ROPE)
            val c = sc.node(22f, 31f, 0)
            sc.beam(b, c, SyntheticScene.WOOD)
            sc.beam(b, sc.node(25f, 31f, 0), SyntheticScene.WOOD)
        }
        return sc
    }

    private fun concretePolys(h: Harness): List<RecordingDrawSink.PolyRec> =
        h.sink.polygons.filter { it.color == Palette.CONCRETE_MID }

    @Test
    fun footingsExistOnlyAtFoundationNodes() {
        val none = Harness(footingScene(anchoredSolid = false, ropeAnchor = false)).also { it.camera.centerX = 24f; it.camera.centerY = 33f; it.camera.setZoom(1f); it.frame() }
        assertEquals(0, concretePolys(none).size, "no anchored node, no footing")
        val one = Harness(footingScene(anchoredSolid = true, ropeAnchor = false)).also { it.camera.centerX = 24f; it.camera.centerY = 33f; it.camera.setZoom(1f); it.frame() }
        assertEquals(1, concretePolys(one).size, "exactly one footing for one foundation node")
    }

    @Test
    fun footingTeamStripeSitsBelowThePlateClearOfTheFloorBeam() {
        val h = Harness(footingScene(anchoredSolid = true, ropeAnchor = false))
        h.camera.centerX = 24f; h.camera.centerY = 33f; h.camera.setZoom(1f)
        h.frame()
        val ground = h.scene.map.terrain.heightAt(24f)
        val stripes = h.sink.fillRects.filter { it.color == Palette.TEAM_BLUE && abs((it.x + it.w / 2f) - 24f) < 0.05f && it.y > ground - 1f && it.y < ground + 1f }
        assertEquals(1, stripes.size, "one team stripe on the footing")
        val st = stripes[0]
        // Bodenbalken und Knotenplatte liegen innerhalb von +-JOINT_D/2 um die Knotenhöhe: der Streifen darf sie nicht berühren
        val clear = ProceduralTextures.JOINT_D / 2f
        assertTrue(st.y + st.h <= ground - clear + 1e-3f, "stripe bottom ${st.y + st.h} must be above the beam/joint band (ground $ground, ±$clear)")
        // ... aber auf dem sichtbaren Sockel: unter der Ankerplatte, innerhalb seiner Breite
        assertTrue(st.y >= ground - 0.4f - 1e-3f, "stripe lies on the visible concrete (top ${ground - 0.4f})")
        assertTrue(st.w in 0.6f..1.2f, "stripe spans the footing: ${st.w}")
    }

    @Test
    fun freeFoundationSlotsGetAFlatTranslucentPadNotAFullFooting() {
        // verankerter Knoten ohne einen einzigen Balken (wie die unbenutzten Plätze der Karte)
        val sc = SyntheticScene()
        sc.node(24f, 34f, 0, true)
        val h = Harness(sc)
        h.camera.centerX = 24f; h.camera.centerY = 33f; h.camera.setZoom(1f)
        h.frame()
        assertEquals(0, concretePolys(h).size, "no opaque concrete footing")
        assertTrue(h.sink.fillRects.none { it.color == Palette.TEAM_BLUE && it.y > 33f && it.y < 35f }, "no team stripe")
        assertEquals(0, h.sink.circles.count { it.color == Palette.STEEL && it.r in 0.04f..0.07f }, "no anchor bolts")
        val pad = h.sink.polygons.filter { (it.color and 0xFFFFFF) == (Palette.CONCRETE_MID and 0xFFFFFF) && (it.color ushr 24) < 255 }
        assertEquals(1, pad.size, "one flat translucent pad")
        var top = Float.MAX_VALUE
        for (k in 0 until pad[0].xy.size / 2) top = min(top, pad[0].xy[k * 2 + 1])
        assertTrue(34f - top < 0.2f, "pad is low: ${34f - top} m")
        // ein Balken am Knoten macht daraus wieder ein volles Fundament
        val sc2 = SyntheticScene(); val a = sc2.node(24f, 34f, 0, true); val b = sc2.node(27f, 34f, 0); sc2.beam(a, b, SyntheticScene.WOOD)
        val h2 = Harness(sc2); h2.camera.centerX = 24f; h2.camera.centerY = 33f; h2.camera.setZoom(1f); h2.frame()
        assertEquals(1, concretePolys(h2).size)
    }

    @Test
    fun footingIsSixtyPercentSunkWithAnchorPlate() {
        val h = Harness(footingScene(anchoredSolid = true, ropeAnchor = false))
        h.camera.centerX = 24f; h.camera.centerY = 33f; h.camera.setZoom(1f)
        h.frame()
        val ground = h.scene.map.terrain.heightAt(24f)
        val above = concretePolys(h)[0].xy
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (k in 0 until above.size / 2) { top = min(top, above[k * 2 + 1]); bottom = maxOf(bottom, above[k * 2 + 1]) }
        assertEquals(ground, bottom, 1e-3f, "visible concrete ends at the ground line")
        val visible = bottom - top
        // versenkter Teil: das Polygon darunter (Querschnitt) mit 60 % der Gesamthöhe
        val sunk = h.sink.polygons.first { it.xy.size == 8 && abs(it.xy[1] - ground) < 1e-3f && it.xy[7] > ground + 0.3f }.xy
        val depth = sunk[7] - ground
        assertEquals(0.6f, depth / (depth + visible), 0.02f, "60 % sunk")
        // Ankerplatte: Verlauf-Rechteck über dem Sockel + 2 Bolzen
        assertTrue(h.sink.gradientCalls > 0 && h.sink.circles.count { it.color == Palette.STEEL && it.r in 0.04f..0.07f } >= 2, "anchor plate with bolts")
    }

    @Test
    fun ropeOnlyAnchorsGetASmallStoneNotAFullFooting() {
        val h = Harness(footingScene(anchoredSolid = false, ropeAnchor = true))
        h.camera.centerX = 22f; h.camera.centerY = 33f; h.camera.setZoom(1f)
        h.frame()
        assertEquals(0, concretePolys(h).size, "no trapezoid footing at a rope anchor")
        assertTrue(h.sink.fillRects.any { it.color == Palette.CONCRETE_MID && it.w < 0.7f }, "small anchor stone")
    }

    // ---- (4) Rauch ----

    private fun smokeBuffers(n: Int, age: Float, life: Float = 3f, size: Float = 1.0f): ParticleBuffers {
        val b = ParticleBuffers(maxOf(n, 1))
        for (i in 0 until n) {
            b.kind[i] = ParticleKind.SMOKE.ordinal
            b.x[i] = 30f + (i % 20) * 0.3f; b.y[i] = 30f - (i / 20) * 0.3f
            b.age[i] = age; b.life[i] = life; b.size[i] = size; b.seed[i] = i
        }
        b.count = n
        return b
    }

    private fun effectRig(): Triple<SceneContext, EffectPainter, RecordingDrawSink> {
        val sc = SyntheticScene()
        val sink = RecordingDrawSink()
        val c = SceneContext().also { it.sink = sink; it.tables = sc.tables; it.map = sc.map; it.setView(1280f, 576f, 1f, 24f, 640f - 30f * 24f, 288f - 30f * 24f) }
        return Triple(c, EffectPainter(c, FxState(false)), sink)
    }

    @Test
    fun smokePuffsAreSmallTranslucentAndFadeWithAge() {
        fun alphaAndRadius(u: Float): Pair<Int, Float> {
            val (_, ep, sink) = effectRig()
            ep.drawParticles(smokeBuffers(1, u * 3f))
            val main = sink.circles.first()
            return (main.color ushr 24) to main.r
        }
        val young = alphaAndRadius(0.05f); val mid = alphaAndRadius(0.55f); val old = alphaAndRadius(0.9f)
        assertTrue(young.first < mid.first, "young puffs start light/translucent (palette: 20 %)")
        assertTrue(old.first < mid.first, "alpha fades at the end of life: old ${old.first} vs mid ${mid.first}")
        // dichter Rauch: Höchstwert 0,8 in der Lebensmitte (Palette alter Rauch 90 %), erst im letzten Viertel klingt er aus
        assertTrue(mid.first >= 0.7f * 255f, "dense smoke column in mid-life: ${mid.first}")
        assertEquals(alphaAndRadius(0.55f).first, alphaAndRadius(0.7f).first, "plateau")
        assertTrue(alphaAndRadius(0.75f).first > alphaAndRadius(0.9f).first && alphaAndRadius(0.9f).first > alphaAndRadius(0.99f).first, "fade-out only in the last quarter")
        assertTrue(mid.first <= (EffectPainter.SMOKE_PEAK * 255f + 1f).toInt(), "never more opaque than the cap")
        assertTrue(old.second > young.second, "puffs grow with age")
        for (u in floatArrayOf(0f, 0.2f, 0.5f, 0.95f)) assertTrue(alphaAndRadius(u).second <= EffectPainter.SMOKE_RMAX + 1e-4f, "radius cap")
        // junger Rauch ist heller als alter (Palette: smoke alt #3a3f47 → jung #6b7078)
        fun luma(u: Float): Int {
            val (_, ep, sink) = effectRig()
            ep.drawParticles(smokeBuffers(1, u * 3f))
            val col = sink.circles.first().color
            return ((col shr 16) and 0xFF) + ((col shr 8) and 0xFF) + (col and 0xFF)
        }
        assertTrue(luma(0.05f) > luma(0.8f), "young smoke is lighter")
    }

    @Test
    fun smokeCoverageIsCappedNoMatterHowManyPuffsExist() {
        val (_, ep, sink) = effectRig()
        ep.drawParticles(smokeBuffers(400, 1.0f, size = 1.2f))
        val smoke = sink.circles.size
        assertTrue(smoke <= 2 * EffectPainter.MAX_SMOKE, "drawn smoke circles $smoke")
        assertTrue(smoke >= EffectPainter.MAX_SMOKE, "still plenty of smoke")
        // gedeckte Fläche: Summe der Kreisflächen bleibt klein gegenüber dem Bild (<= 20 % des Bildes, Summe ohne Überlappungsabzug)
        var area = 0f
        for (c in sink.circles) area += 3.1416f * c.r * c.r
        assertTrue(area < 0.2f * 1280f / 24f * 576f / 24f, "smoke area $area m²")
    }

    /** Fügt Rauchwolke [i] aus [b] ein weiteres Mal in [out] ein (Test-Hilfe für die Swap-Remove-Simulation). */
    private fun copyParticle(b: ParticleBuffers, from: Int, to: Int) {
        b.kind[to] = b.kind[from]; b.x[to] = b.x[from]; b.y[to] = b.y[from]; b.vx[to] = b.vx[from]; b.vy[to] = b.vy[from]
        b.age[to] = b.age[from]; b.life[to] = b.life[from]; b.size[to] = b.size[from]; b.rot[to] = b.rot[from]; b.vrot[to] = b.vrot[from]
        b.color[to] = b.color[from]; b.seed[to] = b.seed[from]; b.flags[to] = b.flags[from]
    }

    /** Positionen (x, y als Paar) der gezeichneten Rauchwolken: jeder Wolke gehören zwei Kreise (Körper, Glanz). */
    private fun drawnPuffs(b: ParticleBuffers): Set<Pair<Float, Float>> {
        val (_, ep, sink) = effectRig()
        ep.drawParticles(b)
        val out = HashSet<Pair<Float, Float>>()
        var i = 0
        while (i + 1 < sink.circles.size) { out.add(sink.circles[i].x to sink.circles[i].y); i += 2 }
        return out
    }

    @Test
    fun smokeSubsetIsStableWhenAnEarlyParticleDies() {
        val b = smokeBuffers(300, 1.0f, size = 1.2f)
        val before = drawnPuffs(b)
        assertTrue(before.size in 40..EffectPainter.MAX_SMOKE, "thinned to the cap: ${before.size}")
        // Das Partikelsystem entfernt per Swap-Remove: das letzte Partikel rückt in die Lücke. Sterben nacheinander
        // mehrere frühe Partikel, dürfen die übrigen Wolken weder verschwinden noch ihre Auswahl wechseln.
        var cur = before
        for (round in 0 until 6) {
            val removed = b.x[round] to b.y[round]
            val last = b.count - 1
            copyParticle(b, last, round)
            b.count = last
            val after = drawnPuffs(b)
            for (p in cur) if (p != removed) assertTrue(p in after, "puff $p flickered off after particle $round died")
            assertTrue(after.size <= EffectPainter.MAX_SMOKE, "cap holds: ${after.size}")
            cur = after
        }
    }

    @Test
    fun smokeCapCountsOnlyPuffsInsideTheView() {
        // 300 Wolken weit außerhalb des Bildes dürfen die 40 sichtbaren nicht ausdünnen
        val b = smokeBuffers(340, 1.0f, size = 1.0f)
        for (i in 40 until 340) b.x[i] = 400f + i * 0.1f
        assertEquals(40, drawnPuffs(b).size, "all visible puffs drawn")
    }

    // ---- (5) Fahne an der Vorderkante der Festung ----

    /** Tuch-Polygone der Teamfarbe [team] (18 Punkte): erste Ecke = Stangenkopf. */
    private fun cloths(h: Harness, team: Int): List<RecordingDrawSink.PolyRec> =
        h.sink.polygons.filter { it.color == Palette.team(team) && it.xy.size == 36 }

    private fun solidCounts(snap: de.bollwerk.engine.view.FrameSnapshot): IntArray {
        val solid = IntArray(snap.nodeCount)
        for (b in 0 until snap.beamCount) if ((snap.beamFlags[b] and BeamFlags.ALIVE) != 0 && snap.beamMaterial[b] != SyntheticScene.ROPE) { solid[snap.beamA[b]]++; solid[snap.beamB[b]]++ }
        return solid
    }

    @Test
    fun flagSitsOnTheTopNodeOfTheFrontColumnOfEachFortOnAPole() {
        val sc = SyntheticScene().both()
        val h = Harness(sc)
        h.frame()
        val snap = sc.snap
        for (team in 0..1) {
            val solid = solidCounts(snap)
            val nodes = (0 until snap.nodeCount).filter { snap.nodeOwner[it] == team && solid[it] >= 2 }
            // Vorderkante = zur Kartenmitte (Team 0 links: größtes x, Team 1 rechts: kleinstes x), wie im Mockup 3-spiel-bauen
            val frontX = if (team == 0) nodes.maxOf { snap.nodeX[it] } else nodes.minOf { snap.nodeX[it] }
            val col = nodes.filter { abs(snap.nodeX[it] - frontX) <= EffectPainter.FLAG_COL }
            val expected = col.minByOrNull { snap.nodeY[it] }!!
            val cloth = cloths(h, team)
            assertEquals(1, cloth.size, "one flag cloth for team $team")
            val poleX = cloth[0].xy[0]; val clothTop = cloth[0].xy[1]
            assertEquals(snap.nodeX[expected], poleX, 1e-3f, "pole stands on the front column of team $team")
            // Stangenkopf = Knoten - 0,2 - 2,6
            assertEquals(snap.nodeY[expected] - 2.8f, clothTop, 1e-3f, "pole stands on the topmost node of that column")
        }
    }

    @Test
    fun flagMovesToTheNextNodeWhenItsNodeIsGone() {
        val sc = SyntheticScene().both()
        val h0 = Harness(sc); h0.frame()
        val head = cloths(h0, 0)[0].xy
        val before = head[0] to (head[1] + 2.8f)
        // alle Balken am Fahnenknoten entfernen: er trägt nichts mehr
        val snap = sc.snap
        for (b in 0 until snap.beamCount) {
            val a = snap.beamA[b]; val e = snap.beamB[b]
            val hitA = abs(snap.nodeX[a] - before.first) < 1e-3f && abs(snap.nodeY[a] - before.second) < 1e-3f
            val hitE = abs(snap.nodeX[e] - before.first) < 1e-3f && abs(snap.nodeY[e] - before.second) < 1e-3f
            if (hitA || hitE) snap.beamFlags[b] = snap.beamFlags[b] and BeamFlags.ALIVE.inv()
        }
        // gleicher Renderer (FxState merkt sich den alten Knoten): er taugt nicht mehr, also wird neu gewählt
        h0.sink.reset(); h0.frame()
        val after = cloths(h0, 0)[0].xy
        val moved = abs(after[0] - before.first) + abs(after[1] + 2.8f - before.second)
        assertTrue(moved > 1f, "flag moved to the next supporting node ($before -> ${after[0]}, ${after[1] + 2.8f})")
        // der neue Knoten ist ein tragender Knoten
        val solid = solidCounts(snap)
        assertTrue((0 until snap.nodeCount).any { solid[it] >= 2 && abs(snap.nodeX[it] - after[0]) < 1e-3f && abs(snap.nodeY[it] - (after[1] + 2.8f)) < 1e-3f }, "new pole node carries >= 2 beams")
    }

    @Test
    fun flagStaysOnItsNodeWhileItRemainsValid() {
        val sc = SyntheticScene().both()
        val h = Harness(sc)
        h.frame()
        val head = cloths(h, 0)[0].xy
        val node0 = head[0] to (head[1] + 2.8f)
        // Wackeln: ein anderer Knoten derselben Spalte steigt über den Fahnenknoten (eine Neuwahl würde springen)
        val snap = sc.snap
        val other = (0 until snap.nodeCount).first { snap.nodeOwner[it] == 0 && abs(snap.nodeX[it] - node0.first) < 0.3f && snap.nodeY[it] > node0.second + 1f && snap.nodeY[it] < node0.second + 4f }
        for (round in 0 until 5) {
            val lift = snap.nodeY[other] - (node0.second - 0.1f * (round + 1))
            snap.nodeY[other] -= lift; snap.nodePrevY[other] -= lift
            h.sink.reset()
            h.frame()
            val now = cloths(h, 0)[0].xy
            assertEquals(node0.first, now[0], 1e-3f, "pole x stays (round $round)")
            assertEquals(node0.second, now[1] + 2.8f, 1e-3f, "pole node stays (round $round)")
        }
    }

    /** Kleine linke Festung: Fahnenknoten D(27,31) mit zwei tragenden Balken; Balken D-E nach vorn für ein Gerät. */
    private fun flagFort(withDevice: String?, deviceT: Float): SyntheticScene {
        val sc = SyntheticScene()
        val a = sc.node(24f, 34f, 0, true); val b = sc.node(27f, 34f, 0, true)
        val c = sc.node(24f, 31f, 0); val d = sc.node(27f, 31f, 0)
        sc.beam(a, b, SyntheticScene.METAL); sc.beam(b, d, SyntheticScene.WOOD); sc.beam(c, d, SyntheticScene.WOOD)
        sc.beam(a, c, SyntheticScene.WOOD); sc.beam(a, d, SyntheticScene.WOOD)
        // Ausleger nach vorn (nur ein tragender Balken am Ende: der Endknoten E trägt keine Fahne)
        val e = sc.node(30f, 31f, 0)
        val de = sc.beam(d, e, SyntheticScene.METAL)
        if (withDevice != null) sc.device(withDevice, de, deviceT, 0)
        return sc
    }

    @Test
    fun clothFliesToTheOtherSideWhenATurbineStandsInItsWay() {
        // Wind +3,2 (nach rechts); Turbine 1 m vor dem Fahnenknoten (Fußpunkt bei x = 28)
        val plain = Harness(flagFort(null, 0f)); plain.frame()
        val withTurbine = Harness(flagFort("turbine", 0.33f)); withTurbine.frame()
        fun extent(h: Harness): Pair<Float, Float> {
            val p = cloths(h, 0)
            assertEquals(1, p.size)
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (k in 0 until 18) { lo = min(lo, p[0].xy[k * 2]); hi = maxOf(hi, p[0].xy[k * 2]) }
            return lo to hi
        }
        val pole = 27f
        val (plo, phi) = extent(plain)
        assertTrue(plo >= pole - 1e-3f && phi > pole + 1f, "without a device the cloth flies with the wind (to the right): $plo..$phi")
        val (tlo, thi) = extent(withTurbine)
        assertTrue(thi <= pole + 1e-3f && tlo < pole - 1f, "with the turbine in the way the cloth flies to the left: $tlo..$thi")
        // dasselbe ohne Tick-Lagedaten des Snapshots: die Lage kommt aus der interpolierten Gerätegeometrie des Frames
        val sc = flagFort("turbine", 0.33f)
        for (k in 0 until sc.snap.deviceCount) { sc.snap.deviceX[k] = 0f; sc.snap.deviceY[k] = 0f }
        val zeroed = Harness(sc); zeroed.frame()
        val (zlo, zhi) = extent(zeroed)
        assertTrue(zhi <= pole + 1e-3f && zlo < pole - 1f, "tick-0 snapshot (deviceX = 0): cloth still avoids the turbine")
    }

    @Test
    fun aDeviceStandingOnTheFlagNodeMovesTheFlagToAnotherNode() {
        val plain = Harness(flagFort(null, 0f)); plain.frame()
        val head0 = cloths(plain, 0)[0].xy
        assertEquals(31f - 2.8f, head0[1], 1e-3f, "flag on the top front node D (y 31)")
        // MG direkt auf dem Knoten D (Balken D-E bei t = 0,05)
        val h = Harness(flagFort("mg", 0.05f)); h.frame()
        val head1 = cloths(h, 0)[0].xy
        assertEquals(27f, head1[0], 1e-3f, "same front column")
        assertEquals(34f - 2.8f, head1[1], 1e-3f, "flag steps down to the next node B (D is occupied)")
    }

    // ---- (6) Blitz örtlich und begrenzt ----

    @Test
    fun explosionBurstIsLocalAndBoundedByWorldAndScreen() {
        for ((vw, vh, scale) in listOf(Triple(1280, 576, 24f), Triple(420, 420, 62.4f), Triple(2560, 1152, 48f))) {
            val sc = SyntheticScene()
            val sink = RecordingDrawSink()
            val c = SceneContext().also { it.sink = sink; it.tables = sc.tables; it.map = sc.map; it.setView(vw.toFloat(), vh.toFloat(), 1f, scale, 0f, 0f) }
            val fx = FxState(false)
            fx.burst = 1f; fx.burstX = 10f; fx.burstY = 10f; fx.burstR = 200f // Reaktor-Explosion mit absurdem Radius
            EffectPainter(c, fx).drawBurst()
            assertEquals(1, sink.glowRadii.size)
            val r = sink.glowRadii[0]
            assertTrue(r <= EffectPainter.BURST_MAX_M + 1e-4f, "world bound: $r")
            assertTrue(r * scale <= EffectPainter.BURST_SCREEN_FRACTION * min(vw, vh) + 1e-2f, "screen bound: ${r * scale}px of ${min(vw, vh)}")
            assertEquals(0, sink.count("fillRect"), "no full-screen wash")
        }
    }

    @Test
    fun explosionFlashStarAndFireballNeverCoverTheScreen() {
        for ((vw, vh, scale) in listOf(Triple(1280, 576, 24f), Triple(420, 420, 62.4f))) {
            val sc = SyntheticScene()
            val p = PooledParticleSystem(seed = 2)
            p.onFx(FxEvent.ReactorDestroyed(1, 10f, 10f, 0), 0f) // größte Explosion (Radius 6,5)
            val flashIdx = (0 until p.buffers.count).first { p.buffers.kind[it] == ParticleKind.FLASH.ordinal }
            assertTrue(p.buffers.size[flashIdx] <= PooledParticleSystem.FLASH_MAX_RADIUS + 1e-4f, "flash particle size ${p.buffers.size[flashIdx]}")
            val sink = RecordingDrawSink()
            val c = SceneContext().also { it.sink = sink; it.tables = sc.tables; it.map = sc.map; it.setView(vw.toFloat(), vh.toFloat(), 1f, scale, 0f, 0f) }
            val ep = EffectPainter(c, FxState(false))
            ep.drawParticles(p.buffers)
            val limit = min(vw, vh) / scale
            // Blitzstern (weißes Polygon): Ausdehnung höchstens 2 × 0,3 der kleineren Bildseite
            for (h in sink.polyHeads.filter { it.color == Palette.WHITE }) assertTrue(h.maxX - h.minX <= 2f * 0.3f * limit + 1e-3f, "flash star width ${h.maxX - h.minX} m vs ${limit} m view")
            // alle Schein-Radien (Blitz-Halo, Feuerball-Halo) bleiben unter der halben Bildseite
            for (r in sink.glowRadii) assertTrue(r <= 0.5f * limit + 1e-3f, "glow $r m vs view $limit m")
            // kein Kreis (Feuerball) größer als ein Bruchteil des Bildes
            for (cr in sink.circles) assertTrue(cr.r <= 0.42f * limit * 1.6f, "fireball circle ${cr.r}")
        }
    }

    @Test
    fun explosionFrameIsNotAWhiteWash() {
        val sc = SyntheticScene().both()
        val h = Harness(sc)
        h.camera.centerX = 88f; h.camera.centerY = 28f; h.camera.setZoom(1f)
        sc.snap.fx.add(sc.explosionEvent(88f, 26f, 30f, 600f)); sc.snap.seq = 90
        h.frame()
        assertEquals(0, h.sink.depth)
        for (r in h.sink.fillRects) assertFalse(r.w >= 1280f * 0.9f && r.h >= 576f * 0.9f && (r.color and 0xFFFFFF) == 0xFFFFFF, "full-screen white fill")
    }

    // ---- (7) Lupe zeigt die echte vergrößerte Welt ----

    @Test
    fun loupeShowsTheMagnifiedBackgroundAndWorldWithCrosshair() {
        val sc = SyntheticScene().both()
        val h = Harness(sc)
        h.camera.centerX = 30f; h.camera.centerY = 29f; h.camera.setZoom(1f)
        h.frame()
        val skyPlain = h.sink.gradientColors.count { it.contentEquals(Palette.SKY_GRADIENT) }
        val regionsPlain = h.sink.count("imageRegion")
        val polysPlain = h.sink.count("fillPolygon")
        h.sink.reset()
        val loupe = Loupe(screenX = 640f, screenY = 400f, worldX = 30f, worldY = 29f)
        h.frame(OverlayState(loupe = loupe))
        assertEquals(1, h.sink.count("clipCircle"))
        assertEquals(skyPlain + 1, h.sink.gradientColors.count { it.contentEquals(Palette.SKY_GRADIENT) }, "sky/background drawn again inside the loupe")
        assertTrue(h.sink.count("imageRegion") > regionsPlain, "beams magnified")
        assertTrue(h.sink.count("fillPolygon") > polysPlain, "mountains, terrain, devices magnified")
        assertTrue(h.sink.count("line") >= 4, "crosshair")
        assertEquals(0, h.sink.depth)
        assertTrue(h.sink.minDepth >= 0)
    }

    // ---- (8) Texturen: Mip-Stufe passend zur Bildschirmdichte ----

    @Test
    fun mipLevelsCoverTheZoomRange() {
        val c = SceneContext()
        for (i in c.texMip.indices) c.texMip[i] = i
        for (m in SceneContext.MIP_TPM.indices) c.jointWorldMip[m] = ProceduralTextures.jointSize(SceneContext.MIP_TPM[m]).toFloat() / SceneContext.MIP_TPM[m]
        var last = -1
        for (scale in floatArrayOf(10f, 24f, 36f, 48f, 72f, 96f, 144f, 300f)) {
            c.selectMip(scale)
            assertTrue(c.mip >= last, "monotonic")
            last = c.mip
            val tpm = SceneContext.MIP_TPM[c.mip]
            assertTrue(tpm >= min(scale * 0.92f, SceneContext.MIP_TPM.last().toFloat()), "level $tpm covers $scale px/m")
            if (c.mip > 0) assertTrue(SceneContext.MIP_TPM[c.mip - 1] < scale * 0.92f, "smallest sufficient level")
            assertEquals(c.mip * SceneContext.MIP_KINDS, c.texWood)
            assertTrue(abs(c.jointWorld - c.jointWorldMip[c.mip]) < 1e-6f)
        }
    }
}
