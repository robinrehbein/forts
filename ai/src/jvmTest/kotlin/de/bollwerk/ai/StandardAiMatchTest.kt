package de.bollwerk.ai

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.loop.CommandRecorder
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.setup.MatchBootstrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ganze Partien mit der Standard-KI auf echtem Content (deterministisch, headless). */
class StandardAiMatchTest {
    private val twoMinutes = 120L * 60L

    @Test
    fun normalAiBuildsBeamsAndAWeaponWithinTwoMinutes() {
        for (d in listOf(Difficulty.NORMAL, Difficulty.HARD)) {
            val m = AiMatch(seed = 7L, difficulties = listOf(d, null))
            val beams0 = m.ownBeams(0)
            val weapons0 = m.ownDevicesOfRole(0, DeviceRole.WEAPON)
            val tech0 = m.ownDevicesOfRole(0, DeviceRole.TECH)
            m.run(twoMinutes)
            val ai = m.ais[0]!!
            assertTrue(m.ownBeams(0) >= beams0 + MIN_NEW_BEAMS, "$d: beams ${m.ownBeams(0)} (start $beams0) ${ai.stats}")
            // HARD zerlegt die passive Festung oft schon vor der Waffen-Stufe (dann endet die Partie vorher)
            val wonEarly = (m.state.result as? GameResult.Winner)?.playerId == 0
            assertTrue(m.ownDevicesOfRole(0, DeviceRole.WEAPON) > weapons0 || wonEarly, "$d: no new weapon within 2 min, ${ai.stats}")
            assertTrue(m.ownDevicesOfRole(0, DeviceRole.TECH) >= tech0 + 2, "$d: tech buildings missing")
            assertTrue(m.ownDevicesOfRole(0, DeviceRole.TURBINE) >= 2, "$d: economy step (second turbine) missing")
            assertEquals(0, m.rejected[0], "$d: ${m.rejectReasons}")
        }
    }

    @Test
    fun easyAiBuildsItsSmallPlan() {
        val m = AiMatch(seed = 3L, difficulties = listOf(null, Difficulty.EASY))
        val beams1 = m.ownBeams(1)
        m.run(twoMinutes)
        assertTrue(m.ownBeams(1) >= beams1 + 3, "easy beams ${m.ownBeams(1)}")
        assertEquals(1, m.ownDevicesOfRole(1, DeviceRole.TECH), "easy builds the workshop")
        assertTrue(m.ais[1]!!.stats.shots > 0)
    }

    /**
     * Unter Beschuss (beide Seiten feuern in jedem Denkschritt, wenn eine Waffe bereit ist) kommt der Bauplan voran:
     * Früher blieb er bei "workshop" stehen, weil Schüsse und Reparaturen die Spar-Reserve ignorierten, und verdrängte
     * Bau-Kandidaten zählten als Fehlversuch (→ Schritt übersprungen, ganze Tech-Kette gesperrt).
     */
    @Test
    fun buildPlanReachesWeaponsStepUnderFireInAiVsAi() {
        var sides = 0
        var reached = 0
        var blocked = 0
        var held = 0
        for (g in nn) {
            for (p in 0..1) {
                val ai = g.match.ais[p]!!
                val pl = ai.buildPlanner!!
                assertEquals(0, pl.failedCount, "seed ${g.match.seed} p$p: plan items failed: ${pl.describeFailed()}")
                blocked += ai.stats.buildsBlocked
                held += ai.stats.shotsHeldForReserve
                sides++
                if (g.weaponsStepTick[p] in 0..WEAPONS_STEP_DEADLINE) reached++
            }
        }
        println("weapons step within ${WEAPONS_STEP_DEADLINE / 60} s: $reached/$sides sides, ${nn.map { it.weaponsStepTick.map { t -> t / 60 } }}; blocked thinks=$blocked, shots held for reserve=$held")
        // der Drop-Pfad (Kandidaten verdrängt) und das Sparen wurden tatsächlich durchlaufen
        assertTrue(blocked > 0 && held > 0, "build drops ($blocked) and reserve holds ($held) should occur under fire")
        assertTrue(reached >= MIN_SIDES_REACHING_WEAPONS, "only $reached of $sides sides finished the weapons step within ${WEAPONS_STEP_DEADLINE / 60} s")
    }

