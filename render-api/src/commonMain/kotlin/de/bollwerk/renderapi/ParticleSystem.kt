package de.bollwerk.renderapi

import de.bollwerk.engine.view.FxEvent

/** Partikelarten (Stil-Bibel §5). */
enum class ParticleKind {
    SPARK, EMBER, SMOKE, DUST, CHUNK, FLASH, FIREBALL, SHOCKWAVE, MUZZLE_FLASH, SPLINTER, DEBRIS,
}

/**
 * Rein visuelle Partikel (kein Sim-State, darf eigenen nicht-deterministischen Zufall nutzen).
 * Wird im Render-Thread mit realer Frame-Zeit fortgeschrieben.
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
}
