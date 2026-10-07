package de.bollwerk.setup.golden

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.view.GameView

/** Zielhilfe der Skripte: Zielwinkel (und Kraft) einer Waffe auf einen Weltpunkt, nur aus dem Zustand gelesen. */
object Aim {
    class Solution(val angle: Float, val power: Float)

    private val POWERS = floatArrayOf(0.9f, 1f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f)

    /** Flachschüsse mit mehr als dieser Elevation (Grad) gelten als Fehllösung (der Löser sucht erst ab 1°). */
    private const val MAX_LOW_ARC_DEG = 60f

    /** @return Winkel/Kraft oder null, wenn das Ziel unerreichbar ist. */
    fun solve(v: GameView, device: Int, tx: Float, ty: Float, highArc: Boolean): Solution? {
        val d = v.deviceView
        val props = v.tables.devices[d.type(device)]
        val w = v.tables.weapons[props.weapon]
        val geo = FloatArray(DeviceGeometry.SIZE)
        val side = (d.flags(device) and DeviceFlags.SIDE_NEG) != 0
        if (w.mode != WeaponMode.BALLISTIC) {
            RuleChecks.mountOn(v, d.beam(device), d.t(device), side, props, d.aimAngle(device), geo)
            return Solution(FastTrig.atan2(-(ty - geo[DeviceGeometry.PIVOT_Y]), tx - geo[DeviceGeometry.PIVOT_X]), 1f)
        }
        for (power in POWERS) {
            var a = d.aimAngle(device)
            var ok = true
            // die Mündung hängt vom Winkel ab: dreimal nachziehen
            for (i in 0 until 3) {
                RuleChecks.mountOn(v, d.beam(device), d.t(device), side, props, a, geo)
                val s = Ballistics.solveAngle(
                    geo[DeviceGeometry.MUZZLE_X], geo[DeviceGeometry.MUZZLE_Y], tx, ty, power, w, v.wind, v.simConfig, highArc,
                )
                if (s.isNaN()) { ok = false; break }
                a = s
            }
            if (!ok) continue
            val elevation = if (a > FloatMath.HALF_PI) FloatMath.PI - a else a
            if (!highArc && elevation > MAX_LOW_ARC_DEG * FloatMath.DEG_TO_RAD) continue
            return Solution(a, power)
        }
        return null
    }

    /** Erstes lebendes, fertig gebautes Gerät von Spieler [owner] mit Typ [type] (Slot) oder −1. */
    fun deviceOf(v: GameView, owner: Int, type: Int): Int {
        val d = v.deviceView
        for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == owner && d.type(i) == type) return i
        return -1
    }
}

/**
 * Skript von Spieler 0 für das Golden "alle Befehlsarten, alle Waffen" (Schlucht). Es liest nur den Zustand und baut Zug um
 * Zug über die **echten Regeln** auf: Bodenbalken, Balken-Teilung samt Zurück, Türen, Techgebäude (Waffenkammer →
 * Upgrade-Zentrum → Fabrik, mit echter Bauzeit), Waffendeck, Turbine (wird wieder abgerissen), Reparatur nach Beschuss, dann
 * Schuss jeder der sechs Waffen und zum Schluss die Aufgabe.
 *
 * Jeder Schritt prüft seinen Befehl vorab mit [RulesValidator] (dieselbe Wahrheit wie das Command-System) und wartet, bis er
 * gültig ist (z. B. genug Metall). Pro Abfrage (alle [POLL] Ticks) sendet höchstens ein Schritt, damit sich Befehle desselben
 * Ticks nie um Ressourcen streiten. Das Skript wird nur zum Aufzeichnen gebraucht; das Replay trägt die angenommenen Commands.
 */
