package de.bollwerk.app.game

import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchStats
import de.bollwerk.app.match.TeamColor
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudPresenterTest {
    private val catalog = HudFixtures.catalog

    @Test
    fun mockupValuesAreFormattedLikeTheStyleGuide() {
        val ui = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = false)
        assertEquals(340, ui.metal)
        assertEquals(12, ui.metalRate)
        assertEquals(180, ui.energy)
        assertEquals(400, ui.energyCap)
        assertEquals(8, ui.energyRate)
        assertEquals(0.45f, ui.energyFill, 1e-4f)
        assertEquals("02:14", ui.timeText)
        assertEquals("3,2", ui.windText)
        assertTrue(ui.windToRight)
        assertEquals(2, ui.enemyPlayerNumber)
        assertTrue(ui.enemyIsRed)
        assertEquals(62, ui.enemyPercent)
        assertNull(ui.turn)
    }

    @Test
    fun englishUsesADecimalPointAndWindDirectionFollowsTheSign() {
        val ui = HudPresenter.present(HudFixtures.mockupHud().copy(windSpeed = -4.25f), HudFixtures.buildTools(), catalog, false, '.')
        assertEquals("4.3", ui.windText)
        assertFalse(ui.windToRight)
    }

    @Test
    fun toolbarShowsSelectionLocksAndAffordability() {
        val ui = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = false)
        val ids = ui.materials.map { it.id }
        assertEquals(listOf("wood", "metal", "armour", "door", "rope"), ids, "toolbar order of mockup 3")
        // Index bleibt der Content-Index (Tür und Seil vertauscht gegenüber dem Content)
        for (m in ui.materials) assertEquals(HudFixtures.material(m.id), m.index, m.id)
        val wood = ui.materials.first { it.id == "wood" }
        assertTrue(wood.selected)
        assertEquals(4, wood.costMetal)
        assertTrue(ui.materials.first { it.id == "armour" }.locked, "armour needs the upgrade centre")
        assertFalse(ui.materials.first { it.id == "metal" }.locked)
        assertEquals(listOf("mine", "turbine"), ui.economy.map { it.id })
        // Werkstatt gebaut → Mörser/Kanone frei; Waffenkammer im Bau → MG gesperrt
        assertFalse(ui.weapons.first { it.id == "mortar" }.locked)
        assertTrue(ui.weapons.first { it.id == "mg" }.locked)
        assertEquals(SelectedInfo("wood", ItemKind.MATERIAL, 4, 0, perMeter = true), ui.selected)
        val door = HudPresenter.present(
            HudFixtures.mockupHud(), HudFixtures.buildTools().copy(selection = ToolSelection.Material(HudFixtures.material("door"))),
            catalog, hotseat = false,
        )
        assertEquals("door", door.selected?.id, "cost box resolves the content index, not the toolbar position")
        assertTrue(door.materials.single { it.selected }.id == "door")
        assertTrue(ui.canUndo)
        val poor = HudPresenter.present(HudFixtures.mockupHud().copy(metal = 100f), HudFixtures.buildTools(), catalog, false)
        assertFalse(poor.economy.first { it.id == "mine" }.affordable, "mine costs 120")
        assertTrue(poor.materials.first { it.id == "wood" }.affordable)
    }

    @Test
    fun techCardsFollowTheMockupStates() {
        val ui = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = false)
        val byId = ui.techs.associateBy { it.deviceId }
        assertEquals(listOf("workshop", "armoury", "upgrade_center", "factory"), ui.techs.map { it.deviceId })
        assertEquals(TechStatus.BUILT, byId.getValue("workshop").status)
        val armoury = byId.getValue("armoury")
        assertEquals(TechStatus.BUILDING, armoury.status)
        assertEquals(0.62f, armoury.progress01, 1e-4f)
        assertEquals(21, armoury.secondsLeft)
        assertEquals(TechStatus.AVAILABLE, byId.getValue("upgrade_center").status)
        assertTrue(byId.getValue("upgrade_center").affordable)
        val factory = byId.getValue("factory")
        assertEquals(TechStatus.LOCKED, factory.status)
        assertEquals("upgrade_center", factory.requiresDeviceId)
        assertEquals(480, factory.costMetal)
        assertEquals(240, factory.costEnergy)
        assertEquals(90, factory.buildSeconds)
        val mortar = byId.getValue("workshop").unlocks.first { it.id == "mortar" }
        assertEquals(120, mortar.damage)
        assertEquals(110, mortar.range)
        assertEquals(15, mortar.shotMetal)
        assertEquals(30, mortar.shotEnergy)
        val mg = byId.getValue("armoury").unlocks.first { it.id == "mg" }
        assertEquals(DamageStyle.SALVO, mg.damageStyle)
        assertEquals(6, mg.damage)
        assertEquals(8, mg.damageSecondary)
        assertEquals(DamageStyle.BEAM, byId.getValue("factory").unlocks.first { it.id == "laser" }.damageStyle)
        assertEquals(DamageStyle.INCENDIARY, byId.getValue("factory").unlocks.first { it.id == "rocket" }.damageStyle)
        assertEquals(DamageStyle.MATERIAL, byId.getValue("upgrade_center").unlocks.first { it.id == "armour" }.damageStyle)
    }

    @Test
    fun aimPanelShowsWeaponPowerAngleAndReload() {
        val ui = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.aimTools(), catalog, hotseat = false)
        assertEquals(ToolMode.AIM, ui.mode)
        val a = ui.aim
        assertEquals("mortar", a.weaponDeviceId)
        assertEquals("mortar", a.weaponId)
        assertEquals("2,5", a.splashText)
        assertEquals(78, a.powerPercent)
        assertEquals(52, a.angleDeg)
        assertEquals("+3", a.windDriftText)
        assertFalse(a.ready)
        assertEquals("1,4", a.reloadText)
        assertEquals(2, a.doorCount)
        assertTrue(a.doorsOpen)
        assertEquals(2, a.weaponCount)
        val none = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.aimTools().copy(weaponRef = -1, aim = null), catalog, false)
        assertNull(none.aim.weaponDeviceId)
        assertFalse(none.aim.ready)
    }

    @Test
    fun hotseatShowsTheTurnClock() {
        val ui = HudPresenter.present(HudFixtures.hotseatHud(), HudFixtures.buildTools(), catalog, hotseat = true)
        val t = assertNotNull(ui.turn)
        assertEquals(TurnPhase.PLAY, t.phase)
        assertEquals(33, t.secondsLeft)
        assertTrue(ui.hotseat)
        assertTrue(ui.canCommand)
        val resolve = HudPresenter.present(HudFixtures.hotseatHud(phase = TurnPhase.RESOLVE), HudFixtures.buildTools(), catalog, true)
        assertFalse(resolve.canCommand)
    }

    @Test
    fun formats() {
        assertEquals("00:00", HudFormat.clock(-3f))
        assertEquals("08:42", HudFormat.clock(522.9f))
        assertEquals("61:01", HudFormat.clock(3661f))
        assertEquals("0,0", HudFormat.decimal1(0.04f, ','))
        assertEquals("-1.5", HudFormat.decimal1(-1.5f, '.'))
        assertEquals("+3", HudFormat.signed(2.6f))
        assertEquals("-2", HudFormat.signed(-2.2f))
        assertEquals("0", HudFormat.signed(0.2f))
    }
}

