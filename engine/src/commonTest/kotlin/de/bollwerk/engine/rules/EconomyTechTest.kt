package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.rules.RuleTables.ARMOURY
import de.bollwerk.engine.rules.RuleTables.FACTORY
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.TURBINE
import de.bollwerk.engine.rules.RuleTables.UPGRADE
import de.bollwerk.engine.rules.RuleTables.WORKSHOP
import de.bollwerk.engine.rules.RuleTables.T_ARMOURY
import de.bollwerk.engine.rules.RuleTables.T_FACTORY
import de.bollwerk.engine.rules.RuleTables.T_UPGRADE
import de.bollwerk.engine.rules.RuleTables.T_WORKSHOP
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wirtschaft: Stil-Bibel §1 (Mine +6 ⚙/s, Turbine +6 ⚡/s × 1,0–1,5, Reaktor +2 ⚡/s, Lager 1000/400). */
class EconomyTest {
    private val hudEnergyRate = 2f + 6f * Economy.turbineFactor(34f, 33.84f)

    /**
     * Fort + 2 Minen und 1 Turbine auf Bodenhöhe = HUD-Beispiel "Metall +12/s, Energie +8/s". Die Turbine sitzt 0,16 m über
     * dem Boden (halbe Balkendicke): Höhenfaktor 1,0067, also 8,04 ⚡/s ≈ 8.
     */
    private fun hudExample(): RulesRig {
        val rig = RulesRig(metal = 340f, energy = 180f)
        val g = rig.ground01
        rig.addDevice(MINE, g, 0.15f)
        rig.addDevice(MINE, g, 0.85f)
        val turbineBeam = rig.addBeam(rig.n26, rig.addAnchor(29f, 34f))
        rig.addDevice(TURBINE, turbineBeam, 0.5f)
        return rig
    }

    @Test
    fun ratesMatchTheStyleGuideHudExample() {
        val rig = hudExample()
        rig.run(1)
        assertEquals(12f, rig.player().metalRate, 1e-4f)  // 2 Minen
        assertEquals(8f, rig.player().energyRate, 0.05f)  // Reaktor 2 + Turbine 6 (Bodenhöhe: Faktor ≈ 1,0)
        assertEquals(hudEnergyRate, rig.player().energyRate, 1e-4f)
        assertEquals(0f, rig.player(1).metalRate, 1e-4f)
        assertEquals(2f, rig.player(1).energyRate, 1e-4f) // gegnerischer Reaktor
    }

    @Test
    fun resourcesGrowByTheRatePerSecond() {
        val rig = hudExample()
        rig.run(60) // 1 s
        assertEquals(340f + 12f, rig.player().metal, 0.01f)
        assertEquals(180f + hudEnergyRate, rig.player().energy, 0.01f)
        rig.run(600) // 10 s
        assertEquals(340f + 12f * 11f, rig.player().metal, 0.05f)
        assertEquals(180f + hudEnergyRate * 11f, rig.player().energy, 0.05f)
    }

    @Test
    fun turbineGainsUpToFiftyPercentWithHeight() {
        assertEquals(1f, Economy.turbineFactor(34f, 34f))
        assertEquals(1f, Economy.turbineFactor(34f, 40f)) // tiefer als der Boden: kein Malus
        assertEquals(1.25f, Economy.turbineFactor(34f, 28f), 1e-5f) // 6 m hoch
        assertEquals(1.5f, Economy.turbineFactor(34f, 22f), 1e-5f)  // ab 12 m hoch: Maximum
        assertEquals(1.5f, Economy.turbineFactor(34f, 10f), 1e-5f)
        assertEquals(1.5f, Economy.turbineFactor(34f, -20f), 1e-5f) // gedeckelt
        // im Spiel: Turbine hoch oben auf einem Mast
        val rig = RulesRig()
        val top = rig.addAnchor(24f, 22f) // 12 m über dem Boden
        val mast = rig.addBeam(top, rig.addAnchor(27f, 22f))
        rig.addDevice(TURBINE, mast, 0.5f)
        rig.run(1)
        // Montagepunkt y = 22 − 0,13 → Faktor 1 + 12,13/24 = 1,505 → gedeckelt 1,5
        assertEquals(2f + 6f * 1.5f, rig.player().energyRate, 1e-3f)
        // mittlere Höhe
        val rig2 = RulesRig()
        val mid = rig2.addBeam(rig2.addAnchor(24f, 28f), rig2.addAnchor(27f, 28f))
        rig2.addDevice(TURBINE, mid, 0.5f)
        rig2.run(1)
        val expected = 2f + 6f * Economy.turbineFactor(34f, 27.84f)
        assertEquals(expected, rig2.player().energyRate, 1e-3f)
        assertTrue(rig2.player().energyRate in 8.5f..10.9f)
    }

