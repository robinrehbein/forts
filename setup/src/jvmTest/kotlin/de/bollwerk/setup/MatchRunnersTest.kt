package de.bollwerk.setup

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.loop.ReplayVerification
import de.bollwerk.engine.loop.ReplayVerifier
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.GameView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spielablauf mit **echtem Content** (Schlucht, Standardfestungen): Runner, Replay, Determinismus, Hotseat. */
class MatchRunnersTest {
    private val db = ClasspathContent.load()
    private val setup = MatchSetup(21L, "schlucht", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)))

    /** Spieler 0 und 1 schießen mit dem Mörser der Startfestung auf den gegnerischen Reaktor (Zielwinkel aus der Ballistik). */
    private class Shooter(private val db: de.bollwerk.content.ContentDb, private val player: Int) : CommandSource {
        override fun commandsFor(tick: Long, view: GameView): List<Command> {
            if (tick % 30L != 0L || tick == 0L) return emptyList()
            val mortarType = db.deviceIndex("mortar")
            var m = -1
            for (i in 0 until view.deviceView.size) {
                if (view.deviceView.isAlive(i) && view.deviceView.owner(i) == player && view.deviceView.type(i) == mortarType) m = i
            }
            if (m < 0 || view.deviceView.reloadTicks(m) > 0 || view.deviceView.buildTicks(m) > 0) return emptyList()
            val enemy = view.player(1 - player).reactorDeviceId
            if (enemy < 0 || !view.deviceView.isAlive(enemy)) return emptyList()
            val a = FloatArray(DeviceGeometry.SIZE); val b = FloatArray(DeviceGeometry.SIZE)
            DeviceGeometry.mount(view, m, a); DeviceGeometry.mount(view, enemy, b)
            val weapon = view.tables.weapons[view.tables.devices[mortarType].weapon]
            val angle = Ballistics.solveAngle(
                a[DeviceGeometry.MUZZLE_X], a[DeviceGeometry.MUZZLE_Y], b[DeviceGeometry.X], b[DeviceGeometry.Y],
                0.9f, weapon, view.wind, view.simConfig, highArc = true,
            )
            if (angle.isNaN()) return emptyList()
            val ref = view.deviceView.ref(m)
            return listOf(Command.SetAim(tick, player, ref, angle, 0.9f), Command.Fire(tick, player, ref))
        }
    }

    private fun runner(seed: Long = 21L) =
        MatchRunners.create(db, setup.copy(seed = seed), sources = listOf(Shooter(db, 0), Shooter(db, 1)))

    @Test
    fun runnerDrivesARealMatchAndPublishesAFullHud() {
        val r = runner()
        var shells = 0
        var seq = -1L
        // Frame für Frame wie die App; 3 s Spielzeit mit Aufholen
        for (i in 0 until 400) {
            r.advance(1f / 30f)
            val s = r.exchange.latest()!!
            if (s.seq != seq) { seq = s.seq; shells += s.fx.count { it is FxEvent.Fired } }
        }
        assertTrue(r.tick >= 700L, "ticks ${r.tick}")
        assertTrue(shells >= 2, "both mortars fired: $shells")
        val h = r.exchange.latest()!!.hud
        assertEquals(r.state.players[0].metal, h.metal)
        assertEquals(db.deviceIndex("mortar"), h.weapons.single { it.typeId == db.deviceIndex("mortar") }.typeId)
        assertTrue(h.weapons.size >= 3, "mortar, cannon, mg: ${h.weapons.size}")
        assertEquals(r.tick * r.state.config.dt, h.timeSeconds, 1e-3f)
        assertTrue(h.metalRate > 0f && h.energyRate > 0f)
        assertEquals(1f, h.ownReactor01)
    }

    @Test
    fun sameSetupTwiceGivesIdenticalCheckpointsAndReplayVerifies() {
        val a = runner().also { it.runTicks(1500) }
        val b = runner().also { it.runTicks(1500) }
        assertNull(ReplayVerifier.firstDifference(a.recordedCheckpoints, b.recordedCheckpoints))
        assertEquals(a.hash(), b.hash())
        val replay = a.toReplay()!!
        assertEquals(db.fingerprint, replay.contentHash)
        assertEquals(db.version, replay.contentVersion)
        assertTrue(replay.commands.count { it is Command.Fire } >= 2)
        // über JSON wie aus einer Datei
        val v = MatchRunners.verify(db, Replay.fromJson(replay.toJson()))
        assertIs<ReplayVerification.Ok>(v, v.message())
        // Wiedergabe über einen Runner liefert denselben Endzustand
        val player = MatchRunners.play(db, replay).also { it.runTicks(replay.ticks) }
        assertEquals(a.hash(), player.hash())
    }

    @Test
    fun replayPlaybackIgnoresLiveInputAndStopsAtTheEndOfTheRecording() {
        val replay = runner().also { it.runTicks(300) }.toReplay()!!
        val player = MatchRunners.play(db, replay)
        player.submit(Command.EndTurn(0, 0)) // UI-Eingabe während der Wiedergabe darf nichts bewirken
        player.submit(Command.Undo(0, 0))
        // wie die App: Frames weiter takten, auch über das Ende hinaus
        for (i in 0 until 600) player.advance(1f / 30f)
        assertEquals(replay.ticks, player.tick)
        assertTrue(player.reachedEnd)
        assertEquals(replay.checkpoints.last().hash, player.hash(), "no live input leaked in, stops exactly at replay.ticks")
        assertEquals(1f, player.exchange.latest()!!.alpha)
    }

    @Test
    fun aTruncatedReplayDoesNotVerifyAgainstTheRealContent() {
        val replay = runner().also { it.runTicks(600) }.toReplay()!!
        val v = MatchRunners.verify(db, replay.copy(ticks = 120L))
        assertIs<ReplayVerification.Diverged>(v)
        assertEquals(ReplayVerification.Kind.INCOMPLETE, v.kind)
    }

    @Test
    fun replayOfAnotherContentVersionIsRefused() {
        val replay = runner().also { it.runTicks(120) }.toReplay()!!
        val v = MatchRunners.verify(db, replay.copy(contentHash = replay.contentHash + 1))
        assertIs<ReplayVerification.Incompatible>(v)
        assertTrue(runCatching { MatchRunners.play(db, replay.copy(engineVersion = 99)) }.isFailure)
    }

    @Test
    fun hotseatSetupUsesTheStandardTurnLengthAndFollowsTheActivePlayer() {
        val hs = MatchRunners.hotseatSetup(3L, "schlucht")
        assertTrue(MatchRunners.isHotseat(hs))
        assertTrue(!MatchRunners.isHotseat(hs.copy(players = listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI, "NORMAL")))))
        val r = MatchRunners.create(db, hs)
        r.runTicks(10)
        var h = r.exchange.latest()!!.hud
        assertEquals(TurnPhase.PLAY, h.turnPhase)
        assertEquals(45f, h.turnSecondsTotal, 1e-3f)
        r.submit(Command.EndTurn(0, 0))
        r.runTicks(r.state.config.turn.resolveTicks.toLong() + 5)
        h = r.exchange.latest()!!.hud
        assertTrue(h.handover)
        assertEquals(1, h.localPlayer)
        assertEquals(3, h.handoverCountdown)
        assertEquals(3f, h.handoverSecondsLeft, 0.1f)
        r.runTicks(r.state.config.turn.handoverTicks.toLong())
        h = r.exchange.latest()!!.hud
        assertEquals(TurnPhase.PLAY, h.turnPhase)
        assertEquals(1, h.activePlayer)
        assertEquals(GameResult.Ongoing, h.result)
    }

}
