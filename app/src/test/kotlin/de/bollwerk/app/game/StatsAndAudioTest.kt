package de.bollwerk.app.game

import de.bollwerk.app.R
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.ui.game.hud.contentNameRes
import de.bollwerk.app.ui.game.hud.rejectReasonRes
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.engine.view.HudModel
import de.bollwerk.engine.view.SnapshotBuilder
import de.bollwerk.engine.view.SnapshotExchange
import de.bollwerk.render.android.audio.HapticStrength
import de.bollwerk.render.android.audio.HapticsSink
import de.bollwerk.render.android.audio.SfxId
import de.bollwerk.render.android.audio.SfxSink
import de.bollwerk.renderapi.Camera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchStatsCounterTest {
    @Test
    fun acceptedFireAndPlaceBeamAreCountedPerPlayer() {
        val s = MatchStatsCounter()
        s.onOutcome(CommandOutcome(1, Command.Fire(1, 0, 5L), CommandResult.Accepted))
        s.onOutcome(CommandOutcome(1, Command.Fire(1, 0, 5L), CommandResult.Rejected(RejectReason.RELOADING)))
        s.onOutcome(CommandOutcome(1, Command.PlaceBeam(1, 1, materialId = 0), CommandResult.Accepted))
        assertEquals(1, s.statsFor(0, 9).shots)
        assertEquals(0, s.statsFor(0, 9).beamsBuilt)
        assertEquals(1, s.statsFor(1, 9).beamsBuilt)
        assertEquals(9, s.statsFor(1, 9).durationSeconds)
    }

    /** Echte Partie (ohne Thread): Zähler einzeln, Fx synthetisch, Besitzer aus dem echten Zustand. */
    private class Fixture {
        val session = MatchSessions.create(HudFixtures.db, MatchConfig(seed = 4))
        val runner = session.runner
        val state get() = runner.state
        val stats = MatchStatsCounter()

        fun beamOf(player: Int): Int {
            val b = state.beamView
            for (i in 0 until b.size) if (b.isAlive(i) && b.owner(i) == player) return b.uid(i)
            error("no beam for $player")
        }

        fun deviceOf(player: Int): Int {
            val d = state.deviceView
            for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == player) return d.uid(i)
            error("no device for $player")
        }

        /** Ein Tick läuft, danach wertet der Zähler [fx] als dessen Ereignisse aus. */
        fun tickWith(vararg fx: FxEvent) {
            runner.runTicks(1)
            stats.sync(state, fx.toList())
        }

        init { stats.sync(state, emptyList()) }
    }

    private fun hit(tick: Long, x: Float, uid: Int, target: HitTarget = HitTarget.BEAM, splash: Boolean = false) =
        FxEvent.Hit(tick, x, 0f, target, uid, 10f, 2, splash)

    @Test
    fun hitsOnEnemyTargetsCountOncePerExplosionAndSalvo() {
        val f = Fixture()
        val s = f.stats
        val enemyBeam = f.beamOf(1)
        val enemyDevice = f.deviceOf(1)
        val ownBeam = f.beamOf(0)
        // Explosion: drei Splash-Treffer mit demselben Zentrum → ein Treffer für Spieler 0
        f.tickWith(hit(100, 50f, enemyBeam, splash = true), hit(100, 50f, enemyDevice, HitTarget.DEVICE, splash = true), hit(100, 50f, enemyBeam, splash = true))
        assertEquals(1, s.statsFor(0, 0).hits)
        // MG-Salve: direkte Treffer kurz nacheinander → einer; später wieder einer
        f.tickWith(hit(200, 51f, enemyBeam), hit(205, 52f, enemyBeam), hit(210, 53f, enemyDevice, HitTarget.DEVICE))
        assertEquals(2, s.statsFor(0, 0).hits)
        f.tickWith(hit(400, 51f, enemyBeam))
        assertEquals(3, s.statsFor(0, 0).hits)
        // Treffer auf eigene Teile von Spieler 0 zählen für Spieler 1
        f.tickWith(hit(500, 10f, ownBeam))
        assertEquals(1, s.statsFor(1, 0).hits)
        f.tickWith(FxEvent.Hit(600, 0f, 0f, HitTarget.TERRAIN, -1, 10f, 2))
        assertEquals(3, s.statsFor(0, 0).hits)
        // Unbekannte uid (nie gesehen): kein Treffer
        f.tickWith(hit(700, 0f, 9_999_999))
        assertEquals(3, s.statsFor(0, 0).hits)
    }

    @Test
    fun brokenBeamsCountAsLostExceptDemolitionDecayAndRepeats() {
        val f = Fixture()
        val own = f.beamOf(0)
        val enemy = f.beamOf(1)
        f.tickWith(
            FxEvent.BeamBroken(1, 0f, 0f, own, 0, 0.5f, BreakCause.DAMAGE),
            FxEvent.BeamBroken(2, 0f, 0f, own, 0, 0.5f, BreakCause.FIRE), // derselbe Balken: nur einmal
            FxEvent.BeamBroken(5, 0f, 0f, enemy, 0, 0.5f, BreakCause.STRAIN),
        )
        assertEquals(1, f.stats.statsFor(0, 0).beamsLost)
        assertEquals(1, f.stats.statsFor(1, 0).beamsLost)
        val g = Fixture()
        g.tickWith(
            FxEvent.BeamBroken(3, 0f, 0f, g.beamOf(0), 0, 0.5f, BreakCause.DELETED),
            FxEvent.BeamBroken(4, 0f, 0f, g.beamOf(1), 0, 0.5f, BreakCause.DECAY),
            FxEvent.BeamBroken(4, 0f, 0f, 9_999_999, 0, 0.5f, BreakCause.DAMAGE), // unbekannt (im selben Tick entstanden)
        )
        g.tickWith(FxEvent.BeamBroken(6, 0f, 0f, g.beamOf(0), 0, 0.5f, BreakCause.DAMAGE)) // nach Abriss: erledigt
        assertEquals(0, g.stats.statsFor(0, 0).beamsLost)
        assertEquals(0, g.stats.statsFor(1, 0).beamsLost)
    }

    /**
     * Echter Bruch im Sim-Takt: ein eigener Holzbalken verbrennt, seine zwei Hälften (HALF) verbrennen danach ebenfalls
     * (je ein weiteres `BeamBroken` desselben Besitzers, Slots werden freigegeben und wiederbelegt) → genau **ein**
     * verlorener Balken. Gezählt wird auf dem Sim-Thread über die angehängte Quelle, ohne Renderer.
     */
    @Test
    fun aRealBeamThatSplitsAndWhoseHalvesBreakCountsOnce() {
        val session = MatchSessions.create(HudFixtures.db, MatchConfig(seed = 4))
        val controller = GameController(session, FakeClock())
        val runner = session.runner
        val state = runner.state
        val beams = state.beams
        val wood = HudFixtures.material("wood")
        runner.runTicks(2)
        var target = -1
        for (i in 0 until beams.size) {
            if (!beams.isAlive(i) || beams.ownerOf[i] != 0 || beams.materialOf[i] != wood) continue
            if ((beams.flags[i] and (BeamFlags.HALF or BeamFlags.DEBRIS)) != 0 || beams.restLen[i] < 1.2f) continue
            target = i
            break
        }
        assertTrue(target >= 0, "start fort has a long wood beam")
        val uid = beams.uidOf[target]
        // Brennt mit kaum TP: bricht im nächsten Tick (Feuer, mit Hälften), bevor das Feuer übergreifen kann
        beams.hpOf[target] = 0.001f
        beams.fireOf[target] = 0.01f
        runner.runTicks(1)
        assertEquals(-1, (0 until beams.size).firstOrNull { beams.isAlive(it) && beams.uidOf[it] == uid } ?: -1, "beam broke")
        val halves = (0 until beams.size).filter { beams.isAlive(it) && (beams.flags[it] and BeamFlags.HALF) != 0 }
        assertEquals(2, halves.size, "the beam split into two halves")
        for (h in halves) { beams.hpOf[h] = 0.001f; beams.fireOf[h] = 0.01f }
        runner.runTicks(3)
        assertTrue(halves.none { beams.isAlive(it) && (beams.flags[it] and BeamFlags.HALF) != 0 }, "halves broke too")
        // Neue Balken belegen die freien Slots wieder (uid-Tabelle statt Slot-Lookup)
        runner.runTicks(30)
        var debrisOriginals = 0
        for (i in 0 until beams.size) {
            if (beams.isAlive(i) && beams.ownerOf[i] == 0 && (beams.flags[i] and BeamFlags.DEBRIS) != 0 &&
                (beams.flags[i] and BeamFlags.HALF) == 0
            ) debrisOriginals++
        }
        assertEquals(0, debrisOriginals, "test beam must not disconnect other beams")
        assertEquals(1, controller.stats.statsFor(0, 0).beamsLost)
        assertEquals(0, controller.stats.statsFor(1, 0).beamsLost)
        controller.stop()
    }
}