    @Test
    fun incomeStopsAtTheStorageCaps() {
        val rig = hudExample()
        rig.state.players[0].metal = 995f
        rig.state.players[0].energy = 399f
        rig.run(120)
        assertEquals(1000f, rig.player().metal, 1e-3f)
        assertEquals(400f, rig.player().energy, 1e-3f)
        assertEquals(1000f, rig.player().metalCap)
        assertEquals(400f, rig.player().energyCap)
    }

    @Test
    fun refundAboveTheCapIsNotCutOff() {
        val rig = RulesRig(metal = 1000f)
        rig.ok(Command.DeleteBeam(rig.tick, 0, rig.bref(rig.beamBetween(rig.n23, rig.apex))))
        assertEquals(1006f, rig.player().metal, 1e-3f)
        rig.run(60)
        assertEquals(1006f, rig.player().metal, 1e-3f, "Einnahmen füllen nur bis zum Lager auf, nehmen aber nichts weg")
    }

    @Test
    fun startResourcesComeFromTheMatchSetupAndAreCapped() {
        val normal = RulesRig()
        assertEquals(400f, normal.player().metal)
        assertEquals(200f, normal.player().energy)
        val rich = RulesRig(metal = 5000f, energy = 5000f)
        assertEquals(1000f, rich.player().metal)
        assertEquals(400f, rich.player().energy)
    }

    @Test
    fun devicesUnderConstructionProduceNothingUntilFinished() {
        val rig = RulesRig(metal = 1000f)
        rig.ok(Command.PlaceDevice(rig.tick, 0, MINE, rig.bref(rig.ground01), 0.85f, true))
        rig.run(1)
        assertEquals(0f, rig.player().metalRate, 1e-6f)
        rig.run(5 * 60 - 1)
        assertEquals(0f, rig.player().metalRate, 1e-6f, "noch genau 1 Tick im Bau")
        rig.run(2)
        assertEquals(6f, rig.player().metalRate, 1e-6f)
        assertFalse((rig.state.devices.flags[rig.devicesOf(MINE).single()] and DeviceFlags.BUILDING) != 0)
    }

    @Test
    fun destroyedOrDisabledProducersStopPaying() {
        val rig = hudExample()
        rig.run(1)
        val mines = rig.devicesOf(MINE)
        rig.state.devices.release(mines[0])
        rig.run(1)
        assertEquals(6f, rig.player().metalRate, 1e-6f)
        rig.state.devices.flags[mines[1]] = rig.state.devices.flags[mines[1]] or DeviceFlags.DISABLED
        rig.run(1)
        assertEquals(0f, rig.player().metalRate, 1e-6f)
    }

    @Test
    fun devicesOnDebrisBeamsProduceNothing() {
        val rig = hudExample()
        rig.state.beams.flags[rig.ground01] = rig.state.beams.flags[rig.ground01] or BeamFlags.DEBRIS
        rig.run(1)
        assertEquals(0f, rig.player().metalRate, 1e-6f)
    }

    @Test
    fun noIncomeAfterTheGameIsOver() {
        val rig = hudExample()
        rig.run(1)
        rig.killReactor(1)
        rig.run(1)
        val m = rig.player().metal
        rig.run(60)
        assertEquals(m, rig.player().metal, 1e-6f)
    }

    @Test
    fun windDriftsDeterministicallyInsideTheMapRange() {
        fun winds(): List<Float> {
            val rig = RulesRig()
            val out = ArrayList<Float>()
            out.add(rig.state.wind)
            for (k in 0 until 20) { rig.run(1800); out.add(rig.state.wind) }
            return out
        }
        val a = winds()
        assertEquals(a, winds())
        assertTrue(a.all { it >= -4f && it <= 4f })
        assertTrue(a.toSet().size > 5, "der Wind ändert sich")
        assertTrue(a.zipWithNext().all { (x, y) -> kotlin.math.abs(y - x) <= 2f + 1e-4f })
    }
}

