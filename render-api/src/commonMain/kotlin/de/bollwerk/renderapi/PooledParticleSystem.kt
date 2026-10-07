package de.bollwerk.renderapi

import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.renderapi.scene.DevKind
import de.bollwerk.renderapi.scene.MatKind
import de.bollwerk.renderapi.scene.RenderRng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [ParticleSystem] mit **fester Kapazität** (Struktur aus Arrays, kein Objekt je Partikel, keine Allokation
 * im Betrieb): [DEFAULT_CAP] = 600, im reduzierten Bewegungsmodus [REDUCED_CAP] = 260 (Prototyp `PMAX`).
 * Ist der Pool voll, werden Rauch, Staub und Glut verworfen bzw. ersetzen sich gegenseitig; wichtige
 * Partikel (Blitz, Feuerball, Ring, Trümmer, Funken) verdrängen nach Möglichkeit ein Wegwerf-Partikel.
 *
 * Reiner Darstellungs-Zustand (eigener [RenderRng], nicht im Sim-Hash). Physik und Mengen aus dem Prototyp
 * (`partTick`, `explosionFX`). [onFx] übersetzt jedes Ereignis genau so, wie es geliefert wird; die
 * "genau einmal je neuem `seq`"-Regel setzt der Szenen-Renderer durch.
 *
 * @param reducedMotion weniger und kürzere Effekte, kein Weiß-Blitz (Barrierefreiheit)
 */
