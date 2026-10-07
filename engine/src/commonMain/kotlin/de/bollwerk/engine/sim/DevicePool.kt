package de.bollwerk.engine.sim

/** Flag-Bits für [DevicePool.flags]. */
object DeviceFlags {
    const val ALIVE: Int = Pool.ALIVE
    /** Gerät sitzt auf der negativen Normalenseite des Balkens (Prototyp: `sx < 0`). */
    const val SIDE_NEG: Int = 1 shl 1
    /** Noch im Bau ([DevicePool.buildTicks] > 0). */
    const val BUILDING: Int = 1 shl 2
    /** Waffe feuerbereit. */
    const val READY: Int = 1 shl 3
    /** Deaktiviert (z. B. keine Energie). */
    const val DISABLED: Int = 1 shl 4
    /** Laser feuert gerade ([DevicePool.beamTicksLeft] > 0); Endpunkt in `laserEndX/Y`. */
    const val FIRING_BEAM: Int = 1 shl 5
    /**
     * PERSISTENT (über `flags` gehasht). Schuss angefordert: Das Command-System (WP3) setzt das Bit beim Anwenden
     * von `Command.Fire` (Zielwinkel/Kraft vorher über `SetAim` in `aimAngle`/`power`). Das Waffen-System
     * (`SystemSlot.WEAPONS`, WP4) verbraucht es im selben Tick: löscht es immer, feuert nur, wenn die Waffe fertig
     * gebaut, nachgeladen und bezahlbar ist (sonst `FxEvent.FireRefused` bei fehlenden Ressourcen).
     */
    const val FIRE_REQUESTED: Int = 1 shl 6
}

/** Lesender Zugriff auf Geräte. */
interface DeviceView : PoolView {
    fun type(id: Int): Int
    fun beam(id: Int): Int
    fun t(id: Int): Float
    fun hp(id: Int): Float
    fun maxHp(id: Int): Float
    fun owner(id: Int): Int
    fun aimAngle(id: Int): Float
    fun power(id: Int): Float
    fun reloadTicks(id: Int): Int
    /** Verbleibende Bauzeit in Ticks (0 = fertig). */
    fun buildTicks(id: Int): Int
    /** Verbleibende Schüsse der laufenden Salve. */
    fun burstLeft(id: Int): Int
    fun flags(id: Int): Int
    /** Montagepunkt (DERIVED, siehe [de.bollwerk.engine.math.DeviceGeometry]). */
    fun x(id: Int): Float
    fun y(id: Int): Float
    /** Außennormale am Montagepunkt. */
    fun nx(id: Int): Float
    fun ny(id: Int): Float
}

/**
 * Geräte (Reaktor, Mine, Turbine, Techgebäude, Waffen) als Structure-of-Arrays.
 * Jedes Gerät sitzt an Parameter [tOf] (0..1) auf einem Balken [beamId].
 * [typeOf] ist ein Index in die Geräte-Liste von `ContentDb` / [SimTables.devices].
 *
 * Türzustand liegt am Balken ([BeamFlags.DOOR_OPEN]), da Türen ein Material sind.
 */
class DevicePool(initialCapacity: Int = 64) : Pool(initialCapacity), DeviceView {
    /** PERSISTENT. */
    var typeOf = IntArray(initialCapacity); private set
    /** PERSISTENT. Balken-Slot, auf dem das Gerät sitzt. */
    var beamId = IntArray(initialCapacity); private set
    /** PERSISTENT. Position entlang des Balkens 0..1 (von Knoten A nach B). */
    var tOf = FloatArray(initialCapacity); private set
    var hpOf = FloatArray(initialCapacity); private set
    var maxHpOf = FloatArray(initialCapacity); private set
    var ownerOf = IntArray(initialCapacity); private set
    /** PERSISTENT. Zielwinkel in Bogenmaß, 0 = rechts, positiv = nach oben (Richtung `(cos a, −sin a)`). */
    var aimAngle = FloatArray(initialCapacity); private set
    /** PERSISTENT. Schusskraft `SimConfig.minPower..maxPower`. */
    var power = FloatArray(initialCapacity); private set
    /** PERSISTENT. Verbleibende Nachladezeit in Ticks. */
    var reloadTicksOf = IntArray(initialCapacity); private set
    /** PERSISTENT. Verbleibende Bauzeit in Ticks. */
    var buildTicks = IntArray(initialCapacity); private set
    /** PERSISTENT. Verbleibende Schüsse der laufenden Salve (MG). */
    var burstLeft = IntArray(initialCapacity); private set
    /** PERSISTENT. Ticks bis zum nächsten Salvenschuss. */
    var burstTicks = IntArray(initialCapacity); private set
    /** PERSISTENT. Laser: verbleibende Strahl-Ticks. */
    var beamTicksLeft = IntArray(initialCapacity); private set
    /** DERIVED. Montageposition (vom Geräte-System je Tick über `DeviceGeometry` aktualisiert). */
    var x = FloatArray(initialCapacity); private set
    var y = FloatArray(initialCapacity); private set
    /** DERIVED. Außennormale am Montagepunkt. */
    var nx = FloatArray(initialCapacity); private set
    var ny = FloatArray(initialCapacity); private set
    /** DERIVED. Endpunkt des aktiven Laserstrahls (gültig bei [DeviceFlags.FIRING_BEAM]). */
    var laserEndX = FloatArray(initialCapacity); private set
    var laserEndY = FloatArray(initialCapacity); private set

