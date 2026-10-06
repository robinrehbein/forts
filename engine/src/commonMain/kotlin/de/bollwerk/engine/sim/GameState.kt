package de.bollwerk.engine.sim

import de.bollwerk.engine.rng.SplitMix64
import de.bollwerk.engine.view.GameView

/**
 * Feste RNG-Strom-IDs. Jedes System zieht nur aus "seinem" Strom, damit ein neuer oder geänderter
 * Verbraucher (z. B. MG-Streuung) die Zufallsfolgen anderer Systeme (Feuer, Trümmer, Wind) nicht verschiebt.
 */
object RngStreams {
    /** Allgemein (Regeln ohne eigenen Strom). */
    const val CORE: Long = 0
    const val WIND: Long = 1
    const val FIRE: Long = 2
    const val DEBRIS: Long = 3
    const val WEAPON: Long = 4

    /** KI-Strom je Spieler (abgeleitet vom Seed, nicht vom Sim-Zustand). */
    fun ai(playerId: Int): Long = 1000L + playerId
}

/**
 * Vollständiger, deterministischer Simulationszustand. Alles, was den Spielausgang beeinflusst,
 * liegt hier (und geht in den [de.bollwerk.engine.loop.StateHash] ein). Rendering, Partikel, Audio
 * und Kamera gehören **nicht** hierher.
 *
 * **Threading:** Der `GameState` gehört ausschließlich dem Sim-Thread. Werkzeuge und KI lesen ihn als
 * [GameView] nur auf dem Sim-Thread; der UI-/Render-Thread sieht nur `FrameSnapshot`s.
 *
 * Anlegen einer Partie: `MatchFactory.create(...)`. Der Konstruktor erzeugt einen leeren Zustand.
 *
 * @param seed Startwert; Unterströme siehe [RngStreams].
 * @param tables Content-Tabellen (Pflicht, damit nie unbemerkt leere Tabellen benutzt werden).
 * @param map Karte.
 */
class GameState(
    val seed: Long,
    override val tables: SimTables,
    override val map: MapSpec,
    val config: SimConfig = SimConfig.DEFAULT,
    playerCount: Int = map.playerCount,
) : GameView {
    /** Aktueller Tick (zählt ab 0, wird von [SimStepper] nach allen Systemen erhöht). */
    override var tick: Long = 0L

    private val root = SplitMix64(seed)

    /** Allgemeiner Sim-RNG ([RngStreams.CORE]). */
    val rng: SplitMix64 = root.derive(RngStreams.CORE)
    val rngWind: SplitMix64 = root.derive(RngStreams.WIND)
    val rngFire: SplitMix64 = root.derive(RngStreams.FIRE)
    val rngDebris: SplitMix64 = root.derive(RngStreams.DEBRIS)
    val rngWeapon: SplitMix64 = root.derive(RngStreams.WEAPON)

    /** Alle Sim-Ströme in fester Reihenfolge (Hash, Serialisierung). */
    val rngStreams: List<SplitMix64> = listOf(rng, rngWind, rngFire, rngDebris, rngWeapon)

    /** Windgeschwindigkeit in m/s (positiv = nach rechts). */
    override var wind: Float = 0f

    val nodes: NodePool = NodePool().also { it.substepDt = config.substepDt }
    val beams: BeamPool = BeamPool()
    val devices: DevicePool = DevicePool()
    val projectiles: ProjectilePool = ProjectilePool()

    /** Alle Pools in fester Reihenfolge. */
    val pools: List<Pool> = listOf(nodes, beams, devices, projectiles)

    /** Spieler in fester Reihenfolge, Index = Spieler-ID. Startressourcen setzt [MatchFactory]. */
    val players: List<PlayerState> = List(playerCount) { id ->
        PlayerState(id, 0f, 0f, config.metalCap, config.energyCap, config.undoDepth)
    }

    override var result: GameResult = GameResult.Ongoing

    /** Zugzustand (schreibbar nur für Systeme). */
    val turnState: TurnState = TurnState()
    override val turn: TurnView get() = turnState

    /** Markiert, dass Konnektivität, Massen, Adjazenz und Lösungsreihenfolge neu berechnet werden müssen. */
    var topologyDirty: Boolean = true

    /** Vom [SimStepper] vor allen Systemen aufgerufen: Pool-Zeitstempel und Interpolations-Startpunkte. */
    fun beginTick() {
        for (p in pools) p.now = tick
        val n = nodes
        for (i in 0 until n.size) { n.tickX[i] = n.x[i]; n.tickY[i] = n.y[i] }
        val pr = projectiles
        for (i in 0 until pr.size) { pr.tickX[i] = pr.x[i]; pr.tickY[i] = pr.y[i] }
    }

    /** Vom [SimStepper] nach allen Systemen aufgerufen: verzögerte Freigaben übernehmen. */
    fun endTick() {
        for (p in pools) p.endTick()
    }

    /**
     * Baut alle DERIVED-Daten neu (nach Laden, Lockstep-Rollback oder Aufbau): setzt [topologyDirty], damit
     * das Topologie-/Physik-System Konnektivität, Massen und Lösungsreihenfolge neu berechnet, und baut die
     * Knoten-Adjazenz sofort neu. Gerätepositionen berechnet das Geräte-System im nächsten Tick.
     */
    fun rebuildDerived() {
        topologyDirty = true
        nodes.rebuildAdjacency(beams)
    }

    // ---- GameView ----
    override val terrain: Terrain get() = map.terrain
    override val nodeView: NodeView get() = nodes
    override val beamView: BeamView get() = beams
    override val deviceView: DeviceView get() = devices
    override val projectileView: ProjectileView get() = projectiles
    override val playerCount: Int get() = players.size
    override fun player(id: Int): PlayerView = players[id]
    override val simConfig: SimConfig get() = config
}
