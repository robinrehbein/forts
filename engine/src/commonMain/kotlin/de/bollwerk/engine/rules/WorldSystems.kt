package de.bollwerk.engine.rules

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext

/**
 * [de.bollwerk.engine.sim.SystemSlot.WIND]: alle `WindConfig.changeIntervalTicks` ändert sich der Wind um höchstens
 * `maxChange` m/s (Strom `RngStreams.WIND`) und bleibt in `MapSpec.windMin..windMax`.
 */
class WindSystem : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val cfg = state.config.wind
        val map = state.map
        if (cfg.changeIntervalTicks <= 0 || state.tick <= 0L || map.windMax <= map.windMin) return
        if (state.tick % cfg.changeIntervalTicks.toLong() != 0L) return
        val delta = state.rngWind.nextFloat(-cfg.maxChange, cfg.maxChange)
        state.wind = FloatMath.clamp(state.wind + delta, map.windMin, map.windMax)
    }
}