    /**
     * Legt ein Gerät an. Content-abhängige Werte ([maxHp], [buildTicks]) und die Start-Zielwerte
     * ([aimAngle], [power], typischerweise `WeaponProps.defaultAimDeg` je Seite gespiegelt) übergibt das Regel-System.
     */
    fun alloc(
        type: Int,
        beam: Int,
        t: Float,
        maxHp: Float,
        owner: Int,
        buildTicks: Int,
        sideNegative: Boolean,
        aimAngle: Float,
        power: Float,
    ): Int {
        val id = allocSlot()
        typeOf[id] = type; beamId[id] = beam; tOf[id] = t
        hpOf[id] = maxHp; maxHpOf[id] = maxHp
        ownerOf[id] = owner
        this.aimAngle[id] = aimAngle
        this.power[id] = power
        reloadTicksOf[id] = 0
        this.buildTicks[id] = buildTicks
        burstLeft[id] = 0; burstTicks[id] = 0; beamTicksLeft[id] = 0
        x[id] = 0f; y[id] = 0f; nx[id] = 0f; ny[id] = -1f
        laserEndX[id] = 0f; laserEndY[id] = 0f
        var f = flags[id]
        if (buildTicks > 0) f = f or DeviceFlags.BUILDING
        if (sideNegative) f = f or DeviceFlags.SIDE_NEG
        flags[id] = f
        return id
    }

    override fun resize(newCapacity: Int) {
        typeOf = typeOf.copyOf(newCapacity); beamId = beamId.copyOf(newCapacity)
        tOf = tOf.copyOf(newCapacity); hpOf = hpOf.copyOf(newCapacity); maxHpOf = maxHpOf.copyOf(newCapacity)
        ownerOf = ownerOf.copyOf(newCapacity); aimAngle = aimAngle.copyOf(newCapacity)
        power = power.copyOf(newCapacity); reloadTicksOf = reloadTicksOf.copyOf(newCapacity)
        buildTicks = buildTicks.copyOf(newCapacity); burstLeft = burstLeft.copyOf(newCapacity)
        burstTicks = burstTicks.copyOf(newCapacity); beamTicksLeft = beamTicksLeft.copyOf(newCapacity)
        x = x.copyOf(newCapacity); y = y.copyOf(newCapacity)
        nx = nx.copyOf(newCapacity); ny = ny.copyOf(newCapacity)
        laserEndX = laserEndX.copyOf(newCapacity); laserEndY = laserEndY.copyOf(newCapacity)
    }

    override fun type(id: Int): Int = typeOf[id]
    override fun beam(id: Int): Int = beamId[id]
    override fun t(id: Int): Float = tOf[id]
    override fun hp(id: Int): Float = hpOf[id]
    override fun maxHp(id: Int): Float = maxHpOf[id]
    override fun owner(id: Int): Int = ownerOf[id]
    override fun aimAngle(id: Int): Float = aimAngle[id]
    override fun power(id: Int): Float = power[id]
    override fun reloadTicks(id: Int): Int = reloadTicksOf[id]
    override fun buildTicks(id: Int): Int = buildTicks[id]
    override fun burstLeft(id: Int): Int = burstLeft[id]
    override fun flags(id: Int): Int = flags[id]
    override fun x(id: Int): Float = x[id]
    override fun y(id: Int): Float = y[id]
    override fun nx(id: Int): Float = nx[id]
    override fun ny(id: Int): Float = ny[id]
}
