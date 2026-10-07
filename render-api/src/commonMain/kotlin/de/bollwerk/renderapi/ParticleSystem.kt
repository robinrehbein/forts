package de.bollwerk.renderapi

import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.view.FxEvent

/** Partikelarten (Stil-Bibel §5). */
enum class ParticleKind {
    SPARK, EMBER, SMOKE, DUST, CHUNK, FLASH, FIREBALL, SHOCKWAVE, MUZZLE_FLASH, SPLINTER, DEBRIS,
}

/**
 * Rein visuelle Partikel (kein Sim-State, darf eigenen nicht-deterministischen Zufall nutzen).
 *
 * **Besitzer ist der Szenen-Renderer:** `SceneRenderer.render` ruft [onFx] genau einmal je neuer `snap.seq` und
 * [update] je Frame selbst auf. Die Plattform (Render-Thread, Simrunner) darf beides **nicht** zusätzlich tun
 * (sonst doppelte Effekte und doppelt schnelle Partikel); sie reicht nur dasselbe System jedes Frame durch und
 * setzt bei Bedarf `SceneRenderer.frameDtSeconds` (reale Frame-Zeit, sonst Sim-Zeit).
 */
interface ParticleSystem {
    /** Anzahl aktiver Partikel. */
    val count: Int

    /** Einzelnes Partikel ausstoßen; [color] ARGB (0 = Standard der Art). */
    fun emit(kind: ParticleKind, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, color: Int = 0)

    /** Übersetzt ein Sim-Ereignis in Partikel (Explosion → Blitz, Feuerball, Ring, Trümmer, Rauch …). */
    fun onFx(event: FxEvent, wind: Float)

    /** Fortschreiben um [dt] Sekunden (Wind driftet Rauch/Glut). */
    fun update(dt: Float, wind: Float)

    fun clear()

    /**
     * Struktur-aus-Arrays-Sicht auf die aktiven Partikel für den Szenen-Renderer (Indizes `0 until count`).
     * Additiv (WP6): [ParticleBuffers] ist die gemeinsame Austauschform ohne Allokation je Frame.
     */
    val buffers: ParticleBuffers

    /**
     * Wie [bindWorld] mit zusätzlichen Darstellungsklassen aus dem Content, damit Splitter, Funken und
     * Mündungsfeuer nicht von der Reihenfolge der JSON-Dateien abhängen. [materialKinds] je Material-Index:
     * 0 Holz, 1 Metall, 2 Panzer, 3 Seil, 4 Tür. [weaponKinds] je Waffen-Index: 7 MG, 8 Scharfschütze, 9 Mörser,
     * 10 Kanone, 11 Brandrakete, 12 Laser, sonst 13. Der Szenen-Renderer ruft diese Variante. Standard: ruft
     * [bindWorld] ohne Klassen.
     */
    fun bindWorld(materialColors: IntArray, materialKinds: IntArray, weaponKinds: IntArray, terrain: Terrain?) =
        bindWorld(materialColors, terrain)

    /**
     * Teilt dem System die Welt mit: [materialColors] (Index = Material-Index, ARGB, für Trümmerstücke)
     * und das [terrain] (Trümmer prallen auf, Staub). Der Szenen-Renderer ruft es einmal nach `bind`.
     * Standard: keine Wirkung.
     */
    fun bindWorld(materialColors: IntArray, terrain: Terrain?) {}
}

/**
 * Gemeinsame Partikel-Arrays (SoA, feste Kapazität [capacity]). Nur [count] Einträge sind gültig.
 * `age` und `life` in Sekunden; [kind] ist `ParticleKind.ordinal`; [flags] Bit 0 = hat den Boden berührt.
 */
class ParticleBuffers(val capacity: Int) {
    var count: Int = 0
    val kind = IntArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val age = FloatArray(capacity)
    val life = FloatArray(capacity)
    val size = FloatArray(capacity)
    val rot = FloatArray(capacity)
    val vrot = FloatArray(capacity)
    val color = IntArray(capacity)
    val seed = IntArray(capacity)
    val flags = IntArray(capacity)
}
