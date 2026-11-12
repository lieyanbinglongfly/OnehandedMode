package io.githuh.onehandedmode

import android.content.SharedPreferences
import kotlin.math.roundToInt

object HandySettings {
    const val GROUP = "handy_mode"
    const val MODE = "scale_mode"
    const val PRESET = "preset_percent"
    const val CUSTOM = "custom_percent"
    const val KEEP_NAVIGATION_GESTURES = "keep_navigation_gestures"
    const val SIDE_TAP_SWITCH = "side_tap_switch"
    const val SWIPE_SCALE = "swipe_scale"
    /**
     * Whether the entry hint — the dashed outlines of the two strips and their labels — is drawn.
     * Off keeps the drag outline that appears once the side strip is actually being dragged; that
     * one is feedback for a gesture in progress, not an introduction.
     */
    const val SURROUND_HINTS = "surround_hints"
    /**
     * Keep the framework's own one-handed switch on.
     *
     * Off leaves it exactly as the user set it: the module still answers the edge gesture, but
     * nothing here writes `one_handed_mode_enabled`, so opening the app no longer turns the
     * system switch back on behind the user's back.
     */
    const val KEEP_SYSTEM_ENABLED = "keep_system_one_handed"
    /** Framework setting behind the system's own one-handed switch. */
    const val SYSTEM_ENABLED = "one_handed_mode_enabled"
    /**
     * Copy of [SYSTEM_ENABLED] that the app is allowed to read.
     *
     * Since Android 12 a @hide settings key can only be read by system apps, and reading
     * `one_handed_mode_enabled` from the app threw a SecurityException on every resume. SystemUI
     * (a system app) writes the value here, and the settings page reads this key instead.
     */
    const val SYSTEM_ENABLED_MIRROR = "handy_system_enabled"
    const val SURROUND_COLOR = "surround_color"
    /** What fills the area outside the scaled screen: a flat color, the wallpaper, or a picture. */
    const val BACKGROUND_SOURCE = "surround_background_source"
    const val BACKGROUND_BLUR = "surround_background_blur"
    /** Base64 JPEG of the picked picture, small enough to cross the remote-preferences binder. */
    const val BACKGROUND_IMAGE = "surround_background_image"
    /** Show the entry inside the system Settings app. */
    const val SETTINGS_ENTRY = "settings_entry_enabled"
    /**
     * Send `miui.action.handymode_change` after switching mode. The launcher moves its swipe-up
     * region onto the scaled content when it arrives, so with the broadcast off a swipe near the
     * app's own bottom edge no longer opens recents. Off also means nothing can observe the
     * module through that broadcast.
     */
    const val BROADCAST_MODE = "handy_broadcast_enabled"
    /** The last few colors the user actually applied, newest first. */
    const val RECENT_COLORS = "recent_colors"
    const val SOURCE_SOLID = 0
    const val SOURCE_WALLPAPER = 1
    const val SOURCE_IMAGE = 2
    /**
     * Framework setting that carries a side-strip drag result out of SystemUI. Remote preferences
     * are read-only inside the hooked process on some builds, so the settings database is the only
     * channel the module can write there.
     */
    const val SWIPE_PERCENT = "one_handed_mode_swipe_percent"
    const val PRESET_MODE = 0
    const val CUSTOM_MODE = 1
    const val DEFAULT_PERCENT = 70
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 90
    // Stored when the surround keeps the theme background color reported by the framework.
    const val FOLLOW_THEME = 0
    // User-selected presets, based on the original MI 9 4.5 / 4.0 / 3.5-inch sizes.
    val presets = listOf(80, 70, 60)
    // Opaque presets for the surround area; nine presets plus "auto" fill two rows of five.
    val surroundColors = listOf(
        0xFF000000.toInt(),
        0xFF1C1C1E.toInt(),
        0xFF3A3A3C.toInt(),
        0xFF8E8E93.toInt(),
        0xFFFFFFFF.toInt(),
        0xFF1B4B8F.toInt(),
        0xFF1F5C3A.toInt(),
        0xFF3B2B52.toInt(),
        0xFF8A4B20.toInt(),
        0xFF7A1F1F.toInt(),
    )

    /** First row: black, greys and white — the colors that suit any wallpaper. */
    val commonColors = listOf(
        0xFF000000.toInt(),
        0xFF2C2C2E.toInt(),
        0xFF636366.toInt(),
        0xFFAEAEB2.toInt(),
        0xFFFFFFFF.toInt(),
    )

    /** Second row before anything has been picked: five usable colors, one per hue family. */
    val recommendedColors = listOf(
        0xFF1B6EF3.toInt(),
        0xFF34C759.toInt(),
        0xFFAF52DE.toInt(),
        0xFFFF9500.toInt(),
        0xFFFF3B30.toInt(),
    )

    /** Up to five colors, newest first; falls back to [recommendedColors] on a fresh install. */
    fun recentColors(preferences: SharedPreferences): List<Int> {
        val raw = preferences.getString(RECENT_COLORS, "") ?: ""
        val stored = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            // Neutrals already have a fixed spot in the first row; repeating them below would
            // show the same color twice.
            .filter { it != FOLLOW_THEME && it != 0 && it !in commonColors }
            .distinct()
        return (stored + recommendedColors).distinct().take(5)
    }

    fun recordColor(preferences: SharedPreferences, color: Int) {
        if (color == FOLLOW_THEME) return
        if (color in commonColors) return
        val current = runCatching { recentColors(preferences) }.getOrDefault(recommendedColors)
        // Typing a hex value re-applies the same color on every keystroke; only an actual change
        // is worth a preference write.
        if (current.firstOrNull() == color) return
        val next = (listOf(color) + current).distinct().take(5)
        runCatching {
            preferences.edit().putString(RECENT_COLORS, next.joinToString(",")).apply()
        }
    }

    fun percent(preferences: SharedPreferences): Int {
        val key = if (preferences.getInt(MODE, PRESET_MODE) == CUSTOM_MODE) CUSTOM else PRESET
        return preferences.getInt(key, DEFAULT_PERCENT)
    }

    fun scale(preferences: SharedPreferences): Float = percent(preferences) / 100f

    fun switchSideOnTap(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(SIDE_TAP_SWITCH, true)

    fun resizeOnSwipe(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(SWIPE_SCALE, true)

    fun surroundHints(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(SURROUND_HINTS, true)

    fun keepSystemEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEEP_SYSTEM_ENABLED, false)

    fun broadcastMode(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(BROADCAST_MODE, true)

    /** Opaque ARGB color, or [FOLLOW_THEME] to reuse the framework background color. */
    fun surroundColor(preferences: SharedPreferences): Int =
        preferences.getInt(SURROUND_COLOR, FOLLOW_THEME)

    fun backgroundSource(preferences: SharedPreferences): Int =
        preferences.getInt(BACKGROUND_SOURCE, SOURCE_SOLID)

    /** 0 keeps the picture sharp; 100 is the heaviest blur offered by the picker. */
    fun backgroundBlur(preferences: SharedPreferences): Int =
        preferences.getInt(BACKGROUND_BLUR, 40)

    fun backgroundImage(preferences: SharedPreferences): String =
        preferences.getString(BACKGROUND_IMAGE, "") ?: ""

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