/** Tech: Gebäude zählen herunter, gewähren Techs, zerstörte Gebäude sperren sie wieder. */
class TechTest {
    @Test
    fun techBuildingCountsDownThenUnlocksItsTech() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        rig.ok(Command.PlaceDevice(rig.tick, 0, WORKSHOP, rig.bref(rig.beamBetween(rig.n23, rig.apex)), 0.5f, false))
        val w = rig.devicesOf(WORKSHOP).single()
        assertEquals(1800, rig.state.devices.buildTicks[w]) // 30 s
        assertFalse(T_WORKSHOP in rig.player().techUnlocked)
        rig.run(1799)
        assertEquals(1, rig.state.devices.buildTicks[w])
        assertFalse(T_WORKSHOP in rig.player().techUnlocked)
        rig.run(1)
        assertEquals(0, rig.state.devices.buildTicks[w])
        assertTrue(T_WORKSHOP in rig.player().techUnlocked)
        assertTrue(T_WORKSHOP in rig.state.players[0].techUnlocked)
        assertFalse(T_WORKSHOP in rig.state.players[1].techUnlocked)
        assertEquals(0, rig.state.devices.flags[w] and DeviceFlags.BUILDING)
        val ev = rig.fx.filterIsInstance<FxEvent.TechChanged>().single()
        assertEquals(T_WORKSHOP, ev.techId); assertTrue(ev.unlocked); assertEquals(0, ev.playerId)
    }

    @Test
    fun buildTimesFollowTheStyleGuide() {
        val times = mapOf(WORKSHOP to 30, ARMOURY to 45, UPGRADE to 60, FACTORY to 90)
        for ((type, secs) in times) assertEquals(secs * 60, RuleTables.tables.devices[type].buildTicks, "type $type")
    }

    @Test
    fun destroyedBuildingRelocksItsTechAndTheDependentBlocks() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val w = rig.addDevice(WORKSHOP, rig.ground01, 0.15f)
        rig.run(1)
        assertTrue(T_WORKSHOP in rig.player().techUnlocked)
        rig.state.devices.release(w) // zerstört
        rig.run(1)
        assertFalse(T_WORKSHOP in rig.player().techUnlocked)
        val ev = rig.fx.filterIsInstance<FxEvent.TechChanged>()
        assertEquals(listOf(true, false), ev.map { it.unlocked })
        // der Mörser ist wieder gesperrt
        rig.validates(de.bollwerk.engine.command.RejectReason.LOCKED_TECH,
            Command.PlaceDevice(rig.tick, 0, RuleTables.MORTAR, rig.bref(rig.beamBetween(rig.n23, rig.apex)), 0.85f, true))
    }

    @Test
    fun deletingTheBuildingRelocksTheTech() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val w = rig.addDevice(ARMOURY, rig.ground01, 0.15f)
        rig.run(1)
        assertTrue(T_ARMOURY in rig.player().techUnlocked)
        rig.ok(Command.DeleteDevice(rig.tick, 0, rig.dref(w)))
        assertFalse(T_ARMOURY in rig.player().techUnlocked)
    }

    @Test
    fun buildingUnderConstructionGrantsNothing() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val w = rig.addDevice(UPGRADE, rig.ground01, 0.15f)
        rig.state.devices.buildTicks[w] = 5
        rig.run(4)
        assertFalse(T_UPGRADE in rig.player().techUnlocked)
        rig.run(1)
        assertTrue(T_UPGRADE in rig.player().techUnlocked)
    }

    @Test
    fun factoryChainNeedsTheUpgradeCenterToBuildAndRelocksIndependently() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val up = rig.addDevice(UPGRADE, rig.ground01, 0.15f)
        rig.run(1)
        rig.ok(Command.PlaceDevice(rig.tick, 0, FACTORY, rig.bref(rig.beamBetween(rig.n23, rig.apex)), 0.85f, true))
        rig.run(90 * 60 + 1)
        assertTrue(T_FACTORY in rig.player().techUnlocked)
        // fällt das Upgrade-Zentrum, bleibt die Fabrik-Tech erhalten (Tech folgt nur dem eigenen Gebäude)
        rig.state.devices.release(up)
        rig.run(1)
        assertFalse(T_UPGRADE in rig.player().techUnlocked)
        assertTrue(T_FACTORY in rig.player().techUnlocked)
    }

    @Test
    fun techIsPerPlayer() {
        val rig = RulesRig()
        rig.addDevice(WORKSHOP, rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(94f, 34f)), 0.15f, owner = 1)
        rig.run(1)
        assertTrue(T_WORKSHOP in rig.state.players[1].techUnlocked)
        assertFalse(T_WORKSHOP in rig.state.players[0].techUnlocked)
    }

    @Test
    fun techBuildingOnADebrisBeamNoLongerGrantsItsTech() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        rig.addDevice(WORKSHOP, rig.ground01, 0.15f)
        rig.run(1)
        assertTrue(T_WORKSHOP in rig.player().techUnlocked)
        rig.state.beams.flags[rig.ground01] = rig.state.beams.flags[rig.ground01] or BeamFlags.DEBRIS
        rig.run(1)
        assertFalse(T_WORKSHOP in rig.player().techUnlocked, "wie in der Wirtschaft: Trümmer liefern nichts")
    }
}
