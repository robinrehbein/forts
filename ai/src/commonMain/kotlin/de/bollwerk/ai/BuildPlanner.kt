package de.bollwerk.ai

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.rules.BeamPlan
import de.bollwerk.engine.rules.BeamPlanner
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.BlueprintProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.GameView

/** Ein gewählter Bau-/Wiederaufbau-Schritt (ein Command) mit Kosten und Bewertung. */
class BuildCandidate(val command: Command, val metal: Float, val energy: Float, val score: Float, val key: Long) {
    /** Muss zusammen mit dem nächsten Kandidaten ausgegeben werden (Aussteifung eines neuen Knotens). */
    var pairNext: Boolean = false
}

/**
 * Bauplan der KI: arbeitet die Schritte (`BlueprintProps.steps`: economy → workshop → walls/armour → weapons →
 * factory → reactor_cover) der KI-Bauvorlage der Schwierigkeitsstufe ab und kennt die ganze Vorlage als Soll-Festung
 * für den Wiederaufbau.
 *
 * - Weltlage: Vorlage am Ursprung der eigenen Startfestung (`MapSpec.startForts`, `baseY`, Spiegelung), so wie
 *   `MatchFactory.placeBlueprint` sie setzt. Vorlagen-Knoten werden auf eigene Knoten im Umkreis [NODE_TOL] abgebildet
 *   (die Festung sackt beim Einschwingen etwas ab), sonst als freie Position gebaut.
 * - Jeder Schritt wird vorab mit dem [RulesValidator] gegen die [GameView] geprüft; nur gültige Commands verlassen die KI.
 *   Fehlende Ressourcen → **Sparen** (Reserve für den nächsten Bauschritt, keine späteren Schritte vorziehen);
 *   fehlende/noch gebaute Tech → warten, spätere Schritte dürfen vor; andere Ablehnungen zählen als Fehlversuch, nach
 *   [MAX_INVALID] bzw. [MAX_ISSUES] (ausgegeben, aber nie entstanden) wird der Schritt übersprungen.
 * - Neue freie Knoten: Ein Balken, der einen neuen Knoten erzeugt, wird im selben Denkschritt mit einem zweiten Balken
 *   an denselben Punkt (exakt gleiche Koordinaten → Verschmelzen im Command-System) ausgesteift, sonst kippt der Pfosten.
 *   Das gilt auch beim Wiederaufbau (ein einzelner Balken an einem Gelenk wäre ein Pendel).
 * - Wiederaufbau: Vorlagen-Balken gelten als vorhanden, wenn ein eigener Balken mit beiden Enden im Umkreis [LOOSE_TOL]
 *   der Vorlagen-Punkte steht; abgesackte Knoten in diesem Umkreis werden weiterverwendet. Geräte zuerst (eigenes
 *   Kontingent), Montagebalken zerstörter Geräte mit Bonus; was dreimal nicht an seinem Platz ankommt, ruht
 *   [REBUILD_COOLDOWN_TICKS]. Ein wieder aufgebautes Gerät, das binnen [QUICK_LOSS_TICKS] erneut zerstört wird, wartet
 *   wachsend lange ([DEVICE_BACKOFF_TICKS], doppelt so lange je weiterem schnellen Verlust), damit Metall nicht endlos in einen beschossenen Platz fließt.
 */
class BuildPlanner(private val me: Int, val blueprint: BlueprintProps?) {
    private class Item(val isBeam: Boolean, val index: Int, val step: Int) {
        var done = false
        var failed = false
        var issues = 0
        var invalid = 0
        var issuedTick = -1L
    }

    private var initialized = false
    /** Vorlage und Startfestung vorhanden? */
    var usable: Boolean = false; private set
    private var mirror = false
    private var wx = FloatArray(0)
    private var wy = FloatArray(0)
    private val items = ArrayList<Item>()
    /** Vorlagen-Balken/-Gerät → Schritt-Item (−1 = Teil der Startfestung). */
    private var beamItem = IntArray(0)
    private var deviceItem = IntArray(0)
    private var anchoredNode = BooleanArray(0)
    private var beamCarries = BooleanArray(0)

