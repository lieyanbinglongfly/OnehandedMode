package io.githuh.onehandedmode.hook

import android.view.Display
import kotlin.math.hypot

internal data class ScreenSpec(val width: Int, val height: Int, val diagonal: Float) {
    companion object {
        fun read(display: Display): ScreenSpec {
            val type = Class.forName("android.view.DisplayInfo")
            val info = type.getDeclaredConstructor().newInstance()
            Display::class.java.getDeclaredMethod("getDisplayInfo", type).invoke(display, info)
            val mode = type.getDeclaredMethod("getDefaultMode").invoke(info) as Display.Mode
            val xdpi = type.getField("physicalXDpi").getFloat(info)
            val ydpi = type.getField("physicalYDpi").getFloat(info)
            return ScreenSpec(
                type.getField("logicalWidth").getInt(info),
                type.getField("logicalHeight").getInt(info),
                hypot(mode.physicalWidth / xdpi, mode.physicalHeight / ydpi),
            )
        }
    }
}
