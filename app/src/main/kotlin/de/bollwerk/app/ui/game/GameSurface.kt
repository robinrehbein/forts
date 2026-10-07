package de.bollwerk.app.ui.game

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import de.bollwerk.app.R
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.TouchSample
import de.bollwerk.app.ui.game.hud.rejectReasonRes
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.renderandroid.GameSurfaceView
import de.bollwerk.renderandroid.RenderSettings
import de.bollwerk.renderandroid.TypefaceProvider
import de.bollwerk.renderandroid.ViewportListener
import de.bollwerk.renderapi.scene.SceneTexts
import java.util.Locale

/**
 * Hostet die [GameSurfaceView] (Render-Thread) für die laufende Partie: hängt Snapshot- und Overlay-Quelle samt Kamera an,
 * leitet Touch-Ereignisse an den `InputController` (Werkzeuge, Pinch/Pan, Langdruck, Doppeltipp) und reicht „Reduzierte
 * Effekte" (Partikel, Shake) und die Pause durch. Bei Neustart ([generation]) wird die Fläche neu aufgebaut.
 */
@Composable
fun GameSurface(
    runtime: GameRuntime?,
    generation: Int,
    reducedEffects: Boolean,
    simRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val base = remember(context) {
        RenderSettings(
            typefaces = TypefaceProvider.of(
                ResourcesCompat.getFont(context, R.font.rajdhani_semibold),
                ResourcesCompat.getFont(context, R.font.rajdhani_bold),
            ),
            texts = LocalizedSceneTexts(context),
            hudTopInsetDp = HUD_TOP_INSET_DP,
        )
    }
    key(generation) {
        AndroidView(
            factory = { ctx -> GameSurfaceView(ctx).also { SurfaceBinding(it).install() } },
            modifier = modifier,
            update = { view ->
                val b = SurfaceBinding.of(view)
                b.bind(runtime, base.copy(reducedMotion = reducedEffects))
                view.setPaused(!simRunning)
            },
            onRelease = { view -> SurfaceBinding.of(view).bind(null, null) },
        )
    }
}

/** HUD-Leiste oben (Chips): Lupe und Chips der Spielfläche weichen ihr aus. */
private const val HUD_TOP_INSET_DP = 62f

/** Verbindung View ↔ Partie (am View als Tag gespeichert, damit `update` sie wiederfindet). */
private class SurfaceBinding(private val view: GameSurfaceView) {
    private var runtime: GameRuntime? = null
    private var settings: RenderSettings? = null
    private val sample = TouchSample()
    private val longPress = Runnable { runtime?.input?.onLongPressTimeout(SystemClock.uptimeMillis()) }

    @SuppressLint("ClickableViewAccessibility") // performClick() wird bei ACTION_UP aufgerufen
    fun install() {
        view.setTag(R.id.game_surface_binding, this)
        view.setOnTouchListener { v, ev -> onTouch(v, ev) }
        view.viewportListener = ViewportListener { w, h, d -> runtime?.director?.onViewport(w.toFloat(), h.toFloat(), d) }
    }

    fun bind(rt: GameRuntime?, s: RenderSettings?) {
        if (rt !== runtime) {
            view.removeCallbacks(longPress)
            runtime = rt
            if (rt == null) {
                view.detach()
                settings = null
                return
            }
            settings = s
            view.attach(rt.snapshotSource, rt.overlaySource, rt.camera, s)
            val d = view.resources.displayMetrics.density
            rt.input.density = d
            if (view.width > 0 && view.height > 0) rt.director.onViewport(view.width.toFloat(), view.height.toFloat(), d)
        } else if (s != null && s != settings) {
            settings = s
            view.updateSettings(s)
        }
    }

    private fun onTouch(v: View, ev: MotionEvent): Boolean {
        val rt = runtime ?: return false
        if (!fill(ev)) return true
        rt.input.density = v.resources.displayMetrics.density
        rt.input.onTouch(sample)
        when (sample.action) {
            TouchSample.DOWN -> v.postDelayed(longPress, rt.input.longPressMs)
            TouchSample.MOVE -> if (!rt.input.singleFingerActive) v.removeCallbacks(longPress)
            else -> v.removeCallbacks(longPress)
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
        return true
    }

    /** MotionEvent → [TouchSample] (ohne Allokation). */
    private fun fill(ev: MotionEvent): Boolean {
        val action = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchSample.DOWN
            MotionEvent.ACTION_MOVE -> TouchSample.MOVE
            MotionEvent.ACTION_UP -> TouchSample.UP
            MotionEvent.ACTION_CANCEL -> TouchSample.CANCEL
            MotionEvent.ACTION_POINTER_DOWN -> TouchSample.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchSample.POINTER_UP
            else -> return false
        }
        val lifted = if (action == TouchSample.POINTER_UP || action == TouchSample.UP) ev.actionIndex else -1
        var k = 0
        for (i in 0 until ev.pointerCount) {
            if (i == lifted && action == TouchSample.POINTER_UP) continue
            if (k >= TouchSample.MAX_POINTERS) break
            sample.x[k] = ev.getX(i)
            sample.y[k] = ev.getY(i)
            k++
        }
        sample.action = action
        sample.timeMs = ev.eventTime
        sample.pointerCount = when (action) {
            TouchSample.POINTER_UP, TouchSample.UP -> ev.pointerCount - 1
            else -> ev.pointerCount
        }
        return true
    }

    companion object {
        fun of(view: View): SurfaceBinding = view.getTag(R.id.game_surface_binding) as SurfaceBinding
    }
}

/**
 * Texte der Spielfläche (Ghost-Grund, Scheitelhöhe, Längenchip) aus den App-Ressourcen (DE/EN). Alle Rückgaben sind
 * gecacht: Gründe als Tabelle, Scheitel/Länge für den zuletzt angefragten Wert (keine Allokation je Frame).
 */
class LocalizedSceneTexts(context: Context) : SceneTexts() {
    private val res = context.resources
    private val locale: Locale = res.configuration.locales[0] ?: Locale.ROOT
    private val reasons = Array(RejectReason.entries.size) { res.getString(rejectReasonRes(RejectReason.entries[it])).uppercase(locale) }
    private val invalid = res.getString(R.string.reject_invalid_target).uppercase(locale)
    private val apexFormat = res.getString(R.string.scene_apex)
    private val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    private var apexKey = Int.MIN_VALUE
    private var apexText = ""
    private var lenKey = Int.MIN_VALUE
    private var lenText = ""

    override fun reason(r: RejectReason?): String = if (r == null) invalid else reasons[r.ordinal]

    override fun apex(heightM: Int): String {
        if (heightM != apexKey) {
            apexKey = heightM
            apexText = String.format(locale, apexFormat, heightM)
        }
        return apexText
    }

    override fun length(meters: Float): String {
        val t = (meters * 10f + 0.5f).toInt()
        if (t != lenKey) {
            lenKey = t
            lenText = "${t / 10}$sep${t % 10} m"
        }
        return lenText
    }
}
