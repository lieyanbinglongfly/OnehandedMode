package io.githuh.onehandedmode.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Canvas as AndroidCanvas
import android.app.WallpaperManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.githuh.onehandedmode.HandySettings
import io.githuh.onehandedmode.R
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class PickerMode { Grid, Slider, Monet }

/** Opaque black: forces full opacity on a color picked without an alpha channel. */
private const val OPAQUE = -0x1000000

private const val GRID_COLUMNS = 12
private const val GRID_ROWS = 5

/**
 * One color picker with three ways in, matching the system theme-color dialog: a hue grid, plain
 * HSV sliders, and a palette pulled from the wallpaper.
 *
 * Everything funnels through the same three HSV floats, so the swatch, the hex field and every
 * control always describe the same color — the old wheel kept its own copy of the value and could
 * show something different from what it returned.
 */
@Composable
internal fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val start = remember(initial) { hsvOf(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var alpha by remember {
        mutableFloatStateOf(AndroidColor.alpha(initial).let { if (it == 0) 1f else it / 255f })
    }
    var mode by remember { mutableStateOf(PickerMode.Grid) }
    val scheme = MiuixTheme.colorScheme

    val rgb = AndroidColor.HSVToColor(
        floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)),
    )
    // Never zero: a fully transparent color would be stored as 0, which is the "follow theme"
    // sentinel, and the picker would silently fall back to the system background.
    val alphaInt = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceAtLeast(1)
    val picked = (rgb and 0x00FFFFFF) or (alphaInt shl 24)
    var hexText by remember { mutableStateOf(hexOf(rgb)) }
    // The hex field follows every other control, but never rewrites what is being typed unless
    // the color it describes actually changed.
    LaunchedEffect(rgb) {
        val formatted = hexOf(rgb)
        if (!hexText.equals(formatted, ignoreCase = true)) hexText = formatted
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.color_wheel_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 56.dp, height = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, scheme.onSurface.copy(alpha = .12f), RoundedCornerShape(12.dp)),
                    ) {
                        Checkerboard()
                        Box(Modifier.fillMaxSize().background(Color(picked)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.weight(1f).height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.onSurface.copy(alpha = .06f))
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = hexText,
                            onValueChange = { input ->
                                val filtered = input
                                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                                    .take(6)
                                    .uppercase()
                                hexText = filtered
                                if (filtered.length == 6) {
                                    val parsed = filtered.toIntOrNull(16) ?: return@BasicTextField
                                    val hsv = hsvOf(parsed or OPAQUE)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                color = scheme.onSurface,
                                letterSpacing = 1.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // One dropdown instead of three tabs: the row shows which way of picking is
                // active, and tapping it opens the same kind of list the system settings use.
                PickerModeDropdown(mode) { mode = it }
                when (mode) {
                    PickerMode.Grid -> Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HueGrid(hue, saturation, value) { h, s, v ->
                            hue = h
                            saturation = s
                            value = v
                        }
                        FieldLabel(stringResource(R.string.picker_hue))
                        HueSlider(hue, saturation, value) { hue = it }
                    }
                    PickerMode.Slider -> Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FieldLabel(stringResource(R.string.picker_hue))
                        HueSlider(hue, saturation, value) { hue = it }
                        FieldLabel(stringResource(R.string.picker_saturation))
                        SaturationSlider(hue, saturation, value) { saturation = it }
                        FieldLabel(stringResource(R.string.picker_value))
                        ValueSlider2(hue, saturation, value) { value = it }
                    }
                    PickerMode.Monet -> MonetGrid { color ->
                        val hsv = hsvOf(color or OPAQUE)
                        hue = hsv[0]
                        saturation = hsv[1]
                        value = hsv[2]
                    }
                }
                AlphaSlider(picked, alpha) { alpha = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(scheme.onSurface.copy(alpha = .06f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.dialog_cancel), fontSize = 15.sp) }
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(scheme.primary)
                            .clickable { onConfirm(picked) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.dialog_confirm),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** Small secondary caption above a control, so each parameter is labelled once. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun modeLabel(mode: PickerMode): String = stringResource(
    when (mode) {
        PickerMode.Grid -> R.string.picker_grid
        PickerMode.Slider -> R.string.picker_slider
        PickerMode.Monet -> R.string.picker_monet
    },
)

/**
 * The picker's mode row. This is the library's own spinner preference — the same component the
 * system settings use — so the row, the value, the up/down carets and the popup all come from
 * Miuix instead of being drawn here. The window variant is used because this row lives inside a
 * dialog: an overlay popup belongs to the activity window and would end up behind the dialog.
 */
@Composable
private fun PickerModeDropdown(mode: PickerMode, onSelect: (PickerMode) -> Unit) {
    val modes = PickerMode.entries
    WindowSpinnerPreference(
        items = modes.map { DropdownItem(text = modeLabel(it)) },
        selectedIndex = modes.indexOf(mode),
        title = stringResource(R.string.picker_mode),
        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
        onSelectedIndexChange = { onSelect(modes[it]) },
    )
}

/**
 * Twelve hues across, five steps from a washed-out tint down to a deep shade. Touching anywhere
 * inside the grid picks the cell under the finger; dragging moves the selection with it.
 */
@Composable
private fun HueGrid(hue: Float, saturation: Float, value: Float, onPick: (Float, Float, Float) -> Unit) {
    val cellHeight = 24.dp
    Canvas(
        Modifier.fillMaxWidth()
            .height(cellHeight * GRID_ROWS)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                fun update(position: Offset) {
                    val col = (position.x / (size.width.toFloat() / GRID_COLUMNS))
                        .toInt().coerceIn(0, GRID_COLUMNS - 1)
                    val row = (position.y / (size.height.toFloat() / GRID_ROWS))
                        .toInt().coerceIn(0, GRID_ROWS - 1)
                    onPick(col * 360f / GRID_COLUMNS, gridSaturation(row), gridValue(row))
                }
                detectTapGestures { update(it) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val col = (change.position.x / (size.width.toFloat() / GRID_COLUMNS))
                        .toInt().coerceIn(0, GRID_COLUMNS - 1)
                    val row = (change.position.y / (size.height.toFloat() / GRID_ROWS))
                        .toInt().coerceIn(0, GRID_ROWS - 1)
                    onPick(col * 360f / GRID_COLUMNS, gridSaturation(row), gridValue(row))
                }
            },
    ) {
        val cellWidth = size.width / GRID_COLUMNS
        val cellHeightPx = size.height / GRID_ROWS
        // A hair over each cell: the grid is one continuous surface, not separated tiles.
        val bleed = 0.6f
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLUMNS) {
                val color = AndroidColor.HSVToColor(
                    floatArrayOf(col * 360f / GRID_COLUMNS, gridSaturation(row), gridValue(row)),
                )
                drawRect(
                    color = Color(color),
                    topLeft = Offset(col * cellWidth, row * cellHeightPx),
                    size = Size(cellWidth + bleed, cellHeightPx + bleed),
                )
            }
        }
        // Ring on the cell that best matches the current color.
        val col = ((hue + 180f / GRID_COLUMNS) / (360f / GRID_COLUMNS)).toInt() % GRID_COLUMNS
        val row = nearestRow(saturation, value)
        val centre = Offset((col + .5f) * cellWidth, (row + .5f) * cellHeightPx)
        drawCircle(Color.White, cellWidth * .30f, centre, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = .25f), cellWidth * .30f, centre, style = Stroke(width = 4.dp.toPx()))
        drawCircle(Color.White, cellWidth * .30f, centre, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun gridSaturation(row: Int): Float = when (row) {
    0 -> 0.14f
    1 -> 0.42f
    else -> 1f
}

private fun gridValue(row: Int): Float = when (row) {
    0, 1 -> 1f
    2 -> 0.93f
    3 -> 0.62f
    else -> 0.34f
}

private fun nearestRow(saturation: Float, value: Float): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for (row in 0 until GRID_ROWS) {
        val ds = saturation - gridSaturation(row)
        val dv = value - gridValue(row)
        val distance = ds * ds + dv * dv
        if (distance < bestDistance) {
            bestDistance = distance
            best = row
        }
    }
    return best
}

