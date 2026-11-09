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

/** Adapter for the Xiaomi multitasking implementation on systems with com.miui.core. */
internal class XiaomiFreeformCoexist(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val runtime: () -> HandyRuntime,
) {
    private val multitaskingSettingsRead = ThreadLocal<Boolean>()
    private val logicalInputDispatch = ThreadLocal<Boolean>()
    private val sidebarCoverInit = ThreadLocal<Boolean>()

    fun install(hasLinkageAnimation: Boolean) {
        val settings = type("common.MultiTaskingSettingsObserver")
        val changed = settings.getDeclaredMethod("onOneHandleModeChanged")
        module.deoptimize(changed)
        // This is the exact observer that reads the one-handed activation setting on this platform.
        type("common.MultiTaskingSettingsObserver\$3").declaredMethods.forEach { module.deoptimize(it) }
        module.hook(changed).intercept { chain ->
            multitaskingSettingsRead.set(true)
            try {
                chain.proceed()
            } finally {
                multitaskingSettingsRead.remove()
            }
        }

        val intType = Int::class.javaPrimitiveType!!
        module.hook(Settings.Secure::class.java.getDeclaredMethod(
            "getIntForUser", ContentResolver::class.java, String::class.java, intType, intType,
        )).intercept { chain ->
            if (chain.getArg(1) == "one_handed_mode_activated" && multitaskingSettingsRead.get() == true) {
                // Only Xiaomi's multitasking subsystem sees coexistence; the actual setting and
                // Google's one-handed state, launcher flags and gesture lifecycle remain active.
                0
            } else chain.proceed()
        }

        val receiver = type("miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController\$EventReceiver")
        val receive = receiver.getDeclaredMethod("onInputEvent", InputEvent::class.java)
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
        val floatType = Float::class.javaPrimitiveType!!
        for (name in listOf(
            "miuifreeform.MiuiFreeformModeGestureHandler",
            "miuimultiwinswitch.miuiwindowdecor.MulWinSwitchAnimStatus",
        )) {
            val owner = type(name)
            val query = owner.getDeclaredMethod("isInExcludeRegion", floatType, floatType)
            module.deoptimize(query)
            module.hook(query).intercept { chain ->
                if (logicalInputDispatch.get() == true) {
                    val point = runtime().freeformPointToDisplay(chain.getArg(0) as Float, chain.getArg(1) as Float)
                    chain.proceed(arrayOf(point.x, point.y))
                } else chain.proceed()
            }
        }
        type("miuifreeform.MiuiFreeformModeGestureHandler").getDeclaredMethod(
            "handleMotionEvents", MotionEvent::class.java, Class.forName("android.view.InputMonitor"),
        ).let { module.deoptimize(it) }
        type("miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventHandler").declaredMethods
            .forEach { module.deoptimize(it) }

        // Older multitasking shells have no linkage animation subsystem. Their common
        // settings/input hooks above still apply; keep the newer animation hooks intact.
        if (hasLinkageAnimation) installSidebarAnimation()
        module.log(Log.INFO, "OneHandedMode", "Xiaomi freeform coexistence adapter installed, linkageAnimation=$hasLinkageAnimation")
    }

    @Suppress("DEPRECATION")
    fun hasLinkageAnimation(): Boolean {
        // Inspect the defining shell loader's existing dex files without loading an absent
        // class. This runs during the background package query, before installing hooks.
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
        val initCover = transition.getDeclaredMethod(
            "initCoverLayerCommon", type("common.linkage.MultiTaskingLinkageSession\$LinkageSession"),
            Bitmap::class.java, intType, booleanType, booleanType,
        )
        module.deoptimize(initCover)
        module.hook(initCover).intercept { chain ->
            val session = chain.getArg(0)!!
            if (session.read("linkageWay") == "SidebarFreeForm" && runtime().isOneHandedActive()) {
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
        val parent = type("common.cover.MultiTaskingCoverLayerParent")
        val taskParent = parent.getDeclaredMethod("getCoverLayerParent")
        module.hook(parent.getDeclaredMethod("getCoverLayerTopParent")).intercept { chain ->
            if (sidebarCoverInit.get() == true) taskParent.invoke(chain.thisObject) else chain.proceed()
        }
        module.deoptimize(type("common.cover.MultiTaskingCoverLayerController").getDeclaredMethod(
            "initIconShape", type("common.cover.MultiTaskingShapeAnimInfo"), Float::class.javaPrimitiveType!!,
        ))
        // Deoptimize the caller so ART's inlined private initialization reaches the scoped hook.
        module.deoptimize(transition.getDeclaredMethod(
            "startSidebarLaunchFreeform", android.app.PendingIntent::class.java, intType,
            String::class.java, String::class.java, Bitmap::class.java, android.graphics.Rect::class.java,
            Float::class.javaPrimitiveType!!, intType, intType,
            type("common.IMultiTaskingAnimationStateCallback"),
        ))
    }

    private fun type(name: String): Class<*> =
        Class.forName("com.android.wm.shell.multitasking.$name", false, loader)
}
