package io.githuh.onehandedmode.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/**
 * Keeps the gesture hint — the pill at the bottom of a gesture-navigation screen — aligned with
 * the scaled content while one-handed mode is active.
 *
 * The hint belongs to SystemUI's navigation bar, a window of its own that is not part of the
 * display area the framework scales, so it stays at the physical screen centre while everything
 * else shrinks. There is no SurfaceControl exposed for it here, so the view itself is scaled and
 * translated instead: both are draw-time properties, no layout, no window traffic.
 */
internal class NavigationHandleFollower(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var handle: View? = null
    private var nextSearchAt = 0L
    private var lastScale = -1f
    private var lastSide = -1f

    /**
     * [side] is the horizontal progress of the content: 1 anchored right, 0 anchored left, and
     * anything between while the overlay slides to the other side. Safe from any thread; the
     * view is only ever touched on the main thread.
     */
    fun update(scale: Float, side: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(scale, side)
        } else {
            // Animation frames arrive on the shell thread; one post per distinct value is cheap.
            if (scale == lastScale && side == lastSide) return
            handler.post { apply(scale, side) }
        }
    }

    private fun apply(scale: Float, side: Float) {
        if (scale == lastScale && side == lastSide) return
        val view = resolve() ?: return
        lastScale = scale
        lastSide = side
        if (scale >= 0.999f) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            return
        }
        val windowWidth = view.rootView?.width?.takeIf { it > 0 }?.toFloat()
            ?: context.resources.displayMetrics.widthPixels.toFloat()
        if (windowWidth <= 0f || view.width == 0) return
        // The content covers `windowWidth * scale` and starts at `windowWidth * (1 - scale) * side`,
        // so the hint follows the same centre — including while the overlay slides across.
        val targetCentre = windowWidth * (1f - scale) * side + windowWidth * scale / 2f
        // Layout x only: getLocationInWindow() would already contain the translation we set
        // last frame, which makes the hint drift away from its target.
        val baseCentre = layoutX(view) + view.width / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = targetCentre - baseCentre
    }

    private fun layoutX(view: View): Float {
        var total = 0f
        var current: View? = view
        while (current != null) {
            total += current.left
            current = current.parent as? View
        }
        return total
    }

    /** Drops the transform without waiting for a state change, e.g. on rotation. */
    fun reset() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { reset() }
            return
        }
        lastScale = -1f
        lastSide = -1f
        val view = handle ?: return
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
    }

    private fun resolve(): View? {
        val current = handle
        if (current != null && current.isAttachedToWindow) return current
        val now = SystemClock.uptimeMillis()
        if (now < nextSearchAt) return null
        val found = runCatching { findHandle() }.getOrNull()
        handle = found
        // Searching the whole window tree is expensive; back off when this build has no match.
        if (found == null) nextSearchAt = now + 5000L
        return found
    }

    private fun findHandle(): View? {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val instance = globalClass.getMethod("getInstance").invoke(null)
        val viewsField = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
        val roots = viewsField.get(instance) as? List<*> ?: return null
        for (item in roots) {
            val root = when (item) {
                is View -> item
                null -> continue
                // WindowManagerGlobal keeps ViewRootImpl instances, not the views themselves.
                else -> runCatching {
                    item.javaClass.getMethod("getView").invoke(item) as? View
                }.getOrNull()
            } ?: continue
            search(root)?.let { return it }
        }
        return null
    }

    private fun search(root: View): View? {
        if (isHandle(root)) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index) ?: continue
            search(child)?.let { return it }
        }
        return null
    }

    private fun isHandle(view: View): Boolean {
        if (!view.isShown || view.width <= 0) return false
        val name = view.javaClass.name
        if (name.endsWith("NavigationHandle") || name.endsWith("HomeHandle")) return true
        val id = view.id
        if (id == View.NO_ID) return false
        val entry = runCatching { view.resources.getResourceEntryName(id) }.getOrNull() ?: return false
        return entry == "gesture_handle" || entry == "navigation_bar_handle" || entry == "home_handle"
    }
}