    // je Denkschritt neu aufgelöst
    private var nodeSlot = IntArray(0)
    /** Wie [nodeSlot], aber mit [LOOSE_TOL] (abgesackte/verschobene Knoten; für den Wiederaufbau). */
    private var looseSlot = IntArray(0)
    private var beamSlot = IntArray(0)
    /** Zugeordneter Balken läuft in Vorlagen-Richtung (nodeA ≈ Vorlagen-a). */
    private var beamForward = BooleanArray(0)
    /** Wiederaufbau-Versuche je Vorlagen-Balken, deren Ergebnis nicht an seinem Platz stand, und letzter Versuch. */
    private var rebuildFails = IntArray(0)
    private var rebuildTick = LongArray(0)
    private var deviceSlot = IntArray(0)
    private var deviceMissing = BooleanArray(0)
    /** Je Vorlagen-Gerät: stand es beim letzten [refresh]? Zuletzt wieder aufgebaut (Tick), schnelle Verluste, Sperre bis. */
    private var devicePresent = BooleanArray(0)
    private var deviceRebuiltTick = LongArray(0)
    private var deviceQuickLosses = IntArray(0)
    private var deviceRetryAt = LongArray(0)
    private var virtual = BooleanArray(0)
    private val plan = BeamPlan()
    private val geo = FloatArray(DeviceGeometry.SIZE)

    /**
     * Ressourcen, die der Bauplan als Nächstes ausgeben will (vom letzten [build]): Kosten der vorgeschlagenen
     * Kandidaten plus des Schritts, auf den gespart wird. Die KI zieht ausgegebene Bau-Commands davon ab; alles, was die
     * Reserve respektiert, wartet.
     */
    var reserveMetal: Float = 0f; private set
    var reserveEnergy: Float = 0f; private set
    /** Wartet der Plan beim letzten [build] auf Ressourcen (Sparen)? */
    var saving: Boolean = false; private set
    private var lastProgressTick = 0L
    private var lastDone = 0
    /** Kosten der günstigsten zerstörten Waffe der Soll-Festung (vom letzten [rebuild]; 0 = keine fehlt). */
    var missingWeaponMetal: Float = 0f; private set
    var missingWeaponEnergy: Float = 0f; private set
    /** Phase des nächsten offenen Schritts (`economy`, …) oder `done`. */
    var phase: String = "none"; private set
    /** Anzahl erledigter Schritt-Items. */
    val doneCount: Int get() = items.count { it.done }
    val itemCount: Int get() = items.size
    val failedCount: Int get() = items.count { it.failed }

    /** Ticks seit dem letzten erledigten Schritt-Item, solange gespart wird (sonst 0); Grundlage der Spar-Drosselung. */
    fun savingTicks(tick: Long): Long = if (saving) tick - lastProgressTick else 0L

    /** Sind alle Items des (ersten) Schritts mit Phase [phase] erledigt? `false`, wenn es keinen solchen Schritt gibt. */
    fun stepDone(phase: String): Boolean {
        val bp = blueprint ?: return false
        if (!usable) return false
        val s = bp.steps.indexOfFirst { it.phase == phase }
        if (s < 0) return false
        for (it in items) if (it.step == s && !it.done) return false
        return true
    }

