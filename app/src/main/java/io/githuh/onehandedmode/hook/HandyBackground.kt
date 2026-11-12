package io.githuh.onehandedmode.hook

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.SurfaceControl
import java.lang.reflect.Method
import kotlin.math.max

/**
 * The flat color layer that fills the area outside the scaled screen.
 *
 * It used to have a second form: a SurfaceControl built with setBufferSize/setFormat and fed a
 * HardwareBuffer through Transaction.setBuffer. On real devices that layer made SurfaceFlinger
 * composite a buffer with no producer attached and the whole display went black, so the picture
 * path is gone for good. Wallpapers and picked pictures are painted by the mask window itself
 * (see HandySurround.MaskView), which is a plain View and cannot take the display down.
 */
@SuppressLint("BlockedPrivateApi") // Runs only inside platform SystemUI under LSPosed.
internal class HandyBackground {
    private var surface: SurfaceControl? = null
    private var pending = false
    private var width = 0
    private var height = 0
    private var color = floatArrayOf(0f, 0f, 0f)
    private var alpha = 1f

    private val colorLayer = SurfaceControl.Builder::class.java.getDeclaredMethod("setColorLayer")
    private val setColor = SurfaceControl.Transaction::class.java.getDeclaredMethod(
        "setColor", SurfaceControl::class.java, FloatArray::class.java,
    )
    private val crop = SurfaceControl.Transaction::class.java.getDeclaredMethod(
        "setWindowCrop", SurfaceControl::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )
    private val show = SurfaceControl.Transaction::class.java.getDeclaredMethod("show", SurfaceControl::class.java)
    private val remove = SurfaceControl.Transaction::class.java.getDeclaredMethod("remove", SurfaceControl::class.java)

    fun prepare(screen: ScreenSpec, backgroundColor: Int, parent: SurfaceControl?) {
        release()
        val builder = SurfaceControl.Builder().setName("MIUI one-handed background")
            .setParent(parent).setOpaque(true)
        colorLayer.invoke(builder)
        surface = builder.build()
        width = screen.width
        height = screen.height
        color = floatArrayOf(
            Color.red(backgroundColor) / 255f,
            Color.green(backgroundColor) / 255f,
            Color.blue(backgroundColor) / 255f,
        )
        alpha = Color.alpha(backgroundColor) / 255f
        pending = true
    }

    fun apply(tx: SurfaceControl.Transaction) {
        if (!pending) return
        val layer = surface ?: return
        setColor.invoke(tx, layer, color)
        crop.invoke(tx, layer, width, height)
        tx.setLayer(layer, -1).setAlpha(layer, alpha)
        show.invoke(tx, layer)
        pending = false
    }

    /** Commits a replacement background while one-handed mode is already running. */
    fun applyNow() {
        val layer = surface ?: return
        if (!pending) return
        SurfaceControl.Transaction().use { tx ->
            setColor.invoke(tx, layer, color)
            crop.invoke(tx, layer, width, height)
            // Same z-order as apply(). A freshly built layer defaults to the top of its parent,
            // so omitting this paints an opaque rectangle over the entire display.
            tx.setLayer(layer, -1).setAlpha(layer, alpha)
            show.invoke(tx, layer)
            tx.apply()
        }
        pending = false
    }

    /**
     * Repaints the layer after the user picks another surround color. This layer sits under the
     * content, so it is what shows through the scaled-down window's rounded corners; without this
     * the corners keep the old color until the mode is restarted.
     */
    fun recolor(backgroundColor: Int) {
        val layer = surface ?: return
        color = floatArrayOf(
            Color.red(backgroundColor) / 255f,
            Color.green(backgroundColor) / 255f,
            Color.blue(backgroundColor) / 255f,
        )
        alpha = Color.alpha(backgroundColor) / 255f
        SurfaceControl.Transaction().use { tx ->
            setColor.invoke(tx, layer, color)
            tx.setAlpha(layer, alpha)
            tx.apply()
        }
    }

    fun hide() {
        surface?.let { layer ->
            SurfaceControl.Transaction().use { tx ->
                remove.invoke(tx, layer)
                tx.apply()
            }
            layer.release()
        }
        surface = null
        pending = false
    }

    private fun release() {
        surface?.let { layer ->
            // Detach before releasing: a layer that reached the display and is dropped without
            // a remove transaction can stay composed until the process dies.
            runCatching {
                SurfaceControl.Transaction().use { tx ->
                    remove.invoke(tx, layer)
                    tx.apply()
                }
            }
            layer.release()
        }
        surface = null
        pending = false
    }
}
