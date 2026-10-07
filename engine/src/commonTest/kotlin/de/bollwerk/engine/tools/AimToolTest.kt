package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.rules.RuleTables.MG
import de.bollwerk.engine.rules.RuleTables.MORTAR
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.WeaponMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimToolTest {
    private val deg = FloatMath.DEG_TO_RAD

    private class Setup(val rig: ToolRig, val mortar: Int, val ctrl: ToolController) {
        val ctx get() = rig.ctx()
        val ref get() = rig.dref(mortar)
        /** Trefferzentrum des Mörsers (auf der Balkenoberseite). */
        val cx: Float get() = 24.5f
        val cy: Float get() = 33.29f
    }

    private fun setup(
        rig: ToolRig = ToolRig(), settings: ToolSettings = ToolSettings(), select: Boolean = true,
        aim: Float = 52f * FloatMath.DEG_TO_RAD, power: Float = 0.78f,
    ): Setup {
        val m = rig.addDevice(MORTAR, rig.ground01, 0.5f, true, 0, aim, power)
        val c = ToolController(settings)
        c.enterAimMode()
        val s = Setup(rig, m, c)
        if (select) {
            c.pointer(PointerPhase.DOWN, s.cx, s.cy, s.ctx)
            c.pointer(PointerPhase.UP, s.cx, s.cy, s.ctx)
        }
        return s
    }

    /** Zugvektor (dx, dy in Welt-Metern, y nach unten) von einem Startpunkt irgendwo im Gelände. */
    private fun Setup.dragBy(dx: Float, dy: Float, ctx: ToolContext = this.ctx): ToolResult =
        ctrl.drag(ctx, 10f, 20f, 10f + dx, 20f + dy)

    // ---- Auswahl ----

    @Test
    fun tapOnOwnWeaponSelectsIt() {
        val s = setup(select = false)
        val r = s.ctrl.pointer(PointerPhase.DOWN, s.cx + 0.2f, s.cy, s.ctx)
        assertTrue(r.consumed)
        assertEquals(AimToolState.WeaponSelected(s.ref), s.ctrl.aim.state)
        s.ctrl.pointer(PointerPhase.UP, s.cx, s.cy, s.ctx)
        assertEquals(s.ref, s.ctrl.overlay.selectedDeviceRef)
        assertEquals(ToolMode.AIM, s.ctrl.overlay.mode)
        assertNotNull(s.ctrl.overlay.trajectory)
        assertNotNull(s.ctrl.overlay.aim)
    }

    @Test
    fun enemyWeaponsAreNotSelectable() {
        val rig = ToolRig()
        val a = rig.addAnchor(95f, 34f, 1)
        val b = rig.addAnchor(98f, 34f, 1)
        val beam = rig.addBeam(a, b, owner = 1)
        rig.addDevice(MORTAR, beam, 0.5f, true, 1, 128f * deg, 0.78f)
        val s = setup(rig = rig, select = false)
        val r = s.ctrl.pointer(PointerPhase.DOWN, 96.5f, 33.29f, s.ctx)
        assertFalse(r.consumed)
        assertEquals(AimToolState.Idle, s.ctrl.aim.state)
    }

    @Test
    fun downWithoutWeaponAndWithoutSelectionLetsTheCameraPan() {
        val s = setup(select = false)
        assertFalse(s.ctrl.pointer(PointerPhase.DOWN, 40f, 10f, s.ctx).consumed)
        assertFalse(s.ctrl.pointer(PointerPhase.MOVE, 45f, 10f, s.ctx).consumed)
        val up = s.ctrl.pointer(PointerPhase.UP, 45f, 10f, s.ctx)
        assertFalse(up.consumed)
        assertTrue(up.commands.isEmpty())
    }

    @Test
    fun selectingAnotherWeaponSwitchesAndDoesNotAim() {
        val rig = ToolRig()
        val mg = rig.addDevice(MG, rig.addBeam(rig.addAnchor(28f, 34f), rig.addAnchor(31f, 34f)), 0.5f, true)
        val s = setup(rig = rig)
        assertEquals(s.ref, s.ctrl.aim.selectedRef)
        // Tipp auf den MG (28..31, Mitte 29,5)
        val r = s.ctrl.pointer(PointerPhase.DOWN, 29.5f, 33.29f, s.ctx)
        assertTrue(r.consumed)
        s.ctrl.pointer(PointerPhase.MOVE, 33f, 30f, s.ctx)
        val up = s.ctrl.pointer(PointerPhase.UP, 33f, 30f, s.ctx)
        assertTrue(up.commands.isEmpty(), "Auswahl-Geste zielt nicht")
        assertEquals(rig.dref(mg), s.ctrl.aim.selectedRef)
    }

    // ---- Zug → Winkel und Kraft ----

    @Test
    fun dragDirectionIsTheShotDirectionAndLengthIsPower() {
        val s = setup()
        // 5 m nach rechts und 5 m nach oben (Welt-y wächst nach unten)
        val up = s.dragBy(5f, -5f)
        val cmd = assertIs<Command.SetAim>(up.command)
        assertEquals(s.ref, cmd.deviceRef)
        assertEquals(45f * deg, cmd.angle, 2e-3f)
        val len = kotlin.math.sqrt(50f)
        val dead = 1f * ToolConst.AIM_DEAD_ZONE_FACTOR
        val expected = 0.3f + 0.7f * (len - dead) / (1f * ToolConst.AIM_FULL_POWER_FACTOR)
        assertEquals(expected, cmd.power, 1e-4f)
        assertTrue(up.consumed)
    }

    @Test
    fun dragDirections() {
        val s = setup()
        fun angleOf(dx: Float, dy: Float): Float = assertIs<Command.SetAim>(s.dragBy(dx, dy).command).angle
        assertEquals(0f, angleOf(4f, 0f), 2e-3f)
        assertEquals(30f * deg, angleOf(4f * cos(30f * deg), -4f * sin(30f * deg)), 3e-3f)
        assertEquals(70f * deg, angleOf(4f * cos(70f * deg), -4f * sin(70f * deg)), 3e-3f)
        // Zug nach links (weg von der Feindseite) wird geklemmt statt gespiegelt
        val away = angleOf(-4f, -4f)
        assertEquals(85f * deg, away, 1e-4f)
    }

    @Test
    fun angleIsClampedToTheWeaponLimits() {
        val s = setup()
        // Mörser: -10°..85°
        val steep = assertIs<Command.SetAim>(s.dragBy(0f, -5f).command)
        assertEquals(85f * deg, steep.angle, 1e-4f)
        val flat = assertIs<Command.SetAim>(s.dragBy(5f, 5f).command)
        assertEquals(-10f * deg, flat.angle, 1e-4f)
        // die Vorschau nutzt den geklemmten Winkel
        assertEquals(-10f * deg, s.ctrl.overlay.aim!!.angle, 1e-4f)
    }

    @Test
    fun powerIsBetweenThirtyAndHundredPercent() {
        val s = setup()
        val tiny = assertIs<Command.SetAim>(s.dragBy(0.6f, -0.6f).command) // 0,85 m: knapp über der Totzone
        assertTrue(tiny.power >= 0.3f && tiny.power < 0.4f, "power=${tiny.power}")
        val huge = assertIs<Command.SetAim>(s.dragBy(30f, -30f).command)
        assertEquals(1f, huge.power, 1e-6f)
    }

    @Test
    fun deadZoneMeansNoAim() {
        val s = setup()
        val dead = s.dragBy(0.3f, -0.1f)
        assertNull(dead.command)
        assertIs<AimToolState.WeaponSelected>(s.ctrl.aim.state)
        assertEquals(52f * deg, s.ctrl.overlay.aim!!.angle, 1e-4f)
    }

    @Test
    fun powerScalesWithZoomThroughThePickRadius() {
        val s = setup()
        // bei doppeltem Fang-Radius (Hereinzoomen halbiert Meter je dp... hier: größerer Radius = herausgezoomt) braucht der Zug doppelt so viele Meter
        val near = assertIs<Command.SetAim>(s.ctrl.drag(s.rig.ctx(pick = 1f), 10f, 20f, 13f, 17f).command)
        val far = assertIs<Command.SetAim>(s.ctrl.drag(s.rig.ctx(pick = 2f), 10f, 20f, 16f, 14f).command)
        assertEquals(near.power, far.power, 1e-4f)
    }

    @Test
    fun dragCanStartAnywhereAndSetAimIsAcceptedBySim() {
        val s = setup()
        val cmd = assertIs<Command.SetAim>(s.dragBy(4f, -3f).command)
        s.rig.ok(cmd)
        assertEquals(cmd.angle, s.rig.state.devices.aimAngle[s.mortar], 1e-5f)
        assertEquals(cmd.power, s.rig.state.devices.power[s.mortar], 1e-5f)
    }

    @Test
    fun aTapOnTheSelectedWeaponWithoutDraggingSendsNothing() {
        val s = setup()
        val r = s.ctrl.drag(s.ctx, s.cx, s.cy, s.cx + 0.1f, s.cy)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun cancelDuringTheDragSendsNoAimAndKeepsTheSelection() {
        val s = setup()
        s.ctrl.pointer(PointerPhase.DOWN, 10f, 20f, s.ctx)
        s.ctrl.pointer(PointerPhase.MOVE, 14f, 17f, s.ctx)
        assertIs<AimToolState.Aiming>(s.ctrl.aim.state)
        s.ctrl.pointer(PointerPhase.CANCEL, 14f, 17f, s.ctx)
        assertEquals(AimToolState.WeaponSelected(s.ref), s.ctrl.aim.state)
        val up = s.ctrl.pointer(PointerPhase.UP, 14f, 17f, s.ctx)
        assertTrue(up.commands.isEmpty())
    }

    // ---- Spieler 2 (gespiegelte Seite) ----

    @Test
    fun playerTwoAimsMirrored() {
        val rig = ToolRig()
        assertEquals(-1, rig.state.players[1].facing)
        val a = rig.addAnchor(95f, 34f, 1)
        val b = rig.addAnchor(98f, 34f, 1)
        val beam = rig.addBeam(a, b, owner = 1)
        val m = rig.addDevice(MORTAR, beam, 0.5f, true, 1, 128f * deg, 0.78f)
        val ctrl = ToolController()
        ctrl.enterAimMode()
        val ctx = rig.ctx(player = 1)
        ctrl.pointer(PointerPhase.DOWN, 96.5f, 33.29f, ctx)
        ctrl.pointer(PointerPhase.UP, 96.5f, 33.29f, ctx)
        assertEquals(rig.dref(m), ctrl.aim.selectedRef)

        // Zug nach links oben (45°) = Schuss nach links oben: Winkel 135°
        val left = assertIs<Command.SetAim>(ctrl.drag(ctx, 50f, 20f, 46f, 16f).command)
        assertEquals(1, left.playerId)
        assertEquals(135f * deg, left.angle, 3e-3f)
        assertEquals(45f, ctrl.overlay.aim!!.elevationDeg, 0.2f)
        rig.ok(left)
        assertEquals(left.angle, rig.state.devices.aimAngle[m], 1e-4f)

        // Zug flach nach links: Elevation 0 -> Winkel 180° (π)
        val flat = assertIs<Command.SetAim>(ctrl.drag(ctx, 50f, 20f, 44f, 20f).command)
        assertEquals(FloatMath.PI, flat.angle, 3e-3f)

        // Zug zur eigenen Seite (rechts oben) wird auf die Elevationsgrenze (85°) geklemmt: π − 85°
        val away = assertIs<Command.SetAim>(ctrl.drag(ctx, 50f, 20f, 54f, 16f).command)
        assertEquals(FloatMath.PI - 85f * deg, away.angle, 1e-3f)

        // Zug steil nach unten links: Elevation unter −10° wird geklemmt: π + 10°
        val down = assertIs<Command.SetAim>(ctrl.drag(ctx, 50f, 20f, 46f, 24f).command)
        assertEquals(FloatMath.PI + 10f * deg, down.angle, 1e-3f)
        rig.ok(down)
    }

    // ---- Flugbahn ----

    private fun predictLike(rig: ToolRig, deviceSlot: Int): Trajectory {
        val s = rig.state
        val geo = FloatArray(DeviceGeometry.SIZE)
        DeviceGeometry.mount(s, deviceSlot, geo)
        val weapon = s.tables.weapons[s.tables.devices[s.devices.typeOf[deviceSlot]].weapon]
        val buf = FloatArray(1200)
        val n = Ballistics.predict(
            geo[DeviceGeometry.MUZZLE_X], geo[DeviceGeometry.MUZZLE_Y], s.devices.aimAngle[deviceSlot], s.devices.power[deviceSlot],
            weapon.muzzleSpeed, s.wind, s.config, buf, 600, s.terrain, s.map, gravityScale = weapon.gravityScale,
        )
        return Trajectory(buf.copyOf(n * 2), n)
    }

    @Test
    fun trajectoryEqualsBallisticsPredictWithWind() {
        val s = setup()
        val cmd = assertIs<Command.SetAim>(s.dragBy(4f, -3f).command)
        s.rig.ok(cmd)
        s.rig.state.wind = 3.2f
        s.ctrl.refresh(s.ctx)
        val expected = predictLike(s.rig, s.mortar)
        assertTrue(expected.count > 10)
        assertEquals(expected, s.ctrl.overlay.trajectory)
        assertEquals(expected.x(expected.count - 1), s.ctrl.overlay.impactX)
        assertEquals(expected.y(expected.count - 1), s.ctrl.overlay.impactY)
        // anderer Wind -> andere Bahn
        s.rig.state.wind = -3.2f
        s.ctrl.refresh(s.ctx)
        assertNotEquals(expected, s.ctrl.overlay.trajectory)
        assertEquals(predictLike(s.rig, s.mortar), s.ctrl.overlay.trajectory)
    }

    @Test
    fun trajectoryDuringTheDragMatchesThePredictForTheDraggedAim() {
        val s = setup()
        s.rig.state.wind = 2f
        s.ctrl.pointer(PointerPhase.DOWN, 10f, 20f, s.ctx)
        s.ctrl.pointer(PointerPhase.MOVE, 14f, 17f, s.ctx)
        val st = assertIs<AimToolState.Aiming>(s.ctrl.aim.state)
        val geo = FloatArray(DeviceGeometry.SIZE)
        // Mündung beim gezogenen Winkel (Gerät steht noch auf dem alten Winkel)
        de.bollwerk.engine.rules.RuleChecks.mountOn(
            s.rig.state, s.rig.state.devices.beamId[s.mortar], 0.5f, true, RuleTables.tables.devices[MORTAR], st.angle, geo,
        )
        val w = RuleTables.tables.weapons[RuleTables.W_MORTAR]
        val buf = FloatArray(1200)
        val n = Ballistics.predict(
            geo[DeviceGeometry.MUZZLE_X], geo[DeviceGeometry.MUZZLE_Y], st.angle, st.power, w, 2f, s.rig.state.config, buf, 600,
            s.rig.state.terrain, s.rig.state.map,
        )
        assertEquals(Trajectory(buf.copyOf(n * 2), n), st.trajectory)
        assertEquals(st.trajectory, s.ctrl.overlay.trajectory)
    }

    @Test
    fun trajectoryUsesTheWeaponsGravityScale() {
        val base = RuleTables.tables
        val light = base.copy(weapons = base.weapons.mapIndexed { i, w -> if (i == RuleTables.W_MORTAR) w.copy(gravityScale = 0.6f) else w })
        val normal = setup(ToolRig(tables = base))
        val scaled = setup(ToolRig(tables = light))
        normal.rig.state.wind = 0f
        scaled.rig.state.wind = 0f
        normal.ctrl.refresh(normal.ctx)
        scaled.ctrl.refresh(scaled.ctx)
        val tn = normal.ctrl.overlay.trajectory!!
        val ts = scaled.ctrl.overlay.trajectory!!
        assertNotEquals(tn, ts)
        assertTrue(ts.count > tn.count, "weniger Schwerkraft -> längere Flugzeit")
        assertEquals(predictLike(scaled.rig, scaled.mortar), ts)
        assertEquals(predictLike(normal.rig, normal.mortar), tn)
        assertTrue(scaled.ctrl.overlay.aim!!.apexHeightM > normal.ctrl.overlay.aim!!.apexHeightM)
    }

    @Test
    fun aimInfoDescribesAngleSplashApexAndWindDrift() {
        val s = setup()
        s.rig.state.wind = 0f
        s.ctrl.refresh(s.ctx)
        val calm = s.ctrl.overlay.aim!!
        assertEquals(52f, calm.elevationDeg, 0.2f)
        assertEquals(78f, calm.powerPercent, 0.01f)
        assertEquals(2.5f, calm.splashRadiusM)
        assertEquals(0f, calm.windDriftM)
        assertTrue(calm.hasImpact)
        // v = 33 · 0,78 = 25,7 m/s, vy = v sin 52° -> Scheitel ≈ vy² / 2g ≈ 21 m über der Mündung
        assertEquals(21f, calm.apexHeightM, 1.5f)
        assertEquals(s.ctrl.overlay.impactX, s.ctrl.overlay.trajectory!!.x(s.ctrl.overlay.trajectory!!.count - 1))

        s.rig.state.wind = 4f
        s.ctrl.refresh(s.ctx)
        val windy = s.ctrl.overlay.aim!!
        val calmEnd = calm.let { _ -> predictEndX(s, 0f) }
        assertEquals(s.ctrl.overlay.impactX - calmEnd, windy.windDriftM, 1e-3f)
        assertTrue(windy.windDriftM > 0f, "Rückenwind trägt weiter")
        s.rig.state.wind = -4f
        s.ctrl.refresh(s.ctx)
        assertTrue(s.ctrl.overlay.aim!!.windDriftM < 0f)
    }

    private fun predictEndX(s: Setup, wind: Float): Float {
        val st = s.rig.state
        val geo = FloatArray(DeviceGeometry.SIZE)
        DeviceGeometry.mount(st, s.mortar, geo)
        val buf = FloatArray(1200)
        val n = Ballistics.predict(
            geo[DeviceGeometry.MUZZLE_X], geo[DeviceGeometry.MUZZLE_Y], st.devices.aimAngle[s.mortar], st.devices.power[s.mortar],
            st.tables.weapons[RuleTables.W_MORTAR], wind, st.config, buf, 600, st.terrain, st.map,
        )
        return buf[(n - 1) * 2]
    }

    @Test
    fun windDriftIsMeasuredInShotDirectionForPlayerTwo() {
        val rig = ToolRig()
        val a = rig.addAnchor(95f, 34f, 1)
        val b = rig.addAnchor(98f, 34f, 1)
        val beam = rig.addBeam(a, b, owner = 1)
        rig.addDevice(MORTAR, beam, 0.5f, true, 1, 128f * deg, 0.78f)
        val ctrl = ToolController()
        ctrl.enterAimMode()
        val ctx = rig.ctx(player = 1)
        ctrl.pointer(PointerPhase.DOWN, 96.5f, 33.29f, ctx)
        ctrl.pointer(PointerPhase.UP, 96.5f, 33.29f, ctx)
        rig.state.wind = 4f // nach rechts = Gegenwind für einen Schuss nach links
        ctrl.refresh(ctx)
        assertTrue(ctrl.overlay.aim!!.windDriftM < 0f)
        rig.state.wind = -4f
        ctrl.refresh(ctx)
        assertTrue(ctrl.overlay.aim!!.windDriftM > 0f)
    }

    @Test
    fun hitscanWeaponsPreviewAStraightLineOverTheirRange() {
        val base = RuleTables.tables
        val hit = base.copy(weapons = base.weapons.mapIndexed { i, w -> if (i == RuleTables.W_MORTAR) w.copy(mode = WeaponMode.HITSCAN, maxRange = 60f) else w })
        val s = setup(ToolRig(tables = hit), aim = 0f)
        val t = s.ctrl.overlay.trajectory!!
        assertEquals(2, t.count)
        assertEquals(60f, abs(t.x(1) - t.x(0)), 1e-2f)
        assertEquals(t.y(0), t.y(1), 1e-3f)
        assertEquals(0f, s.ctrl.overlay.aim!!.apexHeightM)
        assertFalse(s.ctrl.overlay.aim!!.hasImpact)
    }

    // ---- Feuern ----

    @Test
    fun releaseToFireSendsSetAimThenFire() {
        val s = setup(settings = ToolSettings().also { it.releaseToFire = true })
        val r = s.dragBy(4f, -3f)
        assertEquals(2, r.commands.size)
        assertIs<Command.SetAim>(r.commands[0])
        val fire = assertIs<Command.Fire>(r.commands[1])
        assertEquals(s.ref, fire.deviceRef)
        s.rig.send(r.commands[0], r.commands[1]).forEach { assertEquals(de.bollwerk.engine.command.CommandResult.Accepted, it) }
        assertTrue((s.rig.state.devices.flags[s.mortar] and de.bollwerk.engine.sim.DeviceFlags.FIRE_REQUESTED) != 0)
    }

    @Test
    fun releaseToFireIsOffByDefault() {
        val s = setup()
        assertEquals(1, s.dragBy(4f, -3f).commands.size)
    }

    @Test
    fun releaseToFireWhileReloadingReportsTheReason() {
        val s = setup(settings = ToolSettings().also { it.releaseToFire = true })
        s.rig.state.devices.reloadTicksOf[s.mortar] = 100
        val r = s.dragBy(4f, -3f)
        assertEquals(1, r.commands.size)
        assertIs<Command.SetAim>(r.commands[0])
        assertEquals(RejectReason.RELOADING, r.rejected)
    }

    @Test
    fun fireButtonFiresTheSelectedWeaponOrExplainsWhyNot() {
        val s = setup()
        val ok = s.ctrl.fire(s.ctx)
        assertIs<Command.Fire>(ok.command)
        s.rig.ok(ok.command)
        s.rig.step()
        val again = s.ctrl.fire(s.ctx)
        assertNull(again.command)
        assertEquals(RejectReason.RELOADING, again.rejected)
    }

    @Test
    fun fireWithoutSelectionIsRejected() {
        val s = setup(select = false)
        val r = s.ctrl.fire(s.ctx)
        assertNull(r.command)
        assertEquals(RejectReason.INVALID_TARGET, r.rejected)
    }

    @Test
    fun fireNeedsEnergy() {
        val s = setup(ToolRig(energy = 5f).also { })
        val r = s.ctrl.fire(s.ctx)
        assertEquals(RejectReason.NOT_ENOUGH_ENERGY, r.rejected)
    }

    // ---- Waffen wechseln ----

    @Test
    fun cycleWalksThroughOwnWeaponsInSlotOrderAndWraps() {
        val rig = ToolRig()
        val m = rig.addDevice(MORTAR, rig.ground01, 0.5f, true)
        val a = rig.addAnchor(28f, 34f)
        val b = rig.addAnchor(31f, 34f)
        val beam = rig.addBeam(a, b)
        val g = rig.addDevice(MG, beam, 0.5f, true)
        // Waffe des Gegners zählt nicht
        val ea = rig.addAnchor(95f, 34f, 1)
        val eb = rig.addAnchor(98f, 34f, 1)
        rig.addDevice(MORTAR, rig.addBeam(ea, eb, owner = 1), 0.5f, true, 1)
        val ctrl = ToolController()
        ctrl.enterAimMode()
        val ctx = rig.ctx()
        assertEquals(rig.dref(m), ctrl.cycleWeapon(ctx))
        assertEquals(rig.dref(g), ctrl.cycleWeapon(ctx))
        assertEquals(rig.dref(m), ctrl.cycleWeapon(ctx))
        assertEquals(rig.dref(g), ctrl.cycleWeapon(ctx, -1))
        assertEquals(rig.dref(m), ctrl.cycleWeapon(ctx, -1))
        assertEquals(rig.dref(g), ctrl.cycleWeapon(ctx, -1))
        assertEquals(rig.dref(g), ctrl.overlay.selectedDeviceRef)
    }

    @Test
    fun cycleWithoutWeaponsSelectsNothing() {
        val rig = ToolRig()
        val ctrl = ToolController()
        ctrl.enterAimMode()
        assertEquals(-1L, ctrl.cycleWeapon(rig.ctx()))
        assertEquals(AimToolState.Idle, ctrl.aim.state)
    }

    @Test
    fun destroyedWeaponClearsTheSelectionOnRefresh() {
        val s = setup()
        s.rig.state.devices.release(s.mortar)
        s.rig.step(); s.rig.step(); s.rig.step()
        s.ctrl.refresh(s.ctx)
        assertEquals(AimToolState.Idle, s.ctrl.aim.state)
        assertNull(s.ctrl.overlay.trajectory)
        assertEquals(-1L, s.ctrl.overlay.selectedDeviceRef)
    }

    // ---- Vorschau bleibt bis die Sim den Winkel übernommen hat ----

    @Test
    fun previewStaysOnTheDraggedAimUntilTheSimAppliedIt() {
        val s = setup()
        val cmd = assertIs<Command.SetAim>(s.dragBy(4f, -3f).command)
        val held = s.ctrl.overlay.trajectory
        assertIs<AimToolState.Aiming>(s.ctrl.aim.state)
        s.ctrl.refresh(s.ctx) // Sim hat noch nichts übernommen
        assertIs<AimToolState.Aiming>(s.ctrl.aim.state)
        assertEquals(held, s.ctrl.overlay.trajectory)
        s.rig.ok(cmd)
        s.ctrl.refresh(s.ctx)
        assertIs<AimToolState.WeaponSelected>(s.ctrl.aim.state)
        assertEquals(cmd.angle, s.ctrl.overlay.aim!!.angle, 1e-4f)
    }

    @Test
    fun rejectedSetAimFallsBackToTheRealAimAfterTheSettleTime() {
        val s = setup()
        s.dragBy(4f, -3f)
        assertIs<AimToolState.Aiming>(s.ctrl.aim.state)
        s.rig.run(ToolConst.AIM_SETTLE_TICKS + 1) // Command wurde nie gesendet
        s.ctrl.refresh(s.ctx)
        assertIs<AimToolState.WeaponSelected>(s.ctrl.aim.state)
        assertEquals(52f * deg, s.ctrl.overlay.aim!!.angle, 1e-4f)
    }

    // ---- Live-Zielen ----

    @Test
    fun liveAimSendsRateLimitedSetAimWhileDragging() {
        val s = setup(settings = ToolSettings().also { it.liveAimIntervalTicks = 5 })
        s.ctrl.pointer(PointerPhase.DOWN, 10f, 20f, s.ctx)
        val first = s.ctrl.pointer(PointerPhase.MOVE, 14f, 17f, s.ctx)
        assertEquals(1, first.commands.size)
        assertIs<Command.SetAim>(first.commands[0])
        val same = s.ctrl.pointer(PointerPhase.MOVE, 14.5f, 16.5f, s.ctx)
        assertTrue(same.commands.isEmpty(), "gleicher Tick: kein zweites SetAim")
        s.rig.run(6)
        val later = s.ctrl.pointer(PointerPhase.MOVE, 15f, 16f, s.ctx)
        assertEquals(1, later.commands.size)
        val up = s.ctrl.pointer(PointerPhase.UP, 15f, 16f, s.ctx)
        assertEquals(1, up.commands.size, "Loslassen sendet den Endwert")
    }

    @Test
    fun noLiveAimByDefault() {
        val s = setup()
        s.ctrl.pointer(PointerPhase.DOWN, 10f, 20f, s.ctx)
        assertTrue(s.ctrl.pointer(PointerPhase.MOVE, 14f, 17f, s.ctx).commands.isEmpty())
    }

    // ---- Direkt am Werkzeug ----

    @Test
    fun toolContractWorksStandalone() {
        val rig = ToolRig()
        val m = rig.addDevice(MORTAR, rig.ground01, 0.5f, true, 0, 52f * deg, 0.78f)
        val tool = DefaultAimTool()
        val ctx = rig.ctx()
        tool.onDown(24.5f, 33.29f, ctx)
        assertEquals(AimToolState.WeaponSelected(rig.dref(m)), tool.state)
        tool.onUp(24.5f, 33.29f, ctx)
        tool.onDown(10f, 20f, ctx)
        tool.onMove(13f, 16f, ctx)
        val aiming = assertIs<AimToolState.Aiming>(tool.state)
        assertTrue(aiming.trajectory.count > 0)
        assertEquals(aiming.trajectory, tool.preview!!.trajectory)
        val cmd = assertIs<Command.SetAim>(tool.onUp(13f, 16f, ctx))
        assertEquals(aiming.angle, cmd.angle)
        assertEquals(aiming.power, cmd.power)
        assertNotNull(cmd)
    }

    // ---- Einschlag nur am Gelände ----

    @Test
    fun aTrajectoryLeavingTheMapHasNoImpactCrosshair() {
        val m0 = RuleTables.map()
        val map = MapSpec(
            m0.id, m0.width, m0.height, m0.terrain, m0.buildZones, m0.ores, m0.foundations, m0.startForts, m0.baseY,
            m0.windMin, m0.windMax, m0.killMinX, 50f, m0.killMinY, m0.killMaxY,
        )
        val s = setup(ToolRig(map = map), aim = 40f * deg, power = 1f)
        val info = s.ctrl.overlay.aim!!
        assertFalse(info.hasImpact, "kein Fadenkreuz in der Luft am Kartenrand")
        assertTrue(info.exitedMap)
        assertTrue(s.ctrl.overlay.impactX.isNaN())
        assertTrue(s.ctrl.overlay.trajectory!!.count > 2)
    }

    @Test
    fun aTrajectoryHittingTheGroundHasAnImpactAndDidNotExitTheMap() {
        val s = setup()
        val info = s.ctrl.overlay.aim!!
        assertTrue(info.hasImpact)
        assertFalse(info.exitedMap)
        assertFalse(s.ctrl.overlay.impactX.isNaN())
    }

    @Test
    fun tinyMuzzleJitterDoesNotRecomputeThePreview() {
        val s = setup()
        s.rig.state.wind = 0f
        s.ctrl.refresh(s.ctx)
        val before = s.ctrl.aim.preview
        val overlay = s.ctrl.overlay
        // Struktur zittert unter 1 mm: dieselbe Vorschau (und dasselbe Overlay), keine neue Bahn
        s.rig.state.nodes.x[s.rig.n23] += 0.0003f
        s.ctrl.refresh(s.ctx)
        assertSame(before, s.ctrl.aim.preview)
        assertSame(overlay, s.ctrl.overlay)
        // Bewegung über 1 mm rechnet neu
        s.rig.state.nodes.x[s.rig.n23] += 0.02f
        s.ctrl.refresh(s.ctx)
        assertNotSame(before, s.ctrl.aim.preview)
    }
}
