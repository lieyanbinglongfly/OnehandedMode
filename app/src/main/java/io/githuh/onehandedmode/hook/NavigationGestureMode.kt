package io.githuh.onehandedmode.hook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import io.githuh.onehandedmode.HandySettings
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier

/** Change only the navigation state sent by SystemUI to the launcher. */
internal class NavigationGestureMode(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val preferences: SharedPreferences,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private class Notification(val service: Any, val arguments: Array<Any?>)
    private var lastNotification: Notification? = null
    private lateinit var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener

    fun install() {
        // Android 16 QPR renamed OverviewProxyService/IOverviewProxy to LauncherProxyService/
        // ILauncherProxy. Resolve the actual ABI from Recents instead of an SDK minor version.
        val recents = type("com.android.systemui.recents.OverviewProxyRecentsImpl")
        val bridge = recents.declaredFields.single {
            it.name == "mOverviewProxyService" || it.name == "mLauncherProxyService"
        }
        val serviceType = bridge.type
        val notify = serviceType.declaredMethods.single { it.name == "notifySystemUiStateFlags" }
            .apply { isAccessible = true }
        // Old releases have one int flag argument. New releases use long flags, with an
        // optional display ID whose position may be reordered by R8.
        val flagsIndex = if (notify.parameterCount == 1) 0
            else notify.parameterTypes.indexOf(Long::class.javaPrimitiveType)
        val flagsType = notify.parameterTypes[flagsIndex]
        serviceType.declaredMethods.forEach { module.deoptimize(it) }
        // Xiaomi R8 keeps this field's concrete type but removes the unused assignment.
        // Deoptimize the class directly; the field's runtime value is therefore unnecessary.
        serviceType.getDeclaredField("mSysUiStateCallback").type.declaredMethods
            .filterNot { Modifier.isAbstract(it.modifiers) }
            .forEach { module.deoptimize(it) }
        serviceType.declaredConstructors.forEach { module.deoptimize(it) }
        // ART can inline the callback and notifier into commitUpdate. Deoptimizing only
        // the callees does not remove that compiled caller. Older systems keep state in
        // SysUiState itself; newer systems expose an interface backed by SysUiStateImpl.
        val state = type("com.android.systemui.model.SysUiState")
        val stateImplementation = if (state.isInterface) type("com.android.systemui.model.SysUiStateImpl") else state
        stateImplementation.declaredMethods.filterNot { Modifier.isAbstract(it.modifiers) }
            .forEach { module.deoptimize(it) }
        stateImplementation.declaredConstructors.forEach { module.deoptimize(it) }
        module.hook(notify).intercept { chain ->
            if (notify.parameterCount == 2 && chain.getArg(1 - flagsIndex) != Display.DEFAULT_DISPLAY) {
                chain.proceed()
            } else {
                val original = Array<Any?>(notify.parameterCount) { chain.getArg(it) }
                lastNotification = Notification(chain.thisObject!!, original)
                if (preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)) {
                    val args = original.copyOf()
                    val flags = (original[flagsIndex] as Number).toLong() and ONE_HANDED_ACTIVE.inv()
                    args[flagsIndex] = if (flagsType == Long::class.javaPrimitiveType) flags else flags.toInt()
                    chain.proceed(args)
                } else chain.proceed()
            }
        }

        // The original notifier owns connection checks and RemoteException handling.
        // Replay its unfiltered arguments when switching modes, including when it was called
        // during construction. Keep the real SystemUI state and other displays unchanged.
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HandySettings.KEEP_NAVIGATION_GESTURES) mainHandler.post {
                lastNotification?.let {
                    notify.invoke(it.service, *it.arguments)
                }
                module.log(Log.INFO, "OneHandedMode", "Navigation gestures enabled=" +
                    preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        module.log(Log.INFO, "OneHandedMode", "Navigation notifier attached: $notify, enabled=" +
            preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false))
    }

    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    private companion object {
        const val ONE_HANDED_ACTIVE = 1L shl 16
    }
}
