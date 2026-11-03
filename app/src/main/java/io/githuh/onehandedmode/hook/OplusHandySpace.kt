package io.githuh.onehandedmode.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import io.githuh.onehandedmode.HandySettings
import kotlin.math.roundToInt

/** The same rounded viewport used by HandyRuntime, shared with the two Oplus input processes. */
@SuppressLint("BlockedPrivateApi") // Executed in the platform/privileged processes under LSPosed.
internal class OplusHandySpace(
    private val context: Context,
    private val preferences: SharedPreferences,
) {
    private class Viewport(val scale: Float = 1f, val left: Float = 0f, val top: Float = 0f) {
        val forward = Matrix().apply {
            setValues(floatArrayOf(scale, 0f, left, 0f, scale, top, 0f, 0f, 1f))
        }
        val inverse = Matrix().apply {
            setValues(floatArrayOf(1f / scale, 0f, -left / scale,
                0f, 1f / scale, -top / scale, 0f, 0f, 1f))
        }
        val windowCompensation = Matrix().apply { setScale(scale, scale) }
    }

    private val applyTransform = MotionEvent::class.java.getDeclaredMethod("applyTransform", Matrix::class.java)
    private val displayId = InputEvent::class.java.getDeclaredMethod("getDisplayId")
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var viewport = readViewport()
    val active: Boolean get() = viewport.scale != 1f

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                viewport = readViewport()
                listeners.forEach { it() }
            }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor("handy_mode"), false, observer)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("one_handed_mode_activated"), false, observer,
        )
    }

    fun onChanged(action: () -> Unit) { listeners.add(action) }

    fun toDisplay(rect: RectF): RectF = RectF(rect).also {
        // An empty region is the native unregister/disabled sentinel.
        if (!it.isEmpty) viewport.forward.mapRect(it)
    }

    fun mapGlobalInput(event: MotionEvent) {
        if (displayId.invoke(event) != Display.DEFAULT_DISPLAY) return
        // transform() preserves raw axes. These input monitors explicitly read getRawX/Y.
        applyTransform.invoke(event, viewport.inverse)
    }

    fun mapWindowInput(event: MotionEvent) {
        if (displayId.invoke(event) != Display.DEFAULT_DISPLAY) return
        val transform = viewport
        val localX = event.x
        val localY = event.y
        applyTransform.invoke(event, transform.inverse)
        // Window input already has the inverse surface scale. Preserve its local coordinates
        // and history while expressing only the raw display coordinates in logical task space.
        event.transform(transform.windowCompensation)
        event.setLocation(localX, localY)
    }

    private fun readViewport(): Viewport {
        val resolver = context.contentResolver
        val mode = Settings.Global.getInt(resolver, "handy_mode", 0)
        val activated = Settings.Secure.getInt(resolver, "one_handed_mode_activated", 0) == 1
        if (mode == 0 || !activated) return Viewport()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val screen = ScreenSpec.read(display)
        val top = (screen.height * (1f - HandySettings.scale(preferences))).roundToInt().toFloat()
        val scale = 1f - top / screen.height
        return Viewport(scale, if (mode == 2) screen.width * (1f - scale) else 0f, top)
    }
}