class GameSnapshotSourceTest {
    @Test
    fun eachSnapshotReachesTheAudioListenerOnceAsFresh() {
        val session = MatchSessions.create(HudFixtures.db, MatchConfig(seed = 4))
        val exchange = SnapshotExchange()
        val cam = Camera(800f, 400f, 2f)
        val frames = ArrayList<Boolean>()
        val src = GameSnapshotSource(exchange, session.tables, session.map, cam, { _, fresh, c ->
            frames += fresh
            assertEquals(800f, c.viewportWidth)
        })
        assertEquals(null, src.latest())
        val builder = SnapshotBuilder(0)
        fun publish() {
            val s = exchange.beginWrite()
            builder.build(session.runner.state, s)
            exchange.publish()
        }
        publish()
        assertNotNull(src.latest())
        assertNotNull(src.latest()) // derselbe Snapshot im nächsten Frame
        publish()
        src.latest()
        assertEquals(listOf(true, false, true), frames)
    }

    /**
     * Die Sim veröffentlicht je Tick mehrere Snapshots, die sich nur im Alpha unterscheiden. Der Renderer muss genau dieses
     * Alpha bekommen (sonst schätzt er es aus der Ankunftszeit und springt bei jeder neuen `seq` auf ~0 zurück).
     */
    @Test
    fun alphaHintIsTheAlphaOfTheLatestSnapshot() {
        val session = MatchSessions.create(HudFixtures.db, MatchConfig(seed = 4))
        val runner = session.runner
        val src = GameSnapshotSource(runner.exchange, session.tables, session.map, Camera(800f, 400f, 2f), simMillis = { 1.5f })
        assertTrue(src.alphaHint().isNaN(), "no snapshot read yet")
        assertEquals(1.5f, src.lastSimMillis())
        runner.runTicks(1)
        for (alpha in floatArrayOf(0.2f, 0.7f)) {
            runner.publish(alpha) // gleicher Tick, nur anderes Alpha
            val snap = assertNotNull(src.latest())
            assertEquals(alpha, snap.alpha)
            assertEquals(alpha, src.alphaHint())
        }
        assertTrue(GameSnapshotSource(runner.exchange, session.tables, session.map, Camera()).lastSimMillis().isNaN())
    }

