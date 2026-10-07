package de.bollwerk.simrunner

import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxBuffer
import de.bollwerk.engine.view.SnapshotBuilder
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.PooledParticleSystem
import de.bollwerk.renderapi.scene.SceneRenderer
import java.awt.AlphaComposite
import java.io.File
import javax.imageio.ImageIO

/**
 * Rendert die Partie mit dem gemeinsamen [SceneRenderer] in ein [java.awt.image.BufferedImage]. Der Renderer
 * läuft in den Ticks vor dem Zeitpunkt mit ([frame]), damit Partikel, Flammen und Explosionen sichtbar sind;
 * [save] schreibt den zuletzt gezeichneten Frame als PNG. Reine Darstellung, nie Teil des Sim-States.
 */
class SceneCapture(state: GameState, val width: Int, val height: Int, density: Float = 1f, view: FloatArray? = null) {
    private val target = AwtTarget(width, height, density)
    private val renderer = SceneRenderer()
    private val particles = PooledParticleSystem(seed = 3)
    private val snapshot = FrameSnapshot()
    private val builder = SnapshotBuilder(localPlayer = -1)
    private val camera = Camera(width.toFloat(), height.toFloat(), density)

    init {
        renderer.bind(state.tables, state.map, target)
        if (view != null) camera.fitRect(view[0], view[1], view[2], view[3])
        else {
            val r = defaultView(state)
            camera.fitRect(r[0], r[1], r[2], r[3])
        }
    }

    /** Wurde seit dem Anlegen mindestens ein Frame gezeichnet? */
    var drawn: Boolean = false; private set

    /** Zeichnet den Zustand [state] (Alpha 1 = Tickende); `fx` wird dabei geleert. */
    fun frame(state: GameState, fx: FxBuffer) {
        builder.build(state, snapshot, fx)
        val g = target.image.createGraphics()
        g.composite = AlphaComposite.Clear
        g.fillRect(0, 0, width, height)
        g.dispose()
        renderer.render(target, snapshot, 1f, camera, OverlayState.NONE, particles)
        drawn = true
    }

    /** Einfarbig oder ganz transparent: das Bild ist mit Sicherheit kaputt (nichts gezeichnet, Vollbild-Blitz). */
    fun isUniform(): Boolean = isUniform(target.image)

    fun save(file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        check(ImageIO.write(target.image, "png", file)) { "no PNG writer available" }
    }

    companion object {
        /** Kopfraum über der höchsten Festungs-Unterkante für Flugbahnen (m). */
        const val HEADROOM = 10f

        /** Ausschnitt aus den Daten: ganze Kartenbreite; oben höchster lebender Knoten minus Kopfraum, unten tiefster Festungsgrund (`baseY`) plus 6 m (y zeigt nach unten; die Schlucht bleibt bewusst angeschnitten). */
        fun defaultView(state: GameState): FloatArray {
            val m = state.map
            var minY = Float.MAX_VALUE
            val n = state.nodes
            for (i in 0 until n.size) if (n.isAlive(i) && n.y[i] < minY) minY = n.y[i]
            var maxGround = 0f
            for (b in m.baseY) if (b > maxGround) maxGround = b
            if (minY == Float.MAX_VALUE) minY = maxGround - 30f
            val top = (minY - HEADROOM).coerceAtLeast(0f)
            val bottom = (maxGround + 6f).coerceAtMost(m.height).coerceAtLeast(top + 10f)
            return floatArrayOf(0f, top, m.width, bottom)
        }

        fun isUniform(img: java.awt.image.BufferedImage): Boolean {
            val first = img.getRGB(0, 0)
            for (y in 0 until img.height) for (x in 0 until img.width) if (img.getRGB(x, y) != first) return false
            return true
        }

        /** `out.png` + Tick 3600 → `out_t3600.png`. */
        fun fileFor(base: String, tick: Long, suffix: Boolean): File {
            if (!suffix) return File(base)
            val f = File(base)
            val name = f.name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ".png"
            return File(f.parentFile, "${stem}_t$tick$ext")
        }
    }
}
