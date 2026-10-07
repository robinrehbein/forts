package de.bollwerk.simrunner

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.GameInfo
import kotlin.system.exitProcess

/** Exit-Codes: 0 ok, 1 Prüfung fehlgeschlagen (`--assert-stable`), 2 Aufruf-/Szenariofehler, 3 interner Fehler (Stacktrace auf stderr). */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    exitProcess(execute(args))
}

/** Testbarer Einstieg: gibt den Exit-Code zurück. */
fun execute(args: Array<String>, out: (String) -> Unit = ::println, err: (String) -> Unit = System.err::println): Int {
    val a = try {
        SimArgs.parse(args)
    } catch (e: IllegalArgumentException) {
        err("error: ${e.message}")
        err(SimArgs.USAGE)
        return 2
    }
    if (a.help) { out(SimArgs.USAGE); return 0 }

    val scenario = try {
        a.scenario?.let { ScenarioIo.load(it) }
    } catch (e: Exception) {
        err("error: ${e.message}")
        return 2
    }
    val plan = RunPlan.of(a, scenario)
    val render = a.renderPng?.let { RenderPlan(it, a.renderAt, a.width, a.height, a.warmup, a.view?.toFloatArray()) }
    a.renderAt.firstOrNull { it > plan.ticks }?.let { err("error: --at $it is beyond the run length (${plan.ticks} ticks)"); return 2 }

    val db: de.bollwerk.content.ContentDb
    val result = try {
        db = ClasspathContent.load()
        Runner.run(db, plan, render)
    } catch (e: IllegalArgumentException) { // z. B. unbekannte Karte
        err("error: ${e.message}")
        return 2
    } catch (e: Throwable) {
        err("INTERNAL ERROR: ${e::class.simpleName}: ${e.message}")
        err(e.stackTraceToString())
        return 3
    }
    out("${GameInfo.TITLE} simrunner | content ${db.version} (fingerprint ${de.bollwerk.engine.loop.StateHash.hex(db.fingerprint)})")
    out(Report.format(result, a))
    if (a.assertStable && result.breaks.isNotEmpty()) {
        err("ASSERT-STABLE FAILED: ${result.breaks.size} beam break(s)")
        return 1
    }
    if (a.assertStable) out("assert-stable: OK (0 beam breaks)")
    if (plan.idle && result.breaks.isNotEmpty()) {
        err("IDLE SCENARIO UNSTABLE: ${result.breaks.size} beam break(s)")
        return 1
    }
    return 0
}