class AllCommandsScript(private val db: ContentDb, private val me: Int = 0) : CommandSource {
    private val wood = db.materialIndex("wood")
    private val metal = db.materialIndex("metal")
    private val armoury = db.deviceIndex("armoury")
    private val upgradeCenter = db.deviceIndex("upgrade_center")
    private val factory = db.deviceIndex("factory")
    private val turbine = db.deviceIndex("turbine")
    private val techUpgrade = db.techIndex("upgrade_center")
    private val techArmoury = db.techIndex("armoury")
    private val techFactory = db.techIndex("factory")

    private abstract inner class Task(val name: String) {
        var done = false
        /** @return Commands (dann ist dieser Schritt in dieser Abfrage der Sender; leer = Abfrage anhalten, kein späterer Schritt sendet) oder null (nichts zu tun, nächster Schritt). */
        abstract fun poll(tick: Long, v: GameView): List<Command>?
    }

    // ---- Hilfen ----

    private fun ownNode(v: GameView, x: Float, y: Float, tol: Float = 0.3f): Int {
        val n = v.nodeView
        var best = -1
        var bestD = tol * tol
        for (i in 0 until n.size) {
            if (!n.isAlive(i) || n.owner(i) != me || (n.flags(i) and NodeFlags.DEBRIS) != 0) continue
            val dx = n.x(i) - x; val dy = n.y(i) - y
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD) { bestD = d2; best = i }
        }
        return best
    }

    private fun near(v: GameView, node: Int, x: Float, y: Float, tol: Float = 0.3f): Boolean {
        val dx = v.nodeView.x(node) - x; val dy = v.nodeView.y(node) - y
        return dx * dx + dy * dy <= tol * tol
    }

    /** Eigener lebender Balken zwischen zwei Weltpunkten (Reihenfolge egal) oder −1. */
    private fun beamBetween(v: GameView, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        val b = v.beamView
        for (i in 0 until b.size) {
            if (!b.isAlive(i) || b.owner(i) != me || (b.flags(i) and BeamFlags.DEBRIS) != 0) continue
            val a = b.nodeA(i); val c = b.nodeB(i)
            if ((near(v, a, x0, y0) && near(v, c, x1, y1)) || (near(v, a, x1, y1) && near(v, c, x0, y0))) return i
        }
        return -1
    }

    /** Eigener Balken, an dem ein Ende bei (x, y) liegt, oder −1. */
    private fun beamEndingAt(v: GameView, x: Float, y: Float): Int {
        val b = v.beamView
        for (i in 0 until b.size) {
            if (!b.isAlive(i) || b.owner(i) != me) continue
            if (near(v, b.nodeA(i), x, y) || near(v, b.nodeB(i), x, y)) return i
        }
        return -1
    }

    private fun beamCmd(tick: Long, v: GameView, x0: Float, y0: Float, x1: Float, y1: Float, mat: Int): Command.PlaceBeam {
        val a = ownNode(v, x0, y0)
        val b = ownNode(v, x1, y1)
        return Command.PlaceBeam(
            tick, me,
            aNodeRef = if (a >= 0) v.nodeView.ref(a) else -1L, aX = x0, aY = y0,
            bNodeRef = if (b >= 0) v.nodeView.ref(b) else -1L, bX = x1, bY = y1,
            materialId = mat,
        )
    }

    private fun valid(v: GameView, c: Command): Boolean = RulesValidator.validate(v, c) == null

    /** Eigene Balken (lebend, keine Trümmer, Holz/Metall) mit Filter, sortiert nach [key] (stabil: Slot-Reihenfolge). */
    private fun candidates(v: GameView, filter: (x0: Float, y0: Float, x1: Float, y1: Float) -> Boolean, key: (Float, Float) -> Float): List<Int> {
        val b = v.beamView
        val n = v.nodeView
        val out = ArrayList<Int>()
        for (i in 0 until b.size) {
            if (!b.isAlive(i) || b.owner(i) != me || (b.flags(i) and BeamFlags.DEBRIS) != 0) continue
            val m = b.material(i)
            if (m != wood && m != metal) continue
            val a = b.nodeA(i); val c = b.nodeB(i)
            if (filter(n.x(a), n.y(a), n.x(c), n.y(c))) out.add(i)
        }
        return out.sortedBy { i ->
            val a = b.nodeA(i); val c = b.nodeB(i)
            key((n.x(a) + n.x(c)) * 0.5f, (n.y(a) + n.y(c)) * 0.5f)
        }
    }

    /** Erste gültige Platzierung von [type] auf den [beams] (Reihenfolge: Balken, Parameter, Seite) oder null. */
    private fun findPlacement(tick: Long, v: GameView, type: Int, beams: List<Int>, ts: FloatArray): Command.PlaceDevice? {
        for (beam in beams) {
            for (t in ts) for (side in booleanArrayOf(true, false)) {
                val c = Command.PlaceDevice(tick, me, type, v.beamView.ref(beam), t, side)
                if (valid(v, c)) return c
            }
        }
        return null
    }

    private val groundSites: (GameView) -> List<Int> = { v ->
        // Bodenbalken der Erweiterung links der Startfestung (x <= 21,5), die nächsten zuerst
        candidates(v, { x0, y0, x1, y1 -> y0 > 33.5f && y1 > 33.5f && x0 < 21.5f && x1 < 21.5f }, { mx, _ -> -mx })
    }

    private val deckSites: (GameView) -> List<Int> = { v ->
        // Waffendeck über der Festung: Balken zwischen x 29,5..33,5 oberhalb y 25,5, oberste zuerst
        candidates(v, { x0, y0, x1, y1 -> y0 < 25.5f && y1 < 25.5f && x0 > 29.5f && x1 > 29.5f && x0 < 33.5f && x1 < 33.5f }, { _, my -> my })
    }

    private val anyTopSites: (GameView) -> List<Int> = { v -> candidates(v, { _, _, _, _ -> true }, { _, my -> my }) }

    private inner class PlaceDeviceTask(
        name: String, val type: Int, val sites: (GameView) -> List<Int>, val ts: FloatArray,
        val ready: (GameView) -> Boolean,
    ) : Task(name) {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (Aim.deviceOf(v, me, type) >= 0) { done = true; return null }
            if (!ready(v)) return null
            return findPlacement(tick, v, type, sites(v), ts)?.let { listOf(it) }
        }
    }

    // ---- Schritte (Reihenfolge = Priorität) ----

    private val groundRun = object : Task("groundRun") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            for (xi in intArrayOf(18, 15, 12, 9, 6, 3)) {
                val x0 = xi.toFloat()
                if (beamBetween(v, x0, 34f, x0 + 3f, 34f) >= 0) continue
                val c = beamCmd(tick, v, x0, 34f, x0 + 3f, 34f, wood)
                return if (valid(v, c)) listOf(c) else null
            }
            done = true
            return null
        }
    }

    private var splitTick = -1L
    /** PlaceBeam mit `aBeamRef` (Teilung) und gleich danach `Undo`: die Teilung wird wieder vereinigt. */
    private val splitAndUndo = object : Task("splitAndUndo") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!groundRun.done || tick < 120L) return null
            if (splitTick < 0L) {
                val b = beamBetween(v, 3f, 34f, 6f, 34f)
                if (b < 0) return null
                val c = Command.PlaceBeam(tick, me, aBeamRef = v.beamView.ref(b), aBeamT = 0.5f, bX = 4.5f, bY = 29.5f, materialId = wood)
                if (!valid(v, c)) return null
                splitTick = tick
                return listOf(c)
            }
            // Solange die Teilung aussteht, hält dieser Schritt die Abfrage an (leere Liste statt null): kein anderer Schritt
            // darf dazwischen senden, sonst nähme das Undo dessen Eintrag statt der Teilung zurück.
            if (tick - splitTick < 30L) return emptyList()
            val c = Command.Undo(tick, me)
            if (!valid(v, c)) return emptyList()
            done = true
            return listOf(c)
        }
    }

    private var keepTick = -1L
    /** PlaceBeam mit `bBeamRef` (Teilung bleibt bestehen). */
    private val splitKeep = object : Task("splitKeep") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!splitAndUndo.done || tick < splitTick + 60L) return null
            val b = beamBetween(v, 6f, 34f, 9f, 34f)
            if (b < 0) return null
            val c = Command.PlaceBeam(tick, me, aX = 7.5f, aY = 29.5f, bBeamRef = v.beamView.ref(b), bBeamT = 0.5f, materialId = wood)
            if (!valid(v, c)) return null
            keepTick = tick
            done = true
            return listOf(c)
        }
    }

    /** Der hängende Balken aus [splitKeep] wird wieder abgerissen (Rückerstattung). */
    private val deleteBeam = object : Task("deleteBeam") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!splitKeep.done || tick < keepTick + 120L) return null
            val b = beamEndingAt(v, 7.5f, 29.5f)
            if (b < 0) { done = true; return null }
            val c = Command.DeleteBeam(tick, me, v.beamView.ref(b))
            if (!valid(v, c)) return null
            done = true
            return listOf(c)
        }
    }

    private val doorRefs = ArrayList<Long>()
    private var doorStep = 0
    private var doorStepTick = -1L

    /**
     * Türen: beide Türen der Startfestung öffnen (fixiert, sonst fängt die nächstgelegene den Mörserschuss ab), die erste
     * wieder schließen und später erneut öffnen. Danach stehen alle Türen offen, die Schüsse am Ende laufen frei.
     */
    private val doors = object : Task("doors") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (tick < 300L) return null
            if (doorRefs.isEmpty()) {
                val b = v.beamView
                for (i in 0 until b.size) {
                    if (b.isAlive(i) && b.owner(i) == me && v.tables.materials[b.material(i)].isDoor && (b.flags(i) and BeamFlags.DEBRIS) == 0) {
                        doorRefs.add(b.ref(i))
                    }
                }
                if (doorRefs.isEmpty()) { done = true; return null }
            }
            if (doorStepTick >= 0L && tick - doorStepTick < DOOR_GAP) return null
            // Schritte: 0 = erste öffnen, 1 = zweite öffnen, 2 = erste schließen, 3 = erste wieder öffnen
            val idx = if (doorStep == 1 && doorRefs.size > 1) 1 else 0
            val c = Command.ToggleDoor(tick, me, doorRefs[idx])
            if (!valid(v, c)) { done = true; return null }
            doorStep++
            doorStepTick = tick
            if (doorStep >= 4 || (doorRefs.size == 1 && doorStep >= 3)) done = true
            return listOf(c)
        }
    }

    private val tsMid = floatArrayOf(0.5f, 0.3f, 0.7f)
    private val tsEnds = floatArrayOf(0.2f, 0.8f, 0.5f)

    private val buildArmoury = PlaceDeviceTask("armoury", armoury, groundSites, tsMid) { groundRun.done }
    private val buildUpgrade = PlaceDeviceTask("upgradeCenter", upgradeCenter, groundSites, tsMid) { buildArmoury.done }

    /** Waffendeck: zwei Säulen, Dach und Strebe auf den oberen Knoten der Startfestung. */
    private val deck = object : Task("deck") {
        private val specs = arrayOf(
            floatArrayOf(30f, 25f, 30f, 20f, 1f), floatArrayOf(33f, 25f, 33f, 20f, 1f),
            floatArrayOf(30f, 20f, 33f, 20f, 1f), floatArrayOf(30f, 25f, 33f, 20f, 0f),
        )

        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!buildUpgrade.done) return null
            for (s in specs) {
                if (beamBetween(v, s[0], s[1], s[2], s[3]) >= 0) continue
                val c = beamCmd(tick, v, s[0], s[1], s[2], s[3], if (s[4] > 0f) metal else wood)
                return if (valid(v, c)) listOf(c) else null
            }
            done = true
            return null
        }
    }

    private var turbineTick = -1L
    /** Zusätzliche Turbine bauen und später abreißen (`DeleteDevice`). */
    private val extraTurbine = object : Task("extraTurbine") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!deck.done) return null
            if (turbineTick < 0L) {
                val c = findPlacement(tick, v, turbine, groundSites(v), tsMid) ?: return null
                turbineTick = tick
                return listOf(c)
            }
            if (tick - turbineTick < 400L) return null
            // die zuletzt gebaute eigene Turbine (die Turbine der Startfestung bleibt)
            val d = v.deviceView
            var last = -1
            for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == me && d.type(i) == turbine && d.beam(i) >= 0) last = i
            val first = Aim.deviceOf(v, me, turbine)
            if (last < 0 || last == first) { done = true; return null }
            val c = Command.DeleteDevice(tick, me, d.ref(last))
            if (!valid(v, c)) return null
            done = true
            return listOf(c)
        }
    }

    /** Reparatur eines vom Gegner beschädigten Balkens (nicht brennend, nicht gebrochen). */
    private val repair = object : Task("repair") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (tick < 900L) return null
            val b = v.beamView
            for (i in 0 until b.size) {
                if (!b.isAlive(i) || b.owner(i) != me || (b.flags(i) and BeamFlags.DEBRIS) != 0) continue
                if (b.fire(i) > 0f || b.hp(i) >= b.maxHp(i) * 0.95f) continue
                val c = Command.RepairBeam(tick, me, b.ref(i))
                if (!valid(v, c)) continue
                done = true
                return listOf(c)
            }
            return null
        }
    }

    private val buildFactory = PlaceDeviceTask("factory", factory, groundSites, tsMid) { it.player(me).hasTech(techUpgrade) }
    // Metall ist knapp: erst die Fabrik (480), dann der Scharfschütze
    private val buildSniper = PlaceDeviceTask("sniper", db.deviceIndex("sniper"), deckSites, tsEnds) { deck.done && buildFactory.done && it.player(me).hasTech(techArmoury) }
    private val buildRocket = PlaceDeviceTask("rocket", db.deviceIndex("rocket"), deckSites, tsEnds) { deck.done && it.player(me).hasTech(techFactory) }
    private val buildLaser = PlaceDeviceTask("laser", db.deviceIndex("laser"), deckSites, tsEnds) { deck.done && it.player(me).hasTech(techFactory) }

    private class Shot(val device: String, val tx: Float, val ty: Float, val highArc: Boolean)

    /**
     * Ziele auf der gegnerischen Festung (Spieler 1, Ursprung x = 96 gespiegelt); der Reaktor liegt bei x ~ 91,5 und bleibt
     * unberührt, damit die Partie bis zur Aufgabe läuft.
     */
    private val shots = listOf(
        Shot("mg", 84f, 29.5f, false),
        Shot("sniper", 88f, 29f, false),
        Shot("mortar", 97.5f, 29f, false),
        Shot("cannon", 84f, 30f, false),
        Shot("rocket", 95f, 28.5f, false),
        Shot("laser", 84f, 29.5f, false),
    )
    private val firedAt = LongArray(shots.size) { -1L }
    private val shotDone = BooleanArray(shots.size)

    private fun allBuilt(v: GameView): Boolean {
        for (name in arrayOf("sniper", "rocket", "laser")) {
            val d = Aim.deviceOf(v, me, db.deviceIndex(name))
            if (d < 0 || v.deviceView.buildTicks(d) > 0) return false
        }
        return true
    }

    /** SetAim + Fire für jede der sechs Waffen, nacheinander (je Abfrage eine). */
    private val fire = object : Task("fire") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!allBuilt(v) || !deleteBeam.done || !doors.done || !repair.done) return null
            for (k in shots.indices) {
                if (shotDone[k]) continue
                val s = shots[k]
                val dev = Aim.deviceOf(v, me, db.deviceIndex(s.device))
                if (dev < 0) { shotDone[k] = true; continue }
                val d = v.deviceView
                if (firedAt[k] >= 0L) {
                    // angenommen, wenn die Waffe nachlädt, Salve läuft oder der Laser strahlt
                    if (d.reloadTicks(dev) > 0 || d.burstLeft(dev) > 0 || (d.flags(dev) and DeviceFlags.FIRING_BEAM) != 0) shotDone[k] = true
                    else if (tick - firedAt[k] > 30L) firedAt[k] = -1L
                    continue
                }
                if (d.buildTicks(dev) > 0 || d.reloadTicks(dev) > 0) continue
                val sol = Aim.solve(v, dev, s.tx, s.ty, s.highArc) ?: continue
                val ref = d.ref(dev)
                val fireCmd = Command.Fire(tick, me, ref)
                if (!valid(v, fireCmd)) continue
                firedAt[k] = tick
                return listOf(Command.SetAim(tick, me, ref, sol.angle, sol.power), fireCmd)
            }
            if (shotDone.all { it }) { done = true; allFiredTick = tick }
            return null
        }
    }

    private var allFiredTick = -1L
    private val surrender = object : Task("surrender") {
        override fun poll(tick: Long, v: GameView): List<Command>? {
            if (!fire.done || tick < allFiredTick + 240L) return null
            done = true
            return listOf(Command.Surrender(tick, me))
        }
    }

    private val tasks: List<Task> = listOf(
        groundRun, splitAndUndo, splitKeep, deleteBeam, doors,
        buildArmoury, buildUpgrade, deck, extraTurbine, repair,
        buildFactory, buildSniper, buildRocket, buildLaser, fire, surrender,
    )

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        if (tick < 5L || tick % POLL != 0L) return emptyList()
        for (t in tasks) {
            if (t.done) continue
            val c = t.poll(tick, view) ?: continue
            return c // auch eine leere Liste: der Schritt hält die Abfrage an (siehe splitAndUndo)
        }
        return emptyList()
    }

    /** Fortschritt für Fehlermeldungen. */
    fun status(): String = tasks.joinToString { "${it.name}=${if (it.done) "done" else "open"}" }

    companion object {
        const val POLL = 10L
        const val DOOR_GAP = 200L
    }
}

