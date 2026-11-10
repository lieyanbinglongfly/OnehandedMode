package io.githuh.onehandedmode.hook

import android.content.ContentResolver
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.libxposed.api.XposedModule

/** Adapter for Xiaomi's multitasking shell implementation. */
internal class XiaomiFreeformCoexist(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val runtime: () -> HandyRuntime,
) {
    private val multitaskingSettingsRead = ThreadLocal<Boolean>()
    private val logicalInputDispatch = ThreadLocal<Boolean>()
    private val sidebarCoverInit = ThreadLocal<Boolean>()

    fun install(hasLinkageAnimation: Boolean) {
        // Resolve every required base target before mutating the process. An incomplete or
        // third-party Xiaomi package must not leave a partially installed adapter behind.
        val settings = type("common.MultiTaskingSettingsObserver")
        val changed = settings.getDeclaredMethod("onOneHandleModeChanged")
        val intType = Int::class.javaPrimitiveType!!
        val secureRead = Settings.Secure::class.java.getDeclaredMethod(
            "getIntForUser", ContentResolver::class.java, String::class.java, intType, intType,
        )
        val settingsCallbacks = type("common.MultiTaskingSettingsObserver\$3").declaredMethods
        val receiver = type("miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController\$EventReceiver")
        val receive = receiver.getDeclaredMethod("onInputEvent", InputEvent::class.java)
        val floatType = Float::class.javaPrimitiveType!!
        val exclusionQueries = listOf(
            "miuifreeform.MiuiFreeformModeGestureHandler",
            "miuimultiwinswitch.miuiwindowdecor.MulWinSwitchAnimStatus",
        ).map { name ->
            val owner = type(name)
            owner.getDeclaredMethod("isInExcludeRegion", floatType, floatType)
        }
        val gestureHandler = type("miuifreeform.MiuiFreeformModeGestureHandler")
        val handleMotionEvents = gestureHandler.getDeclaredMethod(
            "handleMotionEvents", MotionEvent::class.java, Class.forName("android.view.InputMonitor"),
        )
        val switchEventHandlerMethods =
            type("miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventHandler").declaredMethods

        module.deoptimize(changed)
        // This is the exact observer that reads the one-handed activation setting on this platform.
        settingsCallbacks.forEach { module.deoptimize(it) }
        module.hook(changed).intercept { chain ->
            multitaskingSettingsRead.set(true)
            try {
                chain.proceed()
            } finally {
                multitaskingSettingsRead.remove()
            }
        }
        module.hook(secureRead).intercept { chain ->
            if (chain.getArg(1) == "one_handed_mode_activated" && multitaskingSettingsRead.get() == true) {
                // Only Xiaomi's multitasking subsystem sees coexistence; the actual setting and
                // Google's one-handed state, launcher flags and gesture lifecycle remain active.
                0
            } else chain.proceed()
        }
        module.deoptimize(receive)
        module.hook(receive).intercept { chain ->
            val event = chain.getArg(0)
            if (event is MotionEvent && runtime().isOneHandedActive()) {
                // Preserve the input sequence number: the original receiver finishes and recycles
                // this private event after its handlers consume the transformed coordinates.
                runtime().mapFreeformInput(event)
                logicalInputDispatch.set(true)
                try {
                    chain.proceed()
                } finally {
                    logicalInputDispatch.remove()
                }
            } else chain.proceed()
        }

        // WindowManager publishes exclusion regions in physical display space. Query those
        // regions using physical points while the gesture handlers operate in task space.
        for (query in exclusionQueries) {
            module.deoptimize(query)
            module.hook(query).intercept { chain ->
                if (logicalInputDispatch.get() == true) {
                    val point = runtime().freeformPointToDisplay(chain.getArg(0) as Float, chain.getArg(1) as Float)
                    chain.proceed(arrayOf(point.x, point.y))
                } else chain.proceed()
            }
        }
        module.deoptimize(handleMotionEvents)
        switchEventHandlerMethods.forEach { module.deoptimize(it) }

        // Older multitasking shells have no linkage animation subsystem. A changed optional
        // animation API must not disable the already validated common settings/input adapter.
        val linkageInstalled = hasLinkageAnimation && runCatching {
            installSidebarAnimation()
        }.onFailure { error ->
            module.log(Log.WARN, "OneHandedMode", "Skipping Xiaomi linkage animation adapter", error)
        }.isSuccess
        module.log(
            Log.INFO,
            "OneHandedMode",
            "Xiaomi freeform coexistence adapter installed, linkageAnimation=$linkageInstalled",
        )
    }

    @Suppress("DEPRECATION")
    fun hasLinkageAnimation(): Boolean {
        // Inspect the defining shell loader's existing dex files without loading an absent
        // class. This runs during background capability detection, before installing hooks.
        val shellLoader = type("common.MultiTaskingSettingsObserver").classLoader
        val pathList = BaseDexClassLoader::class.java.getDeclaredField("pathList")
            .apply { isAccessible = true }.get(shellLoader)!!
        val elements = pathList.read("dexElements") as Array<*>
        val name = "com.android.wm.shell.multitasking.common.linkage.MultiTaskingLinkageTransition"
        return elements.any { element ->
            val dex = element!!.read("dexFile") as DexFile?
            dex?.entries()?.asSequence()?.any { it == name } == true
        }
    }

    private fun installSidebarAnimation() {
        val intType = Int::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!
        val transition = type("common.linkage.MultiTaskingLinkageTransition")
        val session = type("common.linkage.MultiTaskingLinkageSession\$LinkageSession")
        val initCover = transition.getDeclaredMethod(
            "initCoverLayerCommon", session, Bitmap::class.java, intType, booleanType, booleanType,
        )
        val parent = type("common.cover.MultiTaskingCoverLayerParent")
        val taskParent = parent.getDeclaredMethod("getCoverLayerParent")
        val taskTopParent = parent.getDeclaredMethod("getCoverLayerTopParent")
        val initIconShape = type("common.cover.MultiTaskingCoverLayerController").getDeclaredMethod(
            "initIconShape", type("common.cover.MultiTaskingShapeAnimInfo"), Float::class.javaPrimitiveType!!,
        )
        val animationCallback = type("common.IMultiTaskingAnimationStateCallback")
        // Deoptimize the caller so ART's inlined private initialization reaches the scoped hook.
        val startSidebar = transition.getDeclaredMethod(
            "startSidebarLaunchFreeform", android.app.PendingIntent::class.java, intType,
            String::class.java, String::class.java, Bitmap::class.java, android.graphics.Rect::class.java,
            Float::class.javaPrimitiveType!!, intType, intType, animationCallback,
        )

        // All optional targets are resolved above before any linkage hooks are installed.
        module.deoptimize(initCover)
        module.hook(initCover).intercept { chain ->
            val linkageSession = chain.getArg(0)!!
            if (linkageSession.read("linkageWay") == "SidebarFreeForm" && runtime().isOneHandedActive()) {
                sidebarCoverInit.set(true)
                try {
                    chain.proceed()
                } finally {
                    sidebarCoverInit.remove()
                }
            } else chain.proceed()
        }

        // initIconShape normally lifts only the icon to MaskTopParent, outside the one-handed
        // display area. Keep this sidebar icon with its snapshot and dark layers in MaskParent;
        // their native animation coordinates then inherit the same display transform throughout.
        module.hook(taskTopParent).intercept { chain ->
            if (sidebarCoverInit.get() == true) taskParent.invoke(chain.thisObject) else chain.proceed()
        }
        module.deoptimize(initIconShape)
        module.deoptimize(startSidebar)
    }

    private fun type(name: String): Class<*> =
        Class.forName("com.android.wm.shell.multitasking.$name", false, loader)

    companion object {
        fun isAvailable(loader: ClassLoader): Boolean = runCatching {
            Class.forName(
                "com.android.wm.shell.multitasking.common.MultiTaskingSettingsObserver",
                false,
                loader,
            )
        }.isSuccess
    }
}
