package io.githuh.onehandedmode.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.Surface
import android.view.SurfaceControl
import android.view.animation.PathInterpolator
import io.githuh.onehandedmode.HandySettings
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.roundToInt

/** MIUI's mode-change notification; the launcher re-reads `handy_mode` when it arrives. */
private const val HANDY_MODE_CHANGE = "miui.action.handymode_change"
/** Settings app → SystemUI: enter the mode on the side named by the `side` extra. */
internal const val ACTION_REQUEST_SIDE = "io.githuh.onehandedmode.action.REQUEST_SIDE"
/** Settings app → SystemUI: leave one-handed mode. */
internal const val ACTION_REQUEST_EXIT = "io.githuh.onehandedmode.action.REQUEST_EXIT"
/** Corner radius used when the display reports none; the sliver has to be filled either way. */
private const val FALLBACK_CORNER_DP = 18f

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
    private val overlay = HandySurround(
        context, handler,
        onExit = ::requestExit,
        onSideTap = ::onSideTap,
        onSwipeStart = ::beginScaleSwipe,
        onSwipe = ::updateScaleSwipe,
        onSwipeEnd = ::endScaleSwipe,
        onSettings = ::openSettings,
        onBack = ::goBack,
    )
    private val background = HandyBackground()
    // The navigation bar is its own window, outside the scaled display area; its gesture hint
    // has to be transformed separately so it stays centred on the smaller content.
    private val navHandle = NavigationHandleFollower(context)
    private val stateField = controller.read("mState")!!.javaClass
        .getDeclaredField("sCurrentState").apply { isAccessible = true }
    private val state: Int get() = stateField.getInt(null)
    // Reflecting a static field on every input event was measurable: this is the hot path the
    // touch handler calls for every MotionEvent in SystemUI.
    @Volatile private var cachedState = 0
    private var right = true
    // Horizontal progress of the content: 1 anchored right, 0 anchored left. Switching sides
    // slides this instead of leaving the mode, so it is read by every transform path — including
    // the framework's own entry/exit animation, which comes in on the shell thread.
    @Volatile private var sideProgress = 1f
    private var downX = screen.width / 2f
    // Read from the input thread on every touch, written by the shell and UI threads.
    @Volatile private var scale = HandySettings.scale(preferences)
    private var exitPending = false
    private var resizePending = false
    // Target anchor while a side tap restarts the mode; null keeps the current side.
    private var pendingSide: Boolean? = null
    // Side strip drag: percent at touch down and the last value handed to the organizer.
    private var swipeActive = false
    private var swipeStartPercent = 0
    private var swipePercent = 0
    private var swipeApplied = -1
    private var swipeKickAt = 0L
    // Last time the drag value was pushed to the settings database for the app to observe.
    private var securePublishAt = 0L
    private var restartAt = 0L
    private var entryTime = 0L
    @Volatile private var lastSurroundOffset = Float.NaN
    // Decoding and blurring a wallpaper costs ~150 ms; keep the result until a setting changes.
    private var pictureCacheKey: String? = null
    private var pictureCache: Bitmap? = null
    private var cornerRadius = 0f
    // Animates the content between two scales when the settings app changes the value, instead
    // of the framework's exit-then-re-enter path.
    private var scaleAnimator: ValueAnimator? = null
    private var scaleAnimationId = 0
    // Where the slide started, so each frame can report its own progress along the run. Used to
    // drive the bubble panel's exit off the same curve as the content, instead of a second
    // animation with its own clock.
    private var sideFrom = 1f
    // Target of the run in progress, so the two preference keys written by one slider release
    // do not restart the same animation twice.
    private var scaleTarget = Float.NaN
    // Slides the content to the other side without leaving one-handed mode.
    private var sideAnimator: ValueAnimator? = null
    private var sideAnimationId = 0
    private var sideTarget = Float.NaN
    private lateinit var hiddenBarGesture: XiaomiHiddenBarGesture

    // Live drag path. The display-area leashes are driven directly instead of going through
    // scheduleOffset(): every call to it re-enters the framework animator's start(), which
    // resets the ValueAnimator to fraction 0 and snapped the content back one step per frame.
    // One reusable transaction keeps a drag at a single SurfaceFlinger round trip per frame.
    private val liveTx = SurfaceControl.Transaction()
    private val leashes = ArrayList<SurfaceControl>(4)
    // Crop and corner radius are constant for a given leash; setting them per frame makes
    // SurfaceFlinger recompute the layer geometry on every frame.
    private val preparedLeashes = HashSet<SurfaceControl>(4)

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
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post { applySettings(key) }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            resizePending = false
            requestExit()
        }
    }
    /**
     * The settings app asking for a side: it starts the mode when it is off, slides to the other
     * side when it is on, and does nothing when the request already matches where the content is.
     */
    private val externalSideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            requestSide(intent.getStringExtra("side") != "left")
        }
    }
    private val externalExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            requestExit()
        }
    }
    /**
     * Keeps the readable copy of the framework switch up to date.
     *
     * The settings app cannot read `one_handed_mode_enabled` itself, so this process — a system
     * app, and the only one that may read it — publishes the value under a key of our own.
     */
    private val systemSwitchObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            mirrorSystemSwitch()
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
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(HandySettings.SYSTEM_ENABLED), false, systemSwitchObserver,
            )
        }
        val startFilter = IntentFilter(ACTION_REQUEST_SIDE)
        val exitFilter = IntentFilter(ACTION_REQUEST_EXIT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(externalSideReceiver, startFilter, null, handler, Context.RECEIVER_EXPORTED)
            context.registerReceiver(externalExitReceiver, exitFilter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(externalSideReceiver, startFilter, null, handler)
            context.registerReceiver(externalExitReceiver, exitFilter, null, handler)
        }
        val pilfer = Class.forName("android.view.InputMonitor").getDeclaredMethod("pilferPointers")
        hiddenBarGesture = XiaomiHiddenBarGesture(
            context, handler,
            canStart = { state == 0 && canEnter() },
            pilferPointers = { pilfer.invoke(controller.read("mTouchHandler")!!.read("mInputMonitor")); Unit },
            startOneHanded = { controller.call("startOneHanded"); Unit },
        )
        handler.post { applySettings(fromAttach = true) }
    }

    private fun applySettings(changedKey: String? = null, fromAttach: Boolean = false) {
        // A change reported without a key (the plugin's remote preferences do that) still carries
        // the whole map, so it counts as "the scale may have changed" instead of being ignored.
        val scaleKey = changedKey == null || changedKey == HandySettings.MODE ||
            changedKey == HandySettings.CUSTOM || changedKey == HandySettings.PRESET
        val newScale: Float
        if (fromAttach) {
            // This process cannot write the plugin preferences on current LSPosed builds, so a
            // side-strip drag only survives in the framework copy. That copy therefore wins on
            // attach when it holds a value; otherwise the preferences are published to it.
            if (securePercent() !in HandySettings.MIN_PERCENT..HandySettings.MAX_PERCENT) {
                writeSecurePercent(HandySettings.percent(preferences))
            }
            newScale = readScale()
        } else {
            if (scaleKey) {
                // Publish on every scale change, never only when the copy is missing. The
                // settings app opens in its own process and reads this copy to show the scale in
                // use; an older drag result left there made it display — and then adopt — a
                // scale the user had already replaced.
                writeSecurePercent(HandySettings.percent(preferences))
            }
            // A preferences change for a scale key is authoritative: the settings database may
            // not have caught up with it yet.
            newScale = if (scaleKey) HandySettings.scale(preferences) else readScale()
        }
        if (newScale != scale) {
            if (state == 2) {
                // A value written by the settings app: shrink or grow the content in place. The
                // old path (exit, then re-enter after 300 ms) flashed the full screen first.
                animateScale(newScale)
            } else if (state != 0) {
                // MIUI refreshStatus exits, then re-enters after 300 ms when size changes.
                resizePending = true
                restartAt = SystemClock.uptimeMillis() + 300L
                requestExit()
            }
        }
        // While the animator runs it owns `scale`; let it land on the final value itself.
        if (scaleAnimator == null) scale = newScale
        controller.write("mOffSetFraction", 1f - newScale)
        // The framework's timeout and its swipe-down behaviour would fight the overlay, so those
        // two are always quieted. The on/off switch is different: writing it is what made the
        // system's own one-handed switch flip back on whenever the app opened, so it is only
        // touched when the user asked for that.
        for ((key, value) in listOf(
            "swipe_bottom_to_notification_enabled" to 0,
            "one_handed_mode_timeout" to 0,
            "taps_app_to_exit" to 0,
        )) {
            if (secureGet(key) != value.toString()) securePut(key, value)
        }
        if (HandySettings.keepSystemEnabled(preferences)) {
            if (secureGet(HandySettings.SYSTEM_ENABLED) != "1") {
                securePut(HandySettings.SYSTEM_ENABLED, 1)
            }
            if (controller.read("mIsOneHandedEnabled") != true) {
                controller.write("mIsOneHandedEnabled", true)
                controller.call("updateOneHandedEnabled")
            }
        }
        mirrorSystemSwitch()
        // A surround color change applies immediately; a scale change has already been animated
        // (or queued as an exit and re-entry) above.
        if (state != 0) {
            val backgroundKey = changedKey == HandySettings.SURROUND_COLOR ||
                changedKey == HandySettings.BACKGROUND_SOURCE ||
                changedKey == HandySettings.BACKGROUND_IMAGE ||
                changedKey == HandySettings.BACKGROUND_BLUR
            if (backgroundKey) {
                refreshBackground()
            } else {
                val color = backgroundColor()
                overlay.recolor(color)
                background.recolor(color)
            }
        }
    }

    // Settings.Secure is the only channel this process can write: remote preferences throw
    // "Read only implementation" when edited from the hooked SystemUI on current LSPosed builds.
    private val secureGetMethod by lazy {
        Settings.Secure::class.java.getDeclaredMethod(
            "getStringForUser", ContentResolver::class.java,
            String::class.java, Int::class.javaPrimitiveType,
        )
    }
    private val securePutMethod by lazy {
        Settings.Secure::class.java.getDeclaredMethod(
            "putIntForUser", ContentResolver::class.java,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
    }
    private val secureUser: Int by lazy { controller.read("mUserId") as Int }

    private fun secureGet(key: String): String? = runCatching {
        secureGetMethod.invoke(null, context.contentResolver, key, secureUser) as String?
    }.getOrNull()

    /**
     * Publishes the framework switch under [HandySettings.SYSTEM_ENABLED_MIRROR].
     *
     * `one_handed_mode_enabled` is a @hide key: since Android 12 only system apps may read it, so
     * the settings page crashed on resume when it tried. This process may read it, so it leaves a
     * copy where the app can see it.
     */
    private fun mirrorSystemSwitch() {
        val enabled = if (secureGet(HandySettings.SYSTEM_ENABLED) == "1") 1 else 0
        if (secureGet(HandySettings.SYSTEM_ENABLED_MIRROR) != enabled.toString()) {
            securePut(HandySettings.SYSTEM_ENABLED_MIRROR, enabled)
        }
    }

    private fun securePut(key: String, value: Int) {
        runCatching { securePutMethod.invoke(null, context.contentResolver, key, value, secureUser) }
    }

    private fun securePercent(): Int = secureGet(HandySettings.SWIPE_PERCENT)?.toIntOrNull() ?: -1

    private fun writeSecurePercent(percent: Int) = securePut(HandySettings.SWIPE_PERCENT, percent)

    /** Scale produced by a side-strip drag when one is stored, otherwise the app's setting. */
    private fun readScale(): Float {
        val percent = securePercent()
        return if (percent in HandySettings.MIN_PERCENT..HandySettings.MAX_PERCENT) {
            percent / 100f
        } else {
            HandySettings.scale(preferences)
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
        // A drag may have shortened this and never restored it; every entry starts from 200 ms.
        organizer.write("mEnterExitAnimationDurationMs", 200)
        // Any single unreadable corner used to zero the whole radius — and a zero radius means
        // no wedge is drawn, which left the sliver between the square corner and the arc showing
        // the flat colour layer: four black arcs around the picture. Take the largest readable
        // corner instead, and fall back to the usual phone corner when the display reports none.
        cornerRadius = listOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
        ).mapNotNull { display.getRoundedCorner(it)?.radius }
            .filter { it > 0 }
            .maxOrNull()
            ?.toFloat()
            ?: (FALLBACK_CORNER_DP * context.resources.displayMetrics.density)
        scale = readScale()
        controller.write("mOffSetFraction", 1f - scale)
        right = downX >= screen.width / 2f
        // The leashes live for as long as the organizer does; refresh them per entry so a
        // display reconfiguration cannot leave a stale SurfaceControl behind.
        refreshLeashes()
        return true
    }

    private fun refreshLeashes() {
        leashes.clear()
        preparedLeashes.clear()
        runCatching {
            val map = organizer.read("mDisplayAreaTokenMap") as Map<*, *>
            map.values.filterIsInstance<SurfaceControl>().forEach { leashes.add(it) }
        }
    }

    private fun canEnter(): Boolean = display.rotation == Surface.ROTATION_0 &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        deviceProvisioned

    // Read once: this is a ContentProvider round trip and canEnter() runs from the input path.
    private val deviceProvisioned: Boolean by lazy {
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0
        }.getOrDefault(true)
    }

    fun deferExit(): Boolean {
        if (state == 1) {
            exitPending = true
            return true
        }
        return false
    }

    fun isOneHandedActive(): Boolean = state == 2

    /**
     * Vertical offset of the scaled content, from a source that is never NaN.
     *
     * [lastSurroundOffset] is only written by [transform], i.e. while a framework animation runs.
     * If a gesture arrives before the first frame — or right after a scale change that skipped the
     * animator — it used to be NaN, and every coordinate derived from it was NaN too, which is
     * exactly how a floating-window gesture ends up on the wrong rectangle.
     */
    private fun visualOffset(): Float {
        val offset = lastSurroundOffset
        return if (offset.isNaN() || offset < 0f || offset >= screen.height) {
            screen.height * (1f - scale)
        } else {
            offset
        }
    }

    /** Scale of the picture, derived from the offset the leashes are actually using. */
    private fun pictureScale(): Float {
        val offsetY = visualOffset()
        return (1f - offsetY / screen.height).coerceIn(0.1f, 1f)
    }

    private fun offsetX(scale: Float): Float = screen.width * (1f - scale) * sideProgress

    private fun offsetY(scale: Float): Float = screen.height * (1f - scale)

    /**
     * Moves [event] from display space onto the picture, in place.
     *
     * The multitasking controller recycles this object once its handlers have consumed the mapped
     * coordinates, so it is transformed rather than replaced: a copy would either leak or be
     * freed twice. Only the pointer samples move, which is what those handlers read.
     */
    fun mapFreeformInput(event: MotionEvent) {
        if (state != 2) return
        val current = pictureScale()
        // Reused per thread: this runs for every event of every touch, and a fresh Matrix plus a
        // fresh float array on the input thread is allocation the collector has to chase.
        val values = inputValues.get()!!
        values[0] = 1f / current
        values[2] = -offsetX(current) / current
        values[4] = 1f / current
        values[5] = -offsetY(current) / current
        val inverse = inputMatrix.get()!!
        inverse.setValues(values)
        event.transform(inverse)
    }

    private val inputMatrix = ThreadLocal.withInitial { Matrix() }
    private val inputValues = ThreadLocal.withInitial {
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    /**
     * A copy of [event] that reads picture space on both of its coordinate channels.
     *
     * [MotionEvent.transform] moves only the pointer samples and leaves the raw axes at their
     * display values, and the launch gesture reads those raw axes. Rebuilding the event gives it
     * no offset to inherit, so its raw axes are the mapped point too. [alreadyMapped] says the
     * event went through the receiver mapping above and only needs the copy.
     *
     * Returns null when one-handed mode is off, so the caller forwards the original untouched.
     */
    fun pictureCopy(event: MotionEvent, alreadyMapped: Boolean): MotionEvent? {
        if (state != 2) return null
        if (event.pointerCount != 1) return null
        val x: Float
        val y: Float
        if (alreadyMapped) {
            x = event.x
            y = event.y
        } else {
            val current = pictureScale()
            x = (event.x - offsetX(current)) / current
            y = (event.y - offsetY(current)) / current
        }
        val copy = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, x, y, event.metaState,
        )
        copy.setSource(event.source)
        return copy
    }

    /** Picture point back to display space, for regions WindowManager publishes unscaled. */
    fun freeformPointToDisplay(x: Float, y: Float): PointF {
        val current = pictureScale()
        return PointF(x * current + offsetX(current), y * current + offsetY(current))
    }

    @Suppress("DEPRECATION")
    fun hasVisibleFreeformTask(): Boolean {
        val taskInfo = Class.forName("android.app.TaskInfo")
        val configuration = taskInfo.getField("configuration")
        val visible = taskInfo.getField("isVisible")
        val windowConfiguration = Configuration::class.java.getField("windowConfiguration")
        val mode = Class.forName("android.app.WindowConfiguration").getDeclaredMethod("getWindowingMode")
        // A single pass, no intermediate list: getRunningTasks already walks every task.
        for (task in context.getSystemService(ActivityManager::class.java).getRunningTasks(Int.MAX_VALUE)) {
            if (!visible.getBoolean(task)) continue
            if (mode.invoke(windowConfiguration.get(configuration.get(task))) == 5) return true
        }
        return false
    }

    private fun requestExit() {
        // Land the switch-side slide first: leaving halfway through would jump the content.
        finishSideAnimation()
        if (state == 1) {
            exitPending = true
        } else if (state == 2) {
            controller.call("stopOneHanded")
        }
    }

    /** Tap on the side strip: switch sides, or keep the classic exit when the option is off. */
    private fun onSideTap() {
        if (state == 2 && HandySettings.switchSideOnTap(preferences)) switchSide() else requestExit()
    }

    /** The back button in the side column: the same key the navigation bar would send. */
    private fun goBack() {
        if (!BackKey.send()) Log.w("OneHandedMode", "back key could not be injected")
    }

    /**
     * Puts the mode on [anchorRight], starting it first when it is not running.
     *
     * One entry point for the settings app's two side buttons: off means "enter on this side",
     * already on that side means "nothing to do", on the other side means "slide across".
     */
    private fun requestSide(anchorRight: Boolean) {
        when (state) {
            0 -> {
                downX = if (anchorRight) screen.width.toFloat() else 0f
                controller.call("startOneHanded")
            }
            2 -> {
                if (right == anchorRight) return
                if (leashes.isEmpty()) {
                    pendingSide = anchorRight
                    restartAt = SystemClock.uptimeMillis() + 300L
                    requestExit()
                    return
                }
                animateSideTo(if (anchorRight) 1f else 0f)
            }
        }
    }

    private fun switchSide() {
        if (state != 2 || sideAnimator?.isRunning == true) return
        // Without a leash there is nothing to slide; that build keeps exit-then-re-enter.
        if (leashes.isEmpty()) {
            pendingSide = !right
            restartAt = SystemClock.uptimeMillis() + 300L
            requestExit()
            return
        }
        animateSideTo(if (right) 0f else 1f)
    }

    /**
     * Slides the content to the other half of the display. The scale, the crop and the corner
     * radius are already applied and stay untouched; only the layer position changes, so this is
     * one transaction per frame and the content never leaves the screen.
     */
    private fun animateSideTo(target: Float) {
        if (abs(sideProgress - target) < 0.01f) return
        // Bump the id first: a cancelled animator must not run its completion step.
        sideAnimationId++
        sideAnimator?.cancel()
        val id = sideAnimationId
        sideTarget = target
        sideFrom = sideProgress
        // No adaptive frame skipping: the animator is already paced by the choreographer, and
        // dropping "the next few milliseconds" turned a 120 Hz panel into an irregular 50-60 Hz,
        // which is what the motion looked like — stutter, not slowness.
        val animator = ValueAnimator.ofFloat(sideProgress, target).apply {
            duration = 280L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applySide(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (id != sideAnimationId) return
                    applySide(target)
                    landOnSide()
                }
            })
        }
        sideAnimator = animator
        animator.start()
    }

    /** Horizontal move only: position goes from 0 to `width - scaled width` and back. */
    private fun applySide(progress: Float): Boolean {
        sideProgress = progress
        if (leashes.isEmpty()) return false
        val y = screen.height * (1f - scale)
        val x = screen.width * (1f - scale) * progress
        var applied = false
        for (leash in leashes) {
            val ok = runCatching { liveTx.setPosition(leash, x, y) }.isSuccess
            applied = applied || ok
        }
        if (!applied) return false
        runCatching { liveTx.apply() }
        lastSurroundOffset = y
        // Mask path, bubble, button column and gesture hint all read the same progress value;
        // the fraction is what the bubble panel's exit is timed against.
        overlay.updateSide(progress, sideFraction(progress))
        navHandle.update(scale, progress)
        return true
    }

    /** How far the current slide has travelled, 0 to 1. */
    private fun sideFraction(progress: Float): Float {
        val span = abs(sideTarget - sideFrom)
        return if (span < 0.001f) 1f else (abs(progress - sideFrom) / span).coerceIn(0f, 1f)
    }

    /** Commits the new side once the slide has landed. */
    private fun landOnSide() {
        sideAnimator = null
        val anchorRight = sideTarget > 0.5f
        sideTarget = Float.NaN
        right = anchorRight
        sideProgress = if (anchorRight) 1f else 0f
        overlay.settleSide(anchorRight)
        syncOrganizerOffset()
        // The system's own notion of the anchor has to match where the content now sits.
        publishMode(if (anchorRight) 2 else 1)
    }

    /** Jumps to the end of a running slide, e.g. when a finger lands on the strip mid-animation. */
    private fun finishSideAnimation() {
        val animator = sideAnimator ?: return
        sideAnimationId++
        animator.cancel()
        applySide(sideTarget)
        landOnSide()
    }

    /** Vertical drag on the side strip resizes the window instead of exiting. */
    private fun beginScaleSwipe() {
        // A finger on the strip wins over any running animation; land the slide so a drag never
        // starts from a half-moved position.
        stopScaleAnimation()
        finishSideAnimation()
        // Read the real state here: this runs once per drag, so the reflection is free, and it
        // avoids depending on the cached value staying in step with the callbacks.
        swipeActive = state == 2 && HandySettings.resizeOnSwipe(preferences)
        if (!swipeActive) return
        if (leashes.isEmpty()) refreshLeashes()
        swipeStartPercent = (scale * 100f).roundToInt()
        swipePercent = swipeStartPercent
        swipeApplied = swipeStartPercent
        swipeKickAt = 0L
        // 0 means "publish on the first move", so the app sees the drag start immediately.
        securePublishAt = 0L
        // A resize drag owns the strip: get the button panel out of the way first.
        overlay.closePanel()
        overlay.showSwipeHint()
    }

    private fun updateScaleSwipe(dy: Float) {
        if (!swipeActive) return
        // Dragging 45% of the screen height moves the scale by 60 points, as in the reference.
        val percent = (swipeStartPercent - dy / (screen.height * 0.45f) * 60f).roundToInt()
            .coerceIn(HandySettings.MIN_PERCENT, HandySettings.MAX_PERCENT)
        if (percent == swipePercent) return
        swipePercent = percent
        val now = SystemClock.uptimeMillis()
        // One update per display frame: the transaction is applied immediately, so pushing
        // faster than the compositor frames only burns CPU.
        if (now - swipeKickAt < 16L) return
        if (!applyLiveScale(percent)) return
        swipeKickAt = now
        overlay.showScale(percent)
        // The settings app has no other way to see a drag: remote preferences are read-only in
        // this process, so the live value goes to the settings database, which the app observes
        // through a ContentObserver. Throttled well above the frame rate: a provider write per
        // percent step would cost more than the repaint it accompanies.
        if (now - securePublishAt >= 120L) {
            securePublishAt = now
            writeSecurePercent(percent)
        }
    }

    private fun endScaleSwipe() {
        if (!swipeActive) return
        swipeActive = false
        // The last move may have been throttled; land on the final value before releasing.
        if (swipePercent != swipeApplied) applyLiveScale(swipePercent)
        overlay.hideScale()
        overlay.hideSwipeHint()
        overlay.endSwipe()
        syncOrganizerOffset()
        // Publish once more: the throttled write above can be one step behind the finger.
        if (swipeApplied == swipePercent) writeSecurePercent(swipePercent)
        if (swipePercent == swipeStartPercent) return
        // Persist through the module preferences as well; the remote edit is best effort and must
        // never escape: on this build it throws
        // UnsupportedOperationException("Read only implementation") inside SystemUI.
        runCatching {
            val editor = preferences.edit().putInt(HandySettings.CUSTOM, swipePercent)
            if (preferences.getInt(HandySettings.MODE, HandySettings.PRESET_MODE) == HandySettings.PRESET_MODE) {
                editor.putInt(HandySettings.MODE, HandySettings.CUSTOM_MODE)
            }
            editor.apply()
        }
    }

    /**
     * Moves the display-area leashes straight to the new scale.
     *
     * This mirrors what [transform] does inside the framework animation frames, but without
     * the animator: one transaction for every leash, applied on the spot. The organizer's own
     * bookkeeping is patched up by [syncOrganizerOffset] when the drag ends, so the exit
     * animation still starts from where the finger left the content.
     */
    private fun applyLiveScale(percent: Int): Boolean {
        if (applyScale(percent / 100f)) {
            swipeApplied = percent
            return true
        }
        return applyScaleViaOrganizer(percent)
    }

    /**
     * Drives every leash to [target] in a single transaction. This is the shared tail of the
     * side-strip drag and of [animateScale]: one SurfaceFlinger round trip per step, the mask
     * following, and the navigation hint kept in step.
     */
    private fun applyScale(target: Float): Boolean {
        if (leashes.isEmpty()) return false
        val y = screen.height * (1f - target)
        val x = screen.width * (1f - target) * sideProgress
        var applied = false
        for (leash in leashes) {
            val ok = runCatching {
                if (preparedLeashes.add(leash)) {
                    windowCrop.invoke(liveTx, leash, screen.width, screen.height)
                    corner.invoke(liveTx, leash, cornerRadius)
                }
                matrix.invoke(liveTx, leash, target, 0f, 0f, target)
                liveTx.setPosition(leash, x, y)
            }.isSuccess
            if (!ok) preparedLeashes.remove(leash)
            applied = applied || ok
        }
        if (!applied) return false
        runCatching { liveTx.apply() }
        scale = target
        lastSurroundOffset = y
        // Only the mask repaints; the window bounds stay put for the whole gesture.
        overlay.updateScale(target)
        navHandle.update(target, sideProgress)
        // Keep the organizer's bookkeeping current while animating, so an exit halfway through
        // fades out from wherever the content actually is.
        if (scaleAnimator?.isRunning == true) syncOrganizerOffset()
        return true
    }

    /**
     * Animates from the current scale to [target] without leaving one-handed mode.
     *
     * The settings app can only publish a value (it cannot drive SystemUI), so the value arrives
     * as a preferences change. The framework's own reaction to a size change is to exit and
     * re-enter, which flashes the whole screen; moving the leashes from an animator keeps the
     * content visible for the entire transition.
     */
    private fun animateScale(target: Float) {
        if (abs(target - scale) < 0.005f) return
        // A finger on the strip owns the geometry; its release writes the final value anyway.
        if (swipeActive) return
        if (scaleAnimator?.isRunning == true && abs(scaleTarget - target) < 0.005f) return
        if (leashes.isEmpty()) {
            applyScaleViaOrganizer((target * 100f).roundToInt())
            // No animator runs on this path, so the strips have to be re-synced here.
            overlay.settleScale()
            return
        }
        // Hold the bubbles for the whole run: settleScale() then moves all four at once.
        overlay.freezeColumn(true)
        // Bump the id first: a cancelled animator must not run its completion step.
        scaleAnimationId++
        scaleAnimator?.cancel()
        val id = scaleAnimationId
        scaleTarget = target
        // Same reasoning as the side slide: the choreographer already paces this, and skipping
        // frames by a measured cost only makes the cadence irregular.
        val animator = ValueAnimator.ofFloat(scale, target).apply {
            duration = 260L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applyScale(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (id != scaleAnimationId) return
                    applyScale(target)
                    syncOrganizerOffset()
                    scaleAnimator = null
                    scaleTarget = Float.NaN
                    overlay.hideScale()
                    // The mask and the column followed every frame; the two touch strips are
                    // windows and still sit on the gap the previous scale had.
                    overlay.settleScale()
                }
            })
        }
        scaleAnimator = animator
        // Same indicator as a drag: the value being applied, not the one being typed.
        overlay.showScale((target * 100f).roundToInt())
        animator.start()
    }

    private fun stopScaleAnimation() {
        scaleAnimationId++
        scaleAnimator?.cancel()
        scaleAnimator = null
        scaleTarget = Float.NaN
        overlay.freezeColumn(false)
    }

    /** Fallback for builds where the leash map is unavailable; jitters, but keeps the feature. */
    private fun applyScaleViaOrganizer(percent: Int): Boolean {
        val target = percent / 100f
        val applied = runCatching {
            organizer.call("scheduleOffset", 0, (screen.height * (1f - target)).roundToInt())
        }.isSuccess
        if (applied) {
            scale = target
            swipeApplied = percent
            lastSurroundOffset = screen.height * (1f - target)
            overlay.updateScale(target)
        }
        return applied
    }

    /** Hands the drag result back to the organizer so its exit animation starts from it. */
    private fun syncOrganizerOffset() {
        val y = screen.height * (1f - scale)
        runCatching { organizer.write("mLastVisualOffset", y) }
        runCatching {
            (organizer.read("mLastVisualDisplayBounds") as android.graphics.Rect).offsetTo(0, y.roundToInt())
        }
        runCatching { controller.write("mOffSetFraction", 1f - scale) }
    }

    fun onStateChanged(newState: Int) {
        cachedState = newState
        when (newState) {
            1 -> {
                stopScaleAnimation()
                entryTime = SystemClock.elapsedRealtime()
                sideProgress = if (right) 1f else 0f
                lastSurroundOffset = Float.NaN
                // A new entry starts from clean layer geometry; resetWindowsOffset() cleared it.
                preparedLeashes.clear()
                val color = backgroundColor()
                // The color layer only ever holds a flat color now: pictures are painted by the
                // mask window, so there is no SurfaceControl buffer left to composite.
                background.prepare(screen, color, backgroundParent())
                overlay.setPicture(backgroundBitmap())
                // With resize-by-drag off, a tap is all the side strip does, so the hint has to
                // say that instead of describing a drag.
                overlay.show(
                    screen, scale, right, color, cornerRadius,
                    HandySettings.resizeOnSwipe(preferences),
                    HandySettings.switchSideOnTap(preferences),
                    HandySettings.surroundHints(preferences),
                )
                publishMode(if (right) 2 else 1)
            }
            2 -> {
                overlay.onEntryFinished()
                // Announce the mode once more now that the content has actually landed. The
                // launcher computes its swipe region from the state it reads, and the first
                // notification goes out while the entry animation is still running; a second one
                // after the landing makes it recompute against the final geometry.
                publishMode(if (right) 2 else 1)
                if (exitPending) {
                    exitPending = false
                    handler.post { requestExit() }
                }
            }
            3 -> {
                // The framework is fading out from the offset we kept in sync; drop the animator
                // without applying its target so the two animations do not fight.
                stopScaleAnimation()
                overlay.onExitStarted()
            }
            0 -> {
                stopScaleAnimation()
                sideProgress = if (right) 1f else 0f
                exitPending = false
                background.hide()
                overlay.hide()
                navHandle.reset()
                publishMode(0)
                val target = pendingSide
                pendingSide = null
                if (target != null || resizePending) {
                    val anchorRight = target ?: right
                    resizePending = false
                    handler.postDelayed({
                        downX = if (anchorRight) screen.width.toFloat() else 0f
                        controller.call("startOneHanded")
                    }, (restartAt - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                }
            }
        }
    }

    /**
     * Tells the rest of the system which one-handed mode is active.
     *
     * Two channels: `Settings.Global.handy_mode`, which OplusHandySpace reads through a
     * ContentObserver, and `miui.action.handymode_change`, which is what MIUI's launcher acts on —
     * it re-reads the mode and moves its own swipe-up region onto the scaled content, and the
     * floating-window launch picks its bounds from the same state.
     *
     * Deliberately identical to the last build that still sent it (1.0.12): the write and the
     * broadcast happen on the calling thread, in this order. Moving them to a background queue
     * changed *when* the launcher learned about the mode; since the goal here is to rule that out,
     * the original ordering is restored. The only addition is the switch.
     */
    private fun publishMode(mode: Int) {
        val announce = runCatching {
            HandySettings.broadcastMode(preferences)
        }.getOrDefault(true)
        runCatching { Settings.Global.putInt(context.contentResolver, "handy_mode", mode) }
        if (!announce) {
            Log.i("OneHandedMode", "handy_mode=$mode (broadcast off)")
            return
        }
        val intent = Intent(HANDY_MODE_CHANGE).putExtra("handymode", mode)
        if (mode == 0) intent.putExtra("handymodetime", SystemClock.elapsedRealtime() - entryTime)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w("OneHandedMode", "handy_mode=$mode broadcast failed: $it") }
    }

    /** Wallpaper or picked picture, already blurred; null keeps the flat color layer. */
    private fun backgroundBitmap(): Bitmap? {
        val blur = HandySettings.backgroundBlur(preferences)
        val source = HandySettings.backgroundSource(preferences)
        if (source == HandySettings.SOURCE_SOLID) return null
        val image = HandySettings.backgroundImage(preferences)
        val key = "$source:$blur:${image.hashCode()}:${screen.width}x${screen.height}"
        if (pictureCacheKey == key) return pictureCache
        val created = runCatching { buildBackgroundBitmap(source, blur, image) }.getOrNull() ?: return null
        // Never recycle the previous bitmap: the mask window may still be holding it until the
        // replacement reaches it on the next main-thread pass. A few hundred KB can wait for GC.
        pictureCache = created
        pictureCacheKey = key
        return created
    }

    private fun buildBackgroundBitmap(source: Int, blur: Int, image: String): Bitmap? = runCatching {
        when (source) {
            HandySettings.SOURCE_WALLPAPER -> {
                val drawable = WallpaperManager.getInstance(context).drawable ?: return@runCatching null
                // Draw straight into a bitmap that already has the display aspect ratio; a
                // wallpaper drawable stretches to the bounds it is given.
                val width = if (blur > 0) 360 else screen.width
                val height = (width * screen.height.toFloat() / screen.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                val blurred = BackgroundBitmap.materialBlur(bitmap, blur)
                if (blurred !== bitmap) bitmap.recycle()
                blurred
            }
            HandySettings.SOURCE_IMAGE -> {
                val decoded = BackgroundBitmap.decode(image) ?: return@runCatching null
                val fitted = BackgroundBitmap.fit(
                    decoded, screen.width, screen.height, if (blur > 0) 360 else screen.width,
                )
                if (fitted !== decoded) decoded.recycle()
                val blurred = BackgroundBitmap.materialBlur(fitted, blur)
                if (blurred !== fitted) fitted.recycle()
                blurred
            }
            else -> null
        }
    }.getOrNull()

    private fun refreshBackground() {
        val color = backgroundColor()
        // The color layer is built once on entry and removed on exit. Recoloring the live layer
        // keeps this path away from SurfaceControl lifecycle entirely: replacing it used to leave
        // a new layer at the top of its parent, which covered the whole display until reboot.
        background.recolor(color)
        overlay.setPicture(backgroundBitmap())
        overlay.recolor(color)
    }

    private fun backgroundColor(): Int {
        val custom = HandySettings.surroundColor(preferences)
        if (custom != HandySettings.FOLLOW_THEME) return custom
        return if (Build.VERSION.SDK_INT >= 33) {
            controller.read("mTutorialHandler")!!.read("mBackgroundWindowManager")!!
                .call("getThemeColorForBackground") as Int
        } else {
            val color = controller.read("mBackgroundPanelOrganizer")!!.read("mThemeColor") as FloatArray
            Color.rgb((color[0] * 255).roundToInt(), (color[1] * 255).roundToInt(), (color[2] * 255).roundToInt())
        }
    }

    fun onMotionEvent(event: MotionEvent) {
        if (cachedState == 0 && event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.rawX
        hiddenBarGesture.onMotionEvent(event, screen.height)
    }

    fun transform(tx: SurfaceControl.Transaction, leash: SurfaceControl, offset: Float): PointF {
        val y = offset
        val currentScale = 1f - y / screen.height
        val x = screen.width * (1f - currentScale) * sideProgress
        background.apply(tx)
        matrix.invoke(tx, leash, currentScale, 0f, 0f, currentScale)
        // The crop and the corner radius never change while a leash lives. Re-issuing them on
        // every animation frame forces SurfaceFlinger to recompute this layer's geometry twice
        // per frame, so they are applied once per leash instead.
        if (preparedLeashes.add(leash)) {
            windowCrop.invoke(tx, leash, screen.width, screen.height)
            // SurfaceFlinger scales this local radius with the same matrix as the content.
            corner.invoke(tx, leash, cornerRadius)
        }
        lastSurroundOffset = y
        // Follow the entry and exit animations as well as live drags.
        navHandle.update(currentScale, sideProgress)
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
