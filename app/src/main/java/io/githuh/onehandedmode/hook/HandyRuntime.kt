package io.githuh.onehandedmode.hook

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.Surface
import android.view.SurfaceControl
import io.githuh.onehandedmode.HandySettings
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

// This code runs inside the platform SystemUI process under LSPosed.
@SuppressLint("BlockedPrivateApi")
internal class HandyRuntime(
    private val controller: Any,
    private val preferences: SharedPreferences,
    private val backgroundParent: () -> SurfaceControl?,
) : DisplayManager.DisplayListener {
    private val context = controller.read("mContext") as Context
    private val handler = controller.read("mMainHandler") as Handler
    private val organizer = controller.read("mDisplayAreaOrganizer")!!
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private var screen = ScreenSpec.read(display)
    private val overlay = HandySurround(context, handler, ::requestExit, ::openSettings)
    private val background = HandyBackground()
    private val stateField = controller.read("mState")!!.javaClass
        .getDeclaredField("sCurrentState").apply { isAccessible = true }
    private val state: Int get() = stateField.getInt(null)
    private var right = true
    private var downX = screen.width / 2f
    private var scale = HandySettings.scale(preferences)
    private var exitPending = false
    private var resizePending = false
    private var restartAt = 0L
    private var entryTime = 0L
    private var lastSurroundOffset = Float.NaN
    private var cornerRadius = 0f
    private lateinit var hiddenBarGesture: XiaomiHiddenBarGesture

    private val matrix = SurfaceControl.Transaction::class.java.getDeclaredMethod(
        "setMatrix", SurfaceControl::class.java,
        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
    )
    private val corner = SurfaceControl.Transaction::class.java.getDeclaredMethod(
        "setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType,
    )
    private val windowCrop = SurfaceControl.Transaction::class.java.getDeclaredMethod(
        "setWindowCrop", SurfaceControl::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { applySettings() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            resizePending = false
            requestExit()
        }
    }
    private val externalStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // START is idempotent: repeated requests must not toggle an active mode off.
            if (state != 0) return
            downX = if (intent.getStringExtra("side") == "left") 0f else screen.width.toFloat()
            controller.call("startOneHanded")
        }
    }

    fun attach() {
        // The organizer is fully initialized here, including in R8-inlined factories.
        organizer.write("mEnterExitAnimationDurationMs", 200)
        val callbackType = Class.forName(
            "com.android.wm.shell.onehanded.OneHandedTransitionCallback", false, controller.javaClass.classLoader,
        )
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onStartFinished" -> { onStateChanged(2); null }
                "onStopFinished" -> { onStateChanged(0); null }
                "onStartTransition" -> null
                "toString" -> "MIUI one-handed transition callback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> error("Unknown transition callback: ${method.name}")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val callbacks = organizer.read("mTransitionCallbacks") as MutableList<Any>
        callbacks.add(callback)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        context.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), null, handler)
        val startFilter = IntentFilter("io.githuh.onehandedmode.action.START")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(externalStartReceiver, startFilter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(externalStartReceiver, startFilter, null, handler)
        }
        val pilfer = Class.forName("android.view.InputMonitor").getDeclaredMethod("pilferPointers")
        hiddenBarGesture = XiaomiHiddenBarGesture(
            context, handler,
            canStart = { state == 0 && canEnter() },
            pilferPointers = { pilfer.invoke(controller.read("mTouchHandler")!!.read("mInputMonitor")); Unit },
            startOneHanded = { controller.call("startOneHanded"); Unit },
        )
        handler.post { applySettings() }
    }

    private fun applySettings() {
        val newScale = HandySettings.scale(preferences)
        if (newScale != scale && state != 0) {
            // MIUI refreshStatus exits, then re-enters after 300 ms when size changes.
            resizePending = true
            restartAt = SystemClock.uptimeMillis() + 300L
            requestExit()
        }
        scale = newScale
        controller.write("mOffSetFraction", 1f - scale)
        val resolver = context.contentResolver
        val user = controller.read("mUserId") as Int
        val get = Settings.Secure::class.java.getDeclaredMethod(
            "getStringForUser", android.content.ContentResolver::class.java,
            String::class.java, Int::class.javaPrimitiveType,
        )
        val put = Settings.Secure::class.java.getDeclaredMethod(
            "putIntForUser", android.content.ContentResolver::class.java,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        for ((key, value) in listOf(
            "one_handed_mode_enabled" to 1,
            "swipe_bottom_to_notification_enabled" to 0,
            "one_handed_mode_timeout" to 0,
            "taps_app_to_exit" to 0,
        )) {
            if (get.invoke(null, resolver, key, user) != value.toString()) {
                put.invoke(null, resolver, key, value, user)
            }
        }
        if (controller.read("mIsOneHandedEnabled") != true) {
            controller.write("mIsOneHandedEnabled", true)
            controller.call("updateOneHandedEnabled")
        }
    }

    fun prepareEntry(): Boolean {
        if (!canEnter()) return false
        if (state == 2) {
            requestExit()
            return false
        }
        if (state != 0) return false
        // R8 inlines the tutorial's onStateChanged; remove its visual listener directly.
        val listeners = controller.read("mState")!!.read("mStateChangeListeners") as MutableList<*>
        listeners.remove(controller.read("mTutorialHandler"))
        screen = ScreenSpec.read(display)
        cornerRadius = listOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
        ).minOf { display.getRoundedCorner(it)?.radius ?: 0 }.toFloat()
        scale = HandySettings.scale(preferences)
        controller.write("mOffSetFraction", 1f - scale)
        right = downX >= screen.width / 2f
        return true
    }

    private fun canEnter(): Boolean = display.rotation == Surface.ROTATION_0 &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0

    fun deferExit(): Boolean {
        if (state == 1) {
            exitPending = true
            return true
        }
        return false
    }

    fun isOneHandedActive(): Boolean = state == 2

    fun mapFreeformInput(event: MotionEvent) {
        val visualScale = 1f - lastSurroundOffset / screen.height
        val x = if (right) screen.width * (1f - visualScale) else 0f
        val inverse = Matrix().apply {
            setValues(floatArrayOf(
                1f / visualScale, 0f, -x / visualScale,
                0f, 1f / visualScale, -lastSurroundOffset / visualScale,
                0f, 0f, 1f,
            ))
        }
        event.transform(inverse)
    }

    fun freeformPointToDisplay(x: Float, y: Float): PointF {
        val visualScale = 1f - lastSurroundOffset / screen.height
        val offset = if (right) screen.width * (1f - visualScale) else 0f
        return PointF(x * visualScale + offset, y * visualScale + lastSurroundOffset)
    }

    @Suppress("DEPRECATION")
    fun hasVisibleFreeformTask(): Boolean {
        val taskInfo = Class.forName("android.app.TaskInfo")
        val configuration = taskInfo.getField("configuration")
        val visible = taskInfo.getField("isVisible")
        val windowConfiguration = Configuration::class.java.getField("windowConfiguration")
        val mode = Class.forName("android.app.WindowConfiguration").getDeclaredMethod("getWindowingMode")
        return context.getSystemService(ActivityManager::class.java).getRunningTasks(Int.MAX_VALUE).any { task ->
            visible.getBoolean(task) &&
                mode.invoke(windowConfiguration.get(configuration.get(task))) == 5
        }
    }

    private fun requestExit() {
        if (state == 1) {
            exitPending = true
        } else if (state == 2) {
            controller.call("stopOneHanded")
        }
    }

    fun onStateChanged(newState: Int) {
        when (newState) {
            1 -> {
                lastSurroundOffset = Float.NaN
                entryTime = SystemClock.elapsedRealtime()
                val color = backgroundColor()
                background.prepare(screen, color, backgroundParent())
                overlay.show(screen, scale, right)
                publishMode(if (right) 2 else 1)
            }
            2 -> {
                overlay.onEntryFinished()
                if (exitPending) {
                    exitPending = false
                    handler.post { requestExit() }
                }
            }
            3 -> overlay.onExitStarted()
            0 -> {
                exitPending = false
                background.hide()
                overlay.hide()
                publishMode(0)
                if (resizePending) {
                    resizePending = false
                    handler.postDelayed({
                        downX = if (right) screen.width.toFloat() else 0f
                        controller.call("startOneHanded")
                    }, (restartAt - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                }
            }
        }
    }

    private fun publishMode(mode: Int) {
        Settings.Global.putInt(context.contentResolver, "handy_mode", mode)
        val intent = Intent("miui.action.handymode_change").putExtra("handymode", mode)
        if (mode == 0) intent.putExtra("handymodetime", SystemClock.elapsedRealtime() - entryTime)
        context.sendBroadcast(intent)
    }

    private fun backgroundColor(): Int {
        return if (Build.VERSION.SDK_INT >= 33) {
            controller.read("mTutorialHandler")!!.read("mBackgroundWindowManager")!!
                .call("getThemeColorForBackground") as Int
        } else {
            val color = controller.read("mBackgroundPanelOrganizer")!!.read("mThemeColor") as FloatArray
            Color.rgb((color[0] * 255).roundToInt(), (color[1] * 255).roundToInt(), (color[2] * 255).roundToInt())
        }
    }

    fun onMotionEvent(event: MotionEvent) {
        if (state == 0 && event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.rawX
        hiddenBarGesture.onMotionEvent(event, screen.height)
    }

    fun transform(tx: SurfaceControl.Transaction, leash: SurfaceControl, offset: Float): PointF {
        val y = offset
        val currentScale = 1f - y / screen.height
        val x = if (right) screen.width * (1f - currentScale) else 0f
        background.apply(tx)
        matrix.invoke(tx, leash, currentScale, 0f, 0f, currentScale)
        windowCrop.invoke(tx, leash, screen.width, screen.height)
        // SurfaceFlinger scales this local radius with the same matrix as the content.
        corner.invoke(tx, leash, cornerRadius)
        if (y != lastSurroundOffset) {
            lastSurroundOffset = y
        }
        return PointF(x, y)
    }

    private fun openSettings() {
        context.startActivity(
            Intent().setClassName("io.githuh.onehandedmode", "io.githuh.onehandedmode.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val changed = ScreenSpec.read(display)
        if (state != 0 && (display.rotation != Surface.ROTATION_0 || changed != screen)) {
            resizePending = false
            exitPending = false
            organizer.call("finishOffset", 0, 2)
        }
        screen = changed
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
}
