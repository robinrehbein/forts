package de.bollwerk.simrunner

import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.setup.MatchBootstrap
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.Controller
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunnerTest {
    private val db: ContentDb get() = content

    private fun idlePlan(ticks: Long = 600, seed: Long = 1, profile: Boolean = false) =
        RunPlan(name = "idle", map = "schlucht", seed = seed, ticks = ticks, idle = true, profile = profile)

    @Test
    fun idleRunIsDeterministic() {
        val a = Runner.run(db, idlePlan())
        val b = Runner.run(db, idlePlan())
        assertEquals(600L, a.ticksRun)
        assertEquals(a.finalHash, b.finalHash)
        assertEquals(a.hashes, b.hashes) // auch alle Zwischen-Hashes (alle 60 Ticks)
        assertEquals(11, a.hashes.size) // Tick 0, 60, ..., 600
        assertEquals(0, a.breaks.size, "idle forts must stand: ${a.breaks}")
    }

    @Test
    fun hashDependsOnSeedAndTicks() {
        val a = Runner.run(db, idlePlan(seed = 1)).finalHash
        assertNotEquals(a, Runner.run(db, idlePlan(seed = 2)).finalHash)
        assertNotEquals(a, Runner.run(db, idlePlan(ticks = 601)).finalHash)
    }

    @Test
    fun scriptedMortarDuelIsDeterministicAndFires() {
        val sc = ScenarioIo.load(File(ScenarioTest.scenarioDir(), "mortar_duel.json").path).copy(ticks = 500)
        val plan = RunPlan.of(SimArgs(), sc)
        val a = Runner.run(db, plan)
        val b = Runner.run(db, plan)
        assertEquals(a.finalHash, b.finalHash)
        assertTrue(a.shots >= 2, "both mortars should have fired, shots=${a.shots}")
        assertTrue(a.explosions >= 2)
        assertNotEquals(Runner.run(db, idlePlan(ticks = 500)).finalHash, a.finalHash)
    }

    @Test
    fun profilingDoesNotChangeTheSimulationAndListsSlots() {
        val plain = Runner.run(db, idlePlan(ticks = 120))
        val prof = Runner.run(db, idlePlan(ticks = 120, profile = true))
        assertEquals(plain.finalHash, prof.finalHash)
        val stats = prof.profile!!
        assertTrue(stats.any { it.label == "PHYSICS" && it.samples == 120 })
        assertTrue(stats.last().slot == null && stats.last().samples == 120)
        assertTrue(stats.all { it.avgMs >= 0.0 && it.maxMs >= it.p95Ms })
        assertTrue(Report.profile(stats).contains("PHYSICS"))
    }

    @Test
    fun cheatsIgniteSpawnAndLatticeWork() {
        val sc = ScenarioIo.parse(
            """{"name":"c","ticks":90,"commands":[
                {"tick":0,"action":"addLattice","player":0,"material":"wood","x":2,"y":34,"cols":4,"rows":2,"cell":1.0},
                {"tick":0,"action":"ignite","player":1,"material":"wood","beams":2,"fire":0.5},
                {"tick":0,"action":"spawnProjectiles","player":0,"weapon":"mortar","projectiles":5,"x":40,"y":15,"vx":5,"vy":-5,"dvx":1}]}""",
        )
        val r = Runner.run(db, RunPlan.of(SimArgs(), sc))
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        // Gitter 4x2 Zellen: 12 waagrechte + 10 senkrechte + 8 diagonale Balken; Startzahl zählt sie schon mit
        val plain = Runner.run(db, RunPlan(name = "p", map = "schlucht", seed = 1, ticks = 10))
        assertEquals(plain.beamsStart + 30, r.beamsStart)
        assertEquals(r.beamsStart, r.beamsEnd)
        assertEquals(5, r.peakProjectiles)
        assertTrue(r.peakBurning >= 2)
    }

    @Test
    fun unknownDeviceIsReportedNotCrashing() {
        val sc = ScenarioIo.parse("""{"ticks":30,"commands":[{"tick":1,"action":"shoot","player":0,"device":"laser"}]}""")
        val r = Runner.run(db, RunPlan.of(SimArgs(), sc))
        assertTrue(r.warnings.any { "skipped" in it })
    }

    @Test
    fun aiVsAiRunsWithFallbackAgentsAndReportsResult() {
        val r = Runner.run(db, idlePlan(ticks = 120).copy(aiVsAi = true))
        assertEquals(2, r.agents.size)
        assertEquals("ongoing", Report.describe(r.result))
    }

    @Test
    fun rendersPngsAtRequestedTicksWithSuffix() {
        val dir = Files.createTempDirectory("simrunner-test").toFile()
        try {
            val base = File(dir, "shot.png").path
            val r = Runner.run(db, idlePlan(ticks = 40), RenderPlan(base, listOf(40L, 0L), 480, 220, warmup = 3))
            assertEquals(listOf("shot_t0.png", "shot_t40.png"), r.rendered.map { it.name })
            for (f in r.rendered) {
                val img = ImageIO.read(f)
                assertEquals(480, img.width); assertEquals(220, img.height)
                var distinct = HashSet<Int>()
                for (y in 0 until img.height step 10) for (x in 0 until img.width step 10) distinct.add(img.getRGB(x, y))
                assertTrue(distinct.size > 20, "image should not be flat (${distinct.size} colors)")
            }
            // ohne --at: ein Bild ohne Suffix am Ende
            val single = Runner.run(db, idlePlan(ticks = 10), RenderPlan(File(dir, "end.png").path, emptyList(), 320, 160, warmup = 2))
            assertEquals(listOf("end.png"), single.rendered.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileSuffixNaming() {
        assertEquals(File("a/out_t12.png"), SceneCapture.fileFor("a/out.png", 12, true))
        assertEquals(File("out.png"), SceneCapture.fileFor("out.png", 12, false))
        assertEquals(File("out_t5.png"), SceneCapture.fileFor("out", 5, true))
    }

    @Test
    fun cliExitCodes() {
        val out = ArrayList<String>(); val err = ArrayList<String>()
        fun run(vararg a: String) = execute(arrayOf(*a), { out.add(it) }, { err.add(it) })
        assertEquals(0, run("--help"))
        assertEquals(2, run("--bogus"))
        assertEquals(2, run("--scenario", "nope_zzz"))
        assertEquals(2, run("--ticks", "10", "--render", "x.png", "--at", "11"))
        assertEquals(0, run("--ticks", "120", "--assert-stable", "--hash"))
        assertTrue(out.joinToString("\n").contains("assert-stable: OK"))
        // Brüche -> Exit 1: Abriss durch Treffer im Duell
        val sc = File(ScenarioTest.scenarioDir(), "fire_spread.json").path
        assertEquals(1, run("--scenario", sc, "--assert-stable"))
        assertTrue(err.any { "ASSERT-STABLE FAILED" in it })
    }

    @Test
    fun profilerQuantiles() {
        val s = NanoSeries(2)
        for (v in 1..100) s.add(v.toLong())
        assertEquals(95L, s.quantile(0.95)); assertEquals(100L, s.max()); assertEquals(5050L, s.sum())
        assertEquals(0L, NanoSeries().quantile(0.95))
    }

    private fun idleScenarioPlan(map: String? = null): RunPlan =
        RunPlan.of(SimArgs(map = map), ScenarioIo.load(File(ScenarioTest.scenarioDir(), "idle_schlucht.json").path))

    @Test
    fun bundledIdleSchluchtIsStableOver3600Ticks() {
        val r = Runner.run(db, idleScenarioPlan())
        assertEquals(3600L, r.ticksRun)
        assertEquals(0, r.breaks.size, "idle_schlucht must not break: ${r.breaks}")
        assertEquals(r.beamsStart, r.beamsEnd)
        assertTrue(r.beamsStart > 0)
    }

    @Test
    fun huegelMapIsActuallyUsed() {
        val s = Runner.run(db, idleScenarioPlan().copy(ticks = 300))
        val h = Runner.run(db, idleScenarioPlan(map = "huegel").copy(ticks = 300))
        assertEquals("huegel", h.plan.map)
        assertNotEquals(s.finalHash, h.finalHash)
        val out = ArrayList<String>()
        assertEquals(0, execute(arrayOf("--map", "huegel", "--ticks", "60"), { out.add(it) }, {}))
        assertTrue(out.any { "map huegel" in it })
    }

    @Test
    fun idleScenarioWithBreaksExitsOneWithoutAssertStable() {
        val dir = Files.createTempDirectory("simrunner-idle").toFile()
        try {
            val text = File(ScenarioTest.scenarioDir(), "fire_spread.json").readText().replace("\"name\": \"fire_spread\"", "\"name\": \"fire_spread\", \"idle\": true")
            assertTrue("\"idle\": true" in text)
            val f = File(dir, "idle_fire.json").also { it.writeText(text) }
            val err = ArrayList<String>()
            assertEquals(1, execute(arrayOf("--scenario", f.path), {}, { err.add(it) }))
            assertTrue(err.any { "IDLE SCENARIO UNSTABLE" in it })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ticksFlagOverridesScenarioForRepeatedSteps() {
        val sc = ScenarioIo.parse("""{"ticks":100,"commands":[{"tick":0,"action":"fire","every":100,"count":10}]}""")
        assertEquals(2, RunPlan.of(SimArgs(), sc).steps.size)
        assertEquals(6, RunPlan.of(SimArgs(ticks = 500), sc).steps.size)
    }

    @Test
    fun rejectedCommandsAreReported() {
        val sc = ScenarioIo.parse("""{"ticks":150,"commands":[{"tick":60,"action":"shoot","player":0,"device":"mortar","angleDeg":35,"power":0.75},{"tick":70,"action":"fire","player":0,"device":"mortar"}]}""")
        val r = Runner.run(db, RunPlan.of(SimArgs(), sc))
        assertTrue(r.rejections.isNotEmpty(), "a second shot while reloading must be rejected and counted")
        assertTrue(r.warnings.any { "commands rejected" in it })
    }

    @Test
    fun stressStartCountsIncludeTheTickZeroLattices() {
        val sc = ScenarioIo.load(File(ScenarioTest.scenarioDir(), "stress_300.json").path).copy(ticks = 30)
        val r = Runner.run(db, RunPlan.of(SimArgs(), sc))
        assertTrue(r.beamsStart >= 78 + 600, "start count must include the lattices: ${r.beamsStart}")
        assertTrue(r.peakBeams >= r.beamsStart)
    }

    private fun alphaOpaque(img: java.awt.image.BufferedImage): Boolean {
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getRGB(x, y) ushr 24) != 255) return false
        return true
    }

    @Test
    fun uniformDetector() {
        val img = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        assertTrue(SceneCapture.isUniform(img)) // ganz transparent
        img.setRGB(3, 3, 0xFF112233.toInt())
        assertTrue(!SceneCapture.isUniform(img))
    }

    @Test
    fun defaultViewCoversFortsAndHeadroom() {
        for (map in listOf("schlucht", "huegel")) {
            val state = MatchBootstrap.create(db, MatchSetup(1L, map, listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN))))
            val v = SceneCapture.defaultView(state)
            assertEquals(0f, v[0]); assertEquals(state.map.width, v[2])
            var minY = Float.MAX_VALUE
            for (i in 0 until state.nodes.size) if (state.nodes.isAlive(i)) minY = minOf(minY, state.nodes.y[i])
            assertTrue(v[1] <= minY - SceneCapture.HEADROOM + 1e-3f && v[1] >= 0f, "$map top ${v[1]} vs fort top $minY")
            assertTrue(v[3] > state.map.baseY.max() && v[3] <= state.map.height)
        }
    }

    @Test
    fun idleRenderShowsTheFortsNotJustSky() {
        val state = MatchBootstrap.create(db, MatchSetup(1L, "schlucht", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN))))
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until state.nodes.size) if (state.nodes.isAlive(i) && state.nodes.ownerOf[i] == 0) {
            x0 = minOf(x0, state.nodes.x[i]); x1 = maxOf(x1, state.nodes.x[i]); y0 = minOf(y0, state.nodes.y[i]); y1 = maxOf(y1, state.nodes.y[i])
        }
        val w = 400; val h = 300
        fun render(view: FloatArray): java.awt.image.BufferedImage {
            val cap = SceneCapture(state, w, h, view = view)
            cap.frame(state, de.bollwerk.engine.view.FxBuffer())
            val f = File.createTempFile("fort", ".png"); try { cap.save(f); return ImageIO.read(f) } finally { f.delete() }
        }
        val cx = (x0 + x1) / 2; val half = (x1 - x0) / 2 + 2f
        val fort = render(floatArrayOf(cx - half, y0 - 3f, cx + half, y1 - 0.5f))
        val sky = render(floatArrayOf(cx - half, y0 - 3f - 25f, cx + half, y1 - 0.5f - 25f))
        // Himmel ist ein reiner Verlauf: je Zeile gleich. Festungspixel weichen davon ab.
        fun offRowColor(img: java.awt.image.BufferedImage): Double {
            var off = 0; var n = 0
            for (y in 0 until h) { val ref = img.getRGB(2, y); for (x in w / 4 until 3 * w / 4) { n++; if (img.getRGB(x, y) != ref) off++ } }
            return off.toDouble() / n
        }
        val fortOff = offRowColor(fort); val skyOff = offRowColor(sky)
        assertTrue(skyOff < 0.06, "sky-only reference should be a plain gradient ($skyOff)")
        assertTrue(fortOff > 0.15 && fortOff > 3 * skyOff, "fort pixels expected in the fort box ($fortOff)")
        assertTrue(alphaOpaque(fort))
    }

    @Test
    fun explosionFrameIsNotUniformAndOpaque() {
        val sc = ScenarioIo.load(File(ScenarioTest.scenarioDir(), "mortar_duel.json").path).copy(ticks = 700)
        val plan = RunPlan.of(SimArgs(), sc)
        val first = Runner.run(db, plan).explosionLog.first()[0].toLong()
        val dir = Files.createTempDirectory("simrunner-expl").toFile()
        try {
            // einige Ticks nach dem Einschlag (Funken, Rauch, Splitter); die Bildschirm-Blitz-Frames direkt danach prueft render-api
            val at = first + 20
            val r = Runner.run(db, plan.copy(ticks = at), RenderPlan(File(dir, "e.png").path, listOf(at), 480, 220, warmup = 30))
            val img = ImageIO.read(r.rendered.single())
            assertTrue(!SceneCapture.isUniform(img))
            assertTrue(alphaOpaque(img))
            assertTrue(r.warnings.none { "single colour" in it }, r.warnings.toString())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun earlyEndStillRendersTheFinalState() {
        val dir = Files.createTempDirectory("simrunner-early").toFile()
        try {
            val sc = ScenarioIo.parse("""{"ticks":200,"commands":[{"tick":10,"action":"surrender","player":0}]}""")
            val plan = RunPlan.of(SimArgs(aiVsAi = true), sc)
            val r = Runner.run(db, plan, RenderPlan(File(dir, "s.png").path, listOf(150L), 400, 200, warmup = 5))
            assertTrue(r.result is GameResult.Winner)
            assertTrue(r.ticksRun < 150)
            assertTrue(r.warnings.any { "not reached" in it })
            val img = ImageIO.read(r.rendered.single())
            assertTrue(!SceneCapture.isUniform(img), "fallback render must draw the final state")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun aiFallbackIsReported() {
        val r = Runner.run(db, idlePlan(ticks = 30).copy(aiVsAi = true))
        assertTrue(r.warnings.any { "IdleAi" in it })
    }

    companion object {
        val content: ContentDb by lazy { ClasspathContent.load() }
    }
}
