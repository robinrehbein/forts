package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.SystemSlot

/**
 * Registrierung des Physik-Kerns (WP2). Welle 0 kennt nur `SimStepper(Map<SystemSlot, SimSystem>)`; deshalb
 * liefert [all] die vier Physik-Systeme als Map, die der Aufrufer (`MatchBootstrap`/Simrunner/Tests) mit den
 * Systemen der anderen Arbeitspakete zusammenführt:
 *
 * ```
 * val stepper = SimStepper(PhysicsSystems.all() + rulesSystems)   // rulesSystems: WP3, WP4 …
 * ```
 * (`SimStepper` ordnet nach `SystemSlot.ordinal`, die Map-Reihenfolge spielt keine Rolle.)
 *
 * Jeder Aufruf erzeugt einen eigenen [PhysicsWorld]-Cache, den die vier Systeme teilen. Ein Stepper gehört zu
 * **einem** `GameState` (siehe [PhysicsWorld]); für einen zweiten Zustand (KI-Vorausschau, Batch) `all()` erneut
 * aufrufen.
 *
 * **Offene Übergaben (nicht Teil von WP2, Dateien anderer Arbeitspakete):**
 * - Produktions-Verdrahtung: `MatchBootstrap`/`GameSession` (WP5) und der Simrunner (WP12) benutzen noch
 *   `SimStepper.NONE`; sie müssen `PhysicsSystems.all()` in ihren Stepper aufnehmen und vor Tick 0 [settle] aufrufen.
 * - RENDER-Feld `BeamPool.creaking` erreicht den Render-Thread erst, wenn `FrameSnapshot`/`SnapshotBuilder` (WP5)
 *   es kopieren (z. B. `beamCreaking: BooleanArray`); bis dahin liefert `beamLoad01 > SimConfig.creakRatio`
 *   dasselbe Signal.
 * - Topologie-Commands (Bauen, Abriss, Gerät platzieren, WP3) müssen `state.topologyDirty` setzen; Abriss über
 *   `BeamBreaker.breakBeam(…, BreakCause.DELETED)` (still, ohne Split und ohne `DeviceDestroyed`).
 */
object PhysicsSystems {
    /** Die vier WP2-Systeme mit gemeinsamem Cache. */
    fun all(world: PhysicsWorld = PhysicsWorld()): Map<SystemSlot, SimSystem> = linkedMapOf(
        SystemSlot.PHYSICS to PhysicsSystem(world),
        SystemSlot.STRAIN_DAMAGE to StrainDamageSystem(),
        SystemSlot.TOPOLOGY to TopologySystem(world),
        SystemSlot.DEBRIS to DebrisSystem(),
    )

    /** Stepper nur mit dem Physik-Kern (Tests, Simrunner-Physikszenarien). */
    fun stepper(world: PhysicsWorld = PhysicsWorld()): SimStepper = SimStepper(all(world))

    /**
     * Einschwingen ohne Schaden (Prototyp `buildScene`: 420 Ticks mit `state.warm`, danach Geschwindigkeiten 0).
     * Ohne Dehnungsschritt, die TP bleiben unverändert. Für den Partie-Aufbau vor Tick 0; verändert `state.tick`
     * nicht und zieht keinen Zufall.
     * Fx-Ereignisse (z. B. Geräte, die beim Aufbau auf Trümmern sterben) werden verworfen.
     */
    fun settle(state: GameState, ticks: Int = 420) {
        val world = PhysicsWorld()
        val ctx = StepContext()
        world.ensure(state, ctx)
        for (k in 0 until ticks) {
            world.ensure(state, ctx)
            PhysicsSystem.simulate(state, world)
        }
        val n = state.nodes
        for (i in 0 until n.size) {
            if (!n.isAlive(i)) continue
            n.px[i] = n.x[i]; n.py[i] = n.y[i]
            n.tickX[i] = n.x[i]; n.tickY[i] = n.y[i]
        }
        val b = state.beams
        for (j in 0 until b.size) {
            if (!b.isAlive(j)) continue
            b.lambda[j] = 0f
        }
    }
}