    /** Im Spiel: der Sim-Thread (GameController) liefert das Alpha seiner Uhr, ~120 Snapshots je s mit wechselndem Alpha. */
    @Test
    fun controllerSnapshotsCarryTheLoopAlpha() {
        val clock = FakeClock()
        val runtime = GameRuntime(GameController(MatchSessions.create(HudFixtures.db, MatchConfig(seed = 4)), clock))
        val c = runtime.controller
        c.stepOnce()
        clock.advanceMs(1000.0 / 60.0 * 1.25) // 1¼ Tick: ein Tick läuft, Rest 0,25
        c.stepOnce()
        runtime.snapshotSource.latest()
        assertEquals(c.runner.alpha, runtime.snapshotSource.alphaHint(), 1e-4f)
        assertEquals(0.25f, runtime.snapshotSource.alphaHint(), 0.02f)
        val seq = c.runner.publishedSeq
        clock.advanceMs(1000.0 / 60.0 * 0.5) // kein Tick, nur Alpha 0,75
        assertEquals(0, c.stepOnce())
        assertTrue(c.runner.publishedSeq > seq, "alpha-only snapshot")
        runtime.snapshotSource.latest()
        assertEquals(0.75f, runtime.snapshotSource.alphaHint(), 0.02f)
        assertFalse(runtime.snapshotSource.lastSimMillis().isNaN(), "sim time for FrameStats")
        runtime.stop()
    }
}

