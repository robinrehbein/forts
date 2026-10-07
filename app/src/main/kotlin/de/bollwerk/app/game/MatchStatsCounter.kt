package de.bollwerk.app.game

import de.bollwerk.app.match.MatchStats
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.GameView
import de.bollwerk.engine.view.HitTarget
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Kennzahlen für den Gefechtsbericht (Mockup 7) je Spieler. Reine Darstellung, nie Teil des Sim-States. Gezählt wird
 * **ausschließlich auf dem Sim-Thread** (unabhängig davon, ob die Spielfläche zeichnet); gelesen ([statsFor]) von jedem Thread.
 *
 * - **Schüsse** = angenommene `Fire`, **Balken gebaut** = angenommene `PlaceBeam` ([onOutcome], Command-Ergebnisse).
 * - **Balken verloren** ([sync], je Tick): eigene **ursprüngliche** Balken (keine Bruch-Hälfte), die durch Überlast,
 *   Treffer oder Feuer brechen oder als Trümmer vom Bauwerk abfallen; jeder Balken zählt höchstens einmal. Bruch-Hälften
 *   (`BeamFlags.HALF`) zählen nie (sie sind Teile eines bereits gezählten Balkens), Abriss/Zurück (`DELETED`) und
 *   Trümmer-Zerfall (`DECAY`) ebenfalls nicht.
 * - **Treffer** ([sync]): Einschläge auf gegnerische Balken/Geräte; eine Explosion mit vielen Splash-Treffern zählt einmal,
 *   eine MG-Salve einmal je [BURST_TICKS].
 *
 * Besitzer und Art eines Balkens kommen aus einer eigenen Tabelle uid → Zustand, die [sync] nach jedem Tick aus dem
 * Zustand nachführt. Sie überlebt das Freigeben und Wiederbelegen von Pool-Slots (der Snapshot kennt einen gebrochenen
 * Balken nicht mehr; `Pool.release` löscht auch dessen Flags).
 */
class MatchStatsCounter(val playerCount: Int = 2) {
    private val shots = AtomicIntegerArray(playerCount)
    private val hits = AtomicIntegerArray(playerCount)
    private val built = AtomicIntegerArray(playerCount)
    private val lost = AtomicIntegerArray(playerCount)

    // ---- nur Sim-Thread ----
    /** uid → `owner + 1` (Bits 0..7) | [ORIGINAL] | [DONE]; 0 = unbekannt. */
    private var beamInfo = IntArray(256)
    /** uid → `owner + 1`; 0 = unbekannt. */
    private var deviceInfo = IntArray(64)
    private var lastSyncTick = Long.MIN_VALUE
    private var lastHitTick = Long.MIN_VALUE
    private var lastHitX = Float.NaN
    private var lastHitY = Float.NaN
    private val lastBurstHit = LongArray(playerCount) { Long.MIN_VALUE / 2 }

    /** Sim-Thread. */
    fun onOutcome(o: CommandOutcome) {
        if (!o.accepted) return
        val p = o.playerId
        if (p !in 0 until playerCount) return
        when (o.command) {
            is Command.Fire -> shots.incrementAndGet(p)
            is Command.PlaceBeam -> built.incrementAndGet(p)
            else -> Unit
        }
    }

    /**
     * Sim-Thread, nach jedem Tick (bzw. vor dem nächsten): wertet die Fx des zuletzt gelaufenen Ticks ([fx] =
     * `StepContext.fx`, gilt bis zum nächsten `beginTick`) gegen die Tabelle vom Stand **vor** diesem Tick aus und führt
     * die Tabelle danach auf den aktuellen Stand [view] nach. Mehrfachaufrufe ohne neuen Tick sind wirkungslos.
     */
    fun sync(view: GameView, fx: List<FxEvent>) {
        val tick = view.tick
        if (tick == lastSyncTick) return
        val first = lastSyncTick == Long.MIN_VALUE
        lastSyncTick = tick
        if (!first) {
            for (i in fx.indices) {
                when (val e = fx[i]) {
                    is FxEvent.Hit -> onHit(e)
                    is FxEvent.BeamBroken -> onBroken(e)
                    else -> Unit
                }
            }
        }
        scan(view)
    }

    private fun onBroken(e: FxEvent.BeamBroken) {
        val uid = e.beamUid
        val info = beamInfoOf(uid)
        if (info == 0) return // im selben Tick entstanden und gebrochen (Hälfte): nicht zählen
        beamInfo[uid] = info or DONE
        if (e.cause == BreakCause.DELETED || e.cause == BreakCause.DECAY) return
        if ((info and ORIGINAL) == 0 || (info and DONE) != 0) return
        val owner = (info and OWNER_MASK) - 1
        if (owner in 0 until playerCount) lost.incrementAndGet(owner)
    }

