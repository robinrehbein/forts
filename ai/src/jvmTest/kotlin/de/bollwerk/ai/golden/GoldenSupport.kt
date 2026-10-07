package de.bollwerk.ai.golden

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.loop.ReplayVerification
import de.bollwerk.engine.loop.ReplayVerifier
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.setup.MatchRunners
import java.io.File
import kotlin.test.fail

/**
 * Gemeinsame Werkzeuge der Golden-Replays eines Moduls (Kopie der Klasse aus `:setup`, weil Testquellen modulübergreifend nicht geteilt werden;
 * Änderungen an beiden Kopien gemeinsam vornehmen).
 *
 * Ein Golden ist ein eingechecktes [Replay] (Setup, Commands, Hash-Prüfpunkt alle 60 Ticks), aufgezeichnet mit dem
 * **echten** Content ([de.bollwerk.content.ClasspathContent]) über [MatchRunners.create]. Zwei Prüfungen je Golden:
 *
 * 1. [verifyStored]: die Aufnahme spielt über [MatchRunners.verify] bitgleich ab (fängt Änderungen an Sim, Regeln, Content).
 * 2. [reRecordMatches]: erneutes Aufzeichnen (Skript bzw. KI) ergibt dieselbe Datei (fängt Änderungen am Verhalten der
 *    Skripte/der KI, die ein Replay allein nicht bemerkt, weil es nur Commands abspielt).
 *
 * **Regenerieren** (nur nach absichtlicher Verhaltensänderung; bei Sim-/Regeländerung vorher `GameInfo.ENGINE_VERSION` erhöhen):
 * ```
 * BOLLWERK_REGEN_GOLDENS=1 ./gradlew :<modul>:jvmTest --tests '*Golden*' --rerun
 * git diff <modul>/src/jvmTest/resources/goldens
 * ```
 * (`-Dbollwerk.regenGoldens=true` an die Test-JVM wirkt ebenso, z. B. in der IDE; Gradle reicht `-D` der Kommandozeile
 * nicht an den Test-Prozess durch, deshalb ist die Umgebungsvariable der Standardweg.)
 */
class GoldenSupport(private val module: String, private val db: ContentDb) {
    val regen: Boolean =
        System.getenv("BOLLWERK_REGEN_GOLDENS")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true ||
            System.getProperty("bollwerk.regenGoldens") == "true"

    /** Kommando zum Neuerzeugen (für Fehlermeldungen). */
    val regenCommand: String =
        "BOLLWERK_REGEN_GOLDENS=1 ./gradlew :$module:jvmTest --tests '*Golden*' --rerun"

    private fun hint(extra: String = ""): String =
        "${extra}If this change is intentional: bump GameInfo.ENGINE_VERSION (currently ${GameInfo.ENGINE_VERSION}) when sim behaviour changed, " +
            "regenerate goldens with `$regenCommand`, review `git diff $module/src/jvmTest/resources/goldens` and commit. " +
            "If it is NOT intentional: this is a determinism or behaviour regression, fix the code instead."

    /** Datei im Quellbaum (Arbeitsverzeichnis des Test-Tasks ist das Modulverzeichnis; aus der Repo-Wurzel geht es auch). */
    fun sourceFile(name: String): File {
        val rel = "src/jvmTest/resources/goldens/$name.replay.json"
        return if (File("src/jvmTest").isDirectory) File(rel) else File("$module/$rel")
    }

    fun load(name: String): Replay {
        val text = GoldenSupport::class.java.getResourceAsStream("/goldens/$name.replay.json")?.use { it.readBytes().decodeToString() }
            ?: fail("golden replay '$name' is missing (resource /goldens/$name.replay.json). Create it with `$regenCommand`.")
        return Replay.fromJson(text)
    }

    /**
     * Schreibt [fresh] in den Quellbaum, nachdem [coverage] (die Inhaltsprüfungen des Goldens, z. B. "alle Waffen feuern",
     * "Reaktor fällt") und die Selbstwiedergabe bestanden haben. Ein Golden, das nicht hält, was sein Name sagt, wird nicht geschrieben.
     */
    fun regenerate(name: String, fresh: Replay, coverage: (Replay) -> Unit) {
        val v = MatchRunners.verify(db, Replay.fromJson(fresh.toJson()))
        if (v !is ReplayVerification.Ok) fail("freshly recorded golden '$name' does not replay: ${v.message()}")
        try {
            coverage(fresh)
        } catch (e: AssertionError) {
            throw AssertionError("freshly recorded golden '$name' is not written because it does not cover what it claims: ${e.message}", e)
        }
        val f = sourceFile(name)
        f.parentFile.mkdirs()
        f.writeText(fresh.toJson(pretty = true) + "\n")
        println(
            "GOLDEN_REGEN: wrote ${f.absolutePath} (${fresh.commands.size} commands, ${fresh.ticks} ticks, " +
                "${fresh.checkpoints.size} checkpoints, final hash ${StateHash.hex(fresh.checkpoints.last().hash)})",
        )
    }