    /** Aufbau-Kandidaten, die die KI nicht ausgibt, zählen nicht als Versuch (sonst wird das Item fälschlich übersprungen). */
    @Test
    fun plannerCountsOnlyEmittedBuildsAsAttempts() {
        val m = AiMatch(seed = 2L, difficulties = listOf(null, null))
        val st = m.state
        st.players[0].metal = 1000f
        st.players[0].energy = 400f
        val plan = AiFactory.planFor(st.tables, Difficulty.NORMAL)!!
        val pl = BuildPlanner(0, plan)
        val out = ArrayList<BuildCandidate>()
        var firstKey = 0L
        for (k in 0 until 3 * BuildPlanner.MAX_ISSUES) {
            pl.refresh(st)
            out.clear()
            pl.build(st, k.toLong(), 3, 1000f, 1000f, out)
            assertTrue(out.isNotEmpty(), "plan proposes something")
            if (k == 0) firstKey = out[0].key
            assertEquals(firstKey, out[0].key, "a dropped candidate is proposed again")
        }
        assertEquals(0, pl.failedCount, "proposals that were never emitted must not count")
        // tatsächlich ausgegebene, aber nie entstandene Bauten zählen dagegen
        for (k in 0..BuildPlanner.MAX_ISSUES) pl.onBuildIssued(firstKey, 100L + k)
        assertEquals(1, pl.failedCount)
    }

    @Test
    fun validatorRejectsFewAiCommandsOnEveryDifficulty() {
        val total = IntArray(Difficulty.entries.size)
        val rejected = IntArray(Difficulty.entries.size)
        fun add(m: AiMatch) {
            for (p in m.ais.indices) {
                val d = m.ais[p]?.difficulty ?: continue
                total[d.ordinal] += m.accepted[p] + m.rejected[p]
                rejected[d.ordinal] += m.rejected[p]
            }
        }
        for (g in nn) add(g.match)
        for (g in ladder) add(g.match)
        for (d in Difficulty.entries) {
            assertTrue(total[d.ordinal] > 300, "$d should be busy: ${total[d.ordinal]} commands")
            val rate = rejected[d.ordinal].toFloat() / total[d.ordinal]
            println("$d: ${rejected[d.ordinal]} of ${total[d.ordinal]} commands rejected")
            assertTrue(rate <= MAX_REJECT_RATE, "$d rejection rate $rate (${rejected[d.ordinal]} of ${total[d.ordinal]})")
        }
    }

    @Test
    fun hardAiHitsStaticTargetFortWithinFewShots() {
        val m = AiMatch(seed = 11L, difficulties = listOf(Difficulty.HARD, null))
        m.run(90L * 60L)
        val shots = m.ballisticShots[0]
        assertTrue(shots >= HIT_WINDOW, "HARD fired only $shots ballistic shots")
        // passiver Gegner: jede Explosion stammt von Spieler 0; Treffer = Einschlag auf Balken/Gerät der rechten Festung
        val hits = m.explosions.filter { it.x > 60f && it.hitMaterialId != -1 }
        assertTrue(hits.isNotEmpty(), "no hit at all")
        val firstHitTick = hits.first().tick
        val shotsBeforeFirstHit = m.shotLog.count { it.first < firstHitTick }
        assertTrue(shotsBeforeFirstHit in 1..MAX_SHOTS_TO_HIT, "first hit after $shotsBeforeFirstHit shots")
        val rate = hits.size.toFloat() / m.explosions.size
        assertTrue(rate >= MIN_HARD_HIT_RATE, "HARD hit rate $rate (${hits.size}/${m.explosions.size})")
        println("HARD vs static: shots=$shots explosions=${m.explosions.size} hits=${hits.size} firstHitAfter=$shotsBeforeFirstHit")
    }

