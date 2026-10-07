package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/**
 * Ergebnis der Zielvorschau einer Waffe: Flugbahn aus `Ballistics.predict` plus Kennzahlen (siehe [AimInfo]).
 * Die Bahn endet am **ersten Treffer**, den [ShotSweep] mit derselben Kollision wie die Simulation findet ([outcome]).
 */
class AimPreview(
    val deviceRef: Long,
    val angle: Float,
    val power: Float,
    val trajectory: Trajectory,
    val impactX: Float,
    val impactY: Float,
    /**
     * Die Bahn endet an einem Treffer – Gelände, gegnerische Struktur oder (rot) die eigene Festung; [impactX]/[impactY]
     * ist der Einschlag (Fadenkreuz bzw. Warnmarke zeichnen).
     */
    val hasImpact: Boolean,
    val apexX: Float,
    val apexY: Float,
    val apexHeightM: Float,
    val windDriftM: Float,
    val splashRadiusM: Float,
    /** Elevation in Grad von der Waagerechten zur Feindseite (Anzeige "52°"). */
    val elevationDeg: Float,
    val muzzleX: Float,
    val muzzleY: Float,
    val wind: Float,
    /** Die Bahn verlässt die Karte (Rand/oben/unten) ohne Treffer: kein Einschlag, [impactX]/[impactY] ist der Austrittspunkt. */
    val exitedMap: Boolean = false,
    /** FX1: erster Treffer der Bahn (wie die Sim); [TrajectoryOutcome.BLOCKED_OWN] = Schuss explodiert in der eigenen Festung. */
    val outcome: TrajectoryOutcome = TrajectoryOutcome.CLEAR,
    /** Grund-Schlüssel für die Zielkarte ([ShotSweep.REASON_BLOCKED_OWN], "eigene Festung im Weg") oder null. */
    val blockedReasonKey: String? = null,
)

/**
 * Berechnet die Zielvorschau (Flugbahn, Einschlag, Scheitel, Winddrift) für eine Waffe. Benutzt **genau**
 * `Ballistics.predict(…, weapon: WeaponProps, …)` mit der Mündung bei dem Winkel (`RuleChecks.mountOn`/`DeviceGeometry`),
 * der Waffe (Mündungsgeschwindigkeit + `gravityScale`) und dem aktuellen Wind, Gelände und Karte – so entspricht die Vorschau
 * der echten Flugbahn. Hitscan-/Strahlwaffen zeigen eine gerade Linie über die Reichweite.
 *
 * FX1: Die Bahn wird zusätzlich mit [ShotSweep] gegen Balken, Geräte und Gelände geprüft – derselbe Swept-Test wie
 * `ProjectileSystem` (offene Türen, beim Schuss automatisch öffnende Tür und eigener Montagebalken ausgenommen). Sie endet
 * am ersten Treffer; ist das die eigene Festung, ist [AimPreview.outcome] [TrajectoryOutcome.BLOCKED_OWN].
 *
 * Geprüft wird die **ganze Lebensdauer** des Geschosses ([ShotSweep.lifetimeTicks], z. B. Brandrakete 16 s); die
 * gezeigte Bahn reicht ebenso weit (Puffer wächst bei Bedarf einmalig).
 *
 * Das Ergebnis wird zwischengespeichert, solange Eingaben (Winkel, Kraft, Wind, Mündung) bitgleich bleiben, sich die
 * Struktur (Balken, Tür-Bits, Geräte; Lage auf 1 cm) nicht geändert hat und kein ablaufender Tür-Zeitgeber das Ergebnis
 * ändern kann ([ShotSweep.stableTicks]). Ablaufende Zeitgeber unbeteiligter Türen lösen keine Neuberechnung aus.
 */
class AimPreviewer {
    private val geo = FloatArray(DeviceGeometry.SIZE)
    private var buf = FloatArray(ToolConst.TRAJECTORY_MAX_POINTS * 2)
    private var buf0 = FloatArray(ToolConst.TRAJECTORY_MAX_POINTS * 2)
    private val sweep = ShotSweep()
    private var cache: AimPreview? = null
    private var cacheQx = 0
    private var cacheQy = 0
    private var cacheStructure = 0
    private var cacheTick = Long.MIN_VALUE
    private var cacheValidUntil = Long.MIN_VALUE

