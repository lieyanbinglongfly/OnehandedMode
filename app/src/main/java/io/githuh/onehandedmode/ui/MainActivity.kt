package io.githuh.onehandedmode.ui

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.githuh.onehandedmode.HandySettings
import io.githuh.onehandedmode.ModuleApplication
import io.githuh.onehandedmode.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var systemSupported by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                                onRefresh = ::refreshSupport,
                                onAbout = { backStack.add(Page.About) },
                                onHelp = { backStack.add(Page.Help) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSupport()
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

internal val ContentInset = 20.dp
internal val PreferencePadding = PaddingValues(horizontal = ContentInset, vertical = 16.dp)

@Composable
private fun HomeScreen(
    preferences: SharedPreferences?,
    supported: Boolean?,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
) {
    var mode by remember(preferences) { mutableIntStateOf(preferences?.getInt(HandySettings.MODE, 0) ?: 0) }
    var preset by remember(preferences) {
        mutableIntStateOf(preferences?.getInt(HandySettings.PRESET, HandySettings.DEFAULT_PERCENT) ?: HandySettings.DEFAULT_PERCENT)
    }
    var custom by remember(preferences) {
        mutableIntStateOf(preferences?.getInt(HandySettings.CUSTOM, HandySettings.DEFAULT_PERCENT) ?: HandySettings.DEFAULT_PERCENT)
    }
    var keepNavigationGestures by remember(preferences) {
        mutableStateOf(preferences?.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false) ?: false)
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            mode = prefs.getInt(HandySettings.MODE, 0)
            preset = prefs.getInt(HandySettings.PRESET, HandySettings.DEFAULT_PERCENT)
            custom = prefs.getInt(HandySettings.CUSTOM, HandySettings.DEFAULT_PERCENT)
            keepNavigationGestures = prefs.getBoolean(HandySettings.KEEP_NAVIGATION_GESTURES, false)
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
                WindowDropdownPreference(
                    title = stringResource(R.string.choose_scale),
                    items = listOf(stringResource(R.string.preset_scale), stringResource(R.string.custom_scale)),
                    selectedIndex = mode,
                    insideMargin = PreferencePadding,
                    enabled = preferences != null,
                    onSelectedIndexChange = { index ->
                        mode = index
                        preferences!!.edit().putInt(HandySettings.MODE, index).apply()
                    },
                )
            }
        }
        item { SectionTitle(stringResource(if (mode == 0) R.string.preset_scale else R.string.custom_scale)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                if (mode == HandySettings.PRESET_MODE) {
                    HandySettings.presets.forEach { percent ->
                        RadioButtonPreference(
                            title = "$percent%",
                            selected = preset == percent,
                            radioButtonLocation = RadioButtonLocation.End,
                            insideMargin = PreferencePadding,
                            enabled = preferences != null,
                            onClick = {
                                preset = percent
                                preferences!!.edit().putInt(HandySettings.PRESET, percent).apply()
                            },
                        )
                    }
                } else {
                    SliderPreference(
                        title = stringResource(R.string.scale),
                        value = custom.toFloat(),
                        valueText = "$custom%",
                        valueRange = HandySettings.MIN_PERCENT.toFloat()..HandySettings.MAX_PERCENT.toFloat(),
                        steps = HandySettings.MAX_PERCENT - HandySettings.MIN_PERCENT - 1,
                        insideMargin = PreferencePadding,
                        enabled = preferences != null,
                        onValueChange = { custom = it.roundToInt() },
                        onValueChangeFinished = {
                            preferences!!.edit().putInt(HandySettings.CUSTOM, custom).apply()
                        },
                    )
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
                ArrowPreference(title = stringResource(R.string.help), insideMargin = PreferencePadding, onClick = onHelp)
                ArrowPreference(title = stringResource(R.string.about), insideMargin = PreferencePadding, onClick = onAbout)
            }
        }
        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
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
        R.string.help_scale to R.string.help_scale_body,
    )
    SettingsScaffold(title = stringResource(R.string.help), onBack = onBack) {
        sections.forEach { (title, body) ->
            item { SectionTitle(stringResource(title)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(stringResource(body), modifier = Modifier.padding(ContentInset),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 15.sp, lineHeight = 24.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
    }
}