class MatchAudioTest {
    private class RecSfx : SfxSink {
        val played = ArrayList<SfxId>()
        var updates = 0
        var lastFireCount = -1
        override fun play(id: SfxId, volume: Float, pan: Float, pitch: Float) { played += id }
        override fun setFireCount(count: Int) { lastFireCount = count }
        override fun update() { updates++ }
    }

    private class RecHaptics : HapticsSink {
        val pulses = ArrayList<HapticStrength>()
        override fun pulse(strength: HapticStrength) { pulses += strength }
    }

    @Test
    fun fxArePlayedOncePerSeqAndTheLoopIsUpdatedEveryFrame() {
        val sfx = RecSfx()
        val audio = MatchAudio(sfx, RecHaptics(), MatchAudio.idsFor(HudFixtures.catalog), localPlayer = 0)
        val snap = FrameSnapshot().apply {
            seq = 1
            hud = HudModel(localPlayer = 1)
            fx += FxEvent.Explosion(1, 10f, 5f, 2.5f, 120f, 2, -1, false, -1, 0f)
        }
        val cam = Camera(800f, 400f, 2f).also { it.centerX = 10f }
        audio.onFrame(snap, true, cam)
        audio.onFrame(snap, false, cam)
        assertEquals(listOf(SfxId.EXPLOSION), sfx.played)
        assertEquals(2, sfx.updates)
        assertEquals(1, audio.mapper.localPlayerId, "hotseat: follows the HUD player")
    }

    @Test
    fun pausedSimSilencesTheFireLoopAndPlaysNoFx() {
        val sfx = RecSfx()
        val audio = MatchAudio(sfx, RecHaptics(), MatchAudio.idsFor(HudFixtures.catalog), localPlayer = 0)
        val cam = Camera(800f, 400f, 2f)
        val running = FrameSnapshot().apply {
            seq = 1
            beamCount = 1
            beamUid = intArrayOf(1)
            beamOwner = intArrayOf(0)
            beamFlags = intArrayOf(BeamFlags.ALIVE)
            beamFire01 = floatArrayOf(0.8f)
        }
        audio.onFrame(running, true, cam)
        assertEquals(1, sfx.lastFireCount, "one burning beam feeds the fire loop")
        val paused = FrameSnapshot().apply {
            seq = 2
            hud = HudModel(paused = true)
            beamCount = 1
            beamUid = intArrayOf(1)
            beamOwner = intArrayOf(0)
            beamFlags = intArrayOf(BeamFlags.ALIVE)
            beamFire01 = floatArrayOf(0.8f)
            fx += FxEvent.Explosion(1, 10f, 5f, 2.5f, 120f, 2, -1, false, -1, 0f)
        }
        val updates = sfx.updates
        repeat(30) { audio.onFrame(paused, it == 0, cam) }
        assertEquals(0, sfx.lastFireCount, "no crackle while the game is paused")
        assertTrue(sfx.played.isEmpty(), "no fx while paused")
        assertEquals(updates + 30, sfx.updates, "the loop still fades out")
    }

    @Test
    fun settingsMapToVolumeMuteAndHaptics() {
        val a = AppSettings(soundVolume = 0.4f, haptics = false).toAudioSettings()
        assertEquals(0.4f, a.sfxVolume)
        assertFalse(a.muted)
        assertFalse(a.hapticsEnabled)
        assertTrue(AppSettings(soundVolume = 0f).toAudioSettings().muted)
    }
}

class RejectTextTest {
    @Test
    fun everyReasonHasALocalizedTextNamedAfterItsDisplayKey() {
        for (r in RejectReason.entries) {
            val expected = R.string::class.java.getField(r.displayKey).getInt(null)
            assertEquals(expected, rejectReasonRes(r), "resource for $r")
        }
    }

    @Test
    fun everyContentPartHasAName() {
        val db = HudFixtures.db
        for (m in db.materials) assertEquals(R.string::class.java.getField(m.nameKey).getInt(null), contentNameRes(m.id), m.id)
        for (d in db.devices) assertEquals(R.string::class.java.getField(d.nameKey).getInt(null), contentNameRes(d.id), d.id)
    }
}