    /** Anzahl der vollständigen Neuberechnungen (Tests: Zwischenspeicher). */
    var computeCount: Long = 0L
        private set

    /** Vorschau für Gerät [deviceId] (Slot) bei [angle]/[power] (bereits geklemmt). `null`, wenn das Gerät keine Waffe ist. */
    fun preview(view: GameView, deviceId: Int, angle: Float, power: Float): AimPreview? {
        val devices = view.deviceView
        val props = view.tables.devices[devices.type(deviceId)]
        if (props.weapon < 0) return null
        val weapon = view.tables.weapons[props.weapon]
        val beam = devices.beam(deviceId)
        if (beam < 0 || !view.beamView.isAlive(beam)) return null
        val side = (devices.flags(deviceId) and DeviceFlags.SIDE_NEG) != 0
        RuleChecks.mountOn(view, beam, devices.t(deviceId), side, props, angle, geo)
        val mx = geo[DeviceGeometry.MUZZLE_X]
        val my = geo[DeviceGeometry.MUZZLE_Y]
        val ref = devices.ref(deviceId)
        val wind = view.wind
        // Mündung auf 1 mm quantisiert: Zittern der Struktur unter 1 mm löst keine neue Berechnung (und keinen Müll) aus
        val qx = (mx * MUZZLE_QUANT).toInt()
        val qy = (my * MUZZLE_QUANT).toInt()
        val structure = structureKey(view)
        val c = cache
        if (c != null && c.deviceRef == ref && c.angle.toRawBits() == angle.toRawBits() &&
            c.power.toRawBits() == power.toRawBits() && c.wind.toRawBits() == wind.toRawBits() &&
            qx == cacheQx && qy == cacheQy && structure == cacheStructure &&
            view.tick >= cacheTick && view.tick < cacheValidUntil
        ) return c
        computeCount++

        var e = if (view.player(devices.owner(deviceId)).facing >= 0) angle else FloatMath.PI - angle
        while (e > FloatMath.PI) e -= FloatMath.TWO_PI
        while (e <= -FloatMath.PI) e += FloatMath.TWO_PI
        val elevation = e * FloatMath.RAD_TO_DEG

        val result: AimPreview
        if (weapon.mode == WeaponMode.BALLISTIC) {
            val cfg = view.simConfig
            // Prüfhorizont = Lebensdauer wie in der Sim (nicht die Zahl der Anzeige-Punkte), Puffer mit Platz für den Treffer
            val maxPoints = ShotSweep.lifetimeTicks(weapon)
            if (buf.size < (maxPoints + 1) * 2) {
                buf = FloatArray((maxPoints + 1) * 2)
                buf0 = FloatArray((maxPoints + 1) * 2)
            }
            var n = Ballistics.predict(mx, my, angle, power, weapon, wind, cfg, buf, maxPoints, view.terrain, view.map)
            // erster Treffer wie in der Sim (Balken, Geräte, Gelände; Türen, Montagebalken) über die ganze Lebensdauer
            val outcome = sweep.device(view, deviceId, angle, power)
            val k = sweep.hitTick
            val structureHit = outcome == TrajectoryOutcome.BLOCKED_OWN || outcome == TrajectoryOutcome.HIT_ENEMY
            if (outcome.hasHit && k >= 0 && (structureHit || k < n - 1)) {
                // Bahn am Treffer abschneiden: Punkte der Flugticks davor, dann der Treffer selbst
                // (keep ≤ n ≤ maxPoints, Puffer hat maxPoints + 1 Punkte)
                val keep = if (k < n) k else n
                buf[keep * 2] = sweep.hitX; buf[keep * 2 + 1] = sweep.hitY
                n = keep + 1
            }
            val stopped = n > 0 && n < maxPoints
            val hasImpact = if (outcome.hasHit) {
                true
            } else {
                // Einschlag nur bei Geländetreffer (letzter Punkt liegt auf der Geländelinie), nicht beim Verlassen der Karte
                stopped && buf[(n - 1) * 2 + 1] >= view.terrain.heightAt(buf[(n - 1) * 2]) - ToolConst.IMPACT_TERRAIN_EPS
            }
            val exitedMap = stopped && !hasImpact
            var minY = my
            var apexX = mx
            var apexY = my
            for (i in 0 until n) {
                val y = buf[i * 2 + 1]
                if (y < minY) { minY = y; apexX = buf[i * 2]; apexY = y }
            }
            var drift = 0f
            if (wind != 0f && n > 0) {
                val n0 = Ballistics.predict(mx, my, angle, power, weapon, 0f, cfg, buf0, maxPoints, view.terrain, view.map)
                if (n0 > 0) {
                    val dir = if (FastTrig.cos(angle) < 0f) -1f else 1f
                    drift = (buf[(n - 1) * 2] - buf0[(n0 - 1) * 2]) * dir
                }
            }
            val pts = buf.copyOf(n * 2)
            val shown = if (hasImpact && !outcome.hasHit) TrajectoryOutcome.TERRAIN else outcome
            result = AimPreview(
                ref, angle, power, Trajectory(pts, n, shown),
                impactX = if (n > 0) pts[(n - 1) * 2] else Float.NaN, impactY = if (n > 0) pts[(n - 1) * 2 + 1] else Float.NaN,
                hasImpact = hasImpact, apexX = apexX, apexY = apexY, apexHeightM = FloatMath.max(0f, my - minY),
                windDriftM = drift, splashRadiusM = weapon.splashRadius, elevationDeg = elevation, muzzleX = mx, muzzleY = my, wind = wind,
                exitedMap = exitedMap, outcome = shown, blockedReasonKey = reasonFor(shown),
            )
        } else {
            var ex = mx + FastTrig.cos(angle) * weapon.maxRange
            var ey = my - FastTrig.sin(angle) * weapon.maxRange
            val outcome = sweep.device(view, deviceId, angle, power)
            if (outcome.hasHit) { ex = sweep.hitX; ey = sweep.hitY }
            result = AimPreview(
                ref, angle, power, Trajectory(floatArrayOf(mx, my, ex, ey), 2, outcome),
                impactX = ex, impactY = ey, hasImpact = outcome.hasHit, apexX = Float.NaN, apexY = Float.NaN, apexHeightM = 0f,
                windDriftM = 0f, splashRadiusM = weapon.splashRadius, elevationDeg = elevation, muzzleX = mx, muzzleY = my, wind = wind,
                outcome = outcome, blockedReasonKey = reasonFor(outcome),
            )
        }
        cache = result
        cacheQx = qx
        cacheQy = qy
        cacheStructure = structure
        cacheTick = view.tick
        val stable = sweep.stableTicks
        cacheValidUntil = if (stable == Int.MAX_VALUE) Long.MAX_VALUE else view.tick + stable
        return result
    }

