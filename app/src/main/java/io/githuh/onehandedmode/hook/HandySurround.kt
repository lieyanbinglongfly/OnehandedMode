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

/** Fixed surround windows; only the original display surfaces animate. */
internal class HandySurround(
    context: Context,
    private val shellHandler: Handler,
    private val onExit: () -> Unit,
    private val onSettings: () -> Unit,
) {
    private val uiHandler = Handler(context.mainLooper)
    private val windowContext = context.createWindowContext(2024, null)
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val resources = context.createPackageContext("io.githuh.onehandedmode", 0).resources
    private val density = context.resources.displayMetrics.density
    private val buttonSize = (35f * density).roundToInt()
    private val edge = 20f * density
    private var prepared = false
    private var showing = false
    private var right = true
    private var width = 0
    private var height = 0
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

    fun show(screen: ScreenSpec, scale: Float, anchorRight: Boolean) {
        uiHandler.post {
            width = screen.width
            height = screen.height
            right = anchorRight
            gapHeight = (height * (1f - scale)).roundToInt().toFloat()
            val visualScale = 1f - gapHeight / height
            gapWidth = width * (1f - visualScale)
            val top = gapHeight.toInt()
            val side = gapWidth.roundToInt()
            topParams.width = width
            topParams.height = top
            topParams.flags = topParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            sideParams.x = if (right) 0 else width - side
            sideParams.y = top
            sideParams.width = side
            sideParams.height = height - top
            sideParams.flags = sideParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            buttonParams.bottomMargin = ((gapWidth - buttonSize) / 2f).roundToInt()
            entryFinished = false
            prepared = true
            // The synchronized color layer remains the only visible surround background for the
            // entire session. These transparent windows provide touch handling and the button.
        }
    }

    fun onEntryFinished() {
        uiHandler.post {
            if (!prepared) return@post
            prepared = false
            settingsButton = MiuixSettingsButton(windowContext, sideView, resources.getString(R.string.settings)) {
                shellHandler.post { onSettings() }
            }
            settingsButton.view.alpha = 0f
            sideView.addView(settingsButton.view, buttonParams)
            windowManager.addView(topView, topParams)
            windowManager.addView(sideView, sideParams)
            settingsButton.start()
            showing = true
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
            // The background surface fills the surround; stop intercepting touches immediately.
            topParams.flags = topParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            sideParams.flags = sideParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(topView, topParams)
            windowManager.updateViewLayout(sideView, sideParams)
            settingsButton.view.animate().cancel()
            settingsButton.view.animate().alpha(0f).setDuration(100L).withEndAction { detachWindows() }.start()
        }
    }

    fun hide() {
        uiHandler.post {
            prepared = false
            if (showing) detachWindows()
        }
    }

    private fun detachWindows() {
        entryFinished = false
        settingsButton.view.animate().cancel()
        settingsButton.dispose()
        windowManager.removeViewImmediate(topView)
        windowManager.removeViewImmediate(sideView)
        sideView.removeAllViews()
        showing = false
    }

    private fun params(title: String) = WindowManager.LayoutParams(
        1, 1, 2024,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            0x20000000,
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