    /**
     * NORMAL gegen NORMAL auf schlucht endet in **jedem** der [NN_SEEDS] Seeds binnen 10 Sim-Minuten mit einem Sieger
     * (Abnahme: mindestens 3 Seeds). Früher blieben Spiegelpartien offen, in denen beide Seiten ihren Plan vollständig
     * bauten und sich nur die wieder aufgebauten Waffen abschossen; dagegen wirkt die Belagerung
     * ([TargetSelector.SIEGE_START_TICKS]: Graben zum Reaktor mit gebündeltem Nachfeuern, zusätzliche Explosionswaffen
     * und Turbinen aus dem Überschuss).
     */
    @Test
    fun normalVsNormalOnSchluchtEndsWithAWinner() {
        assertTrue(NN_SEEDS >= 3)
        val open = ArrayList<Long>()
        for (g in nn) {
            val r = g.match.state.result
            println("seed ${g.match.seed}: $r after ${g.ticks / 60} s, shots=${g.match.ballisticShots.toList()} peakBeams=${g.peakBeams} ${g.match.ais.map { it?.stats }}")
            if (r !is GameResult.Winner || g.ticks > TEN_MINUTES) open.add(g.match.seed)
            // Wiederaufbau darf nicht endlos Balken stapeln (früher: hängende Einzelbalken an neuen Knoten)
            assertTrue(g.peakBeams <= MAX_BEAMS_PER_PLAYER, "seed ${g.match.seed}: ${g.peakBeams} beams for one player")
        }
        assertTrue(open.isEmpty(), "NORMAL-vs-NORMAL games without a winner within 10 min: seeds $open")
        // die Belagerung greift erst spät: auch lange Partien enden (mindestens eine läuft über den Belagerungsbeginn)
        assertTrue(nn.any { it.ticks > TargetSelector.SIEGE_START_TICKS }, "no game reached the siege phase: ${nn.map { it.ticks / 60 }}")
    }

    @Test
    fun higherDifficultyWinsMostMatches() {
        var hardWins = 0
        var hardGames = 0
        var normalWinsVsEasy = 0
        var easyGames = 0
        for (g in ladder) {
            val r = g.match.state.result as? GameResult.Winner
            val winner = r?.let { g.match.ais[it.playerId]!!.difficulty }
            if (Difficulty.HARD in g.sides) { hardGames++; if (winner == Difficulty.HARD) hardWins++ } else { easyGames++; if (winner == Difficulty.NORMAL) normalWinsVsEasy++ }
        }
        println("HARD beat NORMAL $hardWins/$hardGames, NORMAL beat EASY $normalWinsVsEasy/$easyGames")
        assertTrue(hardWins >= 6, "HARD should beat NORMAL clearly: $hardWins/$hardGames")
        assertTrue(normalWinsVsEasy >= 3, "NORMAL should beat EASY: $normalWinsVsEasy/$easyGames")
    }

    @Test
    fun sameSeedGivesSameFinalHashAndDifferentSeedDiffers() {
        fun play(seed: Long): Long {
            val m = AiMatch(seed = seed, difficulties = listOf(Difficulty.NORMAL, Difficulty.HARD))
            m.run(DET_TICKS)
            assertTrue(m.ais.all { it!!.stats.commands > 0 })
            return StateHash.of(m.state)
        }
        val a = play(5L)
        assertEquals(a, play(5L))
        assertNotEquals(a, play(6L))
    }

    /**
     * Die KI wirkt nur über Commands: Die aufgezeichneten Commands ohne KI in eine frische Session gespielt ergeben
     * denselben StateHash (wichtig, weil `GameState` selbst die `GameView` ist, die die KI liest).
     */
    @Test
    fun recordedAiCommandsReplayWithoutTheAiToTheSameHash() {
        val m = AiMatch(seed = 5L, difficulties = listOf(Difficulty.NORMAL, Difficulty.HARD))
        m.run(REPLAY_TICKS)
        val expected = StateHash.of(m.state)
        assertTrue(m.acceptedCommands.size > 50, "AI should act: ${m.acceptedCommands.size} commands")
        assertTrue(m.acceptedCommands.any { it is Command.Fire } && m.acceptedCommands.any { it is Command.PlaceBeam })
        val replay = MatchBootstrap.createSession(AiMatch.db, m.setup, sources = listOf(ScriptedCommandSource(m.acceptedCommands)))
        var rejected = 0
        replay.recorder = CommandRecorder { _, _, r -> if (r is CommandResult.Rejected) rejected++ }
        assertEquals(REPLAY_TICKS, replay.run(REPLAY_TICKS))
        assertEquals(0, rejected, "replayed commands must all be accepted")
        assertEquals(expected, StateHash.of(replay.state), "replay without AI diverged")
    }

