package de.bollwerk.render.android.audio

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.renderapi.Camera

/**
 * Index-Tabellen des Contents (Reihenfolge in `materials.json` / `weapons.json`); mit [fromIds] aus den geladenen
 * Content-Listen ableitbar, damit der Mapper bei umsortiertem Content nicht still falsch zuordnet.
 */
data class AudioContentIds(
    val wood: Int = 0, val metal: Int = 1, val armour: Int = 2, val rope: Int = 3, val door: Int = 4,
    val mg: Int = 0, val sniper: Int = 1, val mortar: Int = 2, val cannon: Int = 3, val rocket: Int = 4, val laser: Int = 5,
) {
    companion object {
        fun fromIds(materialIds: List<String>, weaponIds: List<String>): AudioContentIds {
            val d = AudioContentIds()
            // fehlender Name → -1 (kein Treffer), nie ein Default-Index, der mit einem echten Eintrag kollidieren könnte
            fun m(n: String, @Suppress("UNUSED_PARAMETER") def: Int) = materialIds.indexOf(n)
            fun w(n: String, @Suppress("UNUSED_PARAMETER") def: Int) = weaponIds.indexOf(n)
            return AudioContentIds(
                m("wood", d.wood), m("metal", d.metal), m("armour", d.armour), m("rope", d.rope), m("door", d.door),
                w("mg", d.mg), w("sniper", d.sniper), w("mortar", d.mortar), w("cannon", d.cannon),
                w("rocket", d.rocket), w("laser", d.laser),
            )
        }
    }
}

/**
 * Übersetzt [FxEvent]s in Sounds und Haptik. [process] gehört dem Render-Thread: jede neue `seq` wird genau einmal
 * verarbeitet (derselbe Snapshot im nächsten Frame nur noch [SfxSink.update]). Positions-Sounds werden über
 * [SpatialMixer] gepannt und nach Entfernung zur Kamera gedämpft. Haptik nur für **eigene** Treffer und Schüsse
 * ([localPlayerId]; −1 = keine).
 */
