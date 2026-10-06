package de.bollwerk.engine.sim

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.view.FxEvent

/**
 * Kontext eines Ticks: die in diesem Tick fälligen Commands, deren Ergebnisse und die Ereignis-Senke für
 * Effekte. Wird pro Session einmal angelegt und je Tick wiederverwendet. Content liegt in `state.tables`.
 */
class StepContext {
    /** Tick, für den der Kontext gerade gilt (für [FxEvent.tick]). */
    var tick: Long = 0L
        private set

    /**
     * Vorgeprüfte Commands dieses Ticks in deterministischer Reihenfolge (Tick, Spieler, Eingang).
     * Das Command-System ([SystemSlot.COMMANDS], WP3) prüft **jedes** Command nacheinander mit demselben
     * `CommandValidator` gegen den sich ändernden Zustand und wendet es an oder lehnt es ab.
     */
    val commands: MutableList<Command> = ArrayList()

    /**
     * Ergebnis je Eintrag in [commands] (gleicher Index), gefüllt vom Command-System. Fehlt ein Eintrag
     * (kein Command-System installiert), gilt das Command als angenommen.
     */
    val results: MutableList<CommandResult> = ArrayList()

    /** Effekt-Ereignisse dieses Ticks (nur für Render/Audio, kein Sim-State). */
    val fx: MutableList<FxEvent> = ArrayList()

    /** Leert die Tick-Listen. */
    fun beginTick(tick: Long) {
        this.tick = tick
        commands.clear()
        results.clear()
        fx.clear()
    }
}

/**
 * Ein Simulationsschritt-Baustein (Commands anwenden, Wirtschaft, Physik, Projektile, Feuer, Schaden …).
 * Implementierungen dürfen nur über [GameState] und [StepContext] kommunizieren und müssen
 * deterministisch sein (siehe CLAUDE.md, Determinismus-Regeln). Pool-Schleifen: `val n = pool.size` vor der
 * Schleife merken und `pool.isNew(i)` überspringen (siehe [Pool]).
 */
fun interface SimSystem {
    fun step(state: GameState, ctx: StepContext)
}

/**
 * Feste Ausführungsreihenfolge der Systeme (Ordinal = Reihenfolge). Zuständige Arbeitspakete:
 * COMMANDS/ECONOMY/TECH/DOORS/RESULT/TURN/WIND = WP3, DEVICES/WEAPONS/PROJECTILES/FIRE/REPAIR = WP4,
 * PHYSICS/STRAIN_DAMAGE/TOPOLOGY/DEBRIS = WP2.
 */
enum class SystemSlot {
    /** Commands prüfen und anwenden (sequenziell, gegen den sich ändernden Zustand). */
    COMMANDS,
    /** Raten berechnen, Ressourcen gutschreiben (Kappung an den Lagergrenzen). */
    ECONOMY,
    /** Bauzeiten herunterzählen, Tech-Sets aus fertigen Gebäuden neu berechnen. */
    TECH,
    /** Gerätepositionen (`DeviceGeometry`), Nachladen. */
    DEVICES,
    /** Feuern, Salven, Hitscan, Laser, Rückstoß. */
    WEAPONS,
    /** Ballistik, Swept-Treffer, Explosionen. */
    PROJECTILES,
    /** Verlet + XPBD, Boden. */
    PHYSICS,
    /** Dehnung → Schaden → Bruch. */
    STRAIN_DAMAGE,
    FIRE,
    REPAIR,
    /** Tür-Timer (automatisches Schließen). */
    DOORS,
    /** Union-Find, Trümmer-Markierung, Adjazenz, Massen, Lösungsreihenfolge. */
    TOPOLOGY,
    /** Trümmer-Alter, Zerfall, Einschläge, Kill-Grenzen. */
    DEBRIS,
    /** Reaktor zerstört → Ergebnis. */
    RESULT,
    /** Zugwechsel/Phasen im Zugmodus. */
    TURN,
    /** Windänderungen. */
    WIND,
}

/**
 * Führt pro Tick die Systeme in [SystemSlot]-Reihenfolge aus (Array nach Ordinal, keine Hash-Iteration):
 * `state.beginTick()` → Systeme → `state.endTick()` → `tick++`.
 */
class SimStepper(systems: Map<SystemSlot, SimSystem> = emptyMap()) {
    private val bySlot: Array<SimSystem?> = arrayOfNulls(SystemSlot.entries.size)

    init {
        for (slot in SystemSlot.entries) bySlot[slot.ordinal] = systems[slot]
    }

    /** System in [slot] oder null. */
    operator fun get(slot: SystemSlot): SimSystem? = bySlot[slot.ordinal]

    /** Einen Tick simulieren. `ctx.commands` muss vorher befüllt sein. */
    fun step(state: GameState, ctx: StepContext) {
        state.beginTick()
        for (i in bySlot.indices) bySlot[i]?.step(state, ctx)
        state.endTick()
        state.tick++
    }

    companion object {
        /** Stepper ohne Systeme (WP0-Platzhalter). */
        val NONE: SimStepper = SimStepper(emptyMap())
    }
}