/**
 * Spieler 1 im All-Commands-Golden: beschießt mit dem Mörser der Startfestung dreimal die Festung von Spieler 0 (weit weg vom
 * Reaktor), damit es beschädigte Balken zum Reparieren gibt. Sonst passiv.
 */
class RaiderScript(private val db: ContentDb, private val me: Int = 1, private val target: Pair<Float, Float> = 27f to 28.5f) : CommandSource {
    private var shots = 0
    private var lastShot = -10_000L
    private var doorsOpened = false

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        // erst alle eigenen Türen öffnen (fixiert), sonst fängt die nächste Tür den Mörserschuss ab
        if (!doorsOpened && tick >= 500L) {
            doorsOpened = true
            val out = ArrayList<Command>()
            val b = view.beamView
            for (i in 0 until b.size) {
                if (b.isAlive(i) && b.owner(i) == me && view.tables.materials[b.material(i)].isDoor && (b.flags(i) and BeamFlags.DOOR_OPEN) == 0) {
                    out.add(Command.ToggleDoor(tick, me, b.ref(i)))
                }
            }
            return out
        }
        if (tick < 600L || tick % 10L != 0L || shots >= MAX_SHOTS || tick - lastShot < 420L) return emptyList()
        val dev = Aim.deviceOf(view, me, db.deviceIndex("mortar"))
        if (dev < 0) return emptyList()
        val d = view.deviceView
        if (d.buildTicks(dev) > 0 || d.reloadTicks(dev) > 0) return emptyList()
        val sol = Aim.solve(view, dev, target.first, target.second, highArc = false) ?: return emptyList()
        val fire = Command.Fire(tick, me, d.ref(dev))
        if (RulesValidator.validate(view, fire) != null) return emptyList()
        shots++
        lastShot = tick
        return listOf(Command.SetAim(tick, me, d.ref(dev), sol.angle, sol.power), fire)
    }

    private companion object {
        const val MAX_SHOTS = 3
    }
}
