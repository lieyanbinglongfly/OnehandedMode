package io.githuh.onehandedmode.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sqrt

/** Restore bottom swipe-down entry when Xiaomi's launcher disables it with the gesture line. */
internal class XiaomiHiddenBarGesture(
    context: Context,
    handler: Handler,
    private val canStart: () -> Boolean,
    private val pilferPointers: () -> Unit,
    private val startOneHanded: () -> Unit,
) {
    private val resolver = context.contentResolver
    private val resources = context.resources
    private val navigationMode = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
    private val navigationHeight = resources.getIdentifier("navigation_bar_gesture_height", "dimen", "android")
    private val squaredSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().let { it * it }
    private var hidden = Settings.Global.getInt(resolver, "hide_gesture_line", 0) == 1
    private var tracking = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            hidden = Settings.Global.getInt(resolver, "hide_gesture_line", 0) == 1
            reset()
        }
    }

    init {
        resolver.registerContentObserver(Settings.Global.getUriFor("hide_gesture_line"), false, observer)
    }

    fun onMotionEvent(event: MotionEvent, displayHeight: Int) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                if (!hidden || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                    event.pointerCount != 1 || resources.getInteger(navigationMode) != 2 || !canStart()) return
                downX = event.rawX
                downY = event.rawY
                tracking = downY > displayHeight - resources.getDimensionPixelSize(navigationHeight)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return
                if (!hidden || !canStart()) {
                    reset()
                    return
                }
                if (!claimed) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx * dx + dy * dy > squaredSlop) {
                        // Use AOSP's 30–150 degree downward cone and system touch slop.
                        if (dy <= 0f || abs(dx) >= dy * sqrt(3f)) {
                            reset()
                        } else {
                            claimed = true
                            pilferPointers()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val start = tracking && claimed && hidden && event.rawY >= downY && canStart()
                reset()
                if (start) startOneHanded()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> reset()
        }
    }

    private fun reset() {
        tracking = false
        claimed = false
    }
}
