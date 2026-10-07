package de.bollwerk.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Belagerung gegen Patt ([TargetSelector.siegeFactor]): erst ab Minute 5, dann linear bis 1. */
class SiegeTest {
    @Test
    fun siegeStartsLateAndRampsUp() {
        val start = TargetSelector.SIEGE_START_TICKS
        val ramp = TargetSelector.SIEGE_RAMP_TICKS.toLong()
        assertEquals(0f, TargetSelector.siegeFactor(0L))
        assertEquals(0f, TargetSelector.siegeFactor(start))
        assertTrue(start >= TargetSelector.LATE_GAME_START_TICKS, "siege must not start before the late game")
        val half = TargetSelector.siegeFactor(start + ramp / 2)
        assertTrue(half > 0.4f && half < 0.6f, "half ramp: $half")
        assertEquals(1f, TargetSelector.siegeFactor(start + ramp))
        assertEquals(1f, TargetSelector.siegeFactor(start + 10 * ramp))
        // monoton steigend
        var prev = 0f
        for (t in 0L..(start + 2 * ramp) step 60L) {
            val f = TargetSelector.siegeFactor(t)
            assertTrue(f >= prev)
            prev = f
        }
    }

    @Test
    fun siegeMakesDiggingTowardsTheReactorWorthMoreThanAFreeWeapon() {
        // grabender Schuss auf den Reaktor (Freiliegen DIGGING) mit vollem Belagerungs-Zuschlag schlägt eine frei
        // liegende Waffe in gleicher Entfernung; ohne Belagerung nicht
        val dist = 60f
        val dig = TargetSelector.score(TargetSelector.valueOf(de.bollwerk.engine.sim.DeviceRole.REACTOR) * TargetSelector.SIEGE_REACTOR_BONUS, AimController.DIGGING, dist)
        val digNoSiege = TargetSelector.score(TargetSelector.valueOf(de.bollwerk.engine.sim.DeviceRole.REACTOR), AimController.DIGGING, dist)
        val weapon = TargetSelector.score(TargetSelector.valueOf(de.bollwerk.engine.sim.DeviceRole.WEAPON), 1f, dist)
        assertTrue(dig > weapon, "siege dig $dig vs free weapon $weapon")
        assertTrue(digNoSiege < weapon)
    }
}
