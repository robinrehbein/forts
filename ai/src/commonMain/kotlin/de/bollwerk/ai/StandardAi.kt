package de.bollwerk.ai

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.BlueprintProps
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.view.GameView

/** Zähler einer KI (Tests, Simrunner-Bericht). */
class AiStats {
    var thinks: Int = 0
    var commands: Int = 0
    var shots: Int = 0
    var builds: Int = 0
    var rebuilds: Int = 0
    var repairs: Int = 0
    var extinguishes: Int = 0
    var doorToggles: Int = 0
    var endTurns: Int = 0
    /** Schüsse, die wegen der Spar-Reserve zurückgehalten wurden, und Reserve-Durchbrüche über den Waffen-Takt. */
    var shotsHeldForReserve: Int = 0
    var reserveBypasses: Int = 0
    /** Schüsse ohne neues Richten (nur Fire). */
    var refires: Int = 0
    /** Zusätzliche Waffen und Turbinen aus Überschuss. */
    var surplusWeapons: Int = 0
    var surplusTurbines: Int = 0
    /** Denkschritte, in denen Bau-Kandidaten verdrängt wurden. */
    var buildsBlocked: Int = 0

    override fun toString(): String =
        "thinks=$thinks commands=$commands shots=$shots builds=$builds rebuilds=$rebuilds repairs=$repairs " +
            "extinguish=$extinguishes doors=$doorToggles surplus=$surplusWeapons surplusTurbines=$surplusTurbines refires=$refires"
}

/**
 * Standard-Gegner-KI (WP11): nutzenbasiert, deterministisch, liest nur die [GameView] und handelt nur über Commands.
 *
 * Alle `difficulty.thinkIntervalTicks` Ticks ein Denkschritt mit höchstens [AiTuning.maxCommandsPerThink] (3) Commands:
 * 1. **Angriff** (je feuerbereite, bezahlbare Waffe): [TargetSelector] (Wert × Freiliegen / Entfernung) und
 *    [AimController] (`Ballistics.solveAngle` mit `gravityScale` und Wind, Bogenwahl nach Hindernisfreiheit,
 *    Zielfehler σ je Stufe) → `SetAim` + `Fire`. Verdeckt nur eine eigene geschlossene Tür die Bahn, wird sie geöffnet.
 * 2. **Reaktion**: Türen schließen, wenn gegnerische Geschosse auf die Festung zufliegen (vom Spieler geöffnete Türen
 *    bleiben sonst offen; beim Schuss öffnet das Waffen-System die nächste Tür ohnehin), brennende Balken löschen,
 *    beschädigte tragende Balken (Reaktor-/Waffenträger, Fundament) reparieren.
 * 3. **Bauen** nach der Bauvorlage der Stufe ([BuildPlanner]): Wirtschaft → Werkstatt → Wände/Panzer → Waffen →
 *    Reaktor-Abdeckung; sparen für den nächsten Schritt (Reserve); Wiederaufbau zerstörter Schlüsselbalken/Geräte.
 *    Ist der Plan fertig und das Lager fast voll, kommen bis zu [MAX_SURPLUS_WEAPONS] zusätzliche Waffen dazu.
 *
 * **Belagerung** (ab [TargetSelector.SIEGE_START_TICKS], gegen Patt in Spiegelpartien): Explosionswaffen graben zum
 * gegnerischen Reaktor ([TargetSelector]) und feuern dabei ohne neues Richten nach ([siegeDig]: Einschläge bündeln sich);
 * der Überschuss geht früher in zusätzliche Explosionswaffen, bei Energiemangel in Turbinen ([surplusDevice]).
 *
 * Alle Kandidaten bekommen eine Bewertung; die besten werden gierig gewählt (Command-Budget, Ressourcen, Reserve).
 * Jedes Command wird vorab mit dem [RulesValidator] gegen die View geprüft. Zufall nur aus [AiRng]
 * (`RngStreams.ai(playerId)`), daher gleiche Partie bei gleichem Seed.
 *
 * **Spar-Reserve**: Was der Bauplan als Nächstes kaufen will, bleibt liegen. Schüsse respektieren sie, außer auf einen
 * per Splash erreichbaren Reaktor, auf eine voll freiliegende Waffe oder höchstens einmal je Waffe und
 * [FIRE_BYPASS_INTERVAL_TICKS]; spart der Plan länger als [SAVE_PATIENCE_TICKS] ohne Fortschritt, gilt nur noch die
 * Reaktor-Ausnahme. Reparaturen (mit ihren echten Kosten) und niedrig bewerteter Wiederaufbau warten zusätzlich auf die
 * günstigste zerstörte Waffe. Werden Bau-Kandidaten verdrängt (Plätze/Geld), bekommen sie im nächsten Denkschritt
 * Vorrang ([SCORE_BUILD_URGENT]); als Bauversuch zählt nur, was tatsächlich ausgegeben wurde.
 *
 * **Rechenzeit** (läuft im Sim-Tick): höchstens [AiTuning.weaponEvalsPerThink] Waffen werden je Denkschritt
 * ballistisch bewertet (reihum; die erste mit lohnendem Schuss beendet die Suche, SetAim + Fire belegen 2 der 3
 * Plätze). Der Löser startet warm aus der letzten Lösung ([AimController]). Liegt die stehende Ausrichtung einer Waffe
 * noch gut (vorhergesagte Bahn), feuert sie ohne neues Richten nach (1 Command, höchstens [MAX_REFIRES_PER_THINK]).
 *
 * @param blueprint Bauvorlage; `null` = nur kämpfen (kein Bauen, kein Wiederaufbau). Die Standard-Vorlage der Stufe
 *   löst [AiFactory.create] auf.
 */
