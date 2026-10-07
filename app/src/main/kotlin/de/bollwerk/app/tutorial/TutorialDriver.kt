package de.bollwerk.app.tutorial

import de.bollwerk.app.match.MatchConfig
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.GameView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Ereignis der Sim-Seite für den Tutorial-Automaten (vom Sim-Thread gemeldet, auf dem UI-Thread ausgewertet). */
sealed interface TutorialSignal {
    /** Ergebnis eines Commands des Spielers. */
    data class Outcome(val outcome: CommandOutcome) : TutorialSignal

    /** Fx-Ereignis aus der Sim (hier nur: eigener Mörser hat gefeuert). */
    data class Fx(val event: FxEvent) : TutorialSignal
}

/**
 * Hält die KI zurück: solange die Sperre zu ist, liefert die Quelle keine Commands (die KI „ruht"). Das Öffnen ist von
 * jedem Thread erlaubt; die KI beginnt dann mit ihrer ersten Entscheidung ab dem nächsten Tick.
 */
class TutorialGate(private val inner: CommandSource) : CommandSource {
    @Volatile
    var open: Boolean = false

    override fun commandsFor(tick: Long, view: GameView): List<Command> = if (open) inner.commandsFor(tick, view) else emptyList()

    override fun isReady(tick: Long): Boolean = inner.isReady(tick)
}

/**
 * Sim-seitige Hälfte des Tutorials einer Partie (Sim-Thread): meldet Command-Ergebnisse des Spielers und den ersten
 * Mörserschuss als [signals], räumt zu Beginn die Start-Minen ab (damit Schritt 2 „Mine auf dem Erz bauen" möglich ist: die
 * Startfestung hat sie schon) und hält die KI-Gegner über [gates] zurück, bis [openGates] sie freigibt.
 *
 * Als [CommandSource] hinter der lokalen Eingabe angemeldet: `GameSession.tick` fragt die Quellen **vor** `ctx.beginTick`,
 * also stehen in `ctx.fx` noch die Effekte des vorigen Ticks, und jeder Tick wird genau einmal gesehen (auch wenn mehrere
 * Ticks je Frame laufen: ein Fx-Abgleich je Frame würde Schüsse in den mittleren Ticks verpassen).
 */
class TutorialDriver(
    private val runner: MatchRunner,
    val ids: TutorialIds,
    val targets: TutorialTargets,
    private val gates: List<TutorialGate>,
) : CommandSource {
    private val _signals = MutableSharedFlow<TutorialSignal>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ereignisse für den Automaten (`tryEmit` vom Sim-Thread). */
    val signals: SharedFlow<TutorialSignal> = _signals.asSharedFlow()

    init {
        runner.addResultListener { o -> if (o.playerId == ids.humanPlayer) _signals.tryEmit(TutorialSignal.Outcome(o)) }
    }

    /** Die KI-Gegner dürfen handeln (Tutorial fertig oder übersprungen). Von jedem Thread. */
    fun openGates() {
        for (g in gates) g.open = true
    }

    val gatesOpen: Boolean get() = gates.all { it.open }

    /** Räumt die Start-Minen des Spielers ab (Commands über die lokale Eingabe, wirken im ersten Tick). Vor dem Start der Sim aufrufen. */
    fun clearStartMines() {
        val devices = runner.state.deviceView
        for (i in 0 until devices.size) {
            if (devices.isAlive(i) && devices.owner(i) == ids.humanPlayer && devices.type(i) == ids.mineDevice) {
                runner.input.push(Command.DeleteDevice(0L, ids.humanPlayer, devices.ref(i)))
            }
        }
    }

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        val fx = runner.session.ctx.fx
        for (i in fx.indices) {
            val e = fx[i]
            if (e is FxEvent.Fired && e.weaponId == ids.mortarWeapon && ownerOfDevice(view, e.deviceUid) == ids.humanPlayer) {
                _signals.tryEmit(TutorialSignal.Fx(e))
            }
        }
        return emptyList()
    }

    private fun ownerOfDevice(view: GameView, uid: Int): Int {
        val d = view.deviceView
        for (i in 0 until d.size) if (d.isAlive(i) && d.uid(i) == uid) return d.owner(i)
        return -1
    }

    companion object {
        /**
         * Hängt das Tutorial an die frisch angelegte Partie [runner] (gegen KI, Mensch = `config.humanPlayerId`):
         * ermittelt Content-Zuordnung und Ziele, meldet den Treiber als Quelle an und räumt die Start-Minen ab.
         */
        fun attach(runner: MatchRunner, db: ContentDb, config: MatchConfig, gates: List<TutorialGate>): TutorialDriver {
            val human = config.humanPlayerId
            val mortarDevice = db.deviceIndex("mortar")
            val ids = TutorialIds(
                humanPlayer = human,
                mineDevice = db.deviceIndex("mine"),
                mortarWeapon = db.deviceWeapon[mortarDevice],
                mortarDevice = mortarDevice,
            )
            val targets = checkNotNull(TutorialTargets.find(runner.state, human, db.materialIndex(ids.woodId))) {
                "tutorial map '${config.map.id}' has no ore or no free beam pair for player $human"
            }
            val driver = TutorialDriver(runner, ids, targets, gates)
            runner.session.addSource(driver)
            driver.clearStartMines()
            return driver
        }
    }
}
