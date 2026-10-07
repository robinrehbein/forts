package de.bollwerk.simrunner

import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.sim.GameResult
import java.util.Locale

/** Textausgabe der Ergebnisse (knapp, für Terminal und CI). */
object Report {
    private fun f(fmt: String, vararg a: Any?): String = String.format(Locale.ROOT, fmt, *a)

    fun format(r: RunResult, args: SimArgs): String = buildString {
        val p = r.plan
        appendLine("== simrunner: ${p.name} | map ${p.map} | seed ${p.seed} | ${r.ticksRun}/${p.ticks} ticks (${f("%.1f", r.ticksRun / 60.0)} s sim) ==")
        if (args.hash) {
            for (h in r.hashes) appendLine("hash tick=${h.tick} ${StateHash.hex(h.hash)}")
        }
        appendLine("final hash: ${StateHash.hex(r.finalHash)}")
        appendLine("result: ${describe(r.result)}")
        appendLine("beams: ${r.beamsStart} -> ${r.beamsEnd} alive (peak ${r.peakBeams}) | nodes: ${r.nodesEnd} | devices: ${r.devicesStart} -> ${r.devicesEnd} | device losses: ${r.deviceLosses}")
        appendLine("combat: shots=${r.shots} explosions=${r.explosions} peakProjectiles=${r.peakProjectiles} peakBurningBeams=${r.peakBurning}")
        if (r.impacts > 0) {
            appendLine(
                f(
                    "hits: %d/%d impacts on beams/devices (%.0f %%); by shooter: enemy %d (%.0f %% of impacts), own fort %d, debris %d",
                    r.structureHits, r.impacts, 100.0 * r.structureHits / r.impacts, r.enemyHits, 100.0 * r.enemyHits / r.impacts,
                    r.ownHits, r.debrisHits,
                ),
            )
        }
        if (r.explosionLog.isNotEmpty()) {
            appendLine("first explosions: " + r.explosionLog.joinToString("; ") { f("t%d (%.1f, %.1f) r%.1f", it[0].toInt(), it[1], it[2], it[3]) })
        }
        val byCause = r.breaks.groupingBy { it.cause }.eachCount().entries.sortedBy { it.key.ordinal }
        appendLine("beam breaks: ${r.breaks.size}" + if (byCause.isEmpty()) "" else " (" + byCause.joinToString { "${it.key}=${it.value}" } + ")")
        for (b in r.breaks.take(MAX_LISTED)) {
            appendLine(f("  tick %d: beam uid %d (%s) at (%.1f, %.1f) cause %s", b.tick, b.beamUid, b.material, b.x, b.y, b.cause))
        }
        if (r.breaks.size > MAX_LISTED) appendLine("  ... ${r.breaks.size - MAX_LISTED} more")
        if (r.agents.isNotEmpty()) appendLine("ai agents: ${r.agents.joinToString()}")
        r.profile?.let { appendLine(profile(it)) }
        for (w in r.warnings) appendLine("warning: $w")
        for (file in r.rendered) appendLine("png: ${file.path}")
        appendLine(f("wall time: %.0f ms (%.3f ms/tick incl. setup)", r.wallMs, if (r.ticksRun > 0) r.wallMs / r.ticksRun else 0.0))
    }.trimEnd()

    fun profile(stats: List<SlotStat>): String = buildString {
        appendLine("profile (ms/tick):")
        appendLine(f("  %-16s %9s %9s %9s", "slot", "avg", "p95", "max"))
        for (s in stats) appendLine(f("  %-16s %9.4f %9.4f %9.4f", s.label, s.avgMs, s.p95Ms, s.maxMs))
    }.trimEnd()

    fun describe(r: GameResult): String = when (r) {
        GameResult.Ongoing -> "ongoing"
        GameResult.Draw -> "draw"
        is GameResult.Winner -> "player ${r.playerId} wins (${r.reason})"
    }

    private const val MAX_LISTED = 20
}