    /** Offene Items mit Grund (Diagnose für Tests und Simrunner). */
    fun describeOpen(view: GameView, tick: Long): String {
        val bp = blueprint ?: return ""
        if (!usable) return "planner unusable"
        val sb = StringBuilder()
        for (it in items) {
            if (it.done) continue
            sb.append(if (it.isBeam) "beam#${it.index}" else "device#${it.index}(${bp.devices[it.index].type})")
            sb.append(" step=${bp.steps[it.step].phase} issues=${it.issues} invalid=${it.invalid} failed=${it.failed}")
            if (it.isBeam) {
                val b = bp.beams[it.index]
                sb.append(" nodes=${nodeSlot[b.a]}/${nodeSlot[b.b]} reason=${RulesValidator.validate(view, beamCommand(view, tick, it.index))}")
            } else {
                val cmd = deviceCommand(view, tick, it.index)
                sb.append(if (cmd == null) " mount missing" else " reason=${RulesValidator.validate(view, cmd)}")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Fehlgeschlagene Items (Diagnose). */
    fun describeFailed(): String {
        val bp = blueprint ?: return ""
        return items.filter { it.failed }.joinToString { i ->
            (if (i.isBeam) "beam#${i.index}" else "device#${i.index}(${bp.devices[i.index].type})") +
                " step=${bp.steps[i.step].phase} issues=${i.issues} invalid=${i.invalid}"
        }
    }

    private fun init(view: GameView) {
        initialized = true
        val bp = blueprint ?: return
        var fort = -1
        val forts = view.map.startForts
        for (i in forts.indices) if (forts[i].owner == me) { fort = i; break }
        if (fort < 0 || me >= view.map.baseY.size) return
        val f = forts[fort]
        mirror = f.mirror
        val oy = view.map.baseY[me]
        wx = FloatArray(bp.nodes.size) { if (mirror) f.originX - bp.nodes[it].x else f.originX + bp.nodes[it].x }
        wy = FloatArray(bp.nodes.size) { oy + bp.nodes[it].y }
        anchoredNode = BooleanArray(bp.nodes.size) { bp.nodes[it].anchored }
        beamItem = IntArray(bp.beams.size) { -1 }
        deviceItem = IntArray(bp.devices.size) { -1 }
        beamCarries = BooleanArray(bp.beams.size)
        for (d in bp.devices) if (d.beam in bp.beams.indices) beamCarries[d.beam] = true
        for ((s, step) in bp.steps.withIndex()) {
            for (b in step.beams) if (b in bp.beams.indices && beamItem[b] < 0) { beamItem[b] = items.size; items.add(Item(true, b, s)) }
            for (d in step.devices) if (d in bp.devices.indices && deviceItem[d] < 0) { deviceItem[d] = items.size; items.add(Item(false, d, s)) }
        }
        nodeSlot = IntArray(bp.nodes.size)
        looseSlot = IntArray(bp.nodes.size)
        beamSlot = IntArray(bp.beams.size)
        beamForward = BooleanArray(bp.beams.size)
        rebuildFails = IntArray(bp.beams.size)
        rebuildTick = LongArray(bp.beams.size) { -1L }
        deviceSlot = IntArray(bp.devices.size)
        deviceMissing = BooleanArray(bp.devices.size)
        devicePresent = BooleanArray(bp.devices.size)
        deviceRebuiltTick = LongArray(bp.devices.size) { -1L }
        deviceQuickLosses = IntArray(bp.devices.size)
        deviceRetryAt = LongArray(bp.devices.size)
        virtual = BooleanArray(bp.nodes.size)
        usable = true
    }

    /** Vorlage gegen den aktuellen Zustand auflösen (zu Beginn jedes Denkschritts). */
    fun refresh(view: GameView) {
        if (!initialized) init(view)
        if (!usable) return
        val bp = blueprint!!
        val nodes = view.nodeView
        for (i in bp.nodes.indices) {
            var best = -1
            var bd = LOOSE_TOL * LOOSE_TOL
            for (j in 0 until nodes.size) {
                if (!nodes.isAlive(j) || nodes.owner(j) != me || (nodes.flags(j) and NodeFlags.DEBRIS) != 0) continue
                val dx = nodes.x(j) - wx[i]; val dy = nodes.y(j) - wy[i]
                val d2 = dx * dx + dy * dy
                if (d2 < bd) { bd = d2; best = j }
            }
            looseSlot[i] = best
            nodeSlot[i] = if (bd < NODE_TOL * NODE_TOL) best else -1
        }
        // Balken über die Lage ihrer Endpunkte zuordnen, nicht über die Knoten-Zuordnung: Nach Brüchen liegen oft
        // mehrere eigene Knoten nahe einem Vorlagen-Knoten; die Wahl des nächsten kann zwischen Denkschritten wechseln,
        // und ein schon wieder aufgebauter Balken würde sonst immer wieder neu gesetzt.
        val beams = view.beamView
        val tol2 = LOOSE_TOL * LOOSE_TOL
        for (k in bp.beams.indices) {
            beamSlot[k] = -1
            beamForward[k] = true
            val ta = bp.beams[k].a; val tb = bp.beams[k].b
            var best = -1
            var bestD = Float.MAX_VALUE
            for (j in 0 until beams.size) {
                if (!beams.isAlive(j) || beams.owner(j) != me || (beams.flags(j) and BeamFlags.DEBRIS) != 0) continue
                val a = beams.nodeA(j); val b = beams.nodeB(j)
                val ax = nodes.x(a); val ay = nodes.y(a); val bx = nodes.x(b); val by = nodes.y(b)
                val f1 = sq(ax - wx[ta], ay - wy[ta]); val f2 = sq(bx - wx[tb], by - wy[tb])
                val r1 = sq(ax - wx[tb], ay - wy[tb]); val r2 = sq(bx - wx[ta], by - wy[ta])
                if (f1 < tol2 && f2 < tol2 && f1 + f2 < bestD) { bestD = f1 + f2; best = j; beamForward[k] = true }
                if (r1 < tol2 && r2 < tol2 && r1 + r2 < bestD) { bestD = r1 + r2; best = j; beamForward[k] = false }
            }
            beamSlot[k] = best
            if (best >= 0) rebuildFails[k] = 0
        }
        val d = view.deviceView
        for (k in bp.devices.indices) {
            deviceSlot[k] = -1
            val dev = bp.devices[k]
            val bb = bp.beams.getOrNull(dev.beam) ?: continue
            val ex = wx[bb.a] + (wx[bb.b] - wx[bb.a]) * dev.t
            val ey = wy[bb.a] + (wy[bb.b] - wy[bb.a]) * dev.t
            var best = -1
            var bd = DEVICE_TOL * DEVICE_TOL
            for (i in 0 until d.size) {
                if (!d.isAlive(i) || d.owner(i) != me || d.type(i) != dev.type) continue
                DeviceGeometry.mount(view, i, geo)
                val dx = geo[DeviceGeometry.X] - ex; val dy = geo[DeviceGeometry.Y] - ey
                val d2 = dx * dx + dy * dy
                if (d2 < bd) { bd = d2; best = i }
            }
            deviceSlot[k] = best
            val present = best >= 0
            if (devicePresent[k] && !present && deviceRebuiltTick[k] >= 0) {
                // wieder aufgebaut und gleich wieder verloren: dieser Platz liegt unter Beschuss → wachsende Pause
                if (view.tick - deviceRebuiltTick[k] < QUICK_LOSS_TICKS) {
                    deviceQuickLosses[k]++
                    deviceRetryAt[k] = view.tick + (DEVICE_BACKOFF_TICKS shl (minOf(deviceQuickLosses[k], MAX_BACKOFF_STEPS) - 1))
                } else {
                    deviceQuickLosses[k] = 0
                }
            }
            devicePresent[k] = present
        }
        var done = 0
        for (it in items) {
            if (!it.done && !it.failed && (if (it.isBeam) beamSlot[it.index] >= 0 else deviceSlot[it.index] >= 0)) it.done = true
            if (it.done) done++
        }
        if (done != lastDone) { lastDone = done; lastProgressTick = view.tick }
    }

    /**
     * Nächste Bauschritte in Plan-Reihenfolge (höchstens [slots] Commands, Kosten ≤ [metal]/[energy]).
     * Reine Vorschau ohne Seiteneffekt auf die Fehlversuchs-Zähler: Erst wenn die KI einen Kandidaten tatsächlich
     * ausgibt, meldet sie ihn über [onBuildIssued] (sonst würden verdrängte Kandidaten als "nie entstanden" gezählt).
     * Setzt [reserveMetal]/[reserveEnergy] (Kosten der Vorschläge + Spar-Ziel) und [saving].
     */
    fun build(view: GameView, tick: Long, slots: Int, metal: Float, energy: Float, out: MutableList<BuildCandidate>) {
        reserveMetal = 0f; reserveEnergy = 0f
        saving = false
        if (!usable) { phase = "none"; return }
        val bp = blueprint!!
        for (i in virtual.indices) virtual[i] = false
        var left = slots
        var m = metal; var e = energy
        phase = "done"
        var phaseSet = false
        var i = 0
        while (i < items.size && left > 0) {
            val it = items[i++]
            if (it.done || it.failed) continue
            if (!phaseSet) { phase = bp.steps[it.step].phase; phaseSet = true }
            if (it.issuedTick == tick) continue
            if (it.isBeam) {
                val b = bp.beams[it.index]
                val aExists = nodeSlot[b.a] >= 0; val bExists = nodeSlot[b.b] >= 0
                if (!aExists && !bExists) continue // wartet auf einen Anschluss (oder entsteht virtuell → nächster Denkschritt)
                val newEnd = if (!aExists && !virtual[b.a]) b.a else if (!bExists && !virtual[b.b]) b.b else -1
                val cmd = beamCommand(view, tick, it.index)
                val reason = RulesValidator.validate(view, cmd)
                BeamPlanner.plan(view, cmd, plan)
                if (reason == RejectReason.NOT_ENOUGH_METAL || (reason == null && plan.cost > m)) {
                    reserveMetal += plan.cost
                    saving = true
                    return // sparen
                }
                if (reason == RejectReason.STILL_BUILDING || reason == RejectReason.LOCKED_TECH) continue // Tech abwarten
                if (reason != null) { fail(it, invalid = true); continue }
                if (newEnd >= 0) {
                    val partner = partnerFor(newEnd, it.index)
                    if (partner >= 0) {
                        val pc = beamCommand(view, tick, partner)
                        val pr = RulesValidator.validate(view, pc)
                        val pCost = costOf(view, pc)
                        if (pr == null) {
                            val firstCost = costOf(view, cmd)
                            if (left < 2) return // im nächsten Denkschritt als Paar
                            if (firstCost + pCost > m) { reserveMetal += firstCost + pCost; saving = true; return }
                            out.add(BuildCandidate(cmd, firstCost, 0f, 0f, keyOfItem(it)).also { c -> c.pairNext = true })
                            out.add(BuildCandidate(pc, pCost, 0f, 0f, keyOfBeam(partner)))
                            m -= firstCost + pCost; left -= 2
                            reserveMetal += firstCost + pCost
                            items.getOrNull(beamItem[partner])?.issuedTick = tick // nicht noch einmal einzeln vorschlagen
                            virtual[newEnd] = true
                            continue
                        }
                    }
                    virtual[newEnd] = true
                }
                val c = costOf(view, cmd)
                out.add(BuildCandidate(cmd, c, 0f, 0f, keyOfItem(it)))
                m -= c; left--
                reserveMetal += c
            } else {
                val dev = bp.devices[it.index]
                val cmd = deviceCommand(view, tick, it.index) ?: continue // Balken fehlt noch
                val props = view.tables.devices[dev.type]
                val reason = RulesValidator.validate(view, cmd)
                when (reason) {
                    null -> {}
                    RejectReason.NOT_ENOUGH_METAL, RejectReason.NOT_ENOUGH_ENERGY -> {
                        reserveMetal += props.costMetal; reserveEnergy += props.costEnergy
                        saving = true
                        return
                    }
                    RejectReason.STILL_BUILDING, RejectReason.LOCKED_TECH -> continue
                    else -> { fail(it, invalid = true); continue }
                }
                if (props.costMetal > m || props.costEnergy > e) {
                    reserveMetal += props.costMetal; reserveEnergy += props.costEnergy
                    saving = true
                    return
                }
                out.add(BuildCandidate(cmd, props.costMetal, props.costEnergy, 0f, keyOfItem(it)))
                m -= props.costMetal; e -= props.costEnergy; left--
                reserveMetal += props.costMetal; reserveEnergy += props.costEnergy
            }
        }
    }

    /**
     * Wiederaufbau zerstörter Teile der Soll-Festung (Startfestung + bereits gebaute Schritte): fehlende Geräte (außer
     * Reaktor), deren Balken steht, und fehlende Balken, deren Endknoten noch stehen. Nur vorab gültige Commands.
     *
     * Geräte haben ein eigenes Kontingent und kommen zuerst (sonst verdrängen ständig neu zerschossene Wandbalken den
     * Wiederaufbau der Waffen). Ein fehlender Balken, der ein fehlendes Gerät trägt, bekommt [REBUILD_MISSING_MOUNT].
     * Setzt [missingWeaponMetal]/[missingWeaponEnergy] auf die günstigste fehlende Waffe (Spar-Ziel), sonst 0.
     */
    fun rebuild(view: GameView, tick: Long, maxCandidates: Int, out: MutableList<BuildCandidate>, ignoreBackoff: Boolean = false) {
        missingWeaponMetal = 0f; missingWeaponEnergy = 0f
        if (!usable) return
        val bp = blueprint!!
        val nodes = view.nodeView
        for (k in bp.devices.indices) deviceMissing[k] = false
        var foundDevices = 0
        for (k in bp.devices.indices) {
            if (deviceSlot[k] >= 0) continue
            val item = deviceItem[k]
            if (item >= 0 && !items[item].done) continue
            val dev = bp.devices[k]
            val props = view.tables.devices.getOrNull(dev.type) ?: continue
            if (props.role == DeviceRole.REACTOR) continue
            deviceMissing[k] = true
            val cmd = deviceCommand(view, tick, k) // null: Montagebalken fehlt (wird unten mit Bonus wieder aufgebaut)
            val reason = cmd?.let { RulesValidator.validate(view, it) }
            val reachable = cmd == null || reason == null ||
                reason == RejectReason.NOT_ENOUGH_METAL || reason == RejectReason.NOT_ENOUGH_ENERGY
            if (props.role == DeviceRole.WEAPON && reachable && (missingWeaponMetal == 0f || props.costMetal < missingWeaponMetal)) {
                missingWeaponMetal = props.costMetal; missingWeaponEnergy = props.costEnergy
            }
            if (foundDevices >= maxCandidates || cmd == null || reason != null || (!ignoreBackoff && tick < deviceRetryAt[k])) continue
            val score = when (props.role) {
                DeviceRole.WEAPON -> REBUILD_WEAPON
                DeviceRole.MINE -> REBUILD_MINE
                else -> REBUILD_OTHER
            }
            out.add(BuildCandidate(cmd, props.costMetal, props.costEnergy, score, keyOfDevice(k)))
            foundDevices++
        }
        var found = 0
        for (k in bp.beams.indices) {
            if (found >= maxCandidates) return
            if (!rebuildable(view, k, tick)) continue
            val b = bp.beams[k]
            val na = looseSlot[b.a]; val nb = looseSlot[b.b]
            val cmd = beamCommand(view, tick, k, loose = true)
            if (RulesValidator.validate(view, cmd) != null) continue
            val cost = costOf(view, cmd)
            var score = REBUILD_BEAM
            if (beamCarries[k]) score += REBUILD_CARRIER_BONUS
            if (carriesMissingDevice(k)) score += REBUILD_MISSING_MOUNT
            if (anchoredNode[b.a] || anchoredNode[b.b]) score += REBUILD_ANCHOR_BONUS
            if (na >= 0 && nb >= 0) {
                out.add(BuildCandidate(cmd, cost, 0f, score, keyOfBeam(k)))
                found++
                continue
            }
            // Neuer freier Knoten: Ein einzelner Balken an einem Gelenk ist ein Pendel und hinge nur herunter
            // (und würde dann endlos neu gesetzt). Nur zusammen mit einem zweiten Balken an denselben Punkt bauen.
            val newEnd = if (na < 0) b.a else b.b
            val partner = rebuildPartner(view, newEnd, k, tick)
            if (partner < 0) continue
            val pc = beamCommand(view, tick, partner, loose = true)
            if (RulesValidator.validate(view, pc) != null) continue
            out.add(BuildCandidate(cmd, cost, 0f, score, keyOfBeam(k)).also { it.pairNext = true })
            out.add(BuildCandidate(pc, costOf(view, pc), 0f, score, keyOfBeam(partner)))
            found++
        }
    }

    /** Fehlt Vorlagen-Balken [k] (schon einmal gebaut) und lässt er sich an mindestens einem stehenden Knoten neu setzen? */
    private fun rebuildable(view: GameView, k: Int, tick: Long): Boolean {
        if (beamSlot[k] >= 0) return false
        val item = beamItem[k]
        if (item >= 0 && !items[item].done) return false // noch nie gebaut: Sache des Bauplans
        if (rebuildFails[k] >= MAX_REBUILD_TRIES && tick < rebuildTick[k] + REBUILD_COOLDOWN_TICKS) return false
        // Endknoten werden locker zugeordnet (abgesackte Knoten bis [LOOSE_TOL] werden weiterverwendet); fehlt einer,
        // steht im Umkreis [LOOSE_TOL] nichts Eigenes mehr und er entsteht neu an der Vorlagen-Position.
        val b = blueprint!!.beams[k]
        return looseSlot[b.a] >= 0 || looseSlot[b.b] >= 0
    }

    /** Zweiter fehlender Vorlagen-Balken von [node] (fehlt) zu einem stehenden Knoten, oder −1. */
    private fun rebuildPartner(view: GameView, node: Int, except: Int, tick: Long): Int {
        val bp = blueprint!!
        for (k in bp.beams.indices) {
            if (k == except) continue
            val b = bp.beams[k]
            val other = if (b.a == node) b.b else if (b.b == node) b.a else continue
            if (looseSlot[other] < 0) continue
            if (!rebuildable(view, k, tick)) continue
            return k
        }
        return -1
    }

    /**
     * Von der KI gemeldet, wenn ein Bau-/Wiederaufbau-Command mit Schlüssel [key] tatsächlich ausgegeben wurde.
     * Merkt einen Wiederaufbau. Steht der Balken beim nächsten Versuch immer noch nicht an seinem Platz (abgerutscht,
     * gleich wieder zerschossen), zählt das als Fehlschlag; nach [MAX_REBUILD_TRIES] ruht er [REBUILD_COOLDOWN_TICKS].
     */
    fun onIssued(key: Long, tick: Long) {
        val k = key - KEY_BASE
        if (usable && k >= 0 && k < rebuildTick.size) markRebuilt(k.toInt(), tick)
        val dk = k - DEVICE_KEY_OFFSET
        if (usable && dk >= 0 && dk < deviceRebuiltTick.size) deviceRebuiltTick[dk.toInt()] = tick
    }

    /**
     * Von der KI gemeldet, wenn ein Kandidat aus [build] mit Schlüssel [key] tatsächlich ausgegeben wurde. Nur solche
     * Ausgaben zählen als Versuch; nach [MAX_ISSUES] Versuchen, die nie an ihrem Platz ankamen, gilt das Item als
     * gescheitert.
     */
    fun onBuildIssued(key: Long, tick: Long) {
        if (!usable) return
        val k = key - KEY_BASE
        val item = when {
            k >= 0 && k < beamItem.size -> beamItem[k.toInt()]
            k >= DEVICE_KEY_OFFSET && k - DEVICE_KEY_OFFSET < deviceItem.size -> deviceItem[(k - DEVICE_KEY_OFFSET).toInt()]
            else -> -1
        }
        val it = items.getOrNull(item) ?: return
        if (it.done || it.failed) return
        it.issues++
        it.issuedTick = tick
        checkIssues(it)
    }

    private fun markRebuilt(k: Int, tick: Long) {
        if (rebuildTick[k] >= 0 && tick < rebuildTick[k] + REBUILD_COOLDOWN_TICKS) rebuildFails[k]++ else rebuildFails[k] = 0
        rebuildTick[k] = tick
    }

    /** Diagnose (Tests, Simrunner): fehlende Geräte der Soll-Festung und warum sie nicht wieder entstehen. */
    fun describeMissing(view: GameView, tick: Long): String {
        if (!usable) return "planner unusable"
        val bp = blueprint!!
        val sb = StringBuilder()
        for (k in bp.devices.indices) {
            if (deviceSlot[k] >= 0) continue
            val dev = bp.devices[k]
            val bb = bp.beams[dev.beam]
            val cmd = deviceCommand(view, tick, k)
            val reason = cmd?.let { RulesValidator.validate(view, it) }
            sb.append("device#$k type=${dev.type} mountBeam=${dev.beam} slot=${beamSlot[dev.beam]} nodes=${nodeSlot[bb.a]}/${nodeSlot[bb.b]} ")
            sb.append(if (cmd == null) "mount missing (rebuildable=${rebuildable(view, dev.beam, tick)} fails=${rebuildFails[dev.beam]})" else "reason=$reason")
            sb.append('\n')
        }
        for (k in bp.beams.indices) {
            if (beamSlot[k] >= 0) continue
            val item = beamItem[k]
            if (item >= 0 && !items[item].done) continue
            val b = bp.beams[k]
            val na = looseSlot[b.a]; val nb = looseSlot[b.b]
            val reason = if (na >= 0 || nb >= 0) RulesValidator.validate(view, beamCommand(view, tick, k, loose = true)) else null
            sb.append("beam#$k ${b.a}-${b.b} looseNodes=$na/$nb fails=${rebuildFails[k]} reason=$reason\n")
        }
        return sb.toString()
    }

    private fun carriesMissingDevice(beam: Int): Boolean {
        val bp = blueprint!!
        for (k in bp.devices.indices) if (deviceMissing[k] && bp.devices[k].beam == beam) return true
        return false
    }

    private fun sq(dx: Float, dy: Float): Float = dx * dx + dy * dy

    /** Nächster offener Vorlagen-Balken, der [node] mit einem schon stehenden Knoten verbindet. */
    private fun partnerFor(node: Int, except: Int): Int {
        val bp = blueprint!!
        for (it in items) {
            if (!it.isBeam || it.done || it.failed || it.index == except) continue
            val b = bp.beams[it.index]
            val other = if (b.a == node) b.b else if (b.b == node) b.a else continue
            if (nodeSlot[other] >= 0) return it.index
        }
        return -1
    }

    private fun fail(it: Item, invalid: Boolean) {
        if (invalid) it.invalid++
        if (it.invalid > MAX_INVALID) it.failed = true
    }

    private fun checkIssues(it: Item) {
        if (it.issues > MAX_ISSUES) it.failed = true
    }

    private fun costOf(view: GameView, cmd: Command.PlaceBeam): Float {
        BeamPlanner.plan(view, cmd, plan)
        return plan.cost
    }

    private fun beamCommand(view: GameView, tick: Long, k: Int, loose: Boolean = false): Command.PlaceBeam {
        val b = blueprint!!.beams[k]
        val slots = if (loose) looseSlot else nodeSlot
        val na = slots[b.a]; val nb = slots[b.b]
        val nodes = view.nodeView
        return Command.PlaceBeam(
            tick = tick, playerId = me,
            aNodeRef = if (na >= 0) nodes.ref(na) else -1L, aX = wx[b.a], aY = wy[b.a],
            bNodeRef = if (nb >= 0) nodes.ref(nb) else -1L, bX = wx[b.b], bY = wy[b.b],
            materialId = b.material,
        )
    }

    private fun deviceCommand(view: GameView, tick: Long, k: Int): Command.PlaceDevice? {
        val bp = blueprint!!
        val dev = bp.devices[k]
        val bb = bp.beams.getOrNull(dev.beam) ?: return null
        val slot = beamSlot[dev.beam]
        if (slot < 0) return null
        val beams = view.beamView
        val forward = beamForward[dev.beam]
        val side = dev.sideNegative != mirror
        return Command.PlaceDevice(
            tick = tick, playerId = me, deviceTypeId = dev.type, beamRef = beams.ref(slot),
            t = if (forward) dev.t else 1f - dev.t, sideNegative = if (forward) side else !side,
        )
    }

    private fun keyOfItem(it: Item): Long = if (it.isBeam) keyOfBeam(it.index) else keyOfDevice(it.index)
    private fun keyOfBeam(k: Int): Long = KEY_BASE + k
    private fun keyOfDevice(k: Int): Long = KEY_BASE + DEVICE_KEY_OFFSET + k

    companion object {
        /** Fangradius Vorlagen-Knoten → eigener Knoten (m). */
        const val NODE_TOL: Float = 0.7f
        /**
         * Lockerer Fangradius (m) für Balken-Endpunkte und Wiederaufbau-Knoten: Nach Treffern hängen Festungsteile oft
         * 1–1,5 m durch; solche Knoten werden weiterverwendet statt daneben neue zu setzen.
         */
        const val LOOSE_TOL: Float = 1.5f
        /** Fangradius Vorlagen-Gerät → eigenes Gerät gleichen Typs (m). */
        const val DEVICE_TOL: Float = 1.3f
        const val MAX_INVALID: Int = 40
        const val MAX_ISSUES: Int = 4
        /** Fehlversuche, nach denen ein Balken [REBUILD_COOLDOWN_TICKS] lang nicht wieder aufgebaut wird. */
        const val MAX_REBUILD_TRIES: Int = 3
        const val REBUILD_COOLDOWN_TICKS: Long = 1800L
        /** Ein wieder aufgebautes Gerät, das schneller als das verloren geht, zählt als "schneller Verlust". */
        const val QUICK_LOSS_TICKS: Long = 5400L
        /** Pause nach einem schnellen Verlust vor dem nächsten Wiederaufbau an diesem Platz; verdoppelt sich je weiterem (bis [MAX_BACKOFF_STEPS] Stufen). */
        const val DEVICE_BACKOFF_TICKS: Long = 900L
        const val MAX_BACKOFF_STEPS: Int = 4
        const val REBUILD_BEAM: Float = 22f
        const val REBUILD_CARRIER_BONUS: Float = 10f
        const val REBUILD_ANCHOR_BONUS: Float = 4f
        /** Zusatz für einen fehlenden Balken, der ein zerstörtes Gerät trägt (erst der Balken, dann das Gerät). */
        const val REBUILD_MISSING_MOUNT: Float = 30f
        const val REBUILD_WEAPON: Float = 70f
        const val REBUILD_MINE: Float = 50f
        const val REBUILD_OTHER: Float = 24f
        private const val KEY_BASE: Long = 1L shl 40
        private const val DEVICE_KEY_OFFSET: Long = 1L shl 20
    }
}