    @Test
    fun aiWithoutBlueprintOnlyFights() {
        val m = AiMatch(
            seed = 7L, difficulties = listOf(Difficulty.NORMAL, null),
            agentFactory = { p, d, t, s -> AiFactory.create(p, d, t, blueprints = null, seed = s) as StandardAi },
        )
        val beams0 = m.ownBeams(0)
        m.run(twoMinutes)
        val ai = m.ais[0]!!
        assertNull(ai.buildPlanner)
        assertEquals(0, ai.stats.builds + ai.stats.rebuilds + ai.stats.surplusWeapons, ai.stats.toString())
        assertTrue(m.acceptedCommands.none { it is Command.PlaceBeam || it is Command.PlaceDevice }, "fight-only AI must not build")
        assertTrue(m.ownBeams(0) <= beams0)
        assertTrue(ai.stats.shots > 0, "fight-only AI still fights")
    }

    /**
     * Eine für einen verdeckten Schuss geöffnete Tür wird bei anfliegenden Geschossen nicht gleich wieder geschlossen
     * (sonst träfe der im selben Denkschritt ausgegebene Schuss die eigene Tür und Öffnen/Schließen wechselten sich ab).
     */
    @Test
    fun doorOpenedForAShotStaysOpenUnderThreat() {
        assertTrue(StandardAi.doorCloseScore(threat = true, pinned = true, neededForShot = true).isNaN())
        assertTrue(StandardAi.doorCloseScore(threat = false, pinned = true, neededForShot = true).isNaN())
        assertEquals(StandardAi.SCORE_DOOR_CLOSE, StandardAi.doorCloseScore(threat = true, pinned = true, neededForShot = false))
        assertEquals(StandardAi.SCORE_DOOR_CLOSE, StandardAi.doorCloseScore(threat = true, pinned = false, neededForShot = false))
        assertEquals(StandardAi.SCORE_DOOR_TIDY, StandardAi.doorCloseScore(threat = false, pinned = true, neededForShot = false))
        assertTrue(StandardAi.doorCloseScore(threat = false, pinned = false, neededForShot = false).isNaN())
        // in echten Partien: keine eigene Öffnung wird vor Ablauf der Haltezeit wieder geschlossen
        for (g in nn + ladder) {
            val m = g.match
            val lastOpen = HashMap<Pair<Int, Long>, Long>()
            for (e in m.doorEvents) {
                val key = e.player to e.beamRef
                if (e.openAfter) { lastOpen[key] = e.tick; continue }
                val t0 = lastOpen.remove(key) ?: continue
                assertTrue(e.tick - t0 >= StandardAi.DOOR_HOLD_TICKS, "seed ${m.seed} p${e.player}: door opened at $t0 was closed again at ${e.tick}")
            }
        }
    }

    /** Rechenzeit je Denkschritt (läuft im Sim-Tick): Zähler statt Wanduhr, damit der Test nicht von der Maschine abhängt. */
    @Test
    fun thinkComputeStaysWithinBudget() {
        for (g in ladder.filter { it.sides.first() == Difficulty.HARD }) {
            for (ai in g.match.ais) {
                val a = ai!!.aimStats
                val thinks = ai.stats.thinks.toFloat()
                val full = a.fullSolves / thinks
                val local = a.localEvals / thinks
                val arcs = a.arcEvaluations / thinks
                println("seed ${g.match.seed} ${ai.difficulty}: full solves/think=$full local evals/think=$local arcs/think=$arcs")
                assertTrue(full <= MAX_FULL_SOLVES_PER_THINK, "${ai.difficulty}: $full full solves per think")
                assertTrue(local <= MAX_LOCAL_EVALS_PER_THINK, "${ai.difficulty}: $local local trajectory evaluations per think")
                assertTrue(arcs <= MAX_ARCS_PER_THINK, "${ai.difficulty}: $arcs (target, power, arc) evaluations per think")
            }
        }
    }

