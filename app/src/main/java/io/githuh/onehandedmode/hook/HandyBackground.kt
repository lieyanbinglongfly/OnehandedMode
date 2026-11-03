package io.githuh.onehandedmode.hook

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.SurfaceControl

/** A color layer committed with the first display-area animation transaction. */
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
        val layer = surface!!
        setColor.invoke(tx, layer, color)
        crop.invoke(tx, layer, width, height)
        tx.setLayer(layer, -1).setAlpha(layer, alpha)
        show.invoke(tx, layer)
        pending = false
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
}
