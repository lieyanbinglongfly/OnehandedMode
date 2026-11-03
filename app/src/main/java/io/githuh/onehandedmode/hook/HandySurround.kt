package io.githuh.onehandedmode.hook

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.doOnNextLayout
import io.githuh.onehandedmode.R
import io.githuh.onehandedmode.ui.MiuixSettingsButton
import kotlin.math.roundToInt

/** System surround outside the AOSP one-handed display area. */
internal class HandySurround(
    context: Context,
    private val shellHandler: Handler,
    private val onExit: () -> Unit,
    private val onSettings: () -> Unit,
) {
    private val uiHandler = Handler(context.mainLooper)
    private val windowContext = context.createWindowContext(2024, null) // TYPE_NAVIGATION_BAR_PANEL
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val resources = context.createPackageContext("io.githuh.onehandedmode", 0).resources
    private val density = context.resources.displayMetrics.density
    private val buttonSize = (35f * density).roundToInt()
    private val edge = 20f * density
    private var showing = false
    private var right = true
    private var width = 0
    private var height = 0
    private var targetScale = 1f
    private var gapWidth = 0f
    private var gapHeight = 0f
    private var entryFinished = false
    private val buttonParams = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }
    private lateinit var settingsButton: MiuixSettingsButton
    private val topView = SurroundView()
    private val sideView = SurroundView()
    private val topParams = params("MIUI one-handed top")
    private val sideParams = params("MIUI one-handed side")

    fun show(screen: ScreenSpec, scale: Float, anchorRight: Boolean, color: Int) {
        uiHandler.post {
            width = screen.width
            height = screen.height
            targetScale = scale
            right = anchorRight
            entryFinished = false
            val border = (width * (1f - targetScale) - buttonSize) / 2f
            buttonParams.bottomMargin = border.roundToInt()
            // Window-backed pixels are included by screenshot capture. The synchronized color
            // layer underneath also covers the gaps while these windows resize during animation.
            topView.setBackgroundColor(color)
            sideView.setBackgroundColor(color)
            settingsButton = MiuixSettingsButton(windowContext, sideView, resources.getString(R.string.settings)) {
                shellHandler.post { onSettings() }
            }
            settingsButton.view.alpha = 0f
            sideView.addView(settingsButton.view, buttonParams)
            gapHeight = 0f
            gapWidth = 0f
            topParams.width = width
            topParams.height = 1
            sideParams.width = 1
            sideParams.height = height
            topView.alpha = 0f
            sideView.alpha = 0f
            windowManager.addView(topView, topParams)
            windowManager.addView(sideView, sideParams)
            settingsButton.start()
            showing = true
        }
    }

    fun update(y: Float, x: Float) {
        uiHandler.post {
            if (!showing) return@post
            gapHeight = y
            gapWidth = x
            val top = y.roundToInt()
            val side = x.roundToInt()
            topParams.width = width
            topParams.height = top.coerceAtLeast(1)
            sideParams.x = if (right) 0 else width - side
            sideParams.y = top
            sideParams.width = side.coerceAtLeast(1)
            sideParams.height = (height - top).coerceAtLeast(1)
            // The button stays bottom-centered in its own layout instead of compensating each
            // asynchronous window move with a separate view translation.
            topView.alpha = 1f
            sideView.alpha = 1f
            windowManager.updateViewLayout(topView, topParams)
            windowManager.updateViewLayout(sideView, sideParams)
        }
    }

    fun onEntryFinished() {
        uiHandler.post {
            if (!showing) return@post
            entryFinished = true
            settingsButton.view.doOnNextLayout {
                if (showing && entryFinished) {
                    settingsButton.view.animate().alpha(1f).setDuration(160L).start()
                }
            }
            settingsButton.view.requestLayout()
        }
    }

    fun onExitStarted() {
        uiHandler.post {
            if (!showing) return@post
            entryFinished = false
            settingsButton.view.animate().cancel()
            settingsButton.view.animate().alpha(0f).setDuration(100L).start()
        }
    }

    fun hide() {
        uiHandler.post {
            if (!showing) return@post
            entryFinished = false
            settingsButton.view.animate().cancel()
            settingsButton.dispose()
            windowManager.removeViewImmediate(topView)
            windowManager.removeViewImmediate(sideView)
            sideView.removeAllViews()
            showing = false
        }
    }

    private fun params(title: String) = WindowManager.LayoutParams(
        1, 1, 2024,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            0x20000000, // FLAG_SLIPPERY: entering an app from the surround starts a normal touch stream.
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        setTitle(title)
        val privateFlags = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
        privateFlags.setInt(this, privateFlags.getInt(this) or 0x20000000)
    }

    private inner class SurroundView : FrameLayout(windowContext) {
        private var multiTouch = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> multiTouch = false
                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
                MotionEvent.ACTION_UP -> if (!multiTouch) {
                    val scale = 1f - gapHeight / this@HandySurround.height
                    val left = if (this@HandySurround.right) gapWidth else 0f
                    val content = RectF(left, gapHeight,
                        left + this@HandySurround.width * scale, this@HandySurround.height.toFloat())
                    content.inset(-edge * scale, -edge * scale)
                    if (!content.contains(event.rawX, event.rawY)) shellHandler.post { onExit() }
                }
            }
            return true
        }
    }
}
