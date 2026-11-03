package io.githuh.onehandedmode.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
    private val useCornerWindow = Build.VERSION.SDK_INT != 37 ||
        !Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
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
    private val cornersView = CornerFillView(windowContext)
    private val topParams = params("MIUI one-handed top")
    private val sideParams = params("MIUI one-handed side")
    private val cornersParams = params("MIUI one-handed corner background").apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    fun show(screen: ScreenSpec, scale: Float, anchorRight: Boolean, color: Int, radius: Float) {
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
            cornersParams.x = if (right) side else 0
            cornersParams.y = top
            cornersParams.width = width - side
            cornersParams.height = height - top
            cornersView.setCorners(color, radius * visualScale)
            buttonParams.bottomMargin = ((gapWidth - buttonSize) / 2f).roundToInt()
            topView.setBackgroundColor(color)
            sideView.setBackgroundColor(color)
            entryFinished = false
            prepared = true
            // During entry the synchronized color layer fills the exposed display. No window
            // relayouts, buffer resizing or Compose creation are scheduled from animation frames.
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
            if (useCornerWindow) {
                cornersView.alpha = 1f
                windowManager.addView(cornersView, cornersParams)
            }
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
            cornersView.alpha = 0f
            // Keep only the fading button above the growing content. The background surface
            // fills the surround, and the two windows stop intercepting touches immediately.
            topView.setBackgroundColor(Color.TRANSPARENT)
            sideView.setBackgroundColor(Color.TRANSPARENT)
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
        if (useCornerWindow) windowManager.removeViewImmediate(cornersView)
        sideView.removeAllViews()
        showing = false
    }

    private class CornerFillView(context: Context) : View(context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var radius = 0f

        fun setCorners(color: Int, cornerRadius: Float) {
            paint.color = color
            radius = cornerRadius
            updatePath(width, height)
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            updatePath(width, height)
        }

        private fun updatePath(width: Int, height: Int) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
            path.addRect(bounds, Path.Direction.CW)
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }
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