    private fun reasonFor(o: TrajectoryOutcome): String? =
        if (o == TrajectoryOutcome.BLOCKED_OWN) ShotSweep.REASON_BLOCKED_OWN else null

    private companion object {
        const val MUZZLE_QUANT: Float = 1000f
        const val STRUCTURE_QUANT: Float = 100f

        /**
         * Schlüssel der Hindernislage für den Zwischenspeicher: lebende Balken mit Lage (1 cm), Material, Besitzer, Tür-Bits
         * und ob ein Tür-Zeitgeber läuft (nicht sein Wert: der zählt nach jedem Schuss 2,6 s lang je Tick herunter; ob er das
         * Ergebnis ändern kann, sagt [ShotSweep.stableTicks]); lebende Geräte mit Balken und Typ. Kein Hash-Container,
         * keine Allokation.
         */
        fun structureKey(view: GameView): Int {
            val beams = view.beamView
            val nodes = view.nodeView
            var h = -0x7ee3623b
            for (j in 0 until beams.size) {
                if (!beams.isAlive(j)) continue
                val a = beams.nodeA(j); val b = beams.nodeB(j)
                h = h * 31 + j
                h = h * 31 + beams.material(j)
                h = h * 31 + beams.owner(j)
                h = h * 31 + (beams.flags(j) and (BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED))
                h = h * 31 + (if (beams.doorTimer(j) > 0) 1 else 0)
                h = h * 31 + (nodes.x(a) * STRUCTURE_QUANT).toInt()
                h = h * 31 + (nodes.y(a) * STRUCTURE_QUANT).toInt()
                h = h * 31 + (nodes.x(b) * STRUCTURE_QUANT).toInt()
                h = h * 31 + (nodes.y(b) * STRUCTURE_QUANT).toInt()
            }
            val d = view.deviceView
            for (i in 0 until d.size) {
                if (!d.isAlive(i)) continue
                h = h * 31 + i
                h = h * 31 + d.type(i)
                h = h * 31 + d.beam(i)
            }
            return h
        }
    }
}