    /** Prüfung 1: die eingecheckte Aufnahme muss mit diesem Build bitgleich abspielbar sein. */
    fun verifyStored(name: String, expectedSetup: MatchSetup): Replay {
        val replay = load(name)
        if (replay.setup != expectedSetup) {
            fail("golden '$name' was recorded for another setup (${replay.setup}) than the test expects ($expectedSetup). Regenerate goldens: `$regenCommand`")
        }
        verifyReplay(name, replay)
        return replay
    }

    /** Spielt [replay] über [MatchRunners.verify] ab und scheitert mit einer erklärenden Meldung (siehe [verifyStored]). */
    fun verifyReplay(name: String, replay: Replay) {
        checkCheckpointGrid(name, replay)
        when (val v = MatchRunners.verify(db, replay)) {
            is ReplayVerification.Ok -> {
                if (v.ticks != replay.ticks) fail("golden '$name': verified ${v.ticks} ticks but the file says ${replay.ticks}")
                if (v.finalHash != replay.checkpoints.last().hash) fail("golden '$name': final hash mismatch")
            }
            is ReplayVerification.Incompatible -> fail(
                "Golden replay '$name' is not compatible with this build (${v.reason}). " +
                    "Content fingerprint or ENGINE_VERSION changed: regenerate goldens with `$regenCommand`, review the diff and commit.",
            )
            is ReplayVerification.Diverged -> fail(
                "Golden replay '$name' failed: ${v.message()}.\n" +
                    "First diverging tick: ${v.tick}" + (if (v.kind == ReplayVerification.Kind.HASH) " (cause in ticks ${v.lastGoodTick + 1}..${v.tick})" else "") + ".\n" +
                    hint(),
            )
        }
    }

    /** Prüfung 2: erneutes Aufzeichnen ([fresh], mit Skript bzw. KI) ergibt genau die eingecheckte Datei. */
    fun reRecordMatches(name: String, fresh: Replay, what: String) {
        val stored = load(name)
        val cp = ReplayVerifier.firstDifference(stored.checkpoints, fresh.checkpoints)
        val n = minOf(stored.commands.size, fresh.commands.size)
        var firstCmd = -1
        for (i in 0 until n) if (stored.commands[i] != fresh.commands[i]) { firstCmd = i; break }
        if (firstCmd < 0 && stored.commands.size != fresh.commands.size) firstCmd = n
        if (cp != null || firstCmd >= 0 || stored.ticks != fresh.ticks) {
            val cmdInfo = if (firstCmd < 0) "commands identical" else
                "first differing command #$firstCmd: stored=${stored.commands.getOrNull(firstCmd)} fresh=${fresh.commands.getOrNull(firstCmd)}"
            fail(
                "Re-recording golden '$name' ($what) no longer reproduces the stored file: " +
                    "first diverging checkpoint tick=${cp ?: "none"}, ticks stored=${stored.ticks} fresh=${fresh.ticks}, $cmdInfo.\n" +
                    hint("The behaviour of the $what changed. "),
            )
        }
        if (stored.toJson(pretty = true) != fresh.toJson(pretty = true)) fail("golden '$name' differs in metadata from a fresh recording. ${hint()}")
    }

    /** Hash-Prüfpunkte liegen auf dem 60-Tick-Raster (Tick 0, 60, 120 …) plus ein Prüfpunkt am Ende. */
    private fun checkCheckpointGrid(name: String, replay: Replay) {
        val interval = Replay.CHECKPOINT_INTERVAL.toLong()
        val ticks = replay.checkpoints.map { it.tick }
        val expected = ArrayList<Long>()
        var t = 0L
        while (t <= replay.ticks) { expected.add(t); t += interval }
        if (expected.last() != replay.ticks) expected.add(replay.ticks)
        if (ticks != expected) fail("golden '$name': checkpoints are not on the $interval-tick grid (have ${ticks.size}, expected ${expected.size}). Regenerate goldens: `$regenCommand`")
    }
}
