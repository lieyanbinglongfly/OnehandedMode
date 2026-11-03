// Material, parallax and blend tokens adapted from YuKongA/Mishka AboutScreen. GPL-3.0-only.
package io.githuh.onehandedmode.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.githuh.onehandedmode.BuildConfig
import io.githuh.onehandedmode.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val progress = remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else {
                val spacer = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                if (spacer != null && spacer.size > 0) {
                    (listState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                } else 0f
            }
        }
    }
    val collapsed by remember { derivedStateOf { progress.value == 1f } }
    val titleAlpha by remember { derivedStateOf { ((progress.value - .35f) / .65f).coerceIn(0f, 1f) } }
    val topBackdrop = rememberBlurBackdrop()
    val blur = Build.VERSION.SDK_INT >= 33 && topBackdrop != null
    val materialBackdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    var logoHeight by remember { mutableStateOf(300.dp) }
    val cardBlend = remember(dark) {
        if (dark) listOf(
            BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
            BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
        ) else listOf(
            BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
            BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
        )
    }
    val logoBlend = remember(dark) {
        if (dark) listOf(
            BlendColorEntry(Color(0xE6A1A1A1), BlurBlendMode.ColorDodge),
            BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xFF1AF500), BlurBlendMode.Lab),
        ) else listOf(
            BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
            BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
        )
    }
    fun Modifier.heroMaterial() = if (blur) textureBlur(
        backdrop = materialBackdrop,
        shape = RectangleShape,
        blurRadius = 150f,
        colors = BlurColors(blendColors = logoBlend),
        contentBlendMode = BlendMode.DstIn,
    ) else this

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidePadding = if (maxWidth > 800.dp) (maxWidth - 800.dp) / 2 + 12.dp else 12.dp
        Scaffold(
            topBar = {
                BlurredBar(topBackdrop, blurActive = blur && collapsed) {
                    SmallTopAppBar(
                        title = stringResource(R.string.about),
                        titleColor = colors.onSurface.copy(alpha = titleAlpha),
                        color = if (collapsed && !blur) colors.surface else Color.Transparent,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackButton(onBack) },
                    )
                }
            },
        ) { padding ->
            val top = padding.calculateTopPadding()
            Box(Modifier.fillMaxSize().then(if (topBackdrop != null) Modifier.layerBackdrop(topBackdrop) else Modifier)) {
                AboutBackground(Modifier.layerBackdrop(materialBackdrop)) { 1f - progress.value }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = top + 92.dp)
                        .onSizeChanged { logoHeight = with(density) { it.height.toDp() } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(100.dp).clipToBounds().graphicsLayer {
                            val p = ((progress.value - .35f) / .15f).coerceIn(0f, 1f)
                            alpha = 1 - p
                            scaleX = 1 - p * .05f
                            scaleY = scaleX
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colors.onBackground),
                            modifier = Modifier.size(100.dp).heroMaterial(),
                        )
                    }
                    Text(
                        text = stringResource(R.string.app_name),
                        color = colors.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 35.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 5.dp).graphicsLayer {
                            val p = ((progress.value - .20f) / .15f).coerceIn(0f, 1f)
                            alpha = 1 - p
                            scaleX = 1 - p * .05f
                            scaleY = scaleX
                        }.heroMaterial(),
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        color = colors.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            val p = ((progress.value - .05f) / .15f).coerceIn(0f, 1f)
                            alpha = 1 - p
                            scaleX = 1 - p * .05f
                            scaleY = scaleX
                        },
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                        .scrollEndHaptic().overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(top = top, start = sidePadding, end = sidePadding),
                ) {
                    item(key = "logoSpacer") { Spacer(Modifier.fillMaxWidth().height(logoHeight + 218.dp)) }
                    item(key = "about") {
                        Box {
                            Spacer(Modifier.fillParentMaxHeight())
                            Column {
                                SectionTitle(stringResource(R.string.project))
                                Card(
                                    modifier = Modifier.fillMaxWidth().squircleClip(16.dp).then(
                                        if (blur) Modifier.textureBlur(
                                            backdrop = materialBackdrop,
                                            shape = RectangleShape,
                                            blurRadius = 60f,
                                            colors = BlurColors(blendColors = cardBlend),
                                        ) else Modifier,
                                    ),
                                    colors = CardDefaults.defaultColors(
                                        if (blur) Color.Transparent else colors.surfaceContainer,
                                        Color.Transparent,
                                    ),
                                ) {
                                    ArrowPreference(
                                        title = stringResource(R.string.project_source),
                                        summary = "github.com/wushidia/OnehandedMode",
                                        insideMargin = PreferencePadding,
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                                "https://github.com/wushidia/OnehandedMode".toUri()))
                                        },
                                    )
                                }
                                SectionTitle(stringResource(R.string.open_source))
                                Card(
                                    modifier = Modifier.fillMaxWidth().squircleClip(16.dp).then(
                                        if (blur) Modifier.textureBlur(
                                            backdrop = materialBackdrop,
                                            shape = RectangleShape,
                                            blurRadius = 60f,
                                            colors = BlurColors(blendColors = cardBlend),
                                        ) else Modifier,
                                    ),
                                    colors = CardDefaults.defaultColors(
                                        if (blur) Color.Transparent else colors.surfaceContainer,
                                        Color.Transparent,
                                    ),
                                ) {
                                    listOf(
                                        "miuix" to "https://github.com/YuKongA/miuix",
                                        "Android Open Source Project" to "https://android.googlesource.com/platform/frameworks/base/",
                                    ).forEach { (name, url) ->
                                        ArrowPreference(
                                            title = name,
                                            summary = url.removePrefix("https://"),
                                            insideMargin = PreferencePadding,
                                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(24.dp).navigationBarsPadding())
                            }
                        }
                    }
                }
            }
        }
    }
}
