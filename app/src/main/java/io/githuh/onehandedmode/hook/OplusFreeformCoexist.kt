package io.githuh.onehandedmode.hook

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowManager
import io.githuh.onehandedmode.HandySettings
import io.github.libxposed.api.XposedModule

/** Adapter for the OnePlus Android 17 FlexibleTask and Smart Sidebar implementations. */
internal class OplusFreeformCoexist(
    private val module: XposedModule,
    private val loader: ClassLoader,
) {
    fun installSystemServer() {
        val observer = Class.forName("com.android.server.wm.OplusZoomSettingsObserver", false, loader)
        val update = observer.getDeclaredMethod("updateOneHandedMode")
        val oneHandedPolicy = observer.getDeclaredField("mOneHeadMode").apply { isAccessible = true }
        module.deoptimize(update)
        module.hook(update).intercept { chain ->
            // This native method only updates the small-window policy and minimizes windows.
            // Keep its cache inactive so FloatHandleView's tap/fling paths can reopen them.
            oneHandedPolicy.setBoolean(chain.thisObject, false)
            null
        }
        module.deoptimize(Class.forName(
            "com.android.server.wm.OplusZoomSettingsObserver\$SettingsObserver", false, loader,
        ).getDeclaredMethod("onChange", Boolean::class.javaPrimitiveType!!, Uri::class.java,
            Int::class.javaPrimitiveType!!))

        lateinit var space: OplusHandySpace
        val init = observer.getDeclaredMethod("init", type("FlexibleTaskController"))
        module.deoptimize(init)
        module.hook(init).intercept { chain ->
            val result = chain.proceed()
            space = OplusHandySpace(chain.thisObject!!.read("mContext") as Context,
                module.getRemotePreferences(HandySettings.GROUP))
            result
        }

        // This private receiver compares raw display points with the view's logical region.
        val receive = type("floathandle.FloatHandleView\$2").getDeclaredMethod("onInputEvent", InputEvent::class.java)
        module.deoptimize(receive)
        module.hook(receive).intercept { chain ->
            val event = chain.getArg(0)
            if (event is MotionEvent && space.active) space.mapGlobalInput(event)
            chain.proceed()
        }

        // PointerEventDispatcher shares its event with other listeners; use a private copy.
        val touch = type("FlexibleTaskController\$TouchListener")
            .getDeclaredMethod("onPointerEvent", MotionEvent::class.java)
        module.deoptimize(touch)
        module.hook(touch).intercept { chain ->
            if (!space.active) chain.proceed() else {
                val event = MotionEvent.obtain(chain.getArg(0) as MotionEvent)
                space.mapGlobalInput(event)
                try { chain.proceed(arrayOf(event)) } finally { event.recycle() }
            }
        }

        // These streams come from windows, whose local coordinates already include the inverse
        // surface matrix. Remap raw coordinates while retaining their local coordinates/history.
        val pointer = type("FlexiblePointerHandler")
        val parameters = arrayOf(MotionEvent::class.java, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, type("FlexibleCaptionView"))
        val windowHandlers = listOf(
            type("floathandle.FloatHandleView").getDeclaredMethod("processTouchEvent", MotionEvent::class.java),
            pointer.getDeclaredMethod("onInputEvent", *parameters),
            pointer.getDeclaredMethod("handleScaleHandleEvent", *parameters),
        )
        for (method in windowHandlers) {
            module.deoptimize(method)
            module.hook(method).intercept { chain ->
                if (!space.active) chain.proceed() else {
                    val event = MotionEvent.obtain(chain.getArg(0) as MotionEvent)
                    space.mapWindowInput(event)
                    val args = Array<Any?>(method.parameterCount) { chain.getArg(it) }
                    args[0] = event
                    try { chain.proceed(args) } finally { event.recycle() }
                }
            }
        }
        module.log(Log.INFO, "OneHandedMode", "OnePlus FlexibleTask coexistence adapter installed")
    }

    fun installSystemUi() {
        val showingMenu = ThreadLocal<Boolean>()
        val menu = Class.forName("com.oplus.flexibletask.menu.FlexibleMenuManager", false, loader)
        val show = menu.getDeclaredMethod("showPopupWindowMenu", ActivityManager.RunningTaskInfo::class.java,
            Boolean::class.javaPrimitiveType!!)
        menu.declaredMethods.forEach { module.deoptimize(it) }
        module.hook(show).intercept { chain ->
            showingMenu.set(true)
            try { chain.proceed() } finally { showingMenu.remove() }
        }
        val helper = Class.forName("com.oplus.onehanded.OneHandedHelper", false, loader)
        module.hook(helper.getDeclaredMethod("isInOneHanded")).intercept { chain ->
            if (showingMenu.get() == true) false else chain.proceed()
        }
        module.hook(menu.getDeclaredMethod("getWindowParams", Int::class.javaPrimitiveType!!)).intercept { chain ->
            val params = chain.proceed() as WindowManager.LayoutParams
            // NAVIGATION_BAR_PANEL is excluded from the one-handed display area. The native
            // menu already uses APPLICATION_OVERLAY when measuring; keep the visible window
            // in that display area too so its size, anchor and input follow the task viewport.
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            params
        }
        module.log(Log.INFO, "OneHandedMode", "OnePlus small-window menu coexistence adapter installed")
    }

    fun installSidebar() {
        val settings = Class.forName("com.coloros.common.settingsvalue.CommonSettingsValueProxy", false, loader)
        module.hook(settings.getDeclaredMethod("getSingleHandModeState", Context::class.java))
            .intercept { 0 }
        // The sidebar caches this value in its observer and uses it to hide the entry handle.
        Class.forName("com.coloros.edgepanel.observers.SingleHandAndroidObserver", false, loader)
            .declaredMethods.forEach { module.deoptimize(it) }
        OplusSidebarInput(module, loader).install()
        module.log(Log.INFO, "OneHandedMode", "OnePlus Smart Sidebar coexistence adapter installed")
    }

    private fun type(name: String): Class<*> = Class.forName("com.android.server.wm.$name", false, loader)

    companion object {
        private val isOplusRom = (Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java)
            .invoke(null, "ro.build.version.oplusrom") as String).isNotEmpty()
        fun isTargetSystem(): Boolean = Build.VERSION.SDK_INT == 37 &&
            Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) && isOplusRom
    }
}
