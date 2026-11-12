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
import kotlin.math.roundToInt

/** Adapter for the Xiaomi multitasking implementation on systems with com.miui.core. */
internal class XiaomiFreeformCoexist(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val runtime: () -> HandyRuntime,
) {
    private val multitaskingSettingsRead = ThreadLocal<Boolean>()
    private val sidebarCoverInit = ThreadLocal<Boolean>()
    /**
     * Set while a dispatch runs with events already expressed in picture space.
     *
     * The multitasking event receiver hands the gesture handler an event it has already mapped;
     * the handler's own entry point must then leave it alone, or one touch would be scaled twice.
     */
    private val logicalInputDispatch = ThreadLocal<Boolean>()

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
            if (chain.getArg(1) == "one_handed_mode_activated" &&
                multitaskingSettingsRead.get() == true
            ) {
                // Only the multitasking subsystem is told that no one-handed mode is running.
                //
                // This is what keeps a floating window where the user put it: told the truth, the
                // subsystem snaps the window against the edge of the shrunken display as the mode
                // opens. The reachability of that window is not bought here — it comes from the
                // event mapping below, which runs regardless of what this setting says.
                0
            } else chain.proceed()
        }

        module.deoptimize(receive)

        // Every touch the multitasking controller sees is turned into picture space before its
        // handlers look at it. This is the piece that makes a floating window — its title bar,
        // close and full-screen buttons, resize corners — answer exactly where the user aims,
        // and it is why the handler below must not map the same event a second time.
        module.hook(receive).intercept { chain ->
            val event = chain.getArg(0)
            if (event is MotionEvent && runtime().isOneHandedActive()) {
                // The original object is transformed in place: its owner recycles it after its
                // handlers are done, so a replacement here would either leak or be freed twice.
                // A failure must not escape into the input thread, and an unmapped event must not
                // be announced as mapped, or the region queries would convert it twice.
                val mapped = runCatching { runtime().mapFreeformInput(event) }.onFailure {
                    module.log(Log.WARN, "OneHandedMode", "freeform input mapping failed", it)
                }.isSuccess
                logicalInputDispatch.set(mapped)
                try {
                    chain.proceed()
                } finally {
                    logicalInputDispatch.remove()
                }
            } else chain.proceed()
        }

        // WindowManager publishes exclusion regions in display space, while the handlers above now
        // work in picture space. Querying them with display points keeps both sides comparable.
        installExcludeRegionMapping()

        switchEventHandlerMethods.forEach { module.deoptimize(it) }

        // The launch gesture reads the InputMonitor on its own, outside the dispatch above, so its
        // coordinates still are display space. Mapping them here moves the launch target onto the
        // picture; without it the swipe only reacts at the physical top of the screen.
        runCatching { installFreeformGestureMapping() }.onFailure {
            module.log(Log.WARN, "OneHandedMode", "freeform gesture mapping failed: $it")
        }

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

    /**
     * Feeds the floating-window launch gesture coordinates of the picture, not of the display.
     *
     * That gesture reads its touches straight from an InputMonitor, so the dispatch mapping above
     * never sees them and they stay in display space. It then compares the finger against a
     * launch rectangle that WindowManager publishes in display space, so at 80% zoom the target
     * only reacts once the finger leaves the picture and reaches the physical top of the screen.
     * Expressing the event in picture space is equivalent to moving that rectangle onto the
     * picture, which is where the user is actually aiming.
     *
     * The two-argument overload is looked up exactly, because it is the one bound to an
     * InputMonitor. Matching by name alone — the previous attempt — bound a different overload on
     * this ROM and mapped nothing at all, which is why the swipe still had to reach the top edge.
     */
    private fun installFreeformGestureMapping() {
        val handler = runCatching { type("miuifreeform.MiuiFreeformModeGestureHandler") }.getOrNull()
        if (handler == null) {
            module.log(Log.WARN, "OneHandedMode", "freeform gesture mapping skipped: no handler")
            return
        }
        val monitor = runCatching { Class.forName("android.view.InputMonitor") }.getOrNull()
        val handle = handler.declaredMethods.firstOrNull { method ->
            method.name == "handleMotionEvents" && method.parameterTypes.size == 2 &&
                MotionEvent::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                (monitor == null || method.parameterTypes[1].isAssignableFrom(monitor))
        }
        if (handle == null) {
            module.log(
                Log.WARN, "OneHandedMode",
                "freeform gesture mapping skipped: no handleMotionEvents(MotionEvent, InputMonitor)",
            )
            return
        }
        module.deoptimize(handle)
        module.hook(handle).intercept { chain ->
            val event = chain.getArg(0) as? MotionEvent
            if (event == null) return@intercept chain.proceed()
            // An event that already went through the receiver above is in picture space; mapping
            // it again would scale one touch twice, so it is only copied.
            val copy = runCatching { runtime().pictureCopy(event, logicalInputDispatch.get() == true) }
                .onFailure { error ->
                    module.log(Log.WARN, "OneHandedMode", "freeform gesture map failed", error)
                }
                .getOrNull()
            if (copy == null) return@intercept chain.proceed()
            logGesture(copy, event.x, event.y)
            val args = Array<Any?>(chain.args.size) { index ->
                if (index == 0) copy else chain.getArg(index)
            }
            chain.proceed(args)
        }
        module.log(
            Log.INFO, "OneHandedMode",
            "freeform gesture mapping installed: " +
                handle.parameterTypes.joinToString { it.simpleName },
        )
    }

    private var gestureLogAt = 0L

    /** One line per touch at most, so the log shows whether this handler sees the swipe at all. */
    private fun logGesture(event: MotionEvent, fromX: Float, fromY: Float) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - gestureLogAt < 400L) return
        gestureLogAt = now
        module.log(
            Log.INFO, "OneHandedMode",
            "freeform launch down: display=${fromX.roundToInt()},${fromY.roundToInt()} " +
                "picture=${event.x.roundToInt()},${event.y.roundToInt()}",
        )
    }

    /**
     * Keeps exclusion-region queries in display space.
     *
     * The mapped dispatch above makes the vendor handlers reason in picture space, while
     * WindowManager publishes those regions in display space. Converting the query point back
     * keeps both sides on the same ruler; without it the regions look shifted by the scale.
     */
    private fun installExcludeRegionMapping() {
        val floatType = Float::class.javaPrimitiveType!!
        for (name in listOf(
            "miuifreeform.MiuiFreeformModeGestureHandler",
            "miuimultiwinswitch.miuiwindowdecor.MulWinSwitchAnimStatus",
        )) {
            runCatching {
                val query = type(name).getDeclaredMethod("isInExcludeRegion", floatType, floatType)
                module.deoptimize(query)
                module.hook(query).intercept { chain ->
                    if (logicalInputDispatch.get() == true) {
                        val point = runtime().freeformPointToDisplay(
                            chain.getArg(0) as Float, chain.getArg(1) as Float,
                        )
                        chain.proceed(arrayOf(point.x, point.y))
                    } else chain.proceed()
                }
            }.onFailure {
                module.log(Log.WARN, "OneHandedMode", "exclude region mapping skipped: $name")
            }
        }
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
        /** Capability probe: unlike a package check this cannot be fooled by a standalone install. */
        fun isAvailable(loader: ClassLoader): Boolean = runCatching {
            Class.forName(
                "com.android.wm.shell.multitasking.common.MultiTaskingSettingsObserver",
                false,
                loader,
            )
        }.isSuccess
    }
}
