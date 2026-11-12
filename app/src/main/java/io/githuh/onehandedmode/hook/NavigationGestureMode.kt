package io.githuh.onehandedmode.hook

import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Looper
import android.util.Log
import android.view.Display
import io.githuh.onehandedmode.HandySettings
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Change only the navigation state sent by SystemUI to the launcher. */
internal class NavigationGestureMode(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val preferences: SharedPreferences,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private class Notification(val service: Any, val arguments: Array<Any?>)
    @Volatile private var lastNotification: Notification? = null
    @Volatile private var proxyService: Any? = null
    private val notifyingFlags = ThreadLocal<Long>()
    private val mirroredNotifications = ConcurrentHashMap<Int, Notification>()
    private val lastTransportStates = ConcurrentHashMap<Int, Int>()
    private lateinit var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener
    private var installed = false
    private var pending: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Installing these hooks requires deoptimizing LauncherProxyService/SysUiStateImpl
     * class-wide, because ART inlines the notifier and the state setter into their callers.
     * Those classes sit on the hot path of every SystemUI state update, so deoptimizing them
     * unconditionally is what made the whole system feel sluggish.
     *
     * The feature is off by default, so the hooks are only installed the first time the user
     * turns "keep Home and Recents gestures" on.
     */
    fun installLazy() {
        if (preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)) {
            install()
            return
        }
        val listener = object : SharedPreferences.OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
                if (key != HandySettings.KEEP_NAVIGATION_GESTURES) return
                if (!prefs.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)) return
                prefs.unregisterOnSharedPreferenceChangeListener(this)
                pending = null
                install()
            }
        }
        pending = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
        module.log(Log.INFO, "OneHandedMode", "Navigation notifier deferred: setting is off")
    }

    fun install() {
        if (installed) return
        installed = true
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
        serviceType.declaredConstructors.forEach { constructor ->
            module.deoptimize(constructor)
            module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                proxyService = chain.thisObject!!
                result
            }
        }
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
                notifyingFlags.set((original[flagsIndex] as Number).toLong())
                try {
                    if (preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)) {
                        val args = original.copyOf()
                        val flags = (original[flagsIndex] as Number).toLong() and ONE_HANDED_ACTIVE.inv()
                        args[flagsIndex] = if (flagsType == Long::class.javaPrimitiveType) flags else flags.toInt()
                        chain.proceed(args)
                    } else chain.proceed()
                } finally {
                    notifyingFlags.remove()
                }
            }
        }

        // The transport hook needs BinderProxy.transact and a launcher proxy field that some
        // ROMs rename. Losing it only costs mirrored-display accuracy, so it must never take
        // the notifier hook down with it.
        runCatching { installTransportHook(serviceType, notify, flagsIndex, flagsType) }
            .onFailure { module.log(Log.WARN, "OneHandedMode", "Navigation transport unavailable: $it") }

        // The original notifier owns connection checks and RemoteException handling.
        // Replay its unfiltered arguments when switching modes, including when it was called
        // during construction. Keep the real SystemUI state and independent launcher connections unchanged.
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HandySettings.KEEP_NAVIGATION_GESTURES) mainHandler.post {
                // A mirrored display can be the last state consumed by the primary launcher.
                // Refresh every stream on that connection, then leave the main display last.
                mirroredNotifications.toSortedMap().values.forEach {
                    notify.invoke(it.service, *it.arguments)
                }
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

    private fun installTransportHook(serviceType: Class<*>, notify: Method, flagsIndex: Int, flagsType: Class<*>) {
        val proxyField = serviceType.declaredFields.single {
            it.name == "mLauncherProxy" || it.name == "mOverviewProxy"
        }.apply { isAccessible = true }
        val descriptor = proxyField.type.name
        val longFlags = flagsType == Long::class.javaPrimitiveType
        val perDisplay = notify.parameterCount == 2
        // Let Parcel generate this platform's request header; do not assume a byte offset
        // or call enforceInterface(), which changes the caller's StrictMode policy.
        val header = Parcel.obtain()
        val flagsOffset = try {
            header.writeInterfaceToken(descriptor)
            header.dataPosition()
        } finally {
            header.recycle()
        }
        val intType = Int::class.javaPrimitiveType!!
        val transact = Class.forName("android.os.BinderProxy").getDeclaredMethod(
            "transact", intType, Parcel::class.java, Parcel::class.java, intType,
        )
        module.deoptimize(transact)
        module.hook(transact).intercept { chain ->
            if (chain.getArg(0) != SYSTEM_UI_STATE_TRANSACTION ||
                (chain.thisObject as IBinder).interfaceDescriptor != descriptor) {
                chain.proceed()
            } else {
                val data = chain.getArg(1) as Parcel
                val position = data.dataPosition()
                data.setDataPosition(flagsOffset)
                val wireFlags = if (longFlags) data.readLong() else data.readInt().toLong()
                val displayId = if (perDisplay) data.readInt() else Display.DEFAULT_DISPLAY
                data.setDataPosition(position)
                val mainLauncher = proxyService?.let { proxyField.get(it) as IInterface? }?.asBinder()
                // SysUIStateOverride inherits the main display's flags, then publishes them
                // with another display ID to the same launcher. Xiaomi's native receiver
                // stores those messages in one shared flags cache, ignoring the display ID.
                // Keep an independent secondary launcher connection outside this addition.
                if (displayId != Display.DEFAULT_DISPLAY && chain.thisObject !== mainLauncher) {
                    chain.proceed()
                } else {
                    // Preserve the pre-filter state when the existing notifier hook ran.
                    // A sender that bypassed that hook supplies its original flags on the wire.
                    val sourceFlags = notifyingFlags.get() ?: wireFlags
                    val originalFlags = (wireFlags and ONE_HANDED_ACTIVE.inv()) or
                        (sourceFlags and ONE_HANDED_ACTIVE)
                    proxyService?.let { service ->
                        val args = arrayOfNulls<Any>(notify.parameterCount)
                        args[flagsIndex] = if (longFlags) originalFlags else originalFlags.toInt()
                        if (perDisplay) args[1 - flagsIndex] = displayId
                        val notification = Notification(service, args)
                        if (displayId == Display.DEFAULT_DISPLAY) lastNotification = notification
                        else mirroredNotifications[displayId] = notification
                    }
                    val enabled = preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)
                    val sentFlags = if (enabled) originalFlags and ONE_HANDED_ACTIVE.inv() else originalFlags
                    data.setDataPosition(flagsOffset)
                    if (longFlags) data.writeLong(sentFlags) else data.writeInt(sentFlags.toInt())
                    data.setDataPosition(position)
                    try {
                        val result = chain.proceed()
                        val active = originalFlags and ONE_HANDED_ACTIVE != 0L
                        val transportState = (if (active) 1 else 0) or (if (enabled) 2 else 0)
                        if (lastTransportStates.put(displayId, transportState) != transportState) {
                            module.log(Log.INFO, "OneHandedMode", "Navigation state sent: display=$displayId, " +
                                "oneHanded=$active, keepGestures=$enabled, " +
                                "sentOneHanded=${sentFlags and ONE_HANDED_ACTIVE != 0L}, delivered=$result")
                        }
                        result
                    } finally {
                        // The caller owns the parcel. Restore both its contents and cursor.
                        data.setDataPosition(flagsOffset)
                        if (longFlags) data.writeLong(wireFlags) else data.writeInt(wireFlags.toInt())
                        data.setDataPosition(position)
                    }
                }
            }
        }
        module.log(Log.INFO, "OneHandedMode", "Navigation transport attached: $descriptor, " +
            "longFlags=$longFlags, perDisplay=$perDisplay")
    }

    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    private companion object {
        const val ONE_HANDED_ACTIVE = 1L shl 16
        const val SYSTEM_UI_STATE_TRANSACTION = 17
    }
}
