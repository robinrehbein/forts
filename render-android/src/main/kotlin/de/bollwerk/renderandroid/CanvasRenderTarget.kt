package de.bollwerk.renderandroid

import de.bollwerk.renderapi.RenderTarget

/**
 * [RenderTarget] auf der Spielfläche. Größe und Dichte werden je Frame aus der Canvas der Oberfläche übernommen
 * ([update]); der gemeinsame Renderer liest nur diese Werte und die [sink].
 */
class CanvasRenderTarget(override val sink: CanvasDrawSink) : RenderTarget {
    override var widthPx: Int = 0
        private set
    override var heightPx: Int = 0
        private set
    override var density: Float = 1f
        private set

    fun update(widthPx: Int, heightPx: Int, density: Float) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.density = density
    }
}
