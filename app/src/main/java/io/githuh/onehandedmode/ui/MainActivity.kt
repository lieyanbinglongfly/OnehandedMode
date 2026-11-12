package io.githuh.onehandedmode.ui

import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.githuh.onehandedmode.HandySettings
import io.githuh.onehandedmode.ModuleApplication
import io.githuh.onehandedmode.R
import io.githuh.onehandedmode.hook.ACTION_REQUEST_EXIT
import io.githuh.onehandedmode.hook.ACTION_REQUEST_SIDE
import io.githuh.onehandedmode.hook.BackgroundBitmap
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var systemSupported by mutableStateOf<Boolean?>(null)
    /** State of the framework's own one-handed switch; null until the module has reported it. */
    private var systemOneHanded by mutableStateOf<Boolean?>(null)
    // Percent produced by a side-strip drag, published by SystemUI in Settings.Secure.
    private var swipedPercent by mutableIntStateOf(-1)
    // SystemUI writes the settings row while the strip is dragged, so the slider can follow a
    // gesture that happens while this screen is on top instead of only after the next resume.
    private val swipeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = syncSwipedPercent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read the live scale before the first composition. Doing it in onStart left the first
        // frame computing its value from the module preferences alone, so a cold start could show
        // a scale the picture was not using.
        syncSwipedPercent()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val service = (application as ModuleApplication).service
                val preferences = remember(service) { service?.getRemotePreferences(HandySettings.GROUP) }
                val backStack = rememberSaveable(saver = PageStackSaver) { mutableStateListOf<NavKey>(Page.Home) }
                val onBack = { backStack.removeAt(backStack.lastIndex); Unit }
                NavDisplay(
                    backStack = backStack,
                    onBack = onBack,
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                ) {
                    entry<Page>(swipeDismiss = NavSwipeDirection.LeftToRight) { current ->
                        when (current) {
                            Page.About -> AboutScreen(onBack)
                            Page.Help -> HelpScreen(onBack)
                            Page.Home -> HomeScreen(
                                preferences = preferences,
                                supported = systemSupported,
                                swipedPercent = swipedPercent,
                                systemOneHanded = systemOneHanded,
                                onRefresh = ::refreshSupport,
                                onOpenSystemSettings = ::openSystemOneHandedSettings,
                                onRequestSide = ::requestSide,
                                onExitMode = ::requestExit,
                                onAbout = { backStack.add(Page.About) },
                                onHelp = { backStack.add(Page.Help) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(HandySettings.SWIPE_PERCENT), false, swipeObserver,
        )
        syncSwipedPercent()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(swipeObserver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Only read it: writing the switch here is what made opening the app turn one-handed
        // mode back on after the user had turned it off. The framework key itself is unreadable
        // from an ordinary app — `one_handed_mode_enabled` is @hide and Android 12 restricts those
        // to system apps, which threw a SecurityException here on every resume — so SystemUI
        // mirrors it into SYSTEM_ENABLED_MIRROR and that is what is read.
        systemOneHanded = runCatching {
            Settings.Secure.getInt(contentResolver, HandySettings.SYSTEM_ENABLED_MIRROR, -1)
        }.getOrDefault(-1).let { if (it < 0) null else it == 1 }
        refreshSupport()
        val adopted = adoptSwipedScale()
        if (adopted > 0) swipedPercent = adopted else syncSwipedPercent()
    }

    /** Picks up a value SystemUI published while the slider was being dragged. */
    private fun syncSwipedPercent() {
        val percent = runCatching {
            Settings.Secure.getInt(contentResolver, HandySettings.SWIPE_PERCENT, -1)
        }.getOrDefault(-1)
        if (percent in HandySettings.MIN_PERCENT..HandySettings.MAX_PERCENT && percent != swipedPercent) {
            swipedPercent = percent
        }
    }

    /**
     * A side-strip drag is stored in the framework settings by SystemUI, which cannot write the
     * module's remote preferences. Pull it in when the app opens so the slider shows the value
     * that is actually in use, and so both processes agree again.
     */
    private fun adoptSwipedScale(): Int {
        // A settings read can throw (restricted key, provider restart); the scale is a nicety.
        val percent = runCatching {
            Settings.Secure.getInt(contentResolver, HandySettings.SWIPE_PERCENT, -1)
        }.getOrDefault(-1)
        if (percent < HandySettings.MIN_PERCENT || percent > HandySettings.MAX_PERCENT) return -1
        val service = (application as ModuleApplication).service ?: return percent
        val preferences = runCatching { service.getRemotePreferences(HandySettings.GROUP) }.getOrNull()
            ?: return percent
        if (HandySettings.percent(preferences) != percent) {
            runCatching {
                preferences.edit()
                    .putInt(HandySettings.CUSTOM, percent)
                    .putInt(HandySettings.MODE, HandySettings.CUSTOM_MODE)
                    .apply()
            }
        }
        return percent
    }

    /**
     * Opens the settings page the module injects its row into.
     *
     * Settings serves its subpages through `SubSettings`, and the page to show is named by the
     * `:settings:show_fragment` extra. The action used before — `com.android.settings.action
     * .SETTINGS` — does not exist on this ROM, so the intent threw and the fallback dropped the
     * user on the settings home screen; `ACTION_SETTINGS` does exist but honours no fragment
     * extra, which is the same wrong page.
     */
    private fun openSystemOneHandedSettings() {
        val candidates = listOf(
            "$SETTINGS_PACKAGE.SubSettings",
            "$SETTINGS_PACKAGE.Settings",
        )
        for (name in candidates) {
            val intent = Intent().setClassName(SETTINGS_PACKAGE, name).apply {
                putExtra(":settings:show_fragment", HANDY_FRAGMENT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Asks SystemUI to put the mode on [side], or to leave it when [side] is null.
     *
     * A broadcast rather than a preference write: it is the channel that already starts the mode
     * from outside, and it does not depend on the hooked process noticing a settings change.
     */
    private fun requestSide(side: String) {
        sendBroadcast(
            Intent(ACTION_REQUEST_SIDE).setPackage(SYSTEM_UI).putExtra("side", side)
        )
    }

    private fun requestExit() {
        sendBroadcast(Intent(ACTION_REQUEST_EXIT).setPackage(SYSTEM_UI))
    }

    private fun refreshSupport() {
        lifecycleScope.launch {
            systemSupported = withContext(Dispatchers.IO) {
                val process = ProcessBuilder("/system/bin/getprop", "ro.support_one_handed_mode").start()
                val supported = process.inputStream.bufferedReader().use { it.readText().trim() == "true" }
                process.waitFor()
                supported && Build.VERSION.SDK_INT in 31..37
            }
        }
    }
}

private enum class Page : NavKey { Home, Help, About }
private val PageStackSaver = Saver<SnapshotStateList<NavKey>, List<String>>(
    save = { stack -> stack.map { (it as Page).name } },
    restore = { names -> mutableStateListOf<NavKey>().apply { addAll(names.map(Page::valueOf)) } },
)

/** SystemUI hosts the module; broadcast targets are addressed to it explicitly. */
private const val SYSTEM_UI = "com.android.systemui"
/** Settings' own fragment hosts, and the page the module adds its row to. */
private const val SETTINGS_PACKAGE = "com.android.settings"
private const val HANDY_FRAGMENT = "com.android.settings.display.HandyModeFragment"

internal val ContentInset = 20.dp
internal val PreferencePadding = PaddingValues(horizontal = ContentInset, vertical = 16.dp)

@Composable
private fun HomeScreen(
    preferences: SharedPreferences?,
    supported: Boolean?,
    swipedPercent: Int,
    systemOneHanded: Boolean?,
    onRefresh: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onRequestSide: (String) -> Unit,
    onExitMode: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
) {
    // One value drives the slider, the presets and the side-strip gesture. The framework copy
    // wins when it carries a valid value: it is what the picture is actually drawn at, and the
    // module preferences can lag behind it on a cold start.
    val livePercent = swipedPercent.takeIf { it in HandySettings.MIN_PERCENT..HandySettings.MAX_PERCENT }
    var percent by remember(preferences) {
        mutableIntStateOf(
            livePercent ?: preferences?.let { HandySettings.percent(it) } ?: HandySettings.DEFAULT_PERCENT
        )
    }
    // The slider itself runs on the raw float from the finger. Feeding it the rounded integer
    // made the thumb chase a value that jumped 1% at a time, and the spring behind it turned
    // that into the "heavy" feel this slider had. The integer stays the source of truth for the
    // label, the presets and the commit on release.
    var sliderValue by remember(preferences) {
        mutableFloatStateOf(
            (livePercent ?: preferences?.let { HandySettings.percent(it) }
                ?: HandySettings.DEFAULT_PERCENT).toFloat()
        )
    }
    // Presets and side-strip drags move `percent` from the outside; the slider follows them.
    LaunchedEffect(percent) {
        if (abs(percent - sliderValue) >= 0.5f) sliderValue = percent.toFloat()
    }
    // A side-strip drag writes Settings.Secure; take it over as soon as the app comes forward.
    LaunchedEffect(swipedPercent) {
        if (swipedPercent in HandySettings.MIN_PERCENT..HandySettings.MAX_PERCENT) percent = swipedPercent
    }
    var keepSystem by remember(preferences) {
        mutableStateOf(preferences?.let { HandySettings.keepSystemEnabled(it) } ?: false)
    }
    var keepNavigationGestures by remember(preferences) {
        mutableStateOf(preferences?.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false) ?: false)
    }
    var switchSideOnTap by remember(preferences) {
        mutableStateOf(preferences?.getBoolean(HandySettings.SIDE_TAP_SWITCH, true) ?: true)
    }
    var resizeOnSwipe by remember(preferences) {
        mutableStateOf(preferences?.getBoolean(HandySettings.SWIPE_SCALE, true) ?: true)
    }
    var broadcastMode by remember(preferences) {
        mutableStateOf(preferences?.getBoolean(HandySettings.BROADCAST_MODE, true) ?: true)
    }
    var surroundColor by remember(preferences) {
        mutableIntStateOf(
            preferences?.getInt(HandySettings.SURROUND_COLOR, HandySettings.FOLLOW_THEME) ?: HandySettings.FOLLOW_THEME
        )
    }
    var settingsEntry by remember(preferences) {
        mutableStateOf(preferences?.getBoolean(HandySettings.SETTINGS_ENTRY, true) ?: true)
    }
    // Second row of swatches: the five colors applied most recently, newest first.
    var recentColors by remember(preferences) {
        mutableStateOf(
            preferences?.let { HandySettings.recentColors(it) } ?: HandySettings.recommendedColors
        )
    }
    var backgroundSource by remember(preferences) {
        mutableIntStateOf(preferences?.let { HandySettings.backgroundSource(it) } ?: HandySettings.SOURCE_SOLID)
    }
    var backgroundBlur by remember(preferences) {
        mutableIntStateOf(preferences?.let { HandySettings.backgroundBlur(it) } ?: 40)
    }
    var backgroundImage by remember(preferences) {
        mutableStateOf(preferences?.let { HandySettings.backgroundImage(it) } ?: "")
    }
    var surroundHints by remember(preferences) {
        mutableStateOf(preferences?.let { HandySettings.surroundHints(it) } ?: true)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val encoded = withContext(Dispatchers.IO) { BackgroundBitmap.encode(context, uri) } ?: return@launch
            backgroundImage = encoded
            backgroundSource = HandySettings.SOURCE_IMAGE
            preferences?.edit()
                ?.putString(HandySettings.BACKGROUND_IMAGE, encoded)
                ?.putInt(HandySettings.BACKGROUND_SOURCE, HandySettings.SOURCE_IMAGE)
                ?.apply()
        }
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            percent = HandySettings.percent(prefs)
            keepNavigationGestures = prefs.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)
            switchSideOnTap = prefs.getBoolean(HandySettings.SIDE_TAP_SWITCH, true)
            resizeOnSwipe = prefs.getBoolean(HandySettings.SWIPE_SCALE, true)
            broadcastMode = prefs.getBoolean(HandySettings.BROADCAST_MODE, true)
            surroundColor = prefs.getInt(HandySettings.SURROUND_COLOR, HandySettings.FOLLOW_THEME)
            settingsEntry = prefs.getBoolean(HandySettings.SETTINGS_ENTRY, true)
            recentColors = HandySettings.recentColors(prefs)
            backgroundSource = HandySettings.backgroundSource(prefs)
            backgroundBlur = HandySettings.backgroundBlur(prefs)
            backgroundImage = HandySettings.backgroundImage(prefs)
            surroundHints = HandySettings.surroundHints(prefs)
            keepSystem = HandySettings.keepSystemEnabled(prefs)
        }
        preferences?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsScaffold(title = stringResource(R.string.app_name)) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            ModuleStatusCard(active = preferences != null, supported = supported, onRefresh = onRefresh)
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                // The system's own switch decides whether the edge gesture does anything; the
                // module only draws the mode. Reading it here, instead of writing it on launch,
                // keeps the two independent.
                ArrowPreference(
                    title = stringResource(R.string.system_one_handed),
                    summary = stringResource(
                        when (systemOneHanded) {
                            true -> R.string.system_one_handed_on
                            false -> R.string.system_one_handed_off
                            null -> R.string.system_one_handed_unknown
                        }
                    ),
                    insideMargin = PreferencePadding,
                    onClick = onOpenSystemSettings,
                )
                SwitchPreference(
                    title = stringResource(R.string.keep_system_one_handed),
                    summary = stringResource(R.string.keep_system_one_handed_summary),
                    checked = keepSystem,
                    onCheckedChange = { checked ->
                        keepSystem = checked
                        preferences!!.edit()
                            .putBoolean(HandySettings.KEEP_SYSTEM_ENABLED, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SliderPreference(
                        title = stringResource(R.string.scale),
                        value = sliderValue,
                        valueText = "$percent%",
                        valueRange = HandySettings.MIN_PERCENT.toFloat()..HandySettings.MAX_PERCENT.toFloat(),
                        // Continuous, like the sliders in the system settings. The 70 discrete
                        // steps this used to have snapped the thumb to every 1% and animated
                        // each snap, which is what made the drag feel damped.
                        steps = 0,
                        insideMargin = PreferencePadding,
                        enabled = preferences != null,
                        onValueChange = {
                            sliderValue = it
                            percent = it.roundToInt()
                        },
                        onValueChangeFinished = {
                            // Land exactly on the whole percent that will be applied.
                            sliderValue = percent.toFloat()
                            commitScale(preferences, percent)
                        },
                    )
                    Text(
                        stringResource(R.string.scale_presets),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = ContentInset),
                    )
                    Spacer(Modifier.height(8.dp))
                    ScalePresets(
                        selected = percent,
                        enabled = preferences != null,
                        onSelect = { value ->
                            percent = value
                            commitScale(preferences, value)
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = ContentInset, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ModeAction(
                            text = stringResource(R.string.mode_left),
                            modifier = Modifier.weight(1f),
                            enabled = preferences != null,
                            onClick = { onRequestSide("left") },
                        )
                        ModeAction(
                            text = stringResource(R.string.mode_right),
                            modifier = Modifier.weight(1f),
                            enabled = preferences != null,
                            onClick = { onRequestSide("right") },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ContentInset)
                            .padding(bottom = 12.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer)
                            .clickable(enabled = preferences != null) { onExitMode() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.mode_exit),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.keep_navigation_gestures),
                    summary = stringResource(R.string.keep_navigation_gestures_summary),
                    checked = keepNavigationGestures,
                    onCheckedChange = { checked ->
                        keepNavigationGestures = checked
                        preferences!!.edit().putBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.side_tap_switch),
                    summary = stringResource(R.string.side_tap_switch_summary),
                    checked = switchSideOnTap,
                    onCheckedChange = { checked ->
                        switchSideOnTap = checked
                        preferences!!.edit().putBoolean(HandySettings.SIDE_TAP_SWITCH, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
                SwitchPreference(
                    title = stringResource(R.string.swipe_scale),
                    summary = stringResource(R.string.swipe_scale_summary),
                    checked = resizeOnSwipe,
                    onCheckedChange = { checked ->
                        resizeOnSwipe = checked
                        preferences!!.edit().putBoolean(HandySettings.SWIPE_SCALE, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                BackgroundSection(
                    recent = recentColors,
                    color = surroundColor,
                    source = backgroundSource,
                    blur = backgroundBlur,
                    image = backgroundImage,
                    enabled = preferences != null,
                    onColor = { selected ->
                        surroundColor = selected
                        backgroundSource = HandySettings.SOURCE_SOLID
                        preferences!!.edit()
                            .putInt(HandySettings.SURROUND_COLOR, selected)
                            .putInt(HandySettings.BACKGROUND_SOURCE, HandySettings.SOURCE_SOLID)
                            .apply()
                        if (selected != HandySettings.FOLLOW_THEME) {
                            HandySettings.recordColor(preferences, selected)
                            recentColors = HandySettings.recentColors(preferences)
                        }
                    },
                    onSource = { selected ->
                        backgroundSource = selected
                        preferences!!.edit().putInt(HandySettings.BACKGROUND_SOURCE, selected).apply()
                    },
                    onBlur = { value ->
                        backgroundBlur = value
                        preferences!!.edit().putInt(HandySettings.BACKGROUND_BLUR, value).apply()
                    },
                    onPickImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                // Belongs to the surround: it decides what the two blank strips show about
                // themselves, right under the colours they are painted with.
                SwitchPreference(
                    title = stringResource(R.string.surround_hints),
                    summary = stringResource(R.string.surround_hints_summary),
                    checked = surroundHints,
                    onCheckedChange = { checked ->
                        surroundHints = checked
                        preferences!!.edit().putBoolean(HandySettings.SURROUND_HINTS, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.settings_entry),
                    summary = stringResource(R.string.settings_entry_summary),
                    checked = settingsEntry,
                    onCheckedChange = { checked ->
                        settingsEntry = checked
                        preferences!!.edit().putBoolean(HandySettings.SETTINGS_ENTRY, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
                SwitchPreference(
                    title = stringResource(R.string.broadcast_mode),
                    summary = stringResource(R.string.broadcast_mode_summary),
                    checked = broadcastMode,
                    onCheckedChange = { checked ->
                        broadcastMode = checked
                        preferences!!.edit().putBoolean(HandySettings.BROADCAST_MODE, checked).apply()
                    },
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = stringResource(R.string.help), insideMargin = PreferencePadding, onClick = onHelp)
                ArrowPreference(title = stringResource(R.string.about), insideMargin = PreferencePadding, onClick = onAbout)
            }
        }
        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
    }
}

/** One of the two "put the mode on this side" buttons. */
@Composable
private fun ModeAction(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .border(1.dp, scheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, color = scheme.onSurface)
    }
}

/** Persists the scale in one shot: SystemUI listens to these keys and re-applies them. */
private fun commitScale(preferences: SharedPreferences?, percent: Int) {
    preferences?.edit()
        ?.putInt(HandySettings.MODE, HandySettings.CUSTOM_MODE)
        ?.putInt(HandySettings.CUSTOM, percent)
        ?.apply()
}

@Composable
private fun ScalePresets(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ContentInset, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HandySettings.presets.forEach { preset ->
            val active = selected == preset
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) scheme.secondaryContainer else scheme.onSurface.copy(alpha = 0.06f))
                    .border(
                        width = 1.dp,
                        color = if (active) scheme.onSurface else scheme.onSurface.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(preset) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$preset%",
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) scheme.onSurface else scheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Opaque black, used to force full opacity on a user supplied RGB value. */
private const val OPAQUE = -0x1000000

/**
 * Range the blur slider is driven over. It shares the track width of the scale slider, so the
 * range is what decides how far one pixel of finger movement goes: the scale slider spans 70
 * points (20–90 %), and a 0–100 blur range across the same track moved 1.43× faster, which is the
 * lighter feel it had. Driving it over 70 points gives it exactly the scale slider's damping.
 */
private const val BLUR_SLIDER_SPAN = 70f

private fun hexOf(color: Int): String = String.format(Locale.US, "%06X", color and 0xFFFFFF)

@Composable
private fun BackgroundSection(
    recent: List<Int>,
    color: Int,
    source: Int,
    blur: Int,
    image: String,
    enabled: Boolean,
    onColor: (Int) -> Unit,
    onSource: (Int) -> Unit,
    onBlur: (Int) -> Unit,
    onPickImage: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var wheelOpen by remember { mutableStateOf(false) }
    var hex by remember(color) { mutableStateOf(if (color == HandySettings.FOLLOW_THEME) "" else hexOf(color)) }
    var sliderBlur by remember(blur) { mutableFloatStateOf(blur.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = ContentInset, vertical = 4.dp)) {
        BackgroundSourcePicker(
            selected = source,
            summary = stringResource(R.string.surround_color_summary),
            enabled = enabled,
            onSelect = onSource,
        )
        Spacer(Modifier.height(14.dp))
        when (source) {
            HandySettings.SOURCE_SOLID -> {
                ColorSwatchGrid(color, recent, enabled, onColor) { wheelOpen = true }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("#", fontSize = 15.sp, color = scheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.secondaryContainer)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (hex.isEmpty()) {
                            Text(
                                stringResource(R.string.surround_color_hex_hint),
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariantSummary,
                            )
                        }
                        BasicTextField(
                            value = hex,
                            onValueChange = { input ->
                                val filtered = input
                                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                                    .take(6)
                                    .uppercase()
                                hex = filtered
                                if (filtered.length == 6) {
                                    filtered.toIntOrNull(16)?.let { onColor(it or OPAQUE) }
                                }
                            },
                            enabled = enabled,
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = scheme.onSurface, letterSpacing = 1.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            HandySettings.SOURCE_WALLPAPER -> {
                Text(
                    stringResource(R.string.background_wallpaper_summary),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
            else -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val preview = remember(image) {
                        runCatching { BackgroundBitmap.decode(image)?.asImageBitmap() }.getOrNull()
                    }
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(scheme.onSurface.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preview != null) {
                            Image(preview, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize())
                        } else {
                            Text("—", fontSize = 14.sp, color = scheme.onSurfaceVariantSummary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(if (image.isEmpty()) R.string.background_no_image else R.string.background_image),
                            fontSize = 15.sp,
                        )
                        Text(
                            stringResource(R.string.background_image_summary),
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(scheme.primary)
                            .clickable(enabled = enabled, onClick = onPickImage)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(if (image.isEmpty()) R.string.background_pick_image else R.string.background_change_image),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onPrimary,
                        )
                    }
                }
            }
        }
        if (source != HandySettings.SOURCE_SOLID) {
            Spacer(Modifier.height(10.dp))
            SliderPreference(
                title = stringResource(R.string.background_blur),
                // Driven over 70 points but still stored as a percentage, so the thumb moves at
                // the same rate as the scale slider while the value keeps 1 % resolution.
                value = sliderBlur * BLUR_SLIDER_SPAN / 100f,
                valueText = "${sliderBlur.roundToInt()}%",
                valueRange = 0f..BLUR_SLIDER_SPAN,
                steps = 0,
                insideMargin = PaddingValues(0.dp),
                enabled = enabled,
                onValueChange = { sliderBlur = it * 100f / BLUR_SLIDER_SPAN },
                onValueChangeFinished = { onBlur(sliderBlur.roundToInt()) },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.background_blur_summary),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    if (wheelOpen) {
        ColorPickerDialog(
            initial = if (color == HandySettings.FOLLOW_THEME) 0xFF1C1C1E.toInt() else color,
            onDismiss = { wheelOpen = false },
            onConfirm = {
                onColor(it)
                wheelOpen = false
            },
        )
    }
}

@Composable
private fun ModuleStatusCard(active: Boolean, supported: Boolean?, onRefresh: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ready = active && supported == true
    val checking = supported == null
    val cardColor = when {
        ready -> if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
        checking -> if (dark) Color(0xFF3D3215) else Color(0xFFFFF3CD)
        else -> if (dark) Color(0xFF3A1E22) else Color(0xFFFFE4E1)
    }
    val title = stringResource(when {
        checking -> R.string.status_checking
        supported == false -> R.string.status_unsupported
        !active -> R.string.status_inactive
        else -> R.string.status_ready
    })
    val system = stringResource(if (supported == true) R.string.status_system_supported else R.string.status_system_unsupported)
    val xposed = stringResource(if (active) R.string.status_xposed_active else R.string.status_xposed_inactive)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = cardColor),
        onClick = onRefresh,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            val iconModifier = Modifier.align(Alignment.BottomEnd).offset(27.dp, 31.dp).size(110.dp)
            if (ready) {
                Icon(
                    painter = painterResource(R.drawable.ic_status_ready),
                    contentDescription = null,
                    tint = Color(0xFF36D167),
                    modifier = iconModifier,
                )
            } else {
                Icon(MiuixIcons.Info, contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .3f), modifier = iconModifier)
            }
            Column(Modifier.padding(horizontal = ContentInset, vertical = 18.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (checking) stringResource(R.string.status_checking_summary) else "$system\n$xposed",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (!active && !checking) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.status_activate_hint), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val sidePadding = if (maxWidth > 800.dp) (maxWidth - 800.dp) / 2 + 12.dp else 12.dp
        val back: @Composable () -> Unit = { if (onBack != null) BackButton(onBack) }
        Scaffold(topBar = {
            BlurredBar(backdrop) {
                if (wide) SmallTopAppBar(title = title, color = barColor, navigationIcon = back)
                else TopAppBar(title = title, color = barColor, scrollBehavior = scrollBehavior, navigationIcon = back)
            }
        }) { padding ->
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                    .scrollEndHaptic().overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), start = sidePadding, end = sidePadding),
                content = content,
            )
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, minHeight = 35.dp, minWidth = 35.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer) {
        Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.back))
    }
}

@Composable
internal fun SectionTitle(text: String) {
    SmallTitle(text = text, insideMargin = PaddingValues(horizontal = ContentInset, vertical = 8.dp))
}

@Composable
private fun HelpScreen(onBack: () -> Unit) {
    val sections = listOf(
        R.string.help_enter to R.string.help_enter_body,
        R.string.help_exit to R.string.help_exit_body,
        R.string.help_surround to R.string.help_surround_body,
        R.string.help_swipe to R.string.help_swipe_body,
        R.string.help_scale to R.string.help_scale_body,
        R.string.help_external to R.string.help_external_body,
    )
    SettingsScaffold(title = stringResource(R.string.help), onBack = onBack) {
        sections.forEach { (title, body) ->
            item { SectionTitle(stringResource(title)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer(Modifier.fillMaxWidth()) {
                        Text(stringResource(body), modifier = Modifier.padding(ContentInset),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 15.sp, lineHeight = 24.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
    }
}
