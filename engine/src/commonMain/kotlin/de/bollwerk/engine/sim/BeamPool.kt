package de.bollwerk.engine.sim

/** Flag-Bits für [BeamPool.flags]. */
object BeamFlags {
    const val ALIVE: Int = Pool.ALIVE
    /** Teil einer unverankerten Komponente (Trümmer). */
    const val DEBRIS: Int = 1 shl 1
    /** Tür-Material: geöffnet, Projektile passieren (Türen sind Balken, siehe Stil-Bibel). */
    const val DOOR_OPEN: Int = 1 shl 2
    /** Hälfte eines gebrochenen Balkens (bricht nicht erneut in Hälften, Prototyp `half`). */
    const val HALF: Int = 1 shl 3
    /** Wird gerade repariert (Rate `RepairConfig.hpFractionPerSec`, pausiert solange er brennt). */
    const val REPAIRING: Int = 1 shl 4
    /** Noch im Bau (reserviert; Balken entstehen im Prototyp sofort). */
    const val BUILDING: Int = 1 shl 5
    /** Tür vom Spieler offen fixiert: kein automatisches Schließen (Prototyp `pinned`). */
    const val DOOR_PINNED: Int = 1 shl 6
    /** Ende A ist gezackt (Bruchkante). */
    const val JAG_A: Int = 1 shl 7
    /** Ende B ist gezackt (Bruchkante). */
    const val JAG_B: Int = 1 shl 8
    /** Tür-Scharnier sitzt an Ende B (sonst A). */
    const val DOOR_HINGE_B: Int = 1 shl 9
}

/** Lesender Zugriff auf Balken. */
interface BeamView : PoolView {
    fun nodeA(id: Int): Int
    fun nodeB(id: Int): Int
    fun material(id: Int): Int
    fun hp(id: Int): Float
    fun maxHp(id: Int): Float
    fun fire(id: Int): Float
    /** Verbleibender Brennstoff 0..1. */
    fun fuel(id: Int): Float
    fun restLength(id: Int): Float
    /** Relative Dehnung, positiv = Zug. */
    fun strain(id: Int): Float
    fun owner(id: Int): Int
    fun flags(id: Int): Int
    /**
     * Restzeit (Ticks) einer automatisch geöffneten Tür bis zum Schließen (0 = kein Timer). FX1, additiv: die
     * Zielvorschau (`ShotSweep`) sagt damit voraus, ob eine gerade offene Tür während des Flugs zufällt.
     */
    fun doorTimer(id: Int): Int = 0
}

/**
 * Balken (XPBD-Abstandsconstraints) als Structure-of-Arrays.
 * [materialOf] ist ein Index in die Material-Liste von `ContentDb` / [SimTables.materials].
 */
class BeamPool(initialCapacity: Int = 256) : Pool(initialCapacity), BeamView {
    /** PERSISTENT. Knoten-ID an Ende A. */
    var a = IntArray(initialCapacity); private set
    /** PERSISTENT. Knoten-ID an Ende B. */
    var b = IntArray(initialCapacity); private set
    /** PERSISTENT. Material-Index. */
    var materialOf = IntArray(initialCapacity); private set
    /** PERSISTENT. Ruhelänge in m. */
    var restLen = FloatArray(initialCapacity); private set
    /** PERSISTENT. */
    var hpOf = FloatArray(initialCapacity); private set
    /** PERSISTENT. */
    var maxHpOf = FloatArray(initialCapacity); private set
    /** DERIVED (vom Physik-System je Tick neu berechnet). Relative Dehnung `(len − rest) / rest` (positiv = Zug). */
    var strainOf = FloatArray(initialCapacity); private set
    /** PERSISTENT. Brandstärke 0..1 (0 = brennt nicht). */
    var fireOf = FloatArray(initialCapacity); private set
    /** PERSISTENT. Verbleibender Brennstoff 0..1 (verkohlt bei niedrigem Wert). */
    var fuelOf = FloatArray(initialCapacity); private set
    /** PERSISTENT. Besitzer (Spieler-ID). */
    var ownerOf = IntArray(initialCapacity); private set
    /** PERSISTENT. Sperrzeit nach Trümmer-Einschlag in Ticks (Prototyp `hitCd`). */
    var hitCooldownTicks = IntArray(initialCapacity); private set
    /** PERSISTENT. Tür: Ticks bis zum automatischen Schließen (0 = kein Timer; Prototyp `auto`). */
    var doorTimerTicks = IntArray(initialCapacity); private set
    /** RENDER. Holzmaserungs-Versatz in m (Kontinuität über Split/Bruch, Prototyp `toff`). */
    var texOffset = FloatArray(initialCapacity); private set
    /** DERIVED/Scratch. XPBD-Lagrange-Akkumulator pro Substep. */
    var lambda = FloatArray(initialCapacity); private set
    /** RENDER. Balken über `SimConfig.creakRatio` seiner Grenzdehnung (Knarzen-Animation), je Tick vom Dehnungs-System gesetzt. */
    var creaking = BooleanArray(initialCapacity); private set
    /**
     * DERIVED. Gauss-Seidel-Lösungsreihenfolge (Balken-IDs, `0 until solveCount`): alle lebenden Balken mit
     * mindestens einem beweglichen Knoten in **aufsteigender Balken-ID** (feste Index-Reihenfolge, nur aus
     * PERSISTENT-Daten ableitbar, damit ein Restore/Rollback bitgleich weiterrechnet). Vom Physik-System
     * (`de.bollwerk.engine.physics`) bei Topologieänderung neu aufgebaut.
     */
    var solveOrder = IntArray(initialCapacity); private set
    var solveCount: Int = 0

