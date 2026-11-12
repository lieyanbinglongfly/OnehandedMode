package io.githuh.onehandedmode.hook

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.content.ContentResolver
import java.util.Locale
import io.githuh.onehandedmode.BuildConfig
import io.githuh.onehandedmode.HandySettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.concurrent.thread

private class AnimationSurface(val leash: SurfaceControl, val offsetBias: Float)

/** Marks the row this module inserts, so a second resume never adds a duplicate. */
private const val SETTINGS_ROW_TAG = "onehanded_enhanced_settings_row"
/** Key of the row added to the preference screen; also the guard against adding it twice. */
private const val SETTINGS_ENTRY_KEY = "onehanded_enhanced_settings_entry"
/** Fragment, activity and intent names that identify the system's one-handed page. */
private val ONE_HANDED_PAGE = Regex("(?i)handymode|one[_ ]?handed|single[_ ]?hand")

class OneHandedModule : XposedModule() {
    private lateinit var runtime: HandyRuntime
    private var screenshotBackgroundParent: SurfaceControl? = null
    @Volatile private var freeformCoexistEnabled = false
    @Volatile private var hasOplusSidebar = false
    private var systemUiHooksInstalled = false
    private var settingsEntryInstalled = false
    @Volatile private var settingsEntryCache: Boolean? = null
    private var settingsEntryCacheAt = 0L
    /** The list the settings row follows, so a removed row never keeps a scroll callback. */
    private var settingsRowScroller: View? = null
    private var platformSupportInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, "OneHandedMode", "Module loaded: version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}), " +
            "process=${param.processName}, systemServer=${param.isSystemServer}")
        if (param.isSystemServer || param.processName == "com.android.systemui") enablePlatformSupport()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // Finish starting system services before probing optional vendor adapters. Reflection
        // and adapter installation run off the system_server main thread.
        val server = Class.forName("com.android.server.SystemServer", false, param.classLoader)
        val startOtherServices = server.declaredMethods.single { it.name == "startOtherServices" }
        deoptimize(startOtherServices)
        hook(startOtherServices).intercept { chain ->
            val result = chain.proceed()
            val context = chain.thisObject!!.read("mSystemContext") as Context
            thread(name = "OneHanded-adapters") {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runCatching {
                    if (OplusFreeformCoexist.isSystemServerAvailable(param.classLoader)) {
                        OplusFreeformCoexist(this, param.classLoader).installSystemServer(context)
                    }
                }.onFailure { error ->
                    log(Log.ERROR, "OneHandedMode", "Skipping OnePlus system-server adapter", error)
                }
            }
            result
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.isFirstPackage && param.packageName == "com.coloros.smartsidebar" &&
            OplusFreeformCoexist.isSidebarAvailable(param.classLoader)
        ) {
            runCatching {
                OplusFreeformCoexist(this, param.classLoader).installSidebar()
            }.onFailure { error ->
                log(Log.ERROR, "OneHandedMode", "Skipping OnePlus sidebar adapter", error)
            }
        }
        if (param.packageName == "com.android.settings") {
            installSettingsEntry(param.classLoader)
            return
        }
        if (param.packageName != "com.android.systemui" || systemUiHooksInstalled) return
        val process = Application.getProcessName()
        log(Log.INFO, "OneHandedMode", "SystemUI package ready: process=$process, firstPackage=${param.isFirstPackage}")
        if (process != "com.android.systemui") return
        systemUiHooksInstalled = true
        enablePlatformSupport()

        val loader = param.classLoader
        val preferences = getRemotePreferences(HandySettings.GROUP)
        val prefix = "com.android.wm.shell.onehanded."
        fun type(name: String) = Class.forName(prefix + name, false, loader)
        fun intercept(type: Class<*>, name: String, vararg parameters: Class<*>, action: XposedInterface.Hooker) {
            hook(type.getDeclaredMethod(name, *parameters)).intercept(action)
        }
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!

        // R8 removes SettingsUtil getters. The framework settings boundary remains unchanged.
        intercept(Settings.Secure::class.java, "getIntForUser",
            ContentResolver::class.java, String::class.java, intType, intType) { chain ->
            when (chain.getArg(1) as String) {
                "one_handed_mode_enabled" -> 1
                "one_handed_mode_timeout", "taps_app_to_exit", "swipe_bottom_to_notification_enabled" -> 0
                else -> chain.proceed()
            }
        }
        val controller = type("OneHandedController")
        controller.declaredMethods.forEach { deoptimize(it) }
        type("OneHandedSettingsUtil").declaredMethods.forEach { deoptimize(it) }
        for (constructor in controller.declaredConstructors) {
            deoptimize(constructor)
            hook(constructor).intercept { chain ->
                val result = chain.proceed()
                runtime = HandyRuntime(chain.thisObject!!, preferences) { screenshotBackgroundParent }
                runtime.attach()
                val instance = chain.thisObject!!
                val handler = instance.read("mMainHandler") as Handler
                // Queue capability detection after controller initialization. Reflection runs
                // on a background thread and adapter installation returns to the shell thread.
                handler.post {
                    thread(name = "OneHanded-adapters") {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        val hasOplusSystemUi = OplusFreeformCoexist.isSystemUiAvailable(loader)
                        // A class that exists on the target system is a stronger signal than a
                        // package name: a standalone vendor app can satisfy the latter.
                        val xiaomi = if (XiaomiFreeformCoexist.isAvailable(loader)) {
                            XiaomiFreeformCoexist(this, loader) { runtime }
                        } else null
                        // Scanning the multitasking shell's dex files is disk work; do it here
                        // instead of on the main thread that is still finishing startup.
                        val hasLinkageAnimation = xiaomi?.let { adapter ->
                            runCatching { adapter.hasLinkageAnimation() }
                                .onFailure { error ->
                                    log(Log.WARN, "OneHandedMode", "Xiaomi linkage detection failed", error)
                                }
                                .getOrDefault(false)
                        } == true
                        handler.post {
                            installFreeformHooks(
                                loader, instance, hasOplusSystemUi, xiaomi, hasLinkageAnimation,
                            )
                        }
                    }
                }
                log(Log.INFO, "OneHandedMode", "Controller attached")
                result
            }
        }
        intercept(controller, "startOneHanded") { chain ->
            if (!runtime.prepareEntry()) null else chain.proceed()
        }
        intercept(controller, "stopOneHanded", intType) { chain ->
            val reason = chain.getArg(0) as Int
            val shouldStop = when (reason) {
                2, 3, 6 -> false // The surround owns outside touches; keyboard/timeout keep the mode.
                // Coexistence keeps task/focus changes from terminating one-handed mode.
                5 -> !preferences.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false) &&
                    !freeformCoexistEnabled && runtime.hasVisibleFreeformTask()
                else -> true
            }
            if (hasOplusSidebar) {
                log(Log.DEBUG, "OneHandedMode", "OnePlus exit request: reason=$reason, accepted=$shouldStop")
            }
            if (!shouldStop || runtime.deferExit()) null else chain.proceed()
        }
        intercept(type("OneHandedState"), "setState", intType) { chain ->
            val result = chain.proceed()
            val state = chain.getArg(0) as Int
            if (state == 1 || state == 3) runtime.onStateChanged(state)
            result
        }

        // This framework override survives R8, unlike OneHandedTouchHandler.onMotionEvent.
        intercept(type("OneHandedTouchHandler\$EventReceiver"), "onInputEvent", InputEvent::class.java) { chain ->
            val event = chain.getArg(0)
            if (event is MotionEvent) runtime.onMotionEvent(event)
            chain.proceed()
        }
        intercept(type("OneHandedAnimationController\$OneHandedInterpolator"), "getInterpolation", floatType) { chain ->
            circularEaseOut(chain.getArg(0) as Float)
        }

        val animatorType = type("OneHandedAnimationController\$OneHandedTransitionAnimator")
        val leashField = animatorType.getDeclaredField("mLeash").apply { isAccessible = true }
        val currentSurface = ThreadLocal<AnimationSurface>()
        for ((callback, parameter) in listOf(
            "onAnimationUpdate" to ValueAnimator::class.java,
            "onAnimationStart" to Animator::class.java,
            "onAnimationEnd" to Animator::class.java,
        )) {
            val method = animatorType.getDeclaredMethod(callback, parameter)
            deoptimize(method)
            hook(method).intercept { chain ->
                // Frames round with +0.5f; vendor start/end transactions use the exact offset.
                currentSurface.set(AnimationSurface(
                    leashField.get(chain.thisObject) as SurfaceControl,
                    if (callback == "onAnimationUpdate") 0.5f else 0f,
                ))
                try {
                    chain.proceed()
                } finally {
                    currentSurface.remove()
                }
            }
        }
        // Surface helpers and anonymous applySurfaceControlTransaction are also inlined by R8.
        // Amend the original transaction inside the original frame, before it is submitted.
        intercept(SurfaceControl.Transaction::class.java, "setPosition",
            SurfaceControl::class.java, floatType, floatType) { chain ->
            val surface = currentSurface.get()
            if (surface == null || surface.leash !== chain.getArg(0)) {
                chain.proceed()
            } else {
                val position = runtime.transform(
                    chain.thisObject as SurfaceControl.Transaction, surface.leash,
                    (chain.getArg(2) as Float) - surface.offsetBias,
                )
                chain.proceed(arrayOf(surface.leash, position.x, position.y))
            }
        }
        intercept(type("OneHandedDisplayAreaOrganizer"), "resetWindowsOffset") { chain ->
            val result = chain.proceed()
            val leashes = chain.thisObject!!.read("mDisplayAreaTokenMap") as Map<*, *>
            val matrix = SurfaceControl.Transaction::class.java.getDeclaredMethod(
                "setMatrix", SurfaceControl::class.java, floatType, floatType, floatType, floatType,
            )
            SurfaceControl.Transaction().use { tx ->
                leashes.values.forEach { matrix.invoke(tx, it as SurfaceControl, 1f, 0f, 0f, 1f) }
                tx.apply()
            }
            result
        }
        val root = Class.forName("com.android.wm.shell.RootDisplayAreaOrganizer", false, loader)
        val info = Class.forName("android.window.DisplayAreaInfo", false, loader)
        val displayId = info.getField("displayId")
        root.declaredConstructors.forEach { deoptimize(it) }
        root.declaredMethods.forEach { deoptimize(it) }
        intercept(root, "onDisplayAreaAppeared", info, SurfaceControl::class.java) { chain ->
            val result = chain.proceed()
            if (displayId.getInt(chain.getArg(0)) == 0) {
                screenshotBackgroundParent = chain.getArg(1) as SurfaceControl
            }
            result
        }
        log(Log.INFO, "OneHandedMode", "Hooks installed at framework and callback boundaries")
        NavigationGestureMode(this, loader, preferences).installLazy()
        log(Log.INFO, "OneHandedMode", "Navigation gesture hooks installed")
    }

    /**
     * Puts an "enhanced one-handed mode" row on the system's own one-handed page.
     *
     * The page is `SubSettings` hosting `com.android.settings.display.HandyModeFragment`, and its
     * rows are not stock `androidx.preference` items: the ROM draws them with its own
     * `miuix.preference` classes, which is why a plain `Preference` came out blank in 1.0.19 —
     * its title and summary widgets had nothing to bind to, while the item still took height.
     *
     * So the row is built out of the page's *own* preference class — the approach HyperCeiler
     * uses for the entries it adds to system settings: `miuix.preference.TextPreference`, falling
     * back to `miuix.preference.Preference`, attached to the `PreferenceGroup` that holds the
     * switch. The row then is a real list item: it scrolls with the page, and the card surface,
     * divider, type scale and ripple all come from the ROM instead of being approximated.
     *
     * A fragment hook and the activity resume check both feed the same idempotent insert, so the
     * row lands whether the page is built before or after this module first sees it.
     */
    private fun installSettingsEntry(loader: ClassLoader) {
        if (settingsEntryInstalled) return
        settingsEntryInstalled = true
        runCatching {
            val fragment = Class.forName("androidx.preference.PreferenceFragmentCompat", false, loader)
            val onCreate = fragment.getDeclaredMethod("onCreate", Bundle::class.java)
            deoptimize(onCreate)
            hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                val instance = chain.thisObject
                if (instance != null) runCatching { injectIntoFragment(instance, loader) }
                result
            }
            log(Log.INFO, "OneHandedMode", "Settings fragment hook installed")
        }.onFailure { log(Log.WARN, "OneHandedMode", "Settings fragment hook unavailable: $it") }
        runCatching {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            deoptimize(onResume)
            hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null) runCatching { onSettingsResume(activity, loader) }
                result
            }
            log(Log.INFO, "OneHandedMode", "Settings page hook installed")
        }.onFailure { log(Log.WARN, "OneHandedMode", "Settings entry unavailable: $it") }
    }

    /** The setting is read on every resume, so it is cached for a moment instead of hitting binder. */
    private fun settingsEntryEnabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = settingsEntryCache
        if (cached != null && now - settingsEntryCacheAt < 2000L) return cached
        val value = runCatching {
            getRemotePreferences(HandySettings.GROUP)
                .getBoolean(HandySettings.SETTINGS_ENTRY, true)
        }.getOrDefault(true)
        settingsEntryCache = value
        settingsEntryCacheAt = now
        return value
    }

    /**
     * Resume fires for every screen of the settings app, so the list row is tried first and the
     * floating row is only drawn when no list item could be created.
     */
    private fun onSettingsResume(activity: Activity, loader: ClassLoader) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Settings reuses one activity across its screens, so a leftover from a previous page has
        // to go before anything else is decided.
        removeSettingsRow(content)
        if (!settingsEntryEnabled()) return
        if (runCatching { injectIntoScreen(activity, loader) }.getOrDefault(false)) return
        if (isOneHandedPage(activity) || hasOneHandedTitle(content) && hasSwitch(content)) {
            scheduleSettingsRow(activity, loader)
        }
    }

    private fun injectIntoScreen(activity: Activity, loader: ClassLoader): Boolean {
        for (fragment in fragmentsOf(activity)) {
            if (injectIntoFragment(fragment, loader)) return true
        }
        return false
    }

    /** True when this fragment's list now holds the row, or already held it. */
    private fun injectIntoFragment(fragment: Any, loader: ClassLoader): Boolean {
        if (!fragment.javaClass.name.contains("HandyMode", true)) return false
        if (!settingsEntryEnabled()) return false
        val screen = runCatching {
            fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
        }.getOrNull() ?: return false
        val context = runCatching {
            fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context
        }.getOrNull() ?: return false
        return addEntryPreference(screen, context, loader)
    }

    /**
     * Adds the row as a preference of the page.
     *
     * The class matters: a stock `androidx.preference.Preference` has no matching item layout on
     * this ROM and renders empty, while `miuix.preference.TextPreference` is what the rest of the
     * page is made of, so it inherits the card, the divider, the text sizes and the ripple.
     */
    private fun addEntryPreference(screen: Any, context: Context, loader: ClassLoader): Boolean {
        if (findPreference(screen, SETTINGS_ENTRY_KEY) != null) return true
        val preference = createTextPreference(context, loader) ?: return false
        setPreferenceText(preference, "setTitle", entryTitle(context))
        setPreferenceText(preference, "setSummary", entrySummary(context))
        setPreferenceText(preference, "setKey", SETTINGS_ENTRY_KEY)
        setPreferenceClick(preference, context, loader)
        val group = switchGroup(screen) ?: screen
        val add = group.javaClass.methods.firstOrNull {
            it.name == "addPreference" && it.parameterTypes.size == 1
        } ?: return false
        runCatching { add.invoke(group, preference) }.onFailure {
            log(Log.WARN, "OneHandedMode", "Settings row rejected: $it")
            return false
        }
        log(
            Log.INFO, "OneHandedMode",
            "Settings row added: ${preference.javaClass.name} -> ${group.javaClass.name}",
        )
        return true
    }

    private fun createTextPreference(context: Context, loader: ClassLoader): Any? {
        val candidates = listOf(
            "miuix.preference.TextPreference",
            "miuix.preference.Preference",
            "androidx.preference.Preference",
        )
        for (name in candidates) {
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            val instance = runCatching {
                type.getConstructor(Context::class.java).newInstance(context)
            }.getOrNull() ?: runCatching {
                type.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
                    .newInstance(context, null)
            }.getOrNull() ?: continue
            if (name != candidates.first()) {
                log(Log.WARN, "OneHandedMode", "Row falls back to $name")
            }
            return instance
        }
        return null
    }

    private fun setPreferenceText(preference: Any, method: String, value: String) {
        val target = preference.javaClass.methods.firstOrNull {
            it.name == method && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == CharSequence::class.java ||
                    it.parameterTypes[0] == String::class.java)
        } ?: return
        runCatching { target.invoke(preference, value) }
    }

    private fun setPreferenceClick(preference: Any, context: Context, loader: ClassLoader) {
        val setter = preference.javaClass.methods.firstOrNull {
            it.name == "setOnPreferenceClickListener" && it.parameterTypes.size == 1
        } ?: return
        // The listener interface is taken from the setter itself: this ROM may ship its own copy
        // of the preference library, and then the androidx type is not the one it accepts.
        val listenerType = setter.parameterTypes[0]
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            loader, arrayOf(listenerType),
        ) { _, method, _ ->
            when (method.name) {
                "onPreferenceClick" -> {
                    openModule(context)
                    true
                }
                "equals" -> false
                "hashCode" -> System.identityHashCode(preference)
                "toString" -> "OneHandedEntry"
                else -> null
            }
        }
        runCatching { setter.invoke(preference, listener) }
    }

    /** The group that owns the switch: adding there keeps the row in the same card. */
    private fun switchGroup(screen: Any): Any? {
        val groups = preferencesOf(screen).filter { it.javaClass.name.contains("PreferenceGroup") }
        for (group in groups) {
            if (preferencesOf(group).any { isSwitchPreference(it) }) return group
        }
        return groups.firstOrNull()
    }

    private fun preferencesOf(target: Any): List<Any> {
        val count = runCatching {
            target.javaClass.getMethod("getPreferenceCount").invoke(target) as? Int
        }.getOrNull() ?: return emptyList()
        val getter = target.javaClass.methods.firstOrNull {
            it.name == "getPreference" && it.parameterTypes.size == 1
        } ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            runCatching { getter.invoke(target, index) }.getOrNull()
        }
    }

    private fun findPreference(screen: Any, key: String): Any? {
        val getter = screen.javaClass.methods.firstOrNull {
            it.name == "findPreference" && it.parameterTypes.size == 1
        } ?: return null
        return runCatching { getter.invoke(screen, key) }.getOrNull()
    }

    private fun isSwitchPreference(preference: Any): Boolean {
        val name = preference.javaClass.name
        return name.contains("Switch") || name.contains("SlidingButton")
    }

    /** Walks the support fragment tree; the settings page sits one level below its host. */
    private fun fragmentsOf(activity: Activity): List<Any> {
        val manager = runCatching {
            activity.javaClass.getMethod("getSupportFragmentManager").invoke(activity)
        }.getOrNull() ?: return emptyList()
        val found = ArrayList<Any>()
        fun collect(target: Any, depth: Int) {
            if (depth > 4) return
            val children = runCatching {
                target.javaClass.getMethod("getFragments").invoke(target) as? List<*>
            }.getOrNull() ?: return
            for (child in children) {
                if (child == null) continue
                found.add(child)
                val nested = runCatching {
                    child.javaClass.getMethod("getChildFragmentManager").invoke(child)
                }.getOrNull()
                if (nested != null) collect(nested, depth + 1)
            }
        }
        collect(manager, 0)
        return found
    }

    /**
     * The page may still be inflating at onResume: the list row is retried for a few frames, and
     * only after that does the floating row stand in for it.
     */
    private fun scheduleSettingsRow(activity: Activity, loader: ClassLoader, attempt: Int = 0) {
        val decor = activity.window?.decorView ?: return
        decor.postDelayed({
            if (runCatching { injectIntoScreen(activity, loader) }.getOrDefault(false)) return@postDelayed
            if (attempt < 3) {
                scheduleSettingsRow(activity, loader, attempt + 1)
                return@postDelayed
            }
            runCatching { injectSettingsRow(activity) }
        }, if (attempt == 0) 110L else 170L)
    }

    /** Returns true once the page is either handled or clearly not the one-handed page. */
    private fun injectSettingsRow(activity: Activity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        // Settings reuses one activity across its screens, so a row left from a previous page has
        // to go before anything else is decided.
        removeSettingsRow(content)
        if (!settingsEntryEnabled()) {
            log(Log.INFO, "OneHandedMode", "Settings entry is switched off in the module")
            return true
        }
        if (content.width == 0 || content.height == 0) return false
        if (!isOneHandedPage(activity)) {
            // Second chance for a ROM whose intent carries no fragment name: the page title plus a
            // switch. Accessibility also lists a "one-handed mode" row, hence the extra switch.
            if (!hasOneHandedTitle(content) || !hasSwitch(content)) return true
        }
        val anchor = firstSwitch(content) ?: run {
            log(Log.WARN, "OneHandedMode", "Settings page without a switch, retrying")
            return false
        }
        val card = cardBounds(content, anchor) ?: run {
            dumpHierarchy(content, "no card around ${anchor.javaClass.simpleName}")
            return false
        }
        val row = buildSettingsRow(activity)
        content.addView(row, rowParams(content, card, row))
        followScroll(content, row, anchor)
        row.post {
            placeRow(content, row, anchor)
            val shown = row.isShown && row.height > 0
            log(
                if (shown) Log.INFO else Log.WARN, "OneHandedMode",
                "Settings row ${if (shown) "shown" else "not visible"}: " +
                    "${activity.javaClass.simpleName}, card=${card.left},${card.top}-" +
                    "${card.right},${card.bottom}, content=${content.width}x${content.height}, " +
                    "row=${row.width}x${row.height}",
            )
            if (!shown) {
                dumpHierarchy(content, "row not visible")
                removeSettingsRow(content)
            }
        }
        return true
    }

    /**
     * The card that carries the switch: its nearest ancestor that still looks like one item
     * rather than the whole page.
     *
     * Walking up from the switch, each ancestor is wider than the last; the card is the outermost
     * one that still spans most of the width without filling most of the height. Requiring both
     * keeps the page column and the list out of the answer, so the row always ends up right under
     * the card the switch belongs to instead of at the bottom of the window.
     */
    private fun cardBounds(content: ViewGroup, anchor: View): Rect? {
        val rect = Rect()
        var child: View = anchor
        var best: Rect? = null
        var widest: Rect? = null
        var depth = 0
        while (depth++ < 16) {
            val parent = child.parent as? ViewGroup ?: break
            if (parent === content) break
            parent.getDrawingRect(rect)
            content.offsetDescendantRectToMyCoords(parent, rect)
            // The switch may sit in a fixed-width frame, so a narrow ancestor is stepped over
            // rather than treated as the end of the search.
            if (rect.width() >= (content.width * 0.6f).toInt()) {
                widest = Rect(rect)
                if (rect.height() in 1..(content.height * 0.55f).toInt()) best = Rect(rect)
            }
            child = parent
        }
        // `widest` is only the page column or the list — used when the page has no card shape to
        // anchor to, so the row still lands inside the window instead of nowhere.
        return best ?: widest
    }

    private fun rowParams(content: ViewGroup, card: Rect, row: View): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = card.left
            marginEnd = content.width - card.right
            topMargin = card.bottom + dp(row.context, 10)
        }

    /** Keeps the row glued under the card while the page scrolls. */
    private fun placeRow(content: ViewGroup, row: View, anchor: View) {
        if (row.parent !== content) return
        val card = cardBounds(content, anchor) ?: return
        val params = row.layoutParams as? FrameLayout.LayoutParams ?: return
        val gap = dp(row.context, 10)
        val lowest = (content.height - row.height - dp(row.context, 12)).coerceAtLeast(0)
        val top = (card.bottom + gap).coerceIn(0, lowest)
        if (params.topMargin == top && params.marginStart == card.left &&
            params.marginEnd == content.width - card.right
        ) return
        params.marginStart = card.left
        params.marginEnd = content.width - card.right
        params.topMargin = top
        row.requestLayout()
    }

    private fun followScroll(content: ViewGroup, row: View, anchor: View) {
        var parent = anchor.parent as? ViewGroup
        while (parent != null && parent !== content) {
            if (parent.isScrollContainer || parent.javaClass.name.contains("RecyclerView")) {
                parent.setOnScrollChangeListener { _, _, _, _, _ -> placeRow(content, row, anchor) }
                settingsRowScroller = parent
                return
            }
            parent = parent.parent as? ViewGroup
        }
    }

    /**
     * Dumps the page's view tree when the row cannot be placed.
     *
     * The settings page is the ROM's own, so its item layout is not something this module can
     * look up; when placement misses, the class names and sizes in the log are the only way to
     * see what the page is actually built from.
     */
    private fun dumpHierarchy(root: ViewGroup, reason: String) {
        val builder = StringBuilder("Settings hierarchy ($reason):")
        var lines = 0
        fun walk(view: View, depth: Int) {
            if (lines > 80) return
            val rect = Rect()
            view.getDrawingRect(rect)
            builder.append('\n')
                .append("  ".repeat(depth))
                .append(view.javaClass.name)
                .append(" ").append(rect.width()).append('x').append(rect.height())
                .append(" vis=").append(view.visibility)
                .append(" bg=").append(view.background?.javaClass?.simpleName ?: "-")
            lines++
            if (view is ViewGroup && depth < 6) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(root, 0)
        log(Log.INFO, "OneHandedMode", builder.toString())
    }

    private fun firstSwitch(root: ViewGroup): View? {
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            val name = view.javaClass.name
            if (name.contains("Switch") || name.contains("SlidingButton")) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack.add(view.getChildAt(index))
            }
        }
        return null
    }

    private fun removeSettingsRow(content: ViewGroup) {
        settingsRowScroller?.setOnScrollChangeListener(null)
        settingsRowScroller = null
        val stale = content.findViewWithTag<View>(SETTINGS_ROW_TAG) ?: return
        // The row may sit inside a card rather than directly in the content frame.
        runCatching { (stale.parent as? ViewGroup)?.removeView(stale) }
    }

    /**
     * Recognises the page by the fragment name in the launch intent, or by the activity class —
     * both are available before the view tree exists, so a retry can tell "not this page" from
     * "this page, not laid out yet".
     */
    private fun isOneHandedPage(activity: Activity): Boolean {
        if (activity.javaClass.name.contains("Accessibility", true)) return false
        val intent = activity.intent ?: return false
        val hint = listOf(
            ":settings:show_fragment", ":settings:show_fragment_args", "android.intent.extra.TITLE",
        ).joinToString(" ") { runCatching { intent.getStringExtra(it) }.getOrNull() ?: "" }
        return ONE_HANDED_PAGE.containsMatchIn(hint) ||
            ONE_HANDED_PAGE.containsMatchIn(activity.javaClass.name)
    }

    private fun hasSwitch(root: ViewGroup): Boolean = anyView(root) { view ->
        val name = view.javaClass.name
        name.contains("Switch") || name.contains("SlidingButton")
    }

    private fun hasOneHandedTitle(root: ViewGroup): Boolean = anyView(root) { view ->
        view is TextView && isOneHandedTitle(view.text)
    }

    private fun anyView(root: ViewGroup, predicate: (View) -> Boolean): Boolean {
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (predicate(view)) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack.add(view.getChildAt(index))
            }
        }
        return false
    }

    private fun isOneHandedTitle(text: CharSequence?): Boolean {
        if (text == null) return false
        val compact = text.toString().replace(" ", "").replace("-", "").lowercase(Locale.ROOT)
        return compact == "单手模式" || compact == "onehandedmode"
    }

    private fun openModule(context: Context) {
        runCatching {
            context.startActivity(
                Intent().setClassName(
                    "io.githuh.onehandedmode", "io.githuh.onehandedmode.ui.MainActivity",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun entryTitle(context: Context): String =
        if (Locale.getDefault().language.startsWith("en")) "Enhanced one-handed mode" else "增强单手模式设置"

    private fun entrySummary(context: Context): String =
        if (Locale.getDefault().language.startsWith("en")) "Scale, surround background and side buttons"
        else "缩放比例、画面外背景与侧边按钮"

    /**
     * The row that opens the module's own screen.
     *
     * It is a card of its own, sitting right under the switch card: the system's cards have an
     * 18 dp radius, flat white in light mode and near-black in dark mode, with the title and the
     * summary stacked the same way the row above has them.
     */
    private fun buildSettingsRow(activity: Activity): View {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 18).toFloat()
                setColor(if (night) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
            }
            isClickable = true
            isFocusable = true
            tag = SETTINGS_ROW_TAG
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            text = entryTitle(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (night) 0xFFFFFFFF.toInt() else 0xE6000000.toInt())
        })
        texts.addView(TextView(activity).apply {
            text = entrySummary(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (night) 0x99FFFFFF.toInt() else 0x99000000.toInt())
            setPadding(0, dp(activity, 5), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(activity).apply {
            text = "\u203A"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(if (night) 0x59FFFFFF.toInt() else 0x59000000.toInt())
            setPadding(dp(activity, 8), 0, 0, 0)
        })
        row.setOnClickListener { openModule(activity) }
        return row
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun installFreeformHooks(
        loader: ClassLoader,
        controller: Any,
        hasOplusSystemUi: Boolean,
        xiaomi: XiaomiFreeformCoexist?,
        hasLinkageAnimation: Boolean,
    ) {
        // Every vendor adapter is optional: a missing or renamed class must cost only that
        // adapter, never the rest of the module.
        if (xiaomi != null) {
            runCatching {
                xiaomi.install(hasLinkageAnimation)
            }.onSuccess {
                freeformCoexistEnabled = true
            }.onFailure { error ->
                log(Log.ERROR, "OneHandedMode", "Skipping Xiaomi freeform adapter", error)
            }
        }
        if (hasOplusSystemUi) {
            runCatching {
                controller.read("mTaskStackListenerCallback")!!.javaClass.declaredMethods
                    .forEach { deoptimize(it) }
                OplusFreeformCoexist(this, loader).installSystemUi()
            }.onSuccess {
                hasOplusSidebar = true
                freeformCoexistEnabled = true
            }.onFailure { error ->
                log(Log.ERROR, "OneHandedMode", "Skipping OnePlus SystemUI adapter", error)
            }
        }
    }

    private fun enablePlatformSupport() {
        if (platformSupportInstalled) return
        val properties = Class.forName("android.os.SystemProperties")
        hook(properties.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType))
            .intercept { chain ->
                if (chain.getArg(0) == "ro.support_one_handed_mode") true else chain.proceed()
            }
        platformSupportInstalled = true
    }

    companion object {
        fun circularEaseOut(fraction: Float): Float = sqrt(1f - (fraction - 1f) * (fraction - 1f))
    }
}