class PooledParticleSystem(
    val reducedMotion: Boolean = false,
    seed: Int = 7,
    capacity: Int = if (reducedMotion) REDUCED_CAP else DEFAULT_CAP,
) : ParticleSystem {
    override val buffers: ParticleBuffers = ParticleBuffers(capacity)
    override val count: Int get() = buffers.count

    private val rng = RenderRng(seed)
    private var matColors: IntArray = DEFAULT_MATERIAL_COLORS
    private var terrain: Terrain? = null
    private var groundY: Float = 34f

    // Darstellungsklassen aus dem Content (`MatKind`/`DevKind`, vom Szenen-Renderer in `bindWorld` geliefert); die
    // Reihenfolge der JSON-Dateien ist nicht fest. Ohne Angabe gilt die Standardreihenfolge des mitgelieferten Contents.
    private var matKinds: IntArray = DEFAULT_MATERIAL_KINDS
    private var weaponKinds: IntArray = DEFAULT_WEAPON_KINDS
    private var metalColor: Int = Palette.METAL

    private val kinds = ParticleKind.entries
    private val red: Float = if (reducedMotion) 0.5f else 1f

    override fun bindWorld(materialColors: IntArray, terrain: Terrain?) =
        bindWorld(materialColors, IntArray(0), IntArray(0), terrain)

    override fun bindWorld(materialColors: IntArray, materialKinds: IntArray, weaponKinds: IntArray, terrain: Terrain?) {
        matColors = if (materialColors.isEmpty()) DEFAULT_MATERIAL_COLORS else materialColors
        matKinds = if (materialKinds.isEmpty()) DEFAULT_MATERIAL_KINDS else materialKinds
        this.weaponKinds = if (weaponKinds.isEmpty()) DEFAULT_WEAPON_KINDS else weaponKinds
        metalColor = Palette.METAL
        for (i in matKinds.indices) if (matKinds[i] == MatKind.METAL && i < matColors.size) { metalColor = matColors[i]; break }
        this.terrain = terrain
        if (terrain != null) {
            var m = Float.POSITIVE_INFINITY
            for (h in terrain.heights) if (h < m) m = h
            groundY = m
        }
    }

    override fun clear() { buffers.count = 0 }

    override fun emit(kind: ParticleKind, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, color: Int) {
        val b = buffers
        var i: Int
        if (b.count < b.capacity) {
            i = b.count++
        } else {
            if (lowPriority(kind.ordinal)) return
            // vollen Pool: ein Wegwerf-Partikel verdrängen (wenige Versuche, deterministisch)
            i = -1
            for (attempt in 0 until 4) {
                val j = rng.below(b.count)
                if (lowPriority(b.kind[j])) { i = j; break }
            }
            if (i < 0) return
        }
        b.kind[i] = kind.ordinal
        b.x[i] = x; b.y[i] = y; b.vx[i] = vx; b.vy[i] = vy
        b.age[i] = 0f; b.life[i] = if (life > 1e-3f) life else 1e-3f
        b.size[i] = size
        b.rot[i] = rng.next() * TAU
        b.vrot[i] = rng.range(-9f, 9f)
        b.color[i] = if (color != 0) color else defaultColor(kind)
        b.seed[i] = rng.nextInt() and 0xFFFFFF
        b.flags[i] = 0
    }

    /** Standardfarbe der Art, wenn [emit] mit `color = 0` aufgerufen wird. */
    private fun defaultColor(kind: ParticleKind): Int = when (kind) {
        ParticleKind.SPARK -> Palette.SPARK
        ParticleKind.EMBER -> Palette.SPARK
        ParticleKind.CHUNK, ParticleKind.DEBRIS -> Palette.WOOD_DARK
        ParticleKind.SPLINTER -> Palette.WOOD
        else -> Palette.WHITE
    }

    private fun lowPriority(k: Int): Boolean =
        k == ParticleKind.SMOKE.ordinal || k == ParticleKind.DUST.ordinal || k == ParticleKind.EMBER.ordinal

    private fun matColor(materialId: Int, fallback: Int): Int =
        if (materialId >= 0 && materialId < matColors.size) matColors[materialId] else fallback

    // ---------------------------------------------------------------------------------------------
    // Ereignisse → Partikel
    // ---------------------------------------------------------------------------------------------

    override fun onFx(event: FxEvent, wind: Float) {
        when (event) {
            is FxEvent.Explosion -> explosion(event.x, event.y, event.radius, event.damage, explosionDebrisColor(event.hitMaterialId), wind)
            is FxEvent.BeamBroken -> beamBroken(event, wind)
            is FxEvent.Fired -> fired(event, wind)
            is FxEvent.Tracer -> {
                for (i in 0 until 3) emit(ParticleKind.SPARK, event.x1, event.y1, rng.range(-5f, 5f), rng.range(-6f, 0f), rng.range(0.15f, 0.4f), 1f)
            }
            is FxEvent.LaserBeam -> {
                emit(ParticleKind.SPARK, event.x1, event.y1, rng.range(-6f, 6f), rng.range(-7f, 0f), rng.range(0.15f, 0.35f), 1f)
                emit(ParticleKind.EMBER, event.x1, event.y1, rng.range(-1f, 1f), rng.range(-3f, -1f), rng.range(0.4f, 0.8f), rng.range(0.03f, 0.05f))
            }
            is FxEvent.Hit -> hit(event)
            is FxEvent.Ignited -> {
                for (i in 0 until (3 * red + 0.5f).toInt()) emit(ParticleKind.EMBER, event.x + rng.range(-0.3f, 0.3f), event.y, rng.range(-1f, 1f), rng.range(-3f, -1f), rng.range(0.8f, 1.5f), rng.range(0.03f, 0.06f))
                emit(ParticleKind.SMOKE, event.x, event.y - 0.2f, rng.range(-0.3f, 0.3f) + wind * 0.2f, rng.range(-1.2f, -0.5f), rng.range(1.6f, 2.4f), rng.range(0.4f, 0.6f))
            }
            is FxEvent.DeviceDestroyed -> {
                explosion(event.x, event.y, 1.8f, 90f, Palette.DOOR, wind)
            }
            is FxEvent.ReactorDestroyed -> explosion(event.x, event.y, 6.5f, 280f, metalColor, wind)
            is FxEvent.DevicePlaced -> puff(event.x, event.y, 0.45f, 3)
            is FxEvent.BeamPlaced -> puff(event.x, event.y, 0.3f, 2)
            is FxEvent.DoorToggled -> puff(event.x, event.y, 0.4f, 4)
            is FxEvent.DebrisLanded -> {
                val s = if (event.speed < 0f) 0f else event.speed
                val n = (1 + s * 0.4f).coerceAtMost(4f)
                puff(event.x, event.y, 0.25f + 0.04f * s.coerceAtMost(8f), (n * red + 0.5f).toInt())
            }
            is FxEvent.BeamRepaired -> {
                for (i in 0 until 3) emit(ParticleKind.SPARK, event.x, event.y, rng.range(-3f, 3f), rng.range(-4f, -1f), rng.range(0.2f, 0.4f), 1f, Palette.OK)
            }
            is FxEvent.BeamSplit, is FxEvent.TechChanged, is FxEvent.CommandRejected, is FxEvent.FireRefused -> Unit
        }
    }

    /** Trümmerfarbe einer Explosion: Material des getroffenen Balkens, −1 Gelände, sonst (Luft/Gerät) Stahlgrau. */
    private fun explosionDebrisColor(hitMaterialId: Int): Int = when {
        hitMaterialId >= 0 -> matColor(hitMaterialId, Palette.WOOD_DARK)
        hitMaterialId == -1 -> Palette.DIRT
        else -> Palette.DOOR
    }

    /** Explosion als 5-Phasen-Sequenz (Stil-Bibel §5): Blitz → Feuerball → Schockwelle → Trümmer → Rauch. */
    private fun explosion(x: Float, y: Float, radius: Float, damage: Float, col: Int, wind: Float) {
        val r = if (radius < 0.5f) 0.5f else radius
        val k = (r / 2.5f).coerceIn(0.45f, 2.6f)
        if (!reducedMotion) emit(ParticleKind.FLASH, x, y, 0f, 0f, FLASH_LIFE, r * 1.5f)
        emit(ParticleKind.FIREBALL, x, y, 0f, 0f, 0.3f, r * 0.95f)
        emit(ParticleKind.SHOCKWAVE, x, y, 0f, 0f, 0.38f, r * 2.1f)
        val nCh = ((6f + damage / 20f).coerceIn(6f, 12f) * (if (reducedMotion) 0.6f else 1f) + 0.5f).toInt()
        val sk = sqrt(k)
        for (i in 0 until nCh) {
            val a = rng.range(-PI.toFloat() * 0.95f, -PI.toFloat() * 0.05f)
            val s = rng.range(3f, 9f) * sk
            emit(ParticleKind.CHUNK, x, y, cos(a) * s, sin(a) * s, rng.range(1.6f, 2.8f), rng.range(0.1f, 0.22f) * sk, col)
        }
        var n = ((7f + 5f * k) * red + 0.5f).toInt()
        for (i in 0 until n) {
            val a = rng.next() * TAU
            val rr = rng.range(0.2f, 0.9f) * r * 0.5f
            emit(ParticleKind.SMOKE, x + cos(a) * rr, y + sin(a) * rr * 0.6f, rng.range(-0.8f, 0.8f) + wind * 0.25f, rng.range(-1.4f, -0.3f), rng.range(2.6f, 3.4f), rng.range(0.6f, 1.1f) * k)
        }
        n = ((6f * k + 4f) * red + 0.5f).toInt()
        for (i in 0 until n) emit(ParticleKind.DUST, x + rng.range(-1f, 1f) * k, y + rng.range(-0.3f, 0.3f), rng.range(-5f, 5f) * k, rng.range(-3.5f, 0f), rng.range(0.7f, 1.4f), rng.range(0.4f, 0.8f) * k)
        n = ((10f * k + 8f) * red + 0.5f).toInt()
        for (i in 0 until n) emit(ParticleKind.SPARK, x, y, rng.range(-9f, 9f), rng.range(-11f, 2f), rng.range(0.25f, 0.7f), 1f)
        n = (8f * k * red + 0.5f).toInt()
        for (i in 0 until n) emit(ParticleKind.EMBER, x + rng.range(-0.4f, 0.4f), y + rng.range(-0.4f, 0.4f), rng.range(-3f, 3f), rng.range(-5f, -1f), rng.range(0.8f, 1.6f), rng.range(0.03f, 0.06f))
    }

    private fun beamBroken(e: FxEvent.BeamBroken, wind: Float) {
        val mk = if (e.materialId >= 0 && e.materialId < matKinds.size) matKinds[e.materialId] else MatKind.WOOD
        val wood = mk == MatKind.WOOD
        val rope = mk == MatKind.ROPE
        val col = matColor(e.materialId, Palette.WOOD)
        when (e.cause) {
            BreakCause.DECAY -> puff(e.x, e.y, 0.3f, 2)
            else -> {
                if (wood) {
                    for (i in 0 until (6 * red + 0.5f).toInt()) {
                        val a = rng.range(-PI.toFloat(), 0f)
                        val s = rng.range(2f, 6f)
                        emit(ParticleKind.SPLINTER, e.x, e.y, cos(a) * s, sin(a) * s, rng.range(0.8f, 1.5f), rng.range(0.08f, 0.16f), col)
                    }
                    puff(e.x, e.y, 0.4f, 2)
                } else if (rope) {
                    for (i in 0 until 3) emit(ParticleKind.DEBRIS, e.x, e.y, rng.range(-2f, 2f), rng.range(-3f, -0.5f), rng.range(0.6f, 1f), rng.range(0.04f, 0.07f), col)
                } else {
                    // Metall/Panzer/Tür: abgescherte Enden, Funkenregen
                    for (i in 0 until (14 * red + 0.5f).toInt()) emit(ParticleKind.SPARK, e.x, e.y, rng.range(-7f, 7f), rng.range(-9f, 1f), rng.range(0.25f, 0.6f), 1f)
                    for (i in 0 until 3) emit(ParticleKind.DEBRIS, e.x, e.y, rng.range(-3f, 3f), rng.range(-4f, -1f), rng.range(0.8f, 1.3f), rng.range(0.06f, 0.1f), col)
                }
                if (e.cause == BreakCause.FIRE) {
                    for (i in 0 until 3) emit(ParticleKind.EMBER, e.x, e.y, rng.range(-1.5f, 1.5f), rng.range(-4f, -1f), rng.range(0.8f, 1.5f), rng.range(0.03f, 0.06f))
                    emit(ParticleKind.SMOKE, e.x, e.y, wind * 0.2f, rng.range(-1.2f, -0.5f), rng.range(1.6f, 2.4f), rng.range(0.4f, 0.7f))
                }
            }
        }
    }

    private fun fired(e: FxEvent.Fired, wind: Float) {
        val a = e.angle
        val idx = e.weaponId
        val s = muzzleScaleOf(if (idx >= 0 && idx < weaponKinds.size) weaponKinds[idx] else DevKind.GENERIC)
        // Richtung in vx/vy (Einheitsvektor), Position bleibt an der Mündung
        emit(ParticleKind.MUZZLE_FLASH, e.x, e.y, cos(a), -sin(a), MUZZLE_LIFE, s)
        if (s > 0.8f) {
            for (i in 0 until (3 * red + 0.5f).toInt()) emit(ParticleKind.SMOKE, e.x, e.y, cos(a) * 1.5f + rng.range(-0.5f, 0.5f) + wind * 0.2f, -sin(a) * 1.5f + rng.range(-1f, 0f), rng.range(0.9f, 1.6f), rng.range(0.35f, 0.6f))
        }
    }

    /** Mündungsfeuer-Größe je Waffenart (Stil-Bibel §5: MG klein, Kanone groß). */
    private fun muzzleScaleOf(kind: Int): Float = when (kind) {
        DevKind.MG -> 0.55f
        DevKind.SNIPER -> 0.75f
        DevKind.MORTAR -> 0.9f
        DevKind.CANNON -> 1.1f
        DevKind.ROCKET -> 0.85f
        DevKind.LASER -> 0.5f
        else -> 0.8f
    }

    private fun hit(e: FxEvent.Hit) {
        if (e.splash) return // Explosionsschaden: nur Treffer-Blitz (FxState), Partikel kommen von der Explosion
        val col = when (e.target) {
            HitTarget.BEAM -> Palette.WOOD_DARK
            HitTarget.DEVICE -> Palette.STEEL_HI
            HitTarget.TERRAIN -> Palette.DIRT
        }
        for (i in 0 until (3 * red + 0.5f).toInt()) emit(ParticleKind.DEBRIS, e.x, e.y, rng.range(-3f, 3f), rng.range(-4f, -0.5f), rng.range(0.8f, 1.4f), rng.range(0.05f, 0.1f), col)
        if (e.target == HitTarget.DEVICE) for (i in 0 until 4) emit(ParticleKind.SPARK, e.x, e.y, rng.range(-5f, 5f), rng.range(-6f, 0f), rng.range(0.15f, 0.4f), 1f)
        if (e.target == HitTarget.TERRAIN) puff(e.x, e.y, 0.35f, 2)
    }

    private fun puff(x: Float, y: Float, r: Float, n: Int) {
        for (i in 0 until n) emit(ParticleKind.DUST, x + rng.range(-r, r) * 0.4f, y + rng.range(-r, r) * 0.4f, rng.range(-1f, 1f), rng.range(-1.5f, -0.3f), rng.range(0.6f, 1.1f), rng.range(0.25f, 0.5f) * (r / 0.4f).coerceIn(0.6f, 1.6f))
    }

    // ---------------------------------------------------------------------------------------------
    // Fortschreiben
    // ---------------------------------------------------------------------------------------------

    override fun update(dt: Float, wind: Float) {
        val b = buffers
        var i = b.count - 1
        while (i >= 0) {
            val k = b.kind[i]
            b.age[i] += dt
            if (b.age[i] >= b.life[i]) {
                remove(i)
                i--
                continue
            }
            var move = true
            when (k) {
                SMOKE -> {
                    val hgt = ((groundY - b.y[i]) / 14f).coerceIn(0f, 1f)
                    b.vx[i] += (wind * (0.25f + 0.55f * hgt) - b.vx[i]) * dt * 0.7f
                    b.vy[i] += (-0.7f - b.vy[i]) * dt * 0.45f
                }
                EMBER -> {
                    b.vy[i] += (-1.6f - b.vy[i]) * dt * 1.5f
                    b.vx[i] += (wind * 0.45f - b.vx[i]) * dt * 1.2f + sin(b.age[i] * 9f + b.seed[i]) * dt * 3f
                }
                DUST -> {
                    b.vx[i] *= 1f - dt * 2f
                    b.vy[i] *= 1f - dt * 2f
                    b.vy[i] += dt * 0.25f
                }
                SPARK -> b.vy[i] += dt * 9f
                CHUNK, SPLINTER, DEBRIS -> {
                    b.vy[i] += dt * G
                    b.rot[i] += b.vrot[i] * dt
                    val t = terrain
                    if (t != null) {
                        val gy = t.heightAt(b.x[i]) - b.size[i] * 0.5f
                        if (b.y[i] > gy) {
                            if ((b.flags[i] and 1) == 0 && b.vy[i] > 3f) {
                                emit(ParticleKind.DUST, b.x[i], gy, rng.range(-1f, 1f), rng.range(-1f, -0.2f), rng.range(0.5f, 0.9f), rng.range(0.2f, 0.4f))
                            }
                            b.flags[i] = b.flags[i] or 1
                            b.y[i] = gy
                            b.vy[i] *= -0.32f
                            b.vx[i] *= 0.55f
                            b.vrot[i] *= 0.5f
                        }
                    }
                }
                FLASH, FIREBALL, SHOCKWAVE, MUZZLE -> move = false
            }
            if (move) {
                b.x[i] += b.vx[i] * dt
                b.y[i] += b.vy[i] * dt
            }
            i--
        }
    }

    private fun remove(i: Int) {
        val b = buffers
        val last = b.count - 1
        if (i != last) {
            b.kind[i] = b.kind[last]; b.x[i] = b.x[last]; b.y[i] = b.y[last]
            b.vx[i] = b.vx[last]; b.vy[i] = b.vy[last]
            b.age[i] = b.age[last]; b.life[i] = b.life[last]; b.size[i] = b.size[last]
            b.rot[i] = b.rot[last]; b.vrot[i] = b.vrot[last]
            b.color[i] = b.color[last]; b.seed[i] = b.seed[last]; b.flags[i] = b.flags[last]
        }
        b.count = last
    }

    companion object {
        const val DEFAULT_CAP: Int = 600
        const val REDUCED_CAP: Int = 260
        /** Weiß-Blitz: ein Frame (1/50 s). */
        const val FLASH_LIFE: Float = 0.03f
        const val MUZZLE_LIFE: Float = 0.09f
        private const val G = 9.81f
        private const val TAU = (2.0 * PI).toFloat()

        private val SMOKE = ParticleKind.SMOKE.ordinal
        private val EMBER = ParticleKind.EMBER.ordinal
        private val DUST = ParticleKind.DUST.ordinal
        private val SPARK = ParticleKind.SPARK.ordinal
        private val CHUNK = ParticleKind.CHUNK.ordinal
        private val SPLINTER = ParticleKind.SPLINTER.ordinal
        private val DEBRIS = ParticleKind.DEBRIS.ordinal
        private val FLASH = ParticleKind.FLASH.ordinal
        private val FIREBALL = ParticleKind.FIREBALL.ordinal
        private val SHOCKWAVE = ParticleKind.SHOCKWAVE.ordinal
        private val MUZZLE = ParticleKind.MUZZLE_FLASH.ordinal

        /** Materialfarben in Content-Reihenfolge: Holz, Metall, Panzer, Seil, Tür. */
        val DEFAULT_MATERIAL_COLORS: IntArray = intArrayOf(Palette.WOOD, Palette.METAL, Palette.ARMOR, Palette.ROPE, Palette.DOOR)
        private val DEFAULT_MATERIAL_KINDS: IntArray = intArrayOf(MatKind.WOOD, MatKind.METAL, MatKind.ARMOUR, MatKind.ROPE, MatKind.DOOR)
        /** Waffenarten in Content-Reihenfolge: MG, Scharfschütze, Mörser, Kanone, Brandrakete, Laser. */
        private val DEFAULT_WEAPON_KINDS: IntArray = intArrayOf(DevKind.MG, DevKind.SNIPER, DevKind.MORTAR, DevKind.CANNON, DevKind.ROCKET, DevKind.LASER)
    }
}
