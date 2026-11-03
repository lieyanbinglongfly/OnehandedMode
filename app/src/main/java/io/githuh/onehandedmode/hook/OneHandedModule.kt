package io.githuh.onehandedmode.hook

import android.animation.ValueAnimator
import android.animation.Animator
import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import io.githuh.onehandedmode.HandySettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.math.sqrt

private class AnimationSurface(val leash: SurfaceControl, val offsetBias: Float)

class OneHandedModule : XposedModule() {
    private lateinit var runtime: HandyRuntime
    private var isSystemUiProcess = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        isSystemUiProcess = param.processName == "com.android.systemui"
        if (param.isSystemServer || isSystemUiProcess) {
            val properties = Class.forName("android.os.SystemProperties")
            hook(properties.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType))
                .intercept { chain ->
                    if (chain.getArg(0) == "ro.support_one_handed_mode") true else chain.proceed()
                }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!isSystemUiProcess || param.packageName != "com.android.systemui" || !param.isFirstPackage) return

        val loader = param.classLoader
        val preferences = getRemotePreferences(HandySettings.GROUP)
        val prefix = "com.android.wm.shell.onehanded."
        fun type(name: String) = Class.forName(prefix + name, false, loader)
        fun intercept(type: Class<*>, name: String, vararg parameters: Class<*>, action: XposedInterface.Hooker) {
            hook(type.getDeclaredMethod(name, *parameters)).intercept(action)
        }
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!

        // R8 removes SettingsUtil getters. The framework settings boundary remains unchanged.
        intercept(Settings.Secure::class.java, "getIntForUser",
            ContentResolver::class.java, String::class.java, intType, intType) { chain ->
            when (chain.getArg(1) as String) {
                "one_handed_mode_enabled" -> 1
                "one_handed_mode_timeout", "taps_app_to_exit", "swipe_bottom_to_notification_enabled" -> 0
                else -> chain.proceed()
            }
        }
        val controller = type("OneHandedController")
        controller.declaredMethods.forEach { deoptimize(it) }
        type("OneHandedSettingsUtil").declaredMethods.forEach { deoptimize(it) }
        for (constructor in controller.declaredConstructors) {
            deoptimize(constructor)
            hook(constructor).intercept { chain ->
                val result = chain.proceed()
                runtime = HandyRuntime(chain.thisObject!!, preferences)
                runtime.attach()
                log(Log.INFO, "OneHandedMode", "Controller attached")
                result
            }
        }
        intercept(controller, "startOneHanded") { chain ->
            if (!runtime.prepareEntry()) null else chain.proceed()
        }
        intercept(controller, "stopOneHanded", intType) { chain ->
            when (chain.getArg(0) as Int) {
                2, 3, 5, 6 -> null // The MIUI surround owns outside touches; keyboard/app/timeout keep the mode.
                else -> if (runtime.deferExit()) null else chain.proceed()
            }
        }
        intercept(type("OneHandedState"), "setState", intType) { chain ->
            val result = chain.proceed()
            val state = chain.getArg(0) as Int
            if (state == 1 || state == 3) runtime.onStateChanged(state)
            result
        }

        // This framework override survives R8, unlike OneHandedTouchHandler.onMotionEvent.
        intercept(type("OneHandedTouchHandler\$EventReceiver"), "onInputEvent", InputEvent::class.java) { chain ->
            val event = chain.getArg(0)
            if (event is MotionEvent) runtime.onMotionEvent(event)
            chain.proceed()
        }
        intercept(type("OneHandedAnimationController\$OneHandedInterpolator"), "getInterpolation", floatType) { chain ->
            circularEaseOut(chain.getArg(0) as Float)
        }

        val animatorType = type("OneHandedAnimationController\$OneHandedTransitionAnimator")
        val leashField = animatorType.getDeclaredField("mLeash").apply { isAccessible = true }
        val currentSurface = ThreadLocal<AnimationSurface>()
        for ((callback, parameter) in listOf(
            "onAnimationUpdate" to ValueAnimator::class.java,
            "onAnimationStart" to Animator::class.java,
            "onAnimationEnd" to Animator::class.java,
        )) {
            val method = animatorType.getDeclaredMethod(callback, parameter)
            deoptimize(method)
            hook(method).intercept { chain ->
                // Frames round with +0.5f; vendor start/end transactions use the exact offset.
                currentSurface.set(AnimationSurface(
                    leashField.get(chain.thisObject) as SurfaceControl,
                    if (callback == "onAnimationUpdate") 0.5f else 0f,
                ))
                try {
                    chain.proceed()
                } finally {
                    currentSurface.remove()
                }
            }
        }
        // Surface helpers and anonymous applySurfaceControlTransaction are also inlined by R8.
        // Amend the original transaction inside the original frame, before it is submitted.
        intercept(SurfaceControl.Transaction::class.java, "setPosition",
            SurfaceControl::class.java, floatType, floatType) { chain ->
            val surface = currentSurface.get()
            if (surface == null || surface.leash !== chain.getArg(0)) {
                chain.proceed()
            } else {
                val position = runtime.transform(
                    chain.thisObject as SurfaceControl.Transaction, surface.leash,
                    (chain.getArg(2) as Float) - surface.offsetBias,
                )
                chain.proceed(arrayOf(surface.leash, position.x, position.y))
            }
        }
        intercept(type("OneHandedDisplayAreaOrganizer"), "resetWindowsOffset") { chain ->
            val result = chain.proceed()
            val leashes = chain.thisObject!!.read("mDisplayAreaTokenMap") as Map<*, *>
            val matrix = SurfaceControl.Transaction::class.java.getDeclaredMethod(
                "setMatrix", SurfaceControl::class.java, floatType, floatType, floatType, floatType,
            )
            SurfaceControl.Transaction().use { tx ->
                leashes.values.forEach { matrix.invoke(tx, it as SurfaceControl, 1f, 0f, 0f, 1f) }
                tx.apply()
            }
            result
        }
        log(Log.INFO, "OneHandedMode", "Hooks installed at framework and callback boundaries")
    }

    companion object {
        fun circularEaseOut(fraction: Float): Float = sqrt(1f - (fraction - 1f) * (fraction - 1f))
    }
}
