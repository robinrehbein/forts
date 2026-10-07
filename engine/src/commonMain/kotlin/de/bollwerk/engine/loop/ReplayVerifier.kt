package de.bollwerk.engine.loop

import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult

/** Ergebnis von [ReplayVerifier.verify]. */
sealed class ReplayVerification {
    abstract val ok: Boolean

    /** Alle Prüfpunkte stimmen; [finalHash] = StateHash nach [ticks] Ticks. */
    data class Ok(val ticks: Long, val checkpointsChecked: Int, val finalHash: Long) : ReplayVerification() {
        override val ok: Boolean get() = true
    }

    /** Replay passt nicht zu diesem Build (Format, Engine-Version, Content-Fingerabdruck). */
    data class Incompatible(val reason: String) : ReplayVerification() {
        override val ok: Boolean get() = false
    }

    /**
     * Die Wiedergabe weicht ab. [tick] = erster Tick, an dem die Abweichung **festgestellt** wurde (bei [Kind.HASH]:
     * der Prüfpunkt; die Ursache liegt in `(lastGoodTick, tick]`; bei [Kind.REJECTED]: exakt der Tick des Commands).
     */
    data class Diverged(
        val kind: Kind,
        val tick: Long,
        val lastGoodTick: Long,
        val expected: Long,
        val actual: Long,
        val command: Command? = null,
        val detail: String = "",
    ) : ReplayVerification() {
        override val ok: Boolean get() = false
    }

    enum class Kind {
        /** StateHash am Prüfpunkt anders als aufgezeichnet. */
        HASH,
        /** Ein aufgezeichnet angenommenes Command wurde abgelehnt (früheste und genaueste Abweichung). */
        REJECTED,
        /** Die Session konnte nicht weiter simulieren (Quelle nicht bereit). */
        STALLED,
        /**
         * Das Replay ist unvollständig oder verfälscht: keine Prüfpunkte, Prüfpunkte hinter `ticks` (gekürztes `ticks`-Feld)
         * oder kein Prüfpunkt am Ende (`ticks`). Ohne diese Prüfung gälte ein Replay als „ok“, obwohl nichts verglichen wurde.
         */
        INCOMPLETE,
    }

    /** Lesbare Meldung für Testausgaben und Logs. */
    fun message(): String = when (this) {
        is Ok -> "replay ok: $ticks ticks, $checkpointsChecked checkpoints, final hash ${StateHash.hex(finalHash)}"
        is Incompatible -> "replay incompatible: $reason"
        is Diverged -> when (kind) {
            Kind.HASH -> "replay diverged at tick $tick (cause in ticks ${lastGoodTick + 1}..$tick): " +
                "expected hash ${StateHash.hex(expected)}, got ${StateHash.hex(actual)}"
            Kind.REJECTED -> "replay diverged at tick $tick: recorded command was rejected on playback ($detail): $command"
            Kind.STALLED -> "replay stalled at tick $tick (a command source was not ready)"
            Kind.INCOMPLETE -> "replay incomplete at tick $tick: $detail"
        }
    }
}

/**
 * Spielt ein [Replay] gegen eine frisch angelegte Session ab und prüft die Hash-Prüfpunkte.
 *
 * Die Session muss aus **demselben Anfangszustand** stammen wie die Aufnahme (Setup, Config, Content, ggf. Einschwingen:
 * `MatchBootstrap.sessionForReplay` bzw. dessen Entsprechung in Tests) und darf keine eigenen Command-Quellen haben;
 * der Verifier hängt die aufgezeichneten Commands als [ScriptedCommandSource] an.
 */
object ReplayVerifier {
    /**
     * @param contentHash Fingerabdruck des laufenden Contents (`ContentDb.fingerprint`).
     * @param sessionFactory legt die Startsession an; wird erst nach der Kompatibilitätsprüfung aufgerufen.
     */
    fun verify(
        replay: Replay,
        contentHash: Long,
        engineVersion: Int = GameInfo.ENGINE_VERSION,
        sessionFactory: () -> GameSession,
    ): ReplayVerification {
        replay.incompatibility(contentHash, engineVersion)?.let { return ReplayVerification.Incompatible(it) }
        return verify(replay, sessionFactory())
    }

    /** Wie oben, ohne Kompatibilitätsprüfung (die Session ist bereits angelegt). */
    fun verify(replay: Replay, session: GameSession): ReplayVerification {
        session.addSource(ScriptedCommandSource(replay.commands))
        var rejected: ReplayVerification.Diverged? = null
        val previous = session.recorder
        session.recorder = CommandRecorder { tick, cmd, result ->
            previous?.onCommand(tick, cmd, result)
            if (rejected == null && result is CommandResult.Rejected) {
                rejected = ReplayVerification.Diverged(
                    ReplayVerification.Kind.REJECTED, tick, lastGoodTick = tick - 1, expected = 0L, actual = 0L,
                    command = cmd, detail = result.reason.name,
                )
            }
        }
        val marks = replay.checkpoints
        var next = 0
        var checked = 0
        var lastGood = -1L
        val state = session.state

        fun check(): ReplayVerification.Diverged? {
            while (next < marks.size && marks[next].tick < state.tick) next++
            if (next < marks.size && marks[next].tick == state.tick) {
                val want = marks[next++].hash
                val got = session.hash()
                if (want != got) {
                    return ReplayVerification.Diverged(ReplayVerification.Kind.HASH, state.tick, lastGood, want, got)
                }
                checked++
                lastGood = state.tick
            }
            return null
        }

        check()?.let { return it }
        while (state.tick < replay.ticks) {
            if (!session.tick()) {
                return ReplayVerification.Diverged(ReplayVerification.Kind.STALLED, state.tick, lastGood, 0L, 0L)
            }
            rejected?.let { return it }
            check()?.let { return it }
        }
        // Alle Prüfpunkte müssen verglichen worden sein, und der letzte muss am Ende liegen
        if (marks.isEmpty()) return incomplete(state.tick, lastGood, "no checkpoints recorded")
        if (next < marks.size) {
            return incomplete(
                state.tick, lastGood,
                "ticks=${replay.ticks} ends before checkpoint at tick ${marks[next].tick} (${marks.size - next} of ${marks.size} unchecked)",
            )
        }
        if (marks[marks.size - 1].tick != replay.ticks) {
            return incomplete(state.tick, lastGood, "no checkpoint at the final tick ${replay.ticks} (last is ${marks[marks.size - 1].tick})")
        }
        return ReplayVerification.Ok(state.tick, checked, session.hash())
    }

    private fun incomplete(tick: Long, lastGood: Long, detail: String) =
        ReplayVerification.Diverged(ReplayVerification.Kind.INCOMPLETE, tick, lastGood, 0L, 0L, detail = detail)

    /**
     * Erster Tick, an dem zwei Prüfpunktlisten (z. B. zwei Läufe desselben Setups) auseinanderlaufen, oder null.
     * Fehlende Prüfpunkte einer Seite (unterschiedliche Länge) zählen als Abweichung am ersten fehlenden Tick.
     */
    fun firstDifference(a: List<HashCheckpoint>, b: List<HashCheckpoint>): Long? {
        val n = if (a.size < b.size) a.size else b.size
        for (i in 0 until n) if (a[i] != b[i]) return if (a[i].tick < b[i].tick) a[i].tick else b[i].tick
        if (a.size == b.size) return null
        return if (a.size > n) a[n].tick else b[n].tick
    }
}