    @Test
    fun aiPlaysOnlyOnItsTurnInHotseatMode() {
        val m = AiMatch(seed = 4L, difficulties = listOf(Difficulty.HARD, Difficulty.HARD), turnMode = TurnMode.TURNS, turnTicks = 900)
        var maxTurn = 0
        m.run(4000L, stopAtResult = true) { maxTurn = maxOf(maxTurn, it.turn.turnNumber) }
        assertEquals(0, m.rejected.sum(), m.rejectReasons.toString())
        assertTrue(maxTurn >= 3, "turns should advance (AI ends its turn), reached $maxTurn")
        assertTrue(m.ais.all { it!!.stats.shots > 0 })
    }

    /** Eine ganze KI-Partie (bis zum Sieg oder [tenMinutes]). */
    class Game(val match: AiMatch, val sides: List<Difficulty>) {
        var ticks = 0L
        var peakBeams = 0
        /** Tick, an dem der Schritt "weapons" der Vorlage erledigt war (−1 = nie). */
        val weaponsStepTick = LongArray(2) { -1L }
    }

    private companion object {
        const val MIN_NEW_BEAMS = 8
        const val MAX_REJECT_RATE = 0.02f
        const val HIT_WINDOW = 6
        const val MAX_SHOTS_TO_HIT = 3
        const val MIN_HARD_HIT_RATE = 0.6f
        const val DET_TICKS = 3000L
        const val REPLAY_TICKS = 4000L
        /** Vorlage ai_normal hat 51 Balken; Bruchhälften und Wiederaufbau dürfen das nur mäßig übersteigen. */
        const val MAX_BEAMS_PER_PLAYER = 100
        const val TEN_MINUTES = 600L * 60L
        const val NN_SEEDS = 6
        const val WEAPONS_STEP_DEADLINE = 240L * 60L
        const val MIN_SIDES_REACHING_WEAPONS = 7
        /** Früher: jede bereite Waffe × bis zu 5 Ziele × 4 Kräfte × 2 Bögen × 3 volle Rastersuchen je Denkschritt. */
        const val MAX_FULL_SOLVES_PER_THINK = 4f
        const val MAX_LOCAL_EVALS_PER_THINK = 800f
        const val MAX_ARCS_PER_THINK = 25f

        fun play(seed: Long, a: Difficulty, b: Difficulty): Game {
            val g = Game(AiMatch(seed = seed, difficulties = listOf(a, b)), listOf(a, b))
            g.ticks = g.match.run(TEN_MINUTES) { st ->
                if (st.tick % 60L == 0L) g.peakBeams = maxOf(g.peakBeams, g.match.ownBeams(0), g.match.ownBeams(1))
                for (p in 0..1) {
                    if (g.weaponsStepTick[p] < 0 && g.match.ais[p]!!.buildPlanner?.stepDone("weapons") == true) g.weaponsStepTick[p] = st.tick
                }
            }
            return g
        }

        /** NORMAL gegen NORMAL auf schlucht, Seeds 1..[NN_SEEDS]; geteilt von mehreren Tests (Rechenzeit). */
        val nn: List<Game> by lazy { (1L..NN_SEEDS.toLong()).map { play(it, Difficulty.NORMAL, Difficulty.NORMAL) } }

        /** HARD gegen NORMAL (beide Seiten, damit sich Wind und Startfolge ausgleichen) und EASY gegen NORMAL, Seeds 1..4. */
        val ladder: List<Game> by lazy {
            val out = ArrayList<Game>()
            for (seed in 1L..4L) {
                out.add(play(seed, Difficulty.HARD, Difficulty.NORMAL))
                out.add(play(seed, Difficulty.NORMAL, Difficulty.HARD))
                out.add(play(seed, Difficulty.EASY, Difficulty.NORMAL))
            }
            out
        }
    }
}
