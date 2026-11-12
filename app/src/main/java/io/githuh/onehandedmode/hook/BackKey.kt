package io.githuh.onehandedmode.hook

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.lang.reflect.Method

/**
 * Injects the same key event the system navigation bar produces for "back".
 *
 * `InputManager` is a hidden class, so it is resolved reflectively; if the reflection is refused
 * (or the process lacks INJECT_EVENTS) the shell command is tried as a last resort. The failure
 * is logged once per send and never surfaces to the UI: the rest of the button column must keep
 * working even if back cannot be delivered.
 */
internal object BackKey {
    private const val TAG = "OneHandedMode"
    // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    private const val ASYNC = 0

    private var manager: Any? = null
    private var inject: Method? = null
    private var resolved = false
    private var execFallbackTried = false

    fun send(): Boolean {
        if (!resolve()) {
            execFallback()
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val up = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        val sent = inject(down) and inject(up)
        if (!sent) execFallback()
        return sent
    }

    private fun inject(event: KeyEvent): Boolean = runCatching {
        inject!!.invoke(manager, event, ASYNC) as Boolean
    }.getOrElse { false }

    private fun resolve(): Boolean {
        if (resolved) return manager != null && inject != null
        resolved = true
        manager = runCatching {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        }.getOrNull() ?: runCatching {
            val cls = Class.forName("android.hardware.input.InputManager")
            cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        }.getOrNull()
        inject = manager?.javaClass?.methods
            ?.firstOrNull { it.name == "injectInputEvent" && it.parameterTypes.size == 2 }
            ?.apply { isAccessible = true }
        if (manager == null || inject == null) {
            Log.w(TAG, "back injection unavailable, falling back to the input command")
            return false
        }
        return true
    }

    /**
     * Runs `input keyevent` off the main thread. It only succeeds when the process already holds
     * INJECT_EVENTS, but it costs nothing to try and keeps the button useful on odd builds.
     */
    private fun execFallback() {
        if (execFallbackTried) return
        execFallbackTried = true
        runCatching {
            Thread {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent KEYCODE_BACK"))
                }
            }.start()
        }
    }
}
