package de.bollwerk.engine.sim

/** Flag-Bits für [ProjectilePool.flags]. */
object ProjectileFlags {
    const val ALIVE: Int = Pool.ALIVE
    /** Brandgeschoss (entzündet Holz in `WeaponProps.igniteRadius`). */
    const val INCENDIARY: Int = 1 shl 1
}

/** Lesender Zugriff auf Projektile. */
interface ProjectileView : PoolView {
    fun x(id: Int): Float
    fun y(id: Int): Float
    fun vx(id: Int): Float
    fun vy(id: Int): Float
    /** Waffen-Index. */
    fun kind(id: Int): Int
    fun owner(id: Int): Int
    fun ageTicks(id: Int): Int
    fun flags(id: Int): Int
}

/**
 * Projektile als Structure-of-Arrays. [kindOf] ist ein Index in die Waffen-Liste von `ContentDb` /
 * [SimTables.weapons]. Bewegung explizit ballistisch über `Ballistics.step` mit
 * `SimConfig.projectileSubsteps` Teilschritten; [px]/[py] = Position vor dem letzten Teilschritt (Swept-Test).
 */
class ProjectilePool(initialCapacity: Int = 64) : Pool(initialCapacity), ProjectileView {
    /** PERSISTENT. */
    var x = FloatArray(initialCapacity); private set
    var y = FloatArray(initialCapacity); private set
    /** PERSISTENT. Position vor dem letzten Teilschritt (für Swept-Capsule-Tests). */
    var px = FloatArray(initialCapacity); private set
    var py = FloatArray(initialCapacity); private set
    /** PERSISTENT. Geschwindigkeit in m/s. */
    var vx = FloatArray(initialCapacity); private set
    var vy = FloatArray(initialCapacity); private set
    /** PERSISTENT. Waffen-Index. */
    var kindOf = IntArray(initialCapacity); private set
    var ownerOf = IntArray(initialCapacity); private set
    /** PERSISTENT. Verbleibende Lebensdauer in Ticks. */
    var ttl = IntArray(initialCapacity); private set
    /** PERSISTENT. Alter in Ticks. */
    var ageTicks = IntArray(initialCapacity); private set
    /**
     * PERSISTENT. **uid** (`PoolView.uid`, nie wiederverwendet) des abschießenden Geräts, −1 = keines. Bewusst kein
     * Slot: das Projektil lebt bis zu 16 s, der Slot einer zerstörten Waffe kann inzwischen neu belegt sein.
     */
    var sourceDevice = IntArray(initialCapacity); private set
    /**
     * PERSISTENT. Ref des Balkens, auf dem die Waffe sitzt; wird ignoriert, solange
     * `ageTicks < CombatConfig.sourceIgnoreTicks` (Prototyp: `ign` 0,25 s).
     */
    var ignoreBeamRef = LongArray(initialCapacity); private set
    /** PERSISTENT. Noch durchschlagbare Balken (Scharfschütze). */
    var pierceLeft = IntArray(initialCapacity); private set
    /** RENDER. Position zu Beginn des laufenden Ticks (Interpolations-Startpunkt). */
    var tickX = FloatArray(initialCapacity); private set
    var tickY = FloatArray(initialCapacity); private set

    fun alloc(
        x: Float, y: Float, vx: Float, vy: Float,
        kind: Int, owner: Int, ttlTicks: Int,
        sourceDevice: Int = -1,
        ignoreBeamRef: Long = PoolView.NO_REF,
        pierceLeft: Int = 0,
        incendiary: Boolean = false,
    ): Int {
        val id = allocSlot()
        this.x[id] = x; this.y[id] = y; px[id] = x; py[id] = y
        tickX[id] = x; tickY[id] = y
        this.vx[id] = vx; this.vy[id] = vy
        kindOf[id] = kind; ownerOf[id] = owner; ttl[id] = ttlTicks
        ageTicks[id] = 0
        this.sourceDevice[id] = sourceDevice
        this.ignoreBeamRef[id] = ignoreBeamRef
        this.pierceLeft[id] = pierceLeft
        if (incendiary) flags[id] = flags[id] or ProjectileFlags.INCENDIARY
        return id
    }

    override fun resize(newCapacity: Int) {
        x = x.copyOf(newCapacity); y = y.copyOf(newCapacity)
        px = px.copyOf(newCapacity); py = py.copyOf(newCapacity)
        vx = vx.copyOf(newCapacity); vy = vy.copyOf(newCapacity)
        kindOf = kindOf.copyOf(newCapacity); ownerOf = ownerOf.copyOf(newCapacity)
        ttl = ttl.copyOf(newCapacity); ageTicks = ageTicks.copyOf(newCapacity)
        sourceDevice = sourceDevice.copyOf(newCapacity); ignoreBeamRef = ignoreBeamRef.copyOf(newCapacity)
        pierceLeft = pierceLeft.copyOf(newCapacity)
        tickX = tickX.copyOf(newCapacity); tickY = tickY.copyOf(newCapacity)
    }

    override fun x(id: Int): Float = x[id]
    override fun y(id: Int): Float = y[id]
    override fun vx(id: Int): Float = vx[id]
    override fun vy(id: Int): Float = vy[id]
    override fun kind(id: Int): Int = kindOf[id]
    override fun owner(id: Int): Int = ownerOf[id]
    override fun ageTicks(id: Int): Int = ageTicks[id]
    override fun flags(id: Int): Int = flags[id]
}
