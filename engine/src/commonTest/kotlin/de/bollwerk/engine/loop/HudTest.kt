package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnConfig
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.HudModel
import de.bollwerk.engine.view.SnapshotBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [HudModel] gegen den Sim-Zustand: Wirtschaft, Reaktoren, Waffen, Tech-Bau, Ergebnis und Hotseat-Zugzustand. */
class HudTest {
    private fun hud(r: MatchRunner): HudModel = r.exchange.latest()!!.hud

    @Test
    fun economyTimeWindAndCapsMatchTheState() {
        val r = LoopRig.runner(LoopRig.setup(metal = 500f, energy = 100f), sources = listOf(DuelBot(0), DuelBot(1, fireFrom = Long.MAX_VALUE)))
        r.runTicks(600)
        val st = r.state
        val me = st.players[0]
        val h = hud(r)
        assertEquals(me.metal, h.metal)
        assertEquals(me.energy, h.energy)
        assertEquals(me.metalCap, h.metalCap)
        assertEquals(me.energyCap, h.energyCap)
        assertEquals(me.metalRate, h.metalRate)
        assertEquals(me.energyRate, h.energyRate)
        assertTrue(h.metalRate > 0f, "mine produces: ${h.metalRate}")
        assertTrue(h.energyRate > 0f, "turbine + reactor produce: ${h.energyRate}")
        assertEquals(600 * st.config.dt, h.timeSeconds, 1e-4f)
        assertEquals(st.wind, h.windSpeed)
        assertEquals(0, h.localPlayer)
        assertEquals(-1, h.activePlayer)
        assertEquals(TurnMode.REALTIME, h.turnMode)
        assertEquals(GameResult.Ongoing, h.result)
        assertEquals(2, h.playerCount)
        assertEquals(me.undoCount, h.undoCount)
        assertTrue(h.canCommand)
        assertTrue(h.localAlive)
        assertFalse(h.paused)
        assertEquals(1f, h.speed)
    }

    @Test
    fun hudFollowsTheConfiguredLocalPlayer() {
        val r = LoopRig.runner(localPlayer = 1)
        r.runTicks(5)
        val h = hud(r)
        assertEquals(1, h.localPlayer)
        assertEquals(r.state.players[1].metal, h.metal)
    }

    @Test
    fun reactorFractionsAndResult() {
        val r = LoopRig.runner()
        r.runTicks(3)
        assertEquals(1f, hud(r).ownReactor01)
        assertEquals(1f, hud(r).enemyReactor01)
        val st = r.state
        st.devices.hpOf[st.players[1].reactorDeviceId] = 75f // 25 % von 300
        st.devices.hpOf[st.players[0].reactorDeviceId] = 150f
        r.runTicks(1)
        assertEquals(0.5f, hud(r).ownReactor01, 1e-4f)
        assertEquals(0.25f, hud(r).enemyReactor01, 1e-4f)
        // Reaktor des Gegners fällt → Sieg
        val d = st.players[1].reactorDeviceId
        st.devices.hpOf[d] = 0f
        st.devices.release(d)
        r.runTicks(3)
        val h = hud(r)
        assertEquals(0f, h.enemyReactor01)
        assertEquals(GameResult.Winner(0, WinReason.REACTOR_DESTROYED), h.result)
        assertFalse(h.canCommand, "game over blocks commands")
        // Die Sicht des Verlierers
        val loser = LoopRig.runner(localPlayer = 1)
        loser.state.let { s -> val x = s.players[1].reactorDeviceId; s.devices.hpOf[x] = 0f; s.devices.release(x) }
        loser.runTicks(3)
        assertEquals(0f, hud(loser).ownReactor01)
        assertFalse(hud(loser).localAlive)
    }

