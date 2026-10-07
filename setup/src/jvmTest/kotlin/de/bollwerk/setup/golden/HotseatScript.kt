package de.bollwerk.setup.golden

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.view.GameView

/**
 * Beide Hotseat-Spieler in einem Skript (Schlucht, Zugmodus): je Zug bauen und/oder schießen, dann `EndTurn`; Spieler 1
 * gibt im vierten Zug auf. Deckt den Befehl `EndTurn` ab, den es nur im Zugmodus gibt. Je Abfrage (alle 10 Ticks) ein Schritt.
 */
class HotseatScript(private val db: ContentDb) : CommandSource {
    private var turn = 0
    private var step = 0

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        if (tick % 10L != 0L || tick == 0L) return emptyList()
        val t = view.turn
        if (t.phase != TurnPhase.PLAY || view.result != de.bollwerk.engine.sim.GameResult.Ongoing) return emptyList()
        val p = t.activePlayer
        if (t.turnNumber != turn) { turn = t.turnNumber; step = 0 }
        val cmd = next(tick, view, p) ?: return emptyList()
        step++
        return cmd
    }

    private fun next(tick: Long, v: GameView, p: Int): List<Command>? {
        fun x(world: Float) = if (p == 0) world else 120f - world
        return when (turn to step) {
            // Zug 1 (Spieler 0) und Zug 2 (Spieler 1): Bodenbalken bauen, mit dem Mörser schießen, Zug beenden
            1 to 0, 2 to 0 -> listOf(
                Command.PlaceBeam(tick, p, aX = x(18f), aY = 34f, bX = x(21f), bY = 34f, materialId = db.materialIndex("wood")),
            )
            1 to 1, 2 to 1 -> shoot(tick, v, p, "mortar", if (p == 0) 97.5f else 22.5f, 29f, false)
            1 to 2, 2 to 2, 3 to 1 -> listOf(Command.EndTurn(tick, p))
            // Zug 3 (Spieler 0): Kanone
            3 to 0 -> shoot(tick, v, p, "cannon", 84f, 30f, false)
            // Zug 4 (Spieler 1): Aufgabe
            4 to 0 -> listOf(Command.Surrender(tick, p))
            else -> null
        }
    }

    private fun shoot(tick: Long, v: GameView, p: Int, device: String, tx: Float, ty: Float, high: Boolean): List<Command>? {
        val dev = Aim.deviceOf(v, p, db.deviceIndex(device))
        if (dev < 0) return null
        val sol = Aim.solve(v, dev, tx, ty, high) ?: return null
        val ref = v.deviceView.ref(dev)
        val fire = Command.Fire(tick, p, ref)
        if (RulesValidator.validate(v, fire) != null) return null
        return listOf(Command.SetAim(tick, p, ref, sol.angle, sol.power), fire)
    }
}