    /** Tabelle nachführen; ursprüngliche Balken, die gerade zu Trümmern geworden sind, zählen als verloren. */
    private fun scan(view: GameView) {
        val beams = view.beamView
        for (i in 0 until beams.size) {
            if (!beams.isAlive(i)) continue
            val uid = beams.uid(i)
            if (uid < 0) continue
            ensureBeam(uid)
            val flags = beams.flags(i)
            val debris = (flags and BeamFlags.DEBRIS) != 0
            val info = beamInfo[uid]
            if (info == 0) {
                val owner = beams.owner(i)
                val o = if (owner in 0 until playerCount) owner + 1 else 0
                beamInfo[uid] = when {
                    (flags and BeamFlags.HALF) != 0 || debris -> o or DONE
                    else -> o or ORIGINAL
                }
            } else if (debris && (info and DONE) == 0) {
                beamInfo[uid] = info or DONE
                val owner = (info and OWNER_MASK) - 1
                if ((info and ORIGINAL) != 0 && owner in 0 until playerCount) lost.incrementAndGet(owner)
            }
        }
        val devices = view.deviceView
        for (i in 0 until devices.size) {
            if (!devices.isAlive(i)) continue
            val uid = devices.uid(i)
            if (uid < 0) continue
            if (uid >= deviceInfo.size) deviceInfo = deviceInfo.copyOf(grow(deviceInfo.size, uid))
            if (deviceInfo[uid] == 0) {
                val owner = devices.owner(i)
                deviceInfo[uid] = if (owner in 0 until playerCount) owner + 1 else 0
            }
        }
    }

    private fun onHit(e: FxEvent.Hit) {
        if (e.damage <= 0f) return
        val owner = when (e.target) {
            HitTarget.BEAM -> (beamInfoOf(e.targetUid) and OWNER_MASK) - 1
            HitTarget.DEVICE -> (if (e.targetUid in deviceInfo.indices) deviceInfo[e.targetUid] else 0) - 1
            HitTarget.TERRAIN -> -1
        }
        if (owner !in 0 until playerCount || playerCount != 2) return
        val shooter = 1 - owner
        if (e.splash) {
            // Alle Splash-Treffer einer Explosion tragen deren Mittelpunkt und Tick: einmal zählen
            if (e.tick == lastHitTick && e.x == lastHitX && e.y == lastHitY) return
            lastHitTick = e.tick; lastHitX = e.x; lastHitY = e.y
        } else {
            if (e.tick - lastBurstHit[shooter] < BURST_TICKS) return
            lastBurstHit[shooter] = e.tick
        }
        hits.incrementAndGet(shooter)
    }

    private fun beamInfoOf(uid: Int): Int = if (uid in beamInfo.indices) beamInfo[uid] else 0

    private fun ensureBeam(uid: Int) {
        if (uid >= beamInfo.size) beamInfo = beamInfo.copyOf(grow(beamInfo.size, uid))
    }

    private fun grow(size: Int, index: Int): Int {
        var n = size
        while (n <= index) n *= 2
        return n
    }

    /**
     * Hängt den Zähler an [session]: eine Quelle ohne Commands am Ende der Quellenliste, die zu Beginn jedes Ticks
     * [sync] mit den Fx des vorigen Ticks aufruft (beeinflusst die Sim nicht: liefert nie ein Command, immer bereit).
     */
    fun attachTo(session: GameSession): CommandSource {
        val src = object : CommandSource {
            override fun commandsFor(tick: Long, view: GameView): List<Command> {
                sync(view, session.ctx.fx)
                return emptyList()
            }
        }
        session.addSource(src)
        sync(session.state, session.ctx.fx)
        return src
    }

    /** Aktueller Stand für [playerId] mit der Spieldauer [durationSeconds]; von jedem Thread. */
    fun statsFor(playerId: Int, durationSeconds: Int): MatchStats {
        if (playerId !in 0 until playerCount) return MatchStats(durationSeconds = durationSeconds)
        return MatchStats(
            durationSeconds = durationSeconds,
            shots = shots.get(playerId),
            hits = hits.get(playerId),
            beamsBuilt = built.get(playerId),
            beamsLost = lost.get(playerId),
        )
    }

    private companion object {
        /** Treffer einer Waffe innerhalb dieser Ticks gelten als eine Salve (MG 8 Schuss ≈ 0,5 s). */
        const val BURST_TICKS = 40L
        const val OWNER_MASK = 0xFF
        /** Ursprünglicher Balken (keine Hälfte, beim ersten Sehen kein Trümmer): zählt beim Verlust. */
        const val ORIGINAL = 1 shl 8
        /** Bereits gezählt bzw. erledigt (gebrochen, Trümmer, abgerissen). */
        const val DONE = 1 shl 9
    }
}