class StandardAi(
    override val playerId: Int,
    override val difficulty: Difficulty,
    val seed: Long,
    blueprint: BlueprintProps?,
) : AiAgent {
    val tuning: AiTuning = AiTuning.of(difficulty)
    val stats: AiStats = AiStats()
    private val rng = AiRng(seed, playerId)
    private val obstacles = Obstacles()
    private val aim = AimController(obstacles)
    private val selector = TargetSelector(aim)
    private val planner: BuildPlanner? = blueprint?.let { BuildPlanner(playerId, it) }
    private var nextThink = 0L
    private var lastTurnNumber = -1
    private var turnThinks = 0

    private val actions = ArrayList<Action>()
    private val builds = ArrayList<BuildCandidate>()
    private val rebuilds = ArrayList<BuildCandidate>()
    private var usedKeys = LongArray(8)
    private var usedPools = IntArray(8)
    private var usedCount = 0
    /** Reihum-Start der Waffenbewertung (Geräte-Slot). */
    private var weaponCursor = 0
    /** Bau-Kandidaten im letzten Denkschritt verdrängt (keine Plätze/kein Geld) → diesmal Vorrang. */
    private var buildBlocked = false
    /** Letzter Reserve-Durchbruch je Waffe (Ref → Tick). */
    private val bypassRefs = ArrayList<Long>()
    private val bypassTicks = ArrayList<Long>()
    private var lastSurplusTick = -SURPLUS_INTERVAL_TICKS
    /** Letztes Ziel je Waffe (Waffen-Ref → Ziel-Ref), für das Nachfeuern ohne neues Richten. */
    private val lastShotWeapon = ArrayList<Long>()
    private val lastShotTarget = ArrayList<Long>()
    private val current = AimSolution()
    private val mountGeo = FloatArray(DeviceGeometry.SIZE)
    private val castHit = CastHit()
    private val choice = TargetChoice()
    private var beamImportance = FloatArray(64)
    private val sim = FloatArray(4)

    /** Von der KI fixiert geöffnete Türen (Ref) und bis wann sie gebraucht werden. */
    private val pinnedRefs = ArrayList<Long>()
    private val pinnedUntil = ArrayList<Long>()

    /** Bauplan (null = nur kämpfen). */
    val buildPlanner: BuildPlanner? get() = planner

    /** Ballistik-Rechenzähler (Budget-Tests). */
    val aimStats: AimController get() = aim

    /** Letztes gewähltes Ziel (Geräte-Slot) und dessen Freiliegen, für Tests/Debug. */
    var lastTarget: Int = -1; private set
    var lastExposure: Float = 0f; private set

    private class Action(
        val score: Float,
        /** Schlüssel innerhalb von [pool]: höchstens eine Aktion je (Pool, Schlüssel) und Denkschritt. */
        val pool: Int,
        val key: Long,
        val metal: Float,
        val energy: Float,
        val respectsReserve: Boolean,
        val kind: Int,
        val commands: List<Command>,
        /** Zweiter Bau-Schlüssel (Aussteifungs-Partner beim Wiederaufbau, Pool [POOL_PLAN]), sonst [NO_KEY]. */
        val key2: Long = NO_KEY,
        /** Schuss darf die Reserve nur über den Waffen-Takt durchbrechen ([FIRE_BYPASS_INTERVAL_TICKS]). */
        val intervalBypass: Boolean = false,
        /** Schuss: Ref des Ziel-Geräts (für das Nachfeuern), sonst [NO_KEY]. */
        val targetRef: Long = NO_KEY,
    )

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        if (tick < nextThink) return emptyList()
        if (view.result != GameResult.Ongoing) return emptyList()
        if (playerId < 0 || playerId >= view.playerCount) return emptyList()
        if (!view.player(playerId).alive) return emptyList()
        val turn = view.turn
        if (turn.mode == TurnMode.TURNS) {
            if (turn.activePlayer != playerId || turn.phase != TurnPhase.PLAY) { nextThink = tick + 1; return emptyList() }
            if (turn.turnNumber != lastTurnNumber) { lastTurnNumber = turn.turnNumber; turnThinks = 0 }
            turnThinks++
        }
        nextThink = tick + difficulty.thinkIntervalTicks
        val out = think(tick, view)
        if (turn.mode == TurnMode.TURNS && out.isEmpty() && turnThinks > TURN_MIN_THINKS) {
            stats.endTurns++
            stats.commands++
            return listOf(Command.EndTurn(tick, playerId))
        }
        return out
    }

    private fun think(tick: Long, view: GameView): List<Command> {
        stats.thinks++
        val me = playerId
        obstacles.rebuild(view)
        actions.clear()
        var metal = RuleChecks.availableMetal(view, me)
        var energy = RuleChecks.availableEnergy(view, me)

        // Bauplan zuerst trocken durchrechnen: liefert Reihenfolge und Spar-Reserve
        val pl = planner
        builds.clear()
        pl?.refresh(view)
        pl?.build(view, tick, tuning.maxCommandsPerThink, metal, energy, builds)
        var reserveMetal = pl?.reserveMetal ?: 0f
        var reserveEnergy = pl?.reserveEnergy ?: 0f
        // Schüsse respektieren nur die Reserve des Bauplans (nicht das Sparen für eine zerstörte Waffe: deren
        // Wiederaufbau ist ohnehin höher bewertet als ein Schuss)
        var planReserveMetal = reserveMetal
        var planReserveEnergy = reserveEnergy
        val starving = pl != null && pl.savingTicks(tick) > SAVE_PATIENCE_TICKS
        if (tuning.rebuild && pl != null) {
            rebuilds.clear()
            // reich (Lager weitgehend voll): Geräte sofort wieder aufbauen, auch an Plätzen unter Beschuss
            val rich = metal >= SURPLUS_FRACTION * view.simConfig.metalCap
            pl.rebuild(view, tick, MAX_REBUILD_CANDIDATES, rebuilds, ignoreBackoff = rich)
            // fehlt eine Waffe (oder spart der Plan schon zu lange), warten auch Wand-/Trägerbalken; nur Montagebalken,
            // Waffen und Minen nicht
            val reserveScore = if (pl.missingWeaponMetal > 0f || starving) REBUILD_RESERVE_SCORE_WEAPON_MISSING else REBUILD_RESERVE_SCORE
            var k = 0
            while (k < rebuilds.size) {
                val c = rebuilds[k]
                if (c.pairNext && k + 1 < rebuilds.size) { // neuer Knoten: beide Balken gemeinsam (ausgesteift)
                    val p = rebuilds[k + 1]
                    actions.add(Action(c.score, POOL_PLAN, c.key, c.metal + p.metal, 0f, c.score < reserveScore, KIND_REBUILD, listOf(c.command, p.command), p.key))
                    k += 2
                } else {
                    actions.add(Action(c.score, POOL_PLAN, c.key, c.metal, c.energy, c.score < reserveScore, KIND_REBUILD, listOf(c.command)))
                    k++
                }
            }
            // für eine zerstörte Waffe sparen: niedrig bewertete Ausgaben warten, bis sie wieder steht
            reserveMetal = FloatMath.max(reserveMetal, pl.missingWeaponMetal)
            reserveEnergy = FloatMath.max(reserveEnergy, pl.missingWeaponEnergy)
        }

        offense(view, tick, metal, energy, planReserveMetal, planReserveEnergy, starving)
        reactive(view, tick, metal)
        if (tuning.rebuild && pl != null && pl.phase == "done" && pl.missingWeaponMetal == 0f) surplusDevice(view, tick, metal, energy)
        val buildScore = when {
            buildBlocked -> SCORE_BUILD_URGENT
            pl?.phase == "economy" -> SCORE_BUILD_ECONOMY
            else -> SCORE_BUILD
        }
        if (builds.isNotEmpty()) actions.add(Action(buildScore, POOL_PLAN, KEY_BUILD, 0f, 0f, false, KIND_BUILD, emptyList()))

        actions.sortByDescending { it.score } // stabil: Gleichstand behält die Erzeugungsreihenfolge
        val out = ArrayList<Command>(tuning.maxCommandsPerThink)
        usedCount = 0
        var buildsEmitted = 0
        for (a in actions) {
            val left = tuning.maxCommandsPerThink - out.size
            if (left <= 0) break
            if (a.kind == KIND_BUILD) {
                var k = 0
                while (k < builds.size) {
                    val c = builds[k]
                    val n = if (c.pairNext && k + 1 < builds.size) 2 else 1
                    var cm = 0f; var ce = 0f
                    for (q in k until k + n) { cm += builds[q].metal; ce += builds[q].energy }
                    if (n > tuning.maxCommandsPerThink - out.size || cm > metal || ce > energy || keyUsed(POOL_PLAN, c.key)) break
                    for (q in k until k + n) {
                        out.add(builds[q].command)
                        useKey(POOL_PLAN, builds[q].key)
                        pl?.onBuildIssued(builds[q].key, tick) // erst jetzt zählt der Versuch
                    }
                    metal -= cm; energy -= ce
                    // was gekauft ist, muss nicht mehr gespart werden
                    reserveMetal = FloatMath.max(0f, reserveMetal - cm)
                    reserveEnergy = FloatMath.max(0f, reserveEnergy - ce)
                    planReserveMetal = FloatMath.max(0f, planReserveMetal - cm)
                    planReserveEnergy = FloatMath.max(0f, planReserveEnergy - ce)
                    stats.builds += n
                    buildsEmitted += n
                    k += n
                }
                continue
            }
            if (a.commands.size > left) continue
            if (keyUsed(a.pool, a.key)) continue
            if (a.metal > metal || a.energy > energy) continue
            val rm = if (a.kind == KIND_FIRE) planReserveMetal else reserveMetal
            val re = if (a.kind == KIND_FIRE) planReserveEnergy else reserveEnergy
            val dipsIntoReserve = metal - a.metal < rm || energy - a.energy < re
            if (dipsIntoReserve && a.respectsReserve) continue
            if (dipsIntoReserve && a.intervalBypass && !bypassAllowed(a.key, tick)) continue
            out.addAll(a.commands)
            useKey(a.pool, a.key)
            metal -= a.metal; energy -= a.energy
            when (a.kind) {
                KIND_FIRE -> {
                    stats.shots++
                    if (a.commands.size == 1) stats.refires++
                    rememberTarget(view, a.key, a.targetRef)
                    if (dipsIntoReserve && a.intervalBypass) { stats.reserveBypasses++; noteBypass(a.key, tick) }
                }
                KIND_REPAIR -> stats.repairs++
                KIND_EXTINGUISH -> stats.extinguishes++
                KIND_DOOR -> {
                    stats.doorToggles++
                    val cmd = a.commands[0] as Command.ToggleDoor
                    onDoorToggled(view, tick, cmd.beamRef)
                }
                KIND_DOOR_OPEN -> {
                    stats.doorToggles++
                    val cmd = a.commands[0] as Command.ToggleDoor
                    pinnedRefs.add(cmd.beamRef); pinnedUntil.add(tick + DOOR_HOLD_TICKS)
                }
                KIND_SURPLUS -> {
                    stats.surplusWeapons++
                    lastSurplusTick = tick
                }
                KIND_SURPLUS_TURBINE -> {
                    stats.surplusTurbines++
                    lastSurplusTick = tick
                }
                KIND_REBUILD -> {
                    stats.rebuilds++
                    pl?.onIssued(a.key, tick)
                    if (a.key2 != NO_KEY) { pl?.onIssued(a.key2, tick); useKey(POOL_PLAN, a.key2) }
                }
            }
        }
        buildBlocked = builds.isNotEmpty() && buildsEmitted == 0
        if (buildBlocked) stats.buildsBlocked++
        stats.commands += out.size
        return out
    }

    private fun keyUsed(pool: Int, key: Long): Boolean {
        for (i in 0 until usedCount) if (usedKeys[i] == key && usedPools[i] == pool) return true
        return false
    }

    private fun useKey(pool: Int, key: Long) {
        if (usedCount == usedKeys.size) { usedKeys = usedKeys.copyOf(usedCount * 2); usedPools = usedPools.copyOf(usedCount * 2) }
        usedKeys[usedCount] = key; usedPools[usedCount] = pool
        usedCount++
    }

    /** Darf Waffe [ref] die Reserve wieder durchbrechen (letzter Durchbruch ≥ [FIRE_BYPASS_INTERVAL_TICKS] her)? */
    private fun bypassAllowed(ref: Long, tick: Long): Boolean {
        for (i in bypassRefs.indices) if (bypassRefs[i] == ref) return tick - bypassTicks[i] >= FIRE_BYPASS_INTERVAL_TICKS
        return true
    }

    private fun lastTargetOf(weaponRef: Long): Long {
        for (i in lastShotWeapon.indices) if (lastShotWeapon[i] == weaponRef) return lastShotTarget[i]
        return NO_KEY
    }

    private fun rememberTarget(view: GameView, weaponRef: Long, targetRef: Long) {
        if (targetRef == NO_KEY) return
        for (i in lastShotWeapon.indices) if (lastShotWeapon[i] == weaponRef) { lastShotTarget[i] = targetRef; return }
        for (i in lastShotWeapon.indices.reversed()) {
            if (view.deviceView.resolve(lastShotWeapon[i]) < 0) { lastShotWeapon.removeAt(i); lastShotTarget.removeAt(i) }
        }
        lastShotWeapon.add(weaponRef); lastShotTarget.add(targetRef)
    }

    private fun noteBypass(ref: Long, tick: Long) {
        for (i in bypassRefs.indices) if (bypassRefs[i] == ref) { bypassTicks[i] = tick; return }
        // Liste klein halten: lange abgelaufene Einträge ersetzen
        for (i in bypassRefs.indices) if (tick - bypassTicks[i] >= 2 * FIRE_BYPASS_INTERVAL_TICKS) { bypassRefs[i] = ref; bypassTicks[i] = tick; return }
        bypassRefs.add(ref); bypassTicks.add(tick)
    }

    // ---------------------------------------------------------------- Angriff

    private fun offense(
        view: GameView, tick: Long, metal: Float, energy: Float, reserveMetal: Float, reserveEnergy: Float, starving: Boolean,
    ) {
        val me = playerId
        val d = view.deviceView
        val beams = view.beamView
        val tables = view.tables
        val n = d.size
        if (n == 0) return
        if (weaponCursor >= n) weaponCursor = 0
        var evals = 0
        var refires = 0
        // Endspiel (Gegner ohne Waffe): auch grabende Schüsse ohne neues Richten wiederholen, damit sich die Treffer an
        // einer Stelle bündeln und die Reparatur des Gegners überholen
        var enemyWeapons = 0
        for (q in 0 until n) {
            if (!d.isAlive(q)) continue
            val o = d.owner(q)
            val tq = d.type(q)
            if (o != me && o >= 0 && tq >= 0 && tq < tables.devices.size && tables.devices[tq].role == DeviceRole.WEAPON) enemyWeapons++
        }
        val refireMin = if (enemyWeapons == 0) tuning.minExposure else FloatMath.max(tuning.minExposure, REFIRE_MIN_EXPOSURE)
        for (step in 0 until n) {
            if (evals >= tuning.weaponEvalsPerThink) break
            val i = (weaponCursor + step) % n
            if (!d.isAlive(i) || d.owner(i) != me) continue
            val ty = d.type(i)
            if (ty < 0 || ty >= tables.devices.size) continue
            val props = tables.devices[ty]
            if (props.weapon < 0 || props.weapon >= tables.weapons.size) continue
            if (!readyToFire(view, i)) continue
            val b = d.beam(i)
            if (b < 0 || !beams.isAlive(b) || (beams.flags(b) and BeamFlags.DEBRIS) != 0) continue
            val w = tables.weapons[props.weapon]
            if (w.shotMetal > metal || w.shotEnergy > energy) continue
            val ref = d.ref(i)
            // Nachfeuern: Liegt die stehende Ausrichtung (letzter Schuss) noch gut im Ziel, reicht ein Fire (1 Command);
            // so passen mehrere Schüsse in einen Denkschritt. Die Bahn wird dafür neu vorhergesagt (wie der Blick auf
            // den letzten Einschlag), schlechte Treffer werden also nicht blind wiederholt.
            if (refires < MAX_REFIRES_PER_THINK) {
                val prevTarget = lastTargetOf(ref)
                val t = if (prevTarget == NO_KEY) -1 else d.resolve(prevTarget)
                if (t >= 0 && aim.evaluateCurrentAim(view, me, i, props, w, t, current) &&
                    current.doorToOpen < 0 && (current.exposure >= refireMin || siegeDig(view, w, t, current))
                ) {
                    val role = tables.devices[d.type(t)].role
                    val bypass = (role == DeviceRole.REACTOR && current.exposure >= REACTOR_BYPASS_EXPOSURE) ||
                        (!starving && role == DeviceRole.WEAPON && current.exposure >= WEAPON_BYPASS_EXPOSURE)
                    val dips = metal - w.shotMetal < reserveMetal || energy - w.shotEnergy < reserveEnergy
                    val fire = Command.Fire(tick, me, ref)
                    if ((!dips || bypass) && RulesValidator.validate(view, fire) == null) {
                        refires++
                        weaponCursor = (i + 1) % n
                        lastTarget = t
                        lastExposure = current.exposure
                        val sc = TargetSelector.score(TargetSelector.valueOf(role), current.exposure, current.distance)
                        actions.add(
                            Action(SCORE_FIRE + FIRE_WEIGHT * sc * damageFactor(w), POOL_DEVICE, ref, w.shotMetal, w.shotEnergy,
                                !bypass, KIND_FIRE, listOf(fire), targetRef = prevTarget),
                        )
                        continue
                    }
                }
            }
            // reihum: die nächste Bewertung beginnt hinter dieser Waffe
            evals++
            weaponCursor = (i + 1) % n
            if (!selector.choose(view, me, i, props, w, tuning, rng, choice)) continue
            val sol = choice.solution
            if (sol.exposure < tuning.minExposure) continue
            val base = SCORE_FIRE + FIRE_WEIGHT * choice.score * damageFactor(w)
            if (sol.doorToOpen >= 0) {
                if (!tuning.useDoors) continue
                val doorRef = beams.ref(sol.doorToOpen)
                val cmd = Command.ToggleDoor(tick, me, doorRef)
                if (RulesValidator.validate(view, cmd) == null) {
                    actions.add(Action(base - DOOR_OPEN_PENALTY, POOL_BEAM, doorRef, 0f, 0f, false, KIND_DOOR_OPEN, listOf(cmd)))
                }
                continue
            }
            // Spar-Reserve: Reaktor-Treffer immer; voll freiliegende Waffe, sonst einmal je Takt – außer der Plan
            // spart schon zu lange (dann nur noch der Reaktor)
            val role = tables.devices[d.type(choice.targetId)].role
            val alwaysBypass = (role == DeviceRole.REACTOR && sol.exposure >= REACTOR_BYPASS_EXPOSURE) ||
                (!starving && role == DeviceRole.WEAPON && sol.exposure >= WEAPON_BYPASS_EXPOSURE)
            val intervalBypass = !alwaysBypass && !starving
            val dips = metal - w.shotMetal < reserveMetal || energy - w.shotEnergy < reserveEnergy
            if (dips && !alwaysBypass && !(intervalBypass && bypassAllowed(ref, tick))) {
                stats.shotsHeldForReserve++
                continue // diese Waffe wartet; vielleicht hat die nächste ein lohnendes Ziel
            }
            val angle = aim.applyErrorClear(view, me, i, props, w, sol, difficulty.aimErrorDeg, rng) // FX1: nie in die eigene Festung
            val setAim = Command.SetAim(tick, me, ref, angle, sol.power)
            val fire = Command.Fire(tick, me, ref)
            if (RulesValidator.validate(view, setAim) != null || RulesValidator.validate(view, fire) != null) continue
            lastTarget = choice.targetId
            lastExposure = sol.exposure
            actions.add(
                Action(base, POOL_DEVICE, ref, w.shotMetal, w.shotEnergy, !alwaysBypass && !intervalBypass, KIND_FIRE,
                    listOf(setAim, fire), intervalBypass = intervalBypass, targetRef = d.ref(choice.targetId)),
            )
            return // ein Schuss je Denkschritt (SetAim + Fire = 2 von 3 Plätzen)
        }
    }

    /**
     * Belagerung ([TargetSelector.siegeFactor]): Die stehende Bahn einer Explosionswaffe gräbt in die Deckung vor dem
     * gegnerischen Reaktor. Nachfeuern ohne neues Richten bündelt die Einschläge an einer Stelle (kein neuer Zielfehler),
     * so dass die Deckung schneller bricht, als der Gegner repariert.
     */
    private fun siegeDig(view: GameView, w: WeaponProps, target: Int, sol: AimSolution): Boolean {
        if (!(w.splashRadius > 0f) || !(TargetSelector.siegeFactor(view.tick) > 0f)) return false
        val ty = view.deviceView.type(target)
        if (ty < 0 || ty >= view.tables.devices.size || view.tables.devices[ty].role != DeviceRole.REACTOR) return false
        return selector.digsTowards(view, target, sol)
    }

    private fun readyToFire(view: GameView, i: Int): Boolean {
        val d = view.deviceView
        val f = d.flags(i)
        if ((f and (DeviceFlags.BUILDING or DeviceFlags.DISABLED or DeviceFlags.FIRE_REQUESTED or DeviceFlags.FIRING_BEAM)) != 0) return false
        return d.buildTicks(i) == 0 && d.reloadTicks(i) == 0 && d.burstLeft(i) == 0
    }

    /** Grober Schadenswert je Schuss relativ zu 100 (Kanone/Mörser ~1,2, MG ~0,5), für die Feuer-Priorität. */
    private fun damageFactor(w: WeaponProps): Float {
        val burst = if (w.shotsPerBurst > 1) w.shotsPerBurst else 1
        val dmg = when (w.mode) {
            WeaponMode.BEAM -> w.damage * w.beamTicks / 60f
            else -> w.damage * burst + w.splashDamage
        }
        return 0.5f + 0.5f * FloatMath.clamp(dmg / 100f, 0f, 2f)
    }

    // ---------------------------------------------------------------- Überschuss

    /**
     * Plan fertig, keine Waffe fehlt und das Metall-Lager ist fast voll: ein zusätzliches Gerät bauen (sonst verfällt
     * das Einkommen, und zwei KIs schießen sich endlos die wieder aufgebauten Waffen ab).
     * - Ist die Energie knapp (unter [SURPLUS_ENERGY_LOW] des Lagers; Schüsse kosten Energie), schon ab
     *   [SURPLUS_TURBINE_FRACTION] des Metall-Lagers eine zusätzliche Turbine (Typ der Vorlage, höchstens
     *   [MAX_SURPLUS_TURBINES] über die Vorlage hinaus).
     * - Sonst eine Waffe: die Waffe der Vorlage, von der gerade am wenigsten stehen; in der Belagerung nur
     *   Explosionswaffen und schon ab [SIEGE_SURPLUS_BUILD_FRACTION] des Lagers.
     * Platz: der höchste freie Balken mit Platz nach oben (Waffen zusätzlich mit Schussfeld zum Gegner).
     */
    private fun surplusDevice(view: GameView, tick: Long, metal: Float, energy: Float) {
        if (tick - lastSurplusTick < SURPLUS_INTERVAL_TICKS) return
        // Belagerung: früher zusätzliche Explosionswaffen, damit Treffer auf dieselbe Deckung schneller folgen als die
        // Reparatur (eine Tür bricht nur bei zwei Treffern binnen ~2 s)
        val siege = TargetSelector.siegeFactor(tick) > 0f
        val fraction = if (siege) SIEGE_SURPLUS_BUILD_FRACTION else SURPLUS_BUILD_FRACTION
        val bp = planner?.blueprint ?: return
        val tables = view.tables
        val d = view.deviceView
        val me = playerId
        if (energy < SURPLUS_ENERGY_LOW * view.simConfig.energyCap && metal >= SURPLUS_TURBINE_FRACTION * view.simConfig.metalCap) {
            var planTurbines = 0
            var turbineType = -1
            for (k in bp.devices.indices) {
                val ty = bp.devices[k].type
                if (ty < 0 || ty >= tables.devices.size || tables.devices[ty].role != DeviceRole.TURBINE) continue
                planTurbines++
                if (turbineType < 0) turbineType = ty
            }
            if (turbineType >= 0 && countOwn(view, DeviceRole.TURBINE) < planTurbines + MAX_SURPLUS_TURBINES) {
                val p = tables.devices[turbineType]
                if (p.costMetal <= metal && p.costEnergy <= energy && placeSurplus(view, tick, turbineType, weapon = false)) return
            }
        }
        if (metal < fraction * view.simConfig.metalCap) return
        // Waffen-Typen der Vorlage und wie viele davon stehen; die seltenste zuerst, ohne Platz die nächste
        var planWeapons = 0
        for (k in bp.devices.indices) {
            val ty = bp.devices[k].type
            if (ty >= 0 && ty < tables.devices.size && tables.devices[ty].role == DeviceRole.WEAPON) planWeapons++
        }
        if (countOwn(view, DeviceRole.WEAPON) >= planWeapons + MAX_SURPLUS_WEAPONS) return
        var tried = 0L // Bitmaske der schon versuchten Vorlagen-Einträge
        while (true) {
            var bestType = -1
            var bestK = -1
            var bestCount = Int.MAX_VALUE
            for (k in bp.devices.indices) {
                if (k < 64 && (tried and (1L shl k)) != 0L) continue
                val ty = bp.devices[k].type
                if (ty < 0 || ty >= tables.devices.size || tables.devices[ty].role != DeviceRole.WEAPON) continue
                var first = true
                for (q in 0 until k) if (bp.devices[q].type == ty) { first = false; break }
                if (!first) continue
                val p = tables.devices[ty]
                if (p.costMetal > metal || p.costEnergy > energy) continue
                if (siege && (p.weapon < 0 || p.weapon >= tables.weapons.size || !(tables.weapons[p.weapon].splashRadius > 0f))) continue
                var count = 0
                for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == me && d.type(i) == ty) count++
                if (count < bestCount) { bestCount = count; bestType = ty; bestK = k }
            }
            if (bestType < 0 || bestK >= 64) break
            tried = tried or (1L shl bestK)
            if (placeSurplus(view, tick, bestType, weapon = true)) return
        }
        lastSurplusTick = tick // kein Platz: später wieder suchen
    }

    private fun countOwn(view: GameView, role: DeviceRole): Int {
        val d = view.deviceView
        val tables = view.tables
        var n = 0
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.owner(i) != playerId) continue
            val ty = d.type(i)
            if (ty >= 0 && ty < tables.devices.size && tables.devices[ty].role == role) n++
        }
        return n
    }

    /** Bester Platz für ein zusätzliches Gerät [type] (siehe [surplusDevice]) → Aktion. @return `false` ohne Platz. */
    private fun placeSurplus(view: GameView, tick: Long, type: Int, weapon: Boolean): Boolean {
        val tables = view.tables
        val me = playerId
        val props = tables.devices[type]
        // Steilfeuer (Mörser, Mindest-Elevation 20°) braucht kein flaches Schussfeld
        val steep = props.weapon >= 0 && props.weapon < tables.weapons.size && tables.weapons[props.weapon].minAimRad > STEEP_MIN_ELEVATION
        val facing = if (view.player(me).facing >= 0) 1f else -1f
        val beams = view.beamView
        val nodes = view.nodeView
        val mats = tables.materials
        var bestCmd: Command.PlaceDevice? = null
        var bestScore = -Float.MAX_VALUE
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j) || beams.owner(j) != me || (beams.flags(j) and BeamFlags.DEBRIS) != 0) continue
            val m = beams.material(j)
            if (m < 0 || m >= mats.size || mats[m].isDoor || mats[m].tensionOnly) continue
            val a = beams.nodeA(j); val b = beams.nodeB(j)
            for (ti in SURPLUS_T.indices) for (side in 0 until 2) {
                val t = SURPLUS_T[ti]
                val neg = side == 1
                DeviceGeometry.mountAt(
                    nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b), t, neg, mats[m].thickness,
                    props.mountOffset, props.pivotOffset, props.barrelLength, 0f, mountGeo,
                )
                if (mountGeo[DeviceGeometry.NY] > SURPLUS_MIN_UP) continue // nur oben auf (fast) waagerechten Balken
                val score: Float
                if (weapon) {
                    val px = mountGeo[DeviceGeometry.PIVOT_X]; val py = mountGeo[DeviceGeometry.PIVOT_Y]
                    // frei nach oben und schräg zum Gegner (sonst trifft die Waffe die eigene Festung):
                    // schräg nach oben (Mörser, ~60°) und, außer für Steilfeuer, flacher zum Gegner (Kanone, ~35°)
                    if (obstacles.cast(px, py, px + facing * SURPLUS_CLEAR_UP * 0.6f, py - SURPLUS_CLEAR_UP, SURPLUS_CLEAR_RADIUS, j, -1, -1, false, castHit)) continue
                    if (!steep && obstacles.cast(px, py, px + facing * SURPLUS_CLEAR_FWD, py - SURPLUS_CLEAR_RISE, SURPLUS_CLEAR_RADIUS, j, -1, -1, false, castHit)) continue
                    score = -mountGeo[DeviceGeometry.CY] + SURPLUS_FORWARD_WEIGHT * facing * mountGeo[DeviceGeometry.CX]
                } else {
                    // Turbine: frei nach oben, möglichst hoch (Höhenbonus) und weg vom Gegner
                    val cx = mountGeo[DeviceGeometry.CX]; val cy = mountGeo[DeviceGeometry.CY]
                    if (obstacles.cast(cx, cy, cx, cy - SURPLUS_CLEAR_UP, SURPLUS_CLEAR_RADIUS, j, -1, -1, false, castHit)) continue
                    score = -cy - SURPLUS_FORWARD_WEIGHT * facing * cx
                }
                if (score <= bestScore) continue
                val cmd = Command.PlaceDevice(tick, me, type, beams.ref(j), t, neg)
                if (RulesValidator.validate(view, cmd) != null) continue
                bestScore = score
                bestCmd = cmd
            }
        }
        val cmd = bestCmd ?: return false
        actions.add(
            Action(SCORE_SURPLUS, POOL_PLAN, KEY_SURPLUS, props.costMetal, props.costEnergy, true,
                if (weapon) KIND_SURPLUS else KIND_SURPLUS_TURBINE, listOf(cmd)),
        )
        return true
    }

    // ---------------------------------------------------------------- Reaktion

    private fun reactive(view: GameView, tick: Long, metal: Float) {
        val me = playerId
        val beams = view.beamView
        val tables = view.tables
        if (tuning.useDoors) {
            val threat = rng.chance(tuning.reactionChance) && incomingThreat(view)
            for (j in 0 until beams.size) {
                if (!beams.isAlive(j) || beams.owner(j) != me) continue
                val f = beams.flags(j)
                if ((f and BeamFlags.DEBRIS) != 0 || (f and BeamFlags.DOOR_OPEN) == 0) continue
                val m = beams.material(j)
                if (m < 0 || m >= tables.materials.size || !tables.materials[m].isDoor) continue
                val ref = beams.ref(j)
                val score = doorCloseScore(threat, (f and BeamFlags.DOOR_PINNED) != 0, doorStillNeeded(ref, tick))
                if (score.isNaN()) continue
                val cmd = Command.ToggleDoor(tick, me, ref)
                if (RulesValidator.validate(view, cmd) == null) actions.add(Action(score, POOL_BEAM, ref, 0f, 0f, false, KIND_DOOR, listOf(cmd)))
            }
        }
        if (!rng.chance(tuning.reactionChance)) return
        computeImportance(view)
        val repairCfg = view.simConfig.repair
        // Überschuss (Lager fast voll, nichts mehr zu bauen): auch leichte Schäden reparieren statt Metall zu verschenken
        val surplus = metal >= SURPLUS_FRACTION * view.simConfig.metalCap
        val threshold = if (surplus) FloatMath.max(tuning.repairThreshold, SURPLUS_REPAIR_THRESHOLD) else tuning.repairThreshold
        var added = 0
        for (j in 0 until beams.size) {
            if (added >= MAX_REPAIR_CANDIDATES) break
            if (!beams.isAlive(j) || beams.owner(j) != me) continue
            val f = beams.flags(j)
            if ((f and BeamFlags.DEBRIS) != 0) continue
            val imp = if (j < beamImportance.size) beamImportance[j] else 0f
            val maxHp = beams.maxHp(j)
            if (!(maxHp > 0f)) continue
            val frac = beams.hp(j) / maxHp
            val ref = beams.ref(j)
            if (beams.fire(j) > 0f) {
                if (!tuning.extinguish) continue
                if (imp <= 0f && beams.fire(j) < EXTINGUISH_MIN_FIRE) continue
                val cmd = Command.DeleteBeam(tick, me, ref) // brennend: löscht nur das Feuer
                if (RulesValidator.validate(view, cmd) != null) continue
                actions.add(Action(SCORE_EXTINGUISH + 8f * imp, POOL_BEAM, ref, 0f, 0f, false, KIND_EXTINGUISH, listOf(cmd)))
                added++
                continue
            }
            if (frac >= threshold || (f and BeamFlags.REPAIRING) != 0) continue
            val cmd = Command.RepairBeam(tick, me, ref)
            if (RulesValidator.validate(view, cmd) != null) continue
            val m = beams.material(j)
            // echte Kosten (RepairSystem zieht sie über die nächsten Ticks ab); nur stark beschädigte Träger von Reaktor
            // oder Waffe dürfen an die Spar-Reserve
            val cost = repairCfg.costFactor * tables.materials[m].costPerMeter * beams.restLength(j) * (1f - frac)
            val critical = imp >= CRITICAL_IMPORTANCE && frac < CRITICAL_REPAIR_FRACTION
            actions.add(
                Action(
                    SCORE_REPAIR + 12f * (1f + imp) * (1f - frac), POOL_BEAM, ref, cost, 0f,
                    !critical, KIND_REPAIR, listOf(cmd),
                ),
            )
            added++
        }
    }

    /** Wichtigkeit je eigenem Balken: trägt Reaktor (4) / Waffe (2) / anderes Gerät (1,5), hängt am Fundament (+1). */
    private fun computeImportance(view: GameView) {
        val beams = view.beamView
        val nodes = view.nodeView
        if (beamImportance.size < beams.size) beamImportance = FloatArray(beams.size * 2)
        for (j in 0 until beams.size) {
            var v = 0f
            if (beams.isAlive(j) && beams.owner(j) == playerId) {
                val a = beams.nodeA(j); val b = beams.nodeB(j)
                if ((nodes.flags(a) and NodeFlags.ANCHORED) != 0 || (nodes.flags(b) and NodeFlags.ANCHORED) != 0) v += 1f
            }
            beamImportance[j] = v
        }
        val d = view.deviceView
        val props = view.tables.devices
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.owner(i) != playerId) continue
            val b = d.beam(i)
            if (b < 0 || b >= beams.size) continue
            val ty = d.type(i)
            val role = if (ty in props.indices) props[ty].role else DeviceRole.OTHER
            beamImportance[b] += when (role) {
                DeviceRole.REACTOR -> 4f
                DeviceRole.WEAPON -> 2f
                else -> 1.5f
            }
        }
    }

    /** Fliegt ein gegnerisches Geschoss in den nächsten Sekunden in die eigene Festung (Hüllrechteck + Rand)? */
    private fun incomingThreat(view: GameView): Boolean {
        val p = view.projectileView
        if (p.aliveCount == 0) return false
        val beams = view.beamView
        val nodes = view.nodeView
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j) || beams.owner(j) != playerId || (beams.flags(j) and BeamFlags.DEBRIS) != 0) continue
            val a = beams.nodeA(j); val b = beams.nodeB(j)
            minX = FloatMath.min(minX, FloatMath.min(nodes.x(a), nodes.x(b)))
            maxX = FloatMath.max(maxX, FloatMath.max(nodes.x(a), nodes.x(b)))
            minY = FloatMath.min(minY, FloatMath.min(nodes.y(a), nodes.y(b)))
            maxY = FloatMath.max(maxY, FloatMath.max(nodes.y(a), nodes.y(b)))
        }
        if (minX > maxX) return false
        minX -= THREAT_MARGIN; maxX += THREAT_MARGIN; minY -= THREAT_MARGIN; maxY += THREAT_MARGIN
        val cfg = view.simConfig
        val weapons = view.tables.weapons
        for (i in 0 until p.size) {
            if (!p.isAlive(i) || p.owner(i) == playerId) continue
            val k = p.kind(i)
            val gs = if (k in weapons.indices) weapons[k].gravityScale else 1f
            sim[Ballistics.X] = p.x(i); sim[Ballistics.Y] = p.y(i); sim[Ballistics.VX] = p.vx(i); sim[Ballistics.VY] = p.vy(i)
            for (t in 0 until THREAT_LOOKAHEAD_TICKS) {
                val x = sim[Ballistics.X]; val y = sim[Ballistics.Y]
                if (x in minX..maxX && y in minY..maxY) return true
                if (y > maxY + 10f) break
                Ballistics.tick(sim, view.wind, cfg, gs)
            }
        }
        return false
    }

    private fun doorStillNeeded(ref: Long, tick: Long): Boolean {
        for (i in pinnedRefs.indices) if (pinnedRefs[i] == ref) return pinnedUntil[i] > tick
        return false
    }

    private fun onDoorToggled(view: GameView, tick: Long, ref: Long) {
        for (i in pinnedRefs.indices.reversed()) if (pinnedRefs[i] == ref) { pinnedRefs.removeAt(i); pinnedUntil.removeAt(i) }
        // Liste klein halten: veraltete Refs entfernen
        for (i in pinnedRefs.indices.reversed()) {
            if (view.beamView.resolve(pinnedRefs[i]) < 0 || pinnedUntil[i] + DOOR_HOLD_TICKS < tick) { pinnedRefs.removeAt(i); pinnedUntil.removeAt(i) }
        }
    }

    companion object {
        private const val KIND_FIRE = 1
        private const val KIND_BUILD = 2
        private const val KIND_REBUILD = 3
        private const val KIND_REPAIR = 4
        private const val KIND_EXTINGUISH = 5
        private const val KIND_DOOR = 6
        private const val KIND_DOOR_OPEN = 7
        private const val KIND_SURPLUS = 8
        private const val KIND_SURPLUS_TURBINE = 9
        private const val KEY_SURPLUS = Long.MIN_VALUE + 2
        private const val KEY_BUILD = Long.MIN_VALUE
        private const val NO_KEY = Long.MIN_VALUE + 1
        /** Schlüssel-Räume der Aktionen: Geräte-Refs, Balken-Refs, Bauvorlagen-Schlüssel. */
        private const val POOL_DEVICE = 1
        private const val POOL_BEAM = 2
        private const val POOL_PLAN = 3

        const val SCORE_DOOR_CLOSE: Float = 85f

        /**
         * Bewertung, eine offene eigene Tür zu schließen, oder NaN (offen lassen).
         * @param threat ein gegnerisches Geschoss fliegt auf die Festung zu.
         * @param pinned die Tür ist fixiert offen (vom Spieler/der KI geöffnet).
         * @param neededForShot die KI hat sie für einen verdeckten Schuss geöffnet und braucht sie noch
         *   ([DOOR_HOLD_TICKS]); dann bleibt sie auch bei Beschuss offen, sonst träfe dieser Schuss die eigene Tür.
         */
        internal fun doorCloseScore(threat: Boolean, pinned: Boolean, neededForShot: Boolean): Float = when {
            neededForShot -> Float.NaN
            threat -> SCORE_DOOR_CLOSE
            pinned -> SCORE_DOOR_TIDY
            else -> Float.NaN
        }
        const val SCORE_FIRE: Float = 60f
        const val FIRE_WEIGHT: Float = 10f
        const val DOOR_OPEN_PENALTY: Float = 5f
        const val SCORE_BUILD_ECONOMY: Float = 55f
        const val SCORE_BUILD: Float = 34f
        /** Bauen, nachdem die Bau-Kandidaten im letzten Denkschritt verdrängt wurden (z. B. Paar braucht 2 Plätze). */
        const val SCORE_BUILD_URGENT: Float = 90f
        const val SCORE_EXTINGUISH: Float = 40f
        const val SCORE_REPAIR: Float = 30f
        const val SCORE_DOOR_TIDY: Float = 15f
        /** Wiederaufbau unter dieser Bewertung respektiert die Spar-Reserve. */
        const val REBUILD_RESERVE_SCORE: Float = 30f
        const val REBUILD_RESERVE_SCORE_WEAPON_MISSING: Float = 45f
        /** Schuss auf den Reaktor ab diesem Freiliegen (Splash reicht) darf immer an die Spar-Reserve. */
        const val REACTOR_BYPASS_EXPOSURE: Float = 0.6f
        /** Schuss auf eine Waffe ab diesem Freiliegen (Bahn trifft sie frei) darf an die Reserve, solange nicht zu lange gespart wird. */
        const val WEAPON_BYPASS_EXPOSURE: Float = 0.99f
        /** Jede Waffe darf die Reserve höchstens einmal in diesem Abstand für ein beliebiges Ziel durchbrechen. */
        const val FIRE_BYPASS_INTERVAL_TICKS: Long = 600L
        /** Spart der Plan länger ohne Fortschritt, wird gedrosselt (nur noch Reaktor-Schüsse an die Reserve). */
        const val SAVE_PATIENCE_TICKS: Long = 1200L
        const val MAX_REBUILD_CANDIDATES: Int = 3
        const val MAX_REPAIR_CANDIDATES: Int = 12
        /** Balken ab dieser Wichtigkeit (trägt Waffe/Reaktor) und unter [CRITICAL_REPAIR_FRACTION] TP werden auch gegen die Spar-Reserve repariert. */
        const val CRITICAL_IMPORTANCE: Float = 2f
        const val CRITICAL_REPAIR_FRACTION: Float = 0.5f
        /** Ab diesem Anteil des Metall-Lagers gilt die KI als "reich" ([SURPLUS_REPAIR_THRESHOLD]). */
        const val SURPLUS_FRACTION: Float = 0.6f
        const val SURPLUS_REPAIR_THRESHOLD: Float = 0.9f
        const val EXTINGUISH_MIN_FIRE: Float = 0.3f
        const val DOOR_HOLD_TICKS: Long = 600L
        const val THREAT_MARGIN: Float = 2.5f
        const val THREAT_LOOKAHEAD_TICKS: Int = 150
        const val TURN_MIN_THINKS: Int = 2
        /** Zusätzliche Waffe ab diesem Anteil des Metall-Lagers (Plan fertig, nichts fehlt). */
        const val SURPLUS_BUILD_FRACTION: Float = 0.75f
        /** Wie [SURPLUS_BUILD_FRACTION] während der Belagerung (nur Explosionswaffen). */
        const val SIEGE_SURPLUS_BUILD_FRACTION: Float = 0.45f
        const val SURPLUS_INTERVAL_TICKS: Long = 300L
        /** Höchstens so viele Waffen über die der Vorlage hinaus. */
        const val MAX_SURPLUS_WEAPONS: Int = 4
        /** Zusätzliche Turbine, wenn die Energie unter diesen Anteil des Lagers fällt (Schüsse kosten Energie). */
        const val SURPLUS_ENERGY_LOW: Float = 0.25f
        /** Zusätzliche Turbine schon ab diesem Anteil des Metall-Lagers (eine Turbine kostet wenig). */
        const val SURPLUS_TURBINE_FRACTION: Float = 0.25f
        const val MAX_SURPLUS_TURBINES: Int = 3
        const val SCORE_SURPLUS: Float = 33f
        private val SURPLUS_T = floatArrayOf(0.5f, 0.35f, 0.65f, 0.2f, 0.8f)
        /** Normale muss so weit nach oben zeigen (Sim: y nach unten). */
        private const val SURPLUS_MIN_UP = -0.8f
        private const val SURPLUS_CLEAR_UP = 4f
        private const val SURPLUS_CLEAR_FWD = 6f
        private const val SURPLUS_CLEAR_RISE = 4f
        private const val SURPLUS_CLEAR_RADIUS = 0.3f
        private const val SURPLUS_FORWARD_WEIGHT = 0.1f
        private const val STEEP_MIN_ELEVATION = 0.3f
        /** Nachfeuern ohne neues Richten, wenn die stehende Bahn mindestens so gut liegt (Splash in Zielnähe). */
        const val REFIRE_MIN_EXPOSURE: Float = 0.5f
        const val MAX_REFIRES_PER_THINK: Int = 2
    }
}