class FxAudioMapper(
    private val sfx: SfxSink,
    private val haptics: HapticsSink = NoHaptics,
    var localPlayerId: Int = 0,
    private val ids: AudioContentIds = AudioContentIds(),
) {
    private var lastSeq = Long.MIN_VALUE
    private var jitter = 0

    // Nachlade-Erkennung je Geräte-Slot (uid verhindert Verwechslung bei Slot-Wiederverwendung)
    private var prevUid = IntArray(0)
    private var prevReload = FloatArray(0)
    private var resultHandled = false
    private var lastMgHapticTick = Long.MIN_VALUE / 2

    /** Zuletzt gemeldete Zahl brennender Balken. */
    var burningCount: Int = 0
        private set

    /** Setzt die Erkennung zurück (neue Partie). */
    fun reset() {
        lastSeq = Long.MIN_VALUE
        prevUid = IntArray(0)
        prevReload = FloatArray(0)
        resultHandled = false
        lastMgHapticTick = Long.MIN_VALUE / 2
        burningCount = 0
        sfx.setFireCount(0)
    }

    /** @return `true`, wenn der Snapshot neu war und seine Ereignisse verarbeitet wurden. */
    fun process(snap: FrameSnapshot, camera: Camera): Boolean {
        val fresh = snap.seq != lastSeq
        if (fresh) {
            lastSeq = snap.seq
            val fx = snap.fx
            for (i in fx.indices) handle(fx[i], snap, camera)
            reloadPings(snap)
            resultStinger(snap.result)
            burningCount = countBurning(snap)
            sfx.setFireCount(burningCount)
        }
        sfx.update()
        return fresh
    }

    /**
     * Stinger einmal beim Übergang Ongoing → Winner. Draw und Zuschauer (kein lokaler Spieler) bleiben ohne Stinger;
     * das Ergebnis (nicht ReactorDestroyed) entscheidet, damit Aufgabe/Zeitablauf und Rundenmodus stimmen.
     */
    private fun resultStinger(r: GameResult) {
        if (r == GameResult.Ongoing) { resultHandled = false; return }
        if (resultHandled) return
        resultHandled = true
        if (r is GameResult.Winner && localPlayerId >= 0) {
            sfx.play(if (r.playerId == localPlayerId) SfxId.VICTORY else SfxId.DEFEAT, 1f, 0f, 1f)
        }
    }

    private fun countBurning(s: FrameSnapshot): Int {
        var n = 0
        for (i in 0 until s.beamCount) {
            if (s.beamFlags[i] and BeamFlags.ALIVE != 0 && s.beamFire01[i] > FIRE_MIN) n++
        }
        return n
    }

    private fun reloadPings(s: FrameSnapshot) {
        val n = s.deviceCount
        if (prevUid.size < n) {
            val old = prevUid.size
            prevUid = prevUid.copyOf(n).also { it.fill(-1, old, n) }
            prevReload = prevReload.copyOf(n).also { it.fill(1f, old, n) }
        }
        for (i in 0 until n) {
            val alive = s.deviceFlags[i] and DeviceFlags.ALIVE != 0
            val uid = s.deviceUid[i]
            val r = s.deviceReload01[i]
            if (alive && prevUid[i] == uid && s.deviceOwner[i] == localPlayerId &&
                prevReload[i] < 1f && r >= 1f && s.deviceBuild01[i] >= 1f
            ) {
                sfx.play(SfxId.RELOAD_PING, 0.5f, 0f, 1f)
            }
            prevUid[i] = uid
            prevReload[i] = if (alive) r else 1f
        }
    }

    private fun handle(e: FxEvent, s: FrameSnapshot, cam: Camera) {
        when (e) {
            is FxEvent.Fired -> {
                when (e.weaponId) {
                    ids.mg -> at(SfxId.MG, e.x, 0.6f, vary(0.08f), cam)
                    ids.mortar -> at(SfxId.MORTAR, e.x, 1f, 1f, cam)
                    ids.cannon -> at(SfxId.CANNON, e.x, 1f, 1f, cam)
                    ids.sniper -> at(SfxId.CANNON, e.x, 0.6f, 1.4f, cam)
                    ids.rocket -> at(SfxId.MORTAR, e.x, 0.9f, 1.3f, cam)
                    else -> return // Laser: kontinuierlicher Strahl, kein Einzelschuss-Sound
                }
                if (ownsDevice(s, e.deviceUid)) {
                    // MG-Salve: nur der erste Schuss pulsiert (Salve ~0,5 s), sonst brummt es durchgehend
                    if (e.weaponId == ids.mg) {
                        if (e.tick - lastMgHapticTick >= MG_HAPTIC_GAP_TICKS) {
                            lastMgHapticTick = e.tick
                            haptics.pulse(HapticStrength.LIGHT)
                        }
                    } else {
                        haptics.pulse(HapticStrength.LIGHT)
                    }
                }
            }
            is FxEvent.Explosion -> {
                val k = (e.damage / 120f).coerceIn(0.3f, 1.6f)
                val id = if (k < 0.6f) SfxId.EXPLOSION_SMALL else SfxId.EXPLOSION
                at(id, e.x, (0.5f + 0.3f * k).coerceAtMost(1f), (1.15f - 0.2f * k).coerceIn(0.7f, 1.2f), cam)
                if (e.hitBeamUid >= 0 && ownsBeam(s, e.hitBeamUid)) haptics.pulse(HapticStrength.MEDIUM)
            }
            is FxEvent.BeamBroken -> {
                if (e.cause == BreakCause.DELETED || e.cause == BreakCause.DECAY) return
                val m = e.materialId
                when {
                    m == ids.metal || m == ids.armour || m == ids.door -> at(SfxId.METAL_BREAK, e.x, 1f, vary(), cam)
                    m == ids.rope -> at(SfxId.WOOD_BREAK, e.x, 0.4f, vary(), cam)
                    else -> at(SfxId.WOOD_BREAK, e.x, 1f, vary(), cam)
                }
            }
            is FxEvent.Hit -> {
                // Hitscan-Treffer (MG/Scharfschütze) haben keine Explosion; Projektile melden sich über Explosion
                if ((e.weaponId == ids.mg && ids.mg >= 0) || (e.weaponId == ids.sniper && ids.sniper >= 0)) {
                    if (e.target == HitTarget.DEVICE) at(SfxId.METAL_BREAK, e.x, 0.15f, 1.6f, cam)
                    else at(SfxId.THUD, e.x, 0.3f, vary(), cam)
                }
                val own = when (e.target) {
                    HitTarget.BEAM -> ownsBeam(s, e.targetUid)
                    HitTarget.DEVICE -> ownsDevice(s, e.targetUid)
                    HitTarget.TERRAIN -> false
                }
                if (own && e.damage > 0f) haptics.pulse(HapticStrength.LIGHT)
            }
            is FxEvent.DeviceDestroyed -> at(SfxId.METAL_BREAK, e.x, 1f, 0.9f, cam)
            is FxEvent.DevicePlaced -> at(SfxId.PLACE_METAL, e.x, 0.8f, 1f, cam)
            is FxEvent.BeamPlaced -> {
                val metal = e.materialId == ids.metal || e.materialId == ids.armour || e.materialId == ids.door
                at(if (metal) SfxId.PLACE_METAL else SfxId.PLACE_WOOD, e.x, 0.8f, vary(), cam)
            }
            is FxEvent.BeamRepaired -> at(SfxId.PLACE_METAL, e.x, 0.5f, 1.3f, cam)
            is FxEvent.DoorToggled -> at(SfxId.PLACE_METAL, e.x, 0.6f, if (e.open) 0.9f else 0.8f, cam)
            is FxEvent.DebrisLanded -> if (e.speed > DEBRIS_MIN_SPEED) {
                at(SfxId.THUD, e.x, (e.speed / 10f).coerceIn(0.3f, 1f), vary(), cam)
            }
            is FxEvent.TechChanged -> if (e.unlocked && e.playerId == localPlayerId) {
                sfx.play(SfxId.RELOAD_PING, 0.6f, 0f, 1.25f)
            }
            is FxEvent.CommandRejected -> if (e.playerId == localPlayerId) sfx.play(SfxId.CLICK, 0.5f, 0f, 0.6f)
            is FxEvent.FireRefused -> if (e.playerId == localPlayerId) sfx.play(SfxId.CLICK, 0.5f, 0f, 0.5f)
            is FxEvent.ReactorDestroyed -> {
                at(SfxId.EXPLOSION, e.x, 1f, 0.8f, cam) // Stinger: siehe resultStinger
            }
            is FxEvent.Ignited, is FxEvent.BeamSplit, is FxEvent.Tracer, is FxEvent.LaserBeam -> Unit
        }
    }

    private fun at(id: SfxId, worldX: Float, volume: Float, pitch: Float, cam: Camera) {
        sfx.play(id, volume * SpatialMixer.gain(worldX, cam), SpatialMixer.pan(worldX, cam), pitch)
    }

    /** Deterministische Tonhöhen-Variation ±[amount]/2 um 1 (kein Zufall, keine Uhr). */
    private fun vary(amount: Float = 0.1f): Float {
        jitter = (jitter + 7) % 10
        return 1f + (jitter / 9f - 0.5f) * amount
    }

    private fun ownsBeam(s: FrameSnapshot, uid: Int): Boolean {
        for (i in 0 until s.beamCount) if (s.beamUid[i] == uid) return s.beamOwner[i] == localPlayerId
        return false
    }

    private fun ownsDevice(s: FrameSnapshot, uid: Int): Boolean {
        for (i in 0 until s.deviceCount) if (s.deviceUid[i] == uid) return s.deviceOwner[i] == localPlayerId
        return false
    }

    private companion object {
        const val FIRE_MIN = 0.05f
        const val DEBRIS_MIN_SPEED = 6f
        const val MG_HAPTIC_GAP_TICKS = 36L
    }
}
