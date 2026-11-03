package io.githuh.onehandedmode.hook

import android.content.ContentResolver
import android.content.Context
import android.graphics.RectF
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import io.githuh.onehandedmode.HandySettings
import io.github.libxposed.api.XposedModule
import kotlin.math.roundToInt

/** Match the sidebar's global input registration to its transformed windows. */
internal class OplusSidebarInput(private val module: XposedModule, private val loader: ClassLoader) {
    private lateinit var space: OplusHandySpace
    private val physicalDispatch = ThreadLocal<Boolean>()
    private class Region(val manager: Any, val observer: Any, val config: Int, val bounds: List<RectF>)
    private class Setting(val args: Array<Any?>, val bounds: RectF)
    @Volatile private var region: Region? = null
    @Volatile private var setting: Setting? = null
    private val intType = Int::class.javaPrimitiveType!!
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val secureWrite = Settings.Secure::class.java.getDeclaredMethod(
        "putStringForUser", ContentResolver::class.java, String::class.java, String::class.java,
        String::class.java, booleanType, intType, booleanType,
    )
    private val manager = Class.forName("android.os.OplusGestureMonitorManager", false, loader)
    private val observer = Class.forName("android.os.OplusGestureMonitorManager\$OnGestureRegionObserver", false, loader)
    private val updateRegion = manager.getDeclaredMethod(
        "updateRegionInputEvent", observer, String::class.java, intType, List::class.java,
    )

    fun install() {
        val application = Class.forName("com.coloros.common.App", false, loader)
        module.deoptimize(application.getDeclaredMethod("onCreate"))
        module.hook(application.getDeclaredMethod("onCreate")).intercept { chain ->
            // ActivityThread has published the initial Application by this point, so Settings
            // can recognize the privileged sidebar app before its input subjects are created.
            space = OplusHandySpace(chain.thisObject as Context, module.getRemotePreferences(HandySettings.GROUP))
            space.onChanged(::refresh)
            module.log(Log.INFO, "OneHandedMode", "OnePlus sidebar coordinate space attached")
            chain.proceed()
        }

        val input = Class.forName("com.coloros.edgepanel.scene.subjects.InputEventSubject", false, loader)
        val receive = input.getDeclaredMethod("handleInputEvent", MotionEvent::class.java)
        module.deoptimize(receive)
        module.hook(receive).intercept { chain ->
            if (space.active) space.mapGlobalInput(chain.getArg(0) as MotionEvent)
            chain.proceed()
        }

        // Keep InputUtils' cached rectangle in logical space; only its framework registrations
        // and the region published for SystemUI's edge gestures use physical display space.
        for (name in listOf("registerRegionInputEvent", "updateRegionInputEvent")) {
            val method = manager.getDeclaredMethod(name, observer, String::class.java, intType, List::class.java)
            module.hook(method).intercept { chain ->
                if (chain.getArg(1) != "sidebar" || physicalDispatch.get() == true) {
                    chain.proceed()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val bounds = (chain.getArg(3) as List<RectF>).map(::RectF)
                    val registration = Region(chain.thisObject!!, chain.getArg(0)!!, chain.getArg(2) as Int, bounds)
                    val result = chain.proceed(arrayOf(registration.observer, "sidebar", registration.config,
                        bounds.map(space::toDisplay)))
                    if (result == true) region = registration
                    result
                }
            }
        }
        module.hook(manager.getDeclaredMethod("unregisterRegionInputEvent", observer)).intercept { chain ->
            val result = chain.proceed()
            if (region?.observer === chain.getArg(0)) region = null
            result
        }
        module.hook(secureWrite).intercept { chain ->
            if (chain.getArg(1) != "sidebar_scene_region" || physicalDispatch.get() == true) {
                chain.proceed()
            } else {
                val values = (chain.getArg(2) as String).split(' ').map(String::toFloat)
                val entry = Setting(Array<Any?>(7) { chain.getArg(it) },
                    RectF(values[0], values[1], values[2], values[3]))
                setting = entry
                val args = entry.args.copyOf()
                args[2] = encode(space.toDisplay(entry.bounds))
                chain.proceed(args)
            }
        }
    }

    private fun refresh() {
        physicalDispatch.set(true)
        try {
            setting?.let {
                val args = it.args.copyOf()
                args[2] = encode(space.toDisplay(it.bounds))
                secureWrite.invoke(null, *args)
            }
            region?.let {
                updateRegion.invoke(it.manager, it.observer, "sidebar", it.config, it.bounds.map(space::toDisplay))
            }
        } finally {
            physicalDispatch.remove()
        }
    }

    private fun encode(rect: RectF): String =
        "${rect.left.roundToInt()} ${rect.top.roundToInt()} ${rect.right.roundToInt()} ${rect.bottom.roundToInt()}"
}