@Composable
private fun HueSlider(hue: Float, saturation: Float, value: Float, onChange: (Float) -> Unit) {
    val stops = remember {
        (0..12).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) }
    }
    SliderTrack(
        stops = stops,
        fraction = hue / 360f,
        thumbColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))),
        onChange = { onChange(it * 360f) },
    )
}

@Composable
private fun SaturationSlider(hue: Float, saturation: Float, value: Float, onChange: (Float) -> Unit) {
    val stops = remember(hue, value) {
        listOf(
            Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, value))),
            Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, value))),
        )
    }
    SliderTrack(stops, saturation, Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))), onChange)
}

@Composable
private fun ValueSlider2(hue: Float, saturation: Float, value: Float, onChange: (Float) -> Unit) {
    val stops = remember(hue, saturation) {
        listOf(
            Color.Black,
            Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f))),
        )
    }
    SliderTrack(stops, value, Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))), onChange)
}

@Composable
private fun AlphaSlider(color: Int, alpha: Float, onChange: (Float) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.picker_alpha), fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
            Text("${(alpha * 100).roundToInt()}%", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
        }
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, scheme.onSurface.copy(alpha = .10f), RoundedCornerShape(14.dp)),
        ) {
            Checkerboard()
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            onChange((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    },
            ) {
                val clear = Color(color and 0x00FFFFFF)
                val solid = Color(color or OPAQUE)
                drawRect(Brush.horizontalGradient(listOf(clear, solid)))
                val x = (alpha.coerceIn(0f, 1f) * size.width)
                    .coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
                drawCircle(Color.White, 8.dp.toPx(), Offset(x, size.height / 2f))
                drawCircle(Color(0x33000000), 8.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SliderTrack(
    stops: List<Color>,
    fraction: Float,
    thumbColor: Color,
    onChange: (Float) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.height / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(stops),
                cornerRadius = CornerRadius(radius, radius),
            )
            val x = (fraction.coerceIn(0f, 1f) * size.width)
                .coerceIn(radius, size.width - radius)
            drawCircle(Color.White, radius - 1.dp.toPx(), Offset(x, radius))
            drawCircle(Color(0x33000000), radius - 1.dp.toPx(), Offset(x, radius),
                style = Stroke(1.dp.toPx()))
            drawCircle(thumbColor, radius - 5.dp.toPx(), Offset(x, radius))
        }
    }
}