    @Test
    fun techBuildProgressUnlocksAndWeaponReloadFollowTheDevices() {
        val r = LoopRig.runner(sources = listOf(DuelBot(0), DuelBot(1, fireFrom = Long.MAX_VALUE)))
        r.runTicks(300)
        var h = hud(r)
        val total = RuleTables.tables.devices[RuleTables.WORKSHOP].buildTicks
        val w = h.buildingTech.single()
        assertEquals(RuleTables.WORKSHOP, w.typeId)
        val left = r.state.devices.buildTicks[r.state.devices.resolve(w.deviceRef)]
        assertEquals(1f - left.toFloat() / total, w.progress01, 1e-5f)
        assertEquals(left / 60f, w.secondsLeft, 1e-3f)
        assertTrue(w.progress01 in 0.1f..0.3f, "after 300 of $total ticks: ${w.progress01}")
        assertTrue(h.unlockedTechs.isEmpty())
        assertTrue(h.weapons.isEmpty())

        // Werkstatt fertig (Tick ~1802) → Tech frei, Mörser wird platziert und gebaut
        r.runTicks(1600) // Tick 1900
        h = hud(r)
        assertEquals(listOf(RuleTables.T_WORKSHOP), h.unlockedTechs)
        assertTrue(h.buildingTech.isEmpty())
        val building = h.weapons.single()
        assertEquals(RuleTables.MORTAR, building.typeId)
        assertFalse(building.ready, "still under construction")
        assertTrue(building.build01 in 0f..1f && building.build01 < 1f)

        // Mörser fertig und geladen → bereit; nach dem Schuss lädt er nach
        r.runTicks(175) // 2075: gebaut (1830+240), der Schuss fällt in Tick 2080
        h = hud(r)
        assertTrue(h.weapons.single().ready, "ready: ${h.weapons.single()}")
        assertEquals(1f, h.weapons.single().reload01)
        r.runTicks(20) // Schuss
        h = hud(r)
        val ws = h.weapons.single()
        val dev = r.state.devices.resolve(ws.deviceRef)
        val reloadTotal = RuleTables.tables.weapons[RuleTables.W_MORTAR].reloadTicks
        assertFalse(ws.ready)
        assertEquals(1f - r.state.devices.reloadTicksOf[dev].toFloat() / reloadTotal, ws.reload01, 1e-5f)
        assertTrue(ws.reload01 in 0f..0.2f, "just fired: ${ws.reload01}")
        assertEquals(r.state.devices.aimAngle[dev], ws.aimAngle)
        assertEquals(r.state.devices.power[dev], ws.power)
        // der Fortschritt wächst mit der Nachladezeit
        r.runTicks(300)
        val later = hud(r).weapons.single()
        assertTrue(later.reload01 > 0.8f && later.reload01 < 1f, "after 315 of $reloadTotal ticks: ${later.reload01}")
    }

    @Test
    fun enemyReactorFractionIsOneWhileTheOpponentHasNoReactorYet() {
        val r = LoopRig.runner()
        val st = r.state
        st.players[1].reactorDeviceId = -1 // Gegner hat (noch) keinen Reaktor
        r.runTicks(2)
        assertEquals(1f, hud(r).enemyReactor01)
        // der eigene Reaktor ist davon unberührt
        assertEquals(1f, hud(r).ownReactor01)
        // ohne eigenen Reaktor ebenso 1 (kein Reaktor ≠ zerstört)
        st.players[0].reactorDeviceId = -1
        r.runTicks(1)
        assertEquals(1f, hud(r).ownReactor01)
    }

    @Test
    fun aReusedReactorSlotHoldingAnotherDeviceCountsAsDestroyed() {
        val r = LoopRig.runner(record = false)
        val st = r.state
        val rid = st.players[1].reactorDeviceId
        val d = st.devices
        // Reaktor freigeben; nach endTick ist der Slot (LIFO) frei und wird vom nächsten Gerät belegt: hier eine Mine von Spieler 1
        d.hpOf[rid] = 0f
        d.release(rid)
        st.endTick()
        val beam = d.beamId[st.players[0].reactorDeviceId]
        val reused = d.alloc(RuleTables.MINE, beam, 0.5f, 100f, 1, 0, false, 0f, 0f)
        assertEquals(rid, reused, "the slot of the destroyed reactor was reused")
        assertTrue(d.isAlive(rid))
        assertEquals(1, d.ownerOf[rid]) // gleicher Besitzer, aber kein Reaktor
        val h = SnapshotBuilder(0).buildHud(st)
        assertEquals(0f, h.enemyReactor01, "a mine in the old reactor slot is not read as the reactor")
        // und für Spieler 1 selbst
        assertEquals(0f, SnapshotBuilder(1).buildHud(st).ownReactor01)
    }