    /**
     * DERIVED. Kennung des Physik-Caches (`de.bollwerk.engine.physics.PhysicsWorld`), für den [solveOrder] und die
     * Material-Caches zuletzt aufgebaut wurden, und dessen Aufbau-Nummer. Der Zustand verweist auf den Cache, nie
     * umgekehrt. Nicht serialisiert/gehasht; `null` (z. B. nach Restore) erzwingt einen Neuaufbau.
     */
    var solveCacheOwner: Any? = null
    var solveCacheEpoch: Int = 0

    /**
     * Legt einen Balken zwischen den Knoten [nodeA] und [nodeB] an.
     * @param texOffset Maserungs-Versatz (bei Split/Bruch vom Original übernehmen); NaN = aus der uid ableiten
     *   (Prototyp `(id · 2,371) mod 4`).
     */
    fun alloc(nodeA: Int, nodeB: Int, material: Int, restLength: Float, maxHp: Float, owner: Int, texOffset: Float = Float.NaN): Int {
        val id = allocSlot()
        a[id] = nodeA; b[id] = nodeB
        materialOf[id] = material
        restLen[id] = restLength
        hpOf[id] = maxHp; maxHpOf[id] = maxHp
        strainOf[id] = 0f; fireOf[id] = 0f; fuelOf[id] = 1f
        ownerOf[id] = owner
        hitCooldownTicks[id] = 0; doorTimerTicks[id] = 0
        this.texOffset[id] = if (texOffset.isNaN()) (uidOf[id] * 2.371f) % 4f else texOffset
        lambda[id] = 0f
        creaking[id] = false
        return id
    }

    override fun resize(newCapacity: Int) {
        a = a.copyOf(newCapacity); b = b.copyOf(newCapacity)
        materialOf = materialOf.copyOf(newCapacity); restLen = restLen.copyOf(newCapacity)
        hpOf = hpOf.copyOf(newCapacity); maxHpOf = maxHpOf.copyOf(newCapacity)
        strainOf = strainOf.copyOf(newCapacity); fireOf = fireOf.copyOf(newCapacity)
        fuelOf = fuelOf.copyOf(newCapacity); ownerOf = ownerOf.copyOf(newCapacity)
        hitCooldownTicks = hitCooldownTicks.copyOf(newCapacity); doorTimerTicks = doorTimerTicks.copyOf(newCapacity)
        texOffset = texOffset.copyOf(newCapacity)
        lambda = lambda.copyOf(newCapacity)
        creaking = creaking.copyOf(newCapacity)
        solveOrder = solveOrder.copyOf(newCapacity)
    }

    override fun nodeA(id: Int): Int = a[id]
    override fun nodeB(id: Int): Int = b[id]
    override fun material(id: Int): Int = materialOf[id]
    override fun hp(id: Int): Float = hpOf[id]
    override fun maxHp(id: Int): Float = maxHpOf[id]
    override fun fire(id: Int): Float = fireOf[id]
    override fun fuel(id: Int): Float = fuelOf[id]
    override fun restLength(id: Int): Float = restLen[id]
    override fun strain(id: Int): Float = strainOf[id]
    override fun owner(id: Int): Int = ownerOf[id]
    override fun flags(id: Int): Int = flags[id]
    override fun doorTimer(id: Int): Int = doorTimerTicks[id]
}
