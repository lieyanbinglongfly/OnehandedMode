package io.githuh.onehandedmode

import android.content.SharedPreferences
import kotlin.math.roundToInt

object HandySettings {
    const val GROUP = "handy_mode"
    const val MODE = "scale_mode"
    const val PRESET = "preset_percent"
    const val CUSTOM = "custom_percent"
    const val PRESET_MODE = 0
    const val CUSTOM_MODE = 1
    const val DEFAULT_PERCENT = 70
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 90
    // User-selected presets, based on the original MI 9 4.5 / 4.0 / 3.5-inch sizes.
    val presets = listOf(80, 70, 60)

    fun percent(preferences: SharedPreferences): Int {
        val key = if (preferences.getInt(MODE, PRESET_MODE) == CUSTOM_MODE) CUSTOM else PRESET
        return preferences.getInt(key, DEFAULT_PERCENT)
    }

    fun scale(preferences: SharedPreferences): Float = percent(preferences) / 100f

    fun migrate(preferences: SharedPreferences) {
        val editor = preferences.edit()
        if (!preferences.contains(PRESET) && preferences.contains("handy_mode_size")) {
            val oldSize = preferences.getFloat("handy_mode_size", 4f)
            val ratio = (oldSize / 6.39f * 100).roundToInt()
            editor.putInt(PRESET, presets.minBy { kotlin.math.abs(it - ratio) })
        } else if (preferences.contains(PRESET)) {
            val previous = preferences.getInt(PRESET, DEFAULT_PERCENT)
            if (previous !in presets) {
                val migrated = if (previous in listOf(85, 75, 65)) previous - 5
                    else presets.minBy { kotlin.math.abs(it - previous) }
                editor.putInt(PRESET, migrated)
            }
        }
        editor.remove("handy_mode_state").remove("handy_mode_size").apply()
    }
}