/**
 * Zielwerkzeug (Stil-Bibel §7, Entscheidung): Waffe antippen, dann **irgendwo** ziehen. Die Zugrichtung ist die
 * Schussrichtung (Winkel `atan2(−dy, dx)`, 0 = rechts, positiv = oben), die Zuglänge hinter einer Totzone die Kraft
 * (`minPower..maxPower`, Standard 30..100 %). Der Winkel wird mit `RulesValidator.clampAim` auf die Zielgrenzen der Waffe
 * begrenzt (Elevation von der Waagerechten zur Feindseite; für Spieler mit `facing < 0` gespiegelt, also `π − Elevation`).
 *
 * - **Down** auf einer eigenen Waffe (Trefferzentrum innerhalb `max(1,6 m, 1,4·pickRadius)`) wählt sie (State
 *   [AimToolState.WeaponSelected]); dann ziehen. Ein erneuter Down auf die gewählte Waffe beginnt direkt eine Zielgeste.
 *   Ohne Waffe und ohne Auswahl übernimmt das Werkzeug die Geste nicht ([panning]).
 * - **Move** setzt Winkel/Kraft und berechnet die Flugbahn ([AimToolState.Aiming], [preview]).
 * - **Up** liefert `SetAim` (nur wenn tatsächlich gezogen wurde); die Vorschau bleibt, bis die Sim den Winkel übernommen hat
 *   ([refresh]). [fireCommand] liefert `Fire`, [cycleWeapon] wählt reihum die nächste eigene Waffe.
 */
class DefaultAimTool(private val settings: ToolSettings = ToolSettings()) : AimTool {
    override var state: AimToolState = AimToolState.Idle
        private set

    /** Aktuelle Zielvorschau (auch bei gewählter, nicht gezogener Waffe: aktueller Winkel/Kraft der Waffe) oder `null`. */
    var preview: AimPreview? = null
        private set

    /** Der laufende Down gehört nicht dem Werkzeug (kein Waffenziel, nichts gewählt): Kamera darf schwenken. */
    var panning: Boolean = false
        private set

    /** Finger liegt und zieht/könnte ziehen. */
    var pressed: Boolean = false
        private set

    /** Grund, warum das letzte Loslassen/[fireCommand] kein Command ergab, sonst `null`. */
    var lastReject: RejectReason? = null
        private set

    /** Mit dem letzten Up wurde ein `SetAim` erzeugt (für "Loslassen = Feuern"). */
    var aimedOnRelease: Boolean = false
        private set

    private val previewer = AimPreviewer()
    private val picker = Picker()
    private var dragging = false
    private var moved = false
    private var originX = 0f
    private var originY = 0f
    private var settleSinceTick = 0L
    private var liveAngle = Float.NaN
    private var livePower = Float.NaN
    private var liveTick = NEVER

    /** Ref der gewählten Waffe oder −1. */
    val selectedRef: Long
        get() = when (val s = state) {
            AimToolState.Idle -> -1L
            is AimToolState.WeaponSelected -> s.deviceRef
            is AimToolState.Aiming -> s.deviceRef
        }

    /** Wählt Waffe [ref] direkt (z. B. vom HUD). `false`, wenn sie nicht (mehr) existiert, nicht dem Spieler gehört oder keine Waffe ist. */
    fun select(ref: Long, ctx: ToolContext): Boolean {
        val view = ctx.view
        val id = view.deviceView.resolve(ref)
        if (id < 0 || view.deviceView.owner(id) != ctx.playerId || view.tables.devices[view.deviceView.type(id)].weapon < 0) return false
        dragging = false
        pressed = false
        state = AimToolState.WeaponSelected(ref)
        updateIdlePreview(ctx, id)
        return true
    }

