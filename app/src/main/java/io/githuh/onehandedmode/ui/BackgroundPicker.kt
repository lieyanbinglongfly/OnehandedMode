package io.githuh.onehandedmode.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.githuh.onehandedmode.HandySettings
import io.githuh.onehandedmode.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Solid / wallpaper / picture, as the library's own spinner preference: the same row the system
 * settings use, with the current choice and its up/down carets on the right. The controls that
 * belong to the chosen fill sit directly under it.
 */
@Composable
internal fun BackgroundSourcePicker(
    selected: Int,
    summary: String,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val sources = listOf(
        HandySettings.SOURCE_SOLID,
        HandySettings.SOURCE_WALLPAPER,
        HandySettings.SOURCE_IMAGE,
    )
    OverlaySpinnerPreference(
        items = listOf(
            DropdownItem(text = stringResource(R.string.background_solid)),
            DropdownItem(text = stringResource(R.string.background_wallpaper)),
            DropdownItem(text = stringResource(R.string.background_image)),
        ),
        selectedIndex = sources.indexOf(selected).coerceAtLeast(0),
        title = stringResource(R.string.surround_color),
        summary = summary,
        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 14.dp),
        enabled = enabled,
        onSelectedIndexChange = { onSelect(sources[it]) },
    )
}

/**
 * Twelve swatches in two rows of six: auto, ten presets, then the wheel. The wheel keeps the last
 * position so a custom color never costs a thirteenth slot.
 */
@Composable
internal fun ColorSwatchGrid(
    selected: Int,
    recent: List<Int>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onOpenWheel: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val auto = selected == HandySettings.FOLLOW_THEME
    val known = HandySettings.commonColors + recent
    val custom = !auto && selected !in known
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Row one: auto plus the neutrals. Row two: the picker, then what was used before.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorSwatch(
                modifier = Modifier.weight(1f),
                selected = auto,
                color = null,
                enabled = enabled,
                onClick = { onSelect(HandySettings.FOLLOW_THEME) },
            ) {
                Text(stringResource(R.string.surround_color_auto), fontSize = 11.sp,
                    color = scheme.onSurfaceVariantSummary)
            }
            HandySettings.commonColors.forEach { preset ->
                ColorSwatch(
                    modifier = Modifier.weight(1f),
                    selected = selected == preset,
                    color = preset,
                    enabled = enabled,
                    onClick = { onSelect(preset) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The wheel doubles as the readout for a custom color.
            Box(
                modifier = Modifier.weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (custom) SolidColor(Color(selected)) else RainbowBrush)
                    .border(
                        width = if (custom) 2.dp else 1.dp,
                        color = if (custom) scheme.onSurface else scheme.onSurface.copy(alpha = 0.15f),
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled, onClick = onOpenWheel),
                contentAlignment = Alignment.Center,
            ) {
                if (!custom) {
                    Text("+", fontSize = 18.sp, fontWeight = FontWeight.Light,
                        color = Color.White.copy(alpha = 0.9f))
                }
            }
            repeat(5) { index ->
                val color = recent.getOrNull(index)
                if (color == null) {
                    Spacer(Modifier.weight(1f))
                } else {
                    ColorSwatch(
                        modifier = Modifier.weight(1f),
                        selected = selected == color,
                        color = color,
                        enabled = enabled,
                        onClick = { onSelect(color) },
                    )
                }
            }
        }
    }
}

private val RainbowBrush = Brush.sweepGradient(
    0f to Color(0xFFFF3B30),
    60f / 360f to Color(0xFFFFCC00),
    120f / 360f to Color(0xFF34C759),
    180f / 360f to Color(0xFF32ADE6),
    240f / 360f to Color(0xFF007AFF),
    300f / 360f to Color(0xFFAF52DE),
    1f to Color(0xFFFF3B30),
)

@Composable
private fun ColorSwatch(
    modifier: Modifier = Modifier,
    selected: Boolean,
    color: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(
                if (color == null) Brush.linearGradient(listOf(scheme.secondaryContainer, scheme.surface))
                else SolidColor(Color(color))
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.onSurface else scheme.onSurface.copy(alpha = 0.15f),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