    // ---- Hotseat ----

    private fun hotseat(): MatchRunner {
        val cfg = SimConfig.DEFAULT.copy(turn = TurnConfig(resolveTicks = 60, handoverTicks = 180))
        return LoopRig.runner(LoopRig.setup(turns = true, turnTicks = 120), hotseat = true, config = cfg)
    }

    @Test
    fun hotseatPlayPhaseShowsTheActivePlayersTurn() {
        val r = hotseat()
        r.runTicks(10)
        val h = hud(r)
        assertEquals(TurnMode.TURNS, h.turnMode)
        assertEquals(TurnPhase.PLAY, h.turnPhase)
        assertEquals(0, h.activePlayer)
        assertEquals(0, h.localPlayer)
        assertEquals(1, h.turnNumber)
        assertEquals(110 / 60f, h.turnSecondsLeft, 1e-3f)
        assertEquals(2f, h.turnSecondsTotal, 1e-4f)
        assertEquals(0f, h.resolveSecondsLeft)
        assertEquals(0f, h.handoverSecondsLeft)
        assertFalse(h.handover)
        assertTrue(h.canCommand)
    }

    @Test
    fun hotseatWalksThroughResolveAndHandoverWithCountdown() {
        val r = hotseat()
        r.runTicks(5)
        r.submit(Command.EndTurn(0, 0))
        r.runTicks(2)
        var h = hud(r)
        assertEquals(TurnPhase.RESOLVE, h.turnPhase)
        assertEquals(0, h.activePlayer)
        assertEquals(0, h.localPlayer)
        assertTrue(h.resolveSecondsLeft > 0f && h.resolveSecondsLeft <= 1f)
        assertEquals(0f, h.turnSecondsLeft)
        assertFalse(h.canCommand, "no input while the shots resolve")

        // Übergang RESOLVE → HANDOVER: der nächste Spieler ist aktiv, das HUD folgt ihm, die App zeigt 3-2-1
        val seen = ArrayList<Int>()
        var handoverHud: HudModel? = null
        for (i in 0 until 400) {
            r.runTicks(1)
            h = hud(r)
            if (h.turnPhase == TurnPhase.HANDOVER) {
                if (handoverHud == null) handoverHud = h
                if (seen.lastOrNull() != h.handoverCountdown) seen.add(h.handoverCountdown)
            } else if (handoverHud != null) break
        }
        assertEquals(listOf(3, 2, 1), seen)
        val first = handoverHud!!
        assertTrue(first.handover)
        assertEquals(1, first.activePlayer)
        assertEquals(1, first.localPlayer, "hotseat HUD follows the player who is up next")
        assertEquals(2, first.turnNumber)
        assertFalse(first.canCommand, "the handover screen blocks input")
        assertEquals(3f, first.handoverSecondsLeft, 0.05f)
        assertEquals(r.state.players[1].metal, first.metal)

        // danach beginnt die Spielphase des zweiten Spielers
        assertEquals(TurnPhase.PLAY, h.turnPhase)
        assertEquals(1, h.activePlayer)
        assertEquals(1, h.localPlayer)
        assertTrue(h.canCommand)
        assertFalse(h.handover)
        assertEquals(2, h.turnNumber)
    }

    @Test
    fun withoutHotseatTheHudKeepsItsPlayerEvenInTurnMode() {
        val cfg = SimConfig.DEFAULT.copy(turn = TurnConfig(resolveTicks = 30, handoverTicks = 30))
        val r = LoopRig.runner(LoopRig.setup(turns = true, turnTicks = 20), hotseat = false, config = cfg)
        r.runTicks(60) // erster Zug zu Ende, Spieler 1 ist dran
        val h = hud(r)
        assertEquals(1, h.activePlayer)
        assertEquals(0, h.localPlayer)
        assertFalse(h.canCommand, "not player 0's turn")
    }
}