    /**
     * Wählt reihum die nächste ([step] = 1) bzw. vorige (−1) eigene Waffe in fester Slot-Reihenfolge.
     * @return Ref der Auswahl oder −1, wenn der Spieler keine Waffe hat.
     */
    fun cycleWeapon(ctx: ToolContext, step: Int = 1): Long {
        val view = ctx.view
        val devices = view.deviceView
        val n = devices.size
        if (n == 0) return -1L
        val cur = if (selectedRef >= 0L) devices.resolve(selectedRef) else -1
        var i = if (cur >= 0) cur else if (step >= 0) -1 else n
        for (k in 0 until n) {
            i += if (step >= 0) 1 else -1
            if (i >= n) i = 0
            if (i < 0) i = n - 1
            if (isOwnWeapon(view, i, ctx.playerId)) {
                select(devices.ref(i), ctx)
                return devices.ref(i)
            }
        }
        clear()
        return -1L
    }

    override fun onDown(worldX: Float, worldY: Float, ctx: ToolContext) {
        lastReject = null
        aimedOnRelease = false
        panning = false
        pressed = true
        dragging = false
        moved = false
        val view = ctx.view
        val hit = picker.nearestDevice(
            view, ctx.playerId, worldX, worldY,
            FloatMath.max(ToolConst.WEAPON_PICK_MIN_M, ctx.pickRadiusM * ToolConst.WEAPON_PICK_FACTOR), true,
        )
        val sel = if (selectedRef >= 0L) view.deviceView.resolve(selectedRef) else -1
        if (hit >= 0 && hit != sel) {
            select(view.deviceView.ref(hit), ctx)
            return
        }
        if (sel >= 0) {
            dragging = true
            originX = worldX
            originY = worldY
            return
        }
        panning = true
        pressed = false
    }

    override fun onMove(worldX: Float, worldY: Float, ctx: ToolContext) {
        if (!pressed || !dragging) return
        val view = ctx.view
        val sel = view.deviceView.resolve(selectedRef)
        if (sel < 0) { clear(); return }
        val dx = worldX - originX
        val dy = worldY - originY
        val len = sqrt(dx * dx + dy * dy)
        val dead = ctx.pickRadiusM * ToolConst.AIM_DEAD_ZONE_FACTOR
        if (len < dead) return
        val cfg = view.simConfig
        val full = ctx.pickRadiusM * ToolConst.AIM_FULL_POWER_FACTOR
        val frac = FloatMath.clamp((len - dead) / full, 0f, 1f)
        val power = FloatMath.clamp(cfg.minPower + (cfg.maxPower - cfg.minPower) * frac, cfg.minPower, cfg.maxPower)
        // Zugrichtung = Schussrichtung; Welt-y wächst nach unten
        val angle = RulesValidator.clampAim(view, sel, FastTrig.atan2(-dy, dx))
        moved = true
        setAiming(view, sel, angle, power)
    }

    override fun onUp(worldX: Float, worldY: Float, ctx: ToolContext): Command? {
        lastReject = null
        aimedOnRelease = false
        panning = false
        val wasDragging = dragging
        pressed = false
        dragging = false
        if (!wasDragging || !moved) return null
        val s = state as? AimToolState.Aiming ?: return null
        val view = ctx.view
        if (view.deviceView.resolve(s.deviceRef) < 0) { clear(); return null }
        val cmd = Command.SetAim(view.tick, ctx.playerId, s.deviceRef, s.angle, s.power)
        val reason = ctx.validator.validate(view, cmd)
        if (reason != null) {
            lastReject = reason
            state = AimToolState.WeaponSelected(s.deviceRef)
            val id = view.deviceView.resolve(s.deviceRef)
            if (id >= 0) updateIdlePreview(ctx, id)
            return null
        }
        settleSinceTick = view.tick
        aimedOnRelease = true
        liveAngle = s.angle; livePower = s.power; liveTick = view.tick
        return cmd
    }

    override fun cancel() {
        pressed = false
        dragging = false
        moved = false
        panning = false
        val ref = selectedRef
        state = if (ref >= 0L) AimToolState.WeaponSelected(ref) else AimToolState.Idle
        preview = null
    }

