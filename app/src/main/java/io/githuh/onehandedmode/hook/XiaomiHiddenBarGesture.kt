package io.githuh.onehandedmode.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt
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
    private val largerNavigationHeight = resources.getIdentifier("navigation_bar_gesture_larger_height", "dimen", "android")
    private val squaredSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().let { it * it }
    private var hidden = false
    private var forceFsgNavigation = -1
    private var tracking = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private var activationTop = 0f
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshSettings()
        }
    }

    init {
        resolver.registerContentObserver(Settings.Global.getUriFor(HIDE_GESTURE_LINE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(FORCE_FSG_NAV_BAR), false, observer)
        refreshSettings()
    }

    fun onMotionEvent(event: MotionEvent, displayHeight: Int) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                if (!hidden || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                    event.pointerCount != 1 || !isGestureNavigation() || !canStart()) return
                downX = event.rawX
                downY = event.rawY
                val bottomHeight = dimensionOrDefault(navigationHeight, 24)
                val startHeight = maxOf(bottomHeight, dimensionOrDefault(largerNavigationHeight, 80))
                activationTop = (displayHeight - bottomHeight).coerceAtLeast(0).toFloat()
                // A downward swipe needs room above the hidden line. Allow its origin in
                // Android's larger bottom gesture area, but claim only after it reaches
                // the normal bottom strip. App-content swipes above that strip stay untouched.
                tracking = downY >= (displayHeight - startHeight).coerceAtLeast(0) && downY < displayHeight
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return
                if (!hidden || !isGestureNavigation() || !canStart()) {
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
                        } else if (event.rawY >= activationTop) {
                            claimed = true
                            pilferPointers()
                            Log.i("OneHandedMode", "Hidden-bar downward gesture claimed")
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val start = tracking && claimed && hidden && isGestureNavigation() &&
                    event.rawY >= maxOf(downY, activationTop) && canStart()
                reset()
                if (start) {
                    Log.i("OneHandedMode", "Hidden-bar gesture requesting one-handed mode")
                    startOneHanded()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> reset()
        }
    }

    private fun refreshSettings() {
        hidden = Settings.Global.getInt(resolver, HIDE_GESTURE_LINE, 0) == 1
        forceFsgNavigation = Settings.Global.getInt(resolver, FORCE_FSG_NAV_BAR, -1)
        reset()
        Log.i("OneHandedMode", "Hidden-bar gesture settings: hidden=$hidden, " +
            "forceFsg=$forceFsgNavigation, gestureNavigation=${isGestureNavigation()}")
    }

    private fun isGestureNavigation(): Boolean {
        // Xiaomi owns navigation through this setting; the framework overlay can lag
        // behind it. An explicit button-mode value must also override the old overlay.
        if (forceFsgNavigation >= 0) return forceFsgNavigation == 1
        return navigationMode != 0 && resources.getInteger(navigationMode) == 2
    }

    private fun dimensionOrDefault(id: Int, defaultDp: Int): Int {
        val pixels = if (id != 0) resources.getDimensionPixelSize(id) else 0
        return if (pixels > 0) pixels else (defaultDp * resources.displayMetrics.density).roundToInt()
    }

    private fun reset() {
        tracking = false
        claimed = false
    }

    private companion object {
        const val HIDE_GESTURE_LINE = "hide_gesture_line"
        const val FORCE_FSG_NAV_BAR = "force_fsg_nav_bar"
    }
}