class OutcomeMessagesTest {
    private fun outcome(player: Int, result: CommandResult) =
        CommandOutcome(5L, Command.Fire(5L, player, 1L), result)

    @Test
    fun onlyRejectionsOfLocalHumansBecomeMessages() {
        val human = { p: Int -> p == 0 }
        assertEquals(RejectReason.RELOADING, OutcomeMessages.messageFor(outcome(0, CommandResult.Rejected(RejectReason.RELOADING)), human))
        assertNull(OutcomeMessages.messageFor(outcome(0, CommandResult.Accepted), human))
        assertNull(OutcomeMessages.messageFor(outcome(1, CommandResult.Rejected(RejectReason.RELOADING)), human), "AI rejections stay silent")
        assertNull(OutcomeMessages.messageFor(outcome(0, CommandResult.Rejected(RejectReason.GAME_OVER)), human))
    }
}

class MatchResultsTest {
    private val stats = { p: Int -> MatchStats(durationSeconds = 60, shots = p + 10) }

    @Test
    fun reactorDestroyedAgainstAi() {
        val cfg = MatchConfig()
        val win = MatchResults.from(cfg, GameResult.Winner(0, WinReason.REACTOR_DESTROYED), stats)!!
        assertEquals(EndReason.ENEMY_REACTOR_DESTROYED, win.reason)
        assertTrue(win.isVictory)
        assertEquals(10, win.stats.shots, "report shows the human's numbers")
        val loss = MatchResults.from(cfg, GameResult.Winner(1, WinReason.REACTOR_DESTROYED), stats)!!
        assertEquals(EndReason.OWN_REACTOR_DESTROYED, loss.reason)
        assertFalse(loss.isVictory)
        assertEquals(10, loss.stats.shots)
    }

    @Test
    fun redHumanSurrenderAndHotseatWinner() {
        val red = MatchConfig(team = TeamColor.RED)
        val r = MatchResults.from(red, GameResult.Winner(0, WinReason.SURRENDER), stats)!!
        assertEquals(EndReason.SURRENDER, r.reason)
        assertFalse(r.isVictory)
        assertEquals(11, r.stats.shots)
        val hs = MatchResults.from(MatchConfig(mode = GameMode.HOTSEAT), GameResult.Winner(1, WinReason.REACTOR_DESTROYED), stats)!!
        assertTrue(hs.isVictory)
        assertEquals(1, hs.bannerPlayerId)
        assertEquals(EndReason.ENEMY_REACTOR_DESTROYED, hs.reason)
        assertEquals(11, hs.stats.shots)
    }

    @Test
    fun drawAndOngoing() {
        assertNull(MatchResults.from(MatchConfig(), GameResult.Ongoing, stats))
        val d = MatchResults.from(MatchConfig(), GameResult.Draw, stats)!!
        assertTrue(d.isDraw)
        assertFalse(d.isVictory)
        assertEquals(EndReason.DRAW, d.reason)
        assertEquals(0, d.bannerPlayerId)
    }

    @Test
    fun everyToolSelectionIsPresented() {
        // ToolSelection ist Teil des HUD-Vertrags: alle Varianten werden im Presenter behandelt
        for (s in listOf(ToolSelection.Repair, ToolSelection.Delete, ToolSelection.Door, ToolSelection.None)) {
            val ui = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools().copy(selection = s), HudFixtures.catalog, false)
            assertEquals(s == ToolSelection.Repair, ui.repairSelected)
        }
    }
}