    /** Auswahl und Vorschau verwerfen. */
    fun clear() {
        pressed = false
        dragging = false
        moved = false
        panning = false
        state = AimToolState.Idle
        preview = null
    }

    /**
     * Live-Zielen (`ToolSettings.liveAimIntervalTicks` > 0): `SetAim` für den aktuellen Zug, höchstens alle N Ticks und nur
     * bei geänderten Werten; sonst `null`.
     */
    fun pollLiveAim(ctx: ToolContext): Command? {
        val n = settings.liveAimIntervalTicks
        if (n <= 0) return null
        val s = state as? AimToolState.Aiming ?: return null
        if (!dragging) return null
        val view = ctx.view
        if (liveTick != NEVER && view.tick - liveTick < n) return null
        if (s.angle.toRawBits() == liveAngle.toRawBits() && s.power.toRawBits() == livePower.toRawBits()) return null
        val cmd = Command.SetAim(view.tick, ctx.playerId, s.deviceRef, s.angle, s.power)
        if (ctx.validator.validate(view, cmd) != null) return null
        liveAngle = s.angle; livePower = s.power; liveTick = view.tick
        return cmd
    }

    /** `Fire` für die gewählte Waffe (FEUER-Button / "Loslassen = Feuern") oder `null` mit [lastReject]. */
    fun fireCommand(ctx: ToolContext): Command? {
        lastReject = null
        val view = ctx.view
        val ref = selectedRef
        if (ref < 0L) { lastReject = RejectReason.INVALID_TARGET; return null }
        val cmd = Command.Fire(view.tick, ctx.playerId, ref)
        val reason = ctx.validator.validate(view, cmd)
        if (reason != null) { lastReject = reason; return null }
        return cmd
    }

    /** Gültigkeit der Auswahl und Vorschau nachführen (Wind, Mündung, Waffe zerstört, Aim von der Sim übernommen). Pro Tick/Frame aufrufen. */
    fun refresh(ctx: ToolContext) {
        val ref = selectedRef
        if (ref < 0L) return
        val view = ctx.view
        val id = view.deviceView.resolve(ref)
        if (id < 0 || view.deviceView.owner(id) != ctx.playerId) { clear(); return }
        when (val s = state) {
            is AimToolState.Aiming -> {
                if (dragging) {
                    setAiming(view, id, s.angle, s.power)
                } else {
                    val d = view.deviceView
                    val applied = FloatMath.abs(d.aimAngle(id) - s.angle) < APPLIED_EPS && FloatMath.abs(d.power(id) - s.power) < APPLIED_EPS
                    if (applied || view.tick - settleSinceTick >= ToolConst.AIM_SETTLE_TICKS) {
                        state = AimToolState.WeaponSelected(ref)
                        updateIdlePreview(ctx, id)
                    } else {
                        setAiming(view, id, s.angle, s.power)
                    }
                }
            }
            is AimToolState.WeaponSelected -> updateIdlePreview(ctx, id)
            AimToolState.Idle -> {}
        }
    }

    private fun setAiming(view: GameView, id: Int, angle: Float, power: Float) {
        val p = previewer.preview(view, id, angle, power)
        preview = p
        state = AimToolState.Aiming(
            deviceRef = view.deviceView.ref(id), angle = angle, power = power,
            trajectory = p?.trajectory ?: Trajectory.EMPTY, apexHeightM = p?.apexHeightM ?: 0f, windDriftM = p?.windDriftM ?: 0f,
        )
    }

    private fun updateIdlePreview(ctx: ToolContext, id: Int) {
        val d = ctx.view.deviceView
        preview = previewer.preview(ctx.view, id, d.aimAngle(id), d.power(id))
    }

    private companion object {
        const val NEVER: Long = Long.MIN_VALUE

        /** Toleranz beim Vergleich "Sim hat den Zielwinkel übernommen" (Spiegelung `π − (π − a)` ist nicht bitgenau). */
        const val APPLIED_EPS: Float = 1e-4f
    }

    private fun isOwnWeapon(view: GameView, i: Int, owner: Int): Boolean {
        val d = view.deviceView
        return d.isAlive(i) && d.owner(i) == owner && view.tables.devices[d.type(i)].weapon >= 0
    }
}