/** Palette taken from the wallpaper, refreshed whenever the dialog opens. */
@Composable
private fun MonetGrid(onPick: (Int) -> Unit) {
    val context = LocalContext.current
    var colors by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(Unit) {
        colors = withContext(Dispatchers.IO) { MonetPalette.from(context) }
    }
    if (colors.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.picker_monet_loading), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { color ->
                    Box(
                        Modifier.weight(1f).height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(color))
                            .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = .08f), RoundedCornerShape(10.dp))
                            .clickable { onPick(color) },
                    )
                }
            }
        }
    }
}

/** Small checkerboard behind translucent colors. */
@Composable
private fun Checkerboard() {
    val light = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)
    val dark = MiuixTheme.colorScheme.onSurface.copy(alpha = .14f)
    Canvas(Modifier.fillMaxSize()) {
        drawChecker(light, dark)
    }
}

private fun DrawScope.drawChecker(light: Color, dark: Color) {
    val cell = 6.dp.toPx()
    drawRect(light)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(dark, Offset(x, y), Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private fun hexOf(color: Int): String = String.format(Locale.US, "%06X", color and 0xFFFFFF)

private fun hsvOf(color: Int): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color), hsv,
    )
    return hsv
}

/** Palette extracted from the current system wallpaper, with a neutral fallback. */
internal object MonetPalette {
    private val fallback = listOf(
        0xFF3A6EA5, 0xFF4C8C6A, 0xFF7C5CA8, 0xFFB06A3B,
        0xFF9B4A5E, 0xFF2F7E8C, 0xFF8A6D3B, 0xFF5A6472,
        0xFF6E8C3A, 0xFF8C5C7A, 0xFF3B5B7A, 0xFF7A5A3A,
    ).map { it.toInt() }

    fun from(context: Context, count: Int = 12): List<Int> {
        val sampled = runCatching { sampleWallpaper(context) }.getOrNull()
        if (sampled.isNullOrEmpty()) return fallback.take(count)
        val filled = ArrayList<Int>(count)
        filled.addAll(sampled.take(count))
        while (filled.size < count) filled.add(fallback[filled.size % fallback.size])
        return filled
    }

    private fun sampleWallpaper(context: Context): List<Int>? {
        val manager = WallpaperManager.getInstance(context)
        val drawable = manager.drawable ?: return null
        val side = 48
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, side, side)
        drawable.draw(AndroidCanvas(bitmap))
        val bins = Array(12) { FloatArray(4) }
        val hsv = FloatArray(3)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val pixel = bitmap.getPixel(x, y)
                AndroidColor.colorToHSV(pixel, hsv)
                // Skip near-black and washed-out pixels: they carry no usable hue.
                if (hsv[2] < 0.12f || hsv[1] < 0.10f) continue
                val bin = ((hsv[0] / 30f).toInt()) % 12
                bins[bin][0] += AndroidColor.red(pixel)
                bins[bin][1] += AndroidColor.green(pixel)
                bins[bin][2] += AndroidColor.blue(pixel)
                bins[bin][3] += 1f
            }
        }
        bitmap.recycle()
        val result = ArrayList<Int>(12)
        for (index in 0 until 12) {
            val bin = bins[index]
            if (bin[3] < 1f) continue
            val average = AndroidColor.rgb(
                (bin[0] / bin[3]).roundToInt(),
                (bin[1] / bin[3]).roundToInt(),
                (bin[2] / bin[3]).roundToInt(),
            )
            AndroidColor.colorToHSV(average, hsv)
            // Normalize the tone so a wallpaper color is usable as a flat surround.
            val saturation = hsv[1].coerceIn(0.35f, 0.85f)
            val value = hsv[2].coerceIn(0.42f, 0.88f)
            result.add(AndroidColor.HSVToColor(floatArrayOf(hsv[0], saturation, value)))
        }
        return if (result.isEmpty()) null else result
    }
}
