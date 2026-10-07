package de.bollwerk.renderandroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Canvas, das nur mitschreibt (die android.jar-Attrappe liefert Standardwerte). [calls] enthält je Aufruf
 * `name(args…)`; [count] zählt je Name. Prüft damit die Weiterleitung der [CanvasDrawSink] ohne Gerät.
 */
class RecordingCanvas : Canvas() {
    val calls = ArrayList<String>()
    private val counts = HashMap<String, Int>()
    var saveDepth = 0
    var maxSaveDepth = 0
    var w = 1920
    var h = 1080

    private fun rec(name: String, args: String = "") {
        calls.add("$name($args)")
        counts[name] = (counts[name] ?: 0) + 1
    }

    fun count(name: String): Int = counts[name] ?: 0
    fun clear() { calls.clear(); counts.clear() }
    fun last(name: String): String? = calls.lastOrNull { it.startsWith("$name(") }

    override fun getWidth(): Int = w
    override fun getHeight(): Int = h

    override fun save(): Int { rec("save"); saveDepth++; if (saveDepth > maxSaveDepth) maxSaveDepth = saveDepth; return saveDepth }
    override fun restore() { rec("restore"); saveDepth-- }
    override fun restoreToCount(saveCount: Int) { rec("restoreToCount", "$saveCount") }
    override fun translate(dx: Float, dy: Float) = rec("translate", "$dx,$dy")
    override fun rotate(degrees: Float) = rec("rotate", "$degrees")
    override fun scale(sx: Float, sy: Float) = rec("scale", "$sx,$sy")
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean { rec("clipRect", "$left,$top,$right,$bottom"); return true }
    override fun clipPath(path: Path): Boolean { rec("clipPath"); return true }
    override fun drawColor(color: Int) = rec("drawColor", "$color")
    override fun drawPaint(paint: Paint) = rec("drawPaint")
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = rec("drawRect", "$left,$top,$right,$bottom")
    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) = rec("drawLine", "$startX,$startY,$stopX,$stopY")
    override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) = rec("drawLines", "$offset,$count")
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = rec("drawCircle", "$cx,$cy,$radius")
    override fun drawPath(path: Path, paint: Paint) = rec("drawPath")
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) = rec("drawText", "$text,$x,$y")
    override fun drawBitmap(bitmap: Bitmap, src: android.graphics.Rect?, dst: RectF, paint: Paint?) = rec("drawBitmapRect")
    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) = rec("drawBitmap", "$left,$top")
}
