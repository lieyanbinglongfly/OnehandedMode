package io.githuh.onehandedmode.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Turns a picture the user picked into something the module can hand to SystemUI.
 *
 * Remote preferences cross a binder transaction, so the picture travels as a base64 JPEG capped
 * at [MAX_WIDTH]. The blur runs on a reduced copy and is scaled back up: a box blur at 1/3 of the
 * linear size is both far cheaper and visually indistinguishable once it is stretched again.
 */
internal object BackgroundBitmap {
    private const val MAX_WIDTH = 720
    private const val QUALITY = 72
    private const val BLUR_WIDTH = 360

    fun encode(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val sample = max(1, bounds.outWidth / MAX_WIDTH)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val scaled = if (decoded.width > MAX_WIDTH) {
            Bitmap.createScaledBitmap(
                decoded, MAX_WIDTH, (decoded.height * MAX_WIDTH.toFloat() / decoded.width).toInt(), true,
            )
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    fun decode(encoded: String): Bitmap? = runCatching {
        if (encoded.isEmpty()) return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }.getOrNull()

    /**
     * Center-crops [source] to the display aspect ratio and scales it down to [maxWidth].
     *
     * The mask window stretches the bitmap back to the full display through a BitmapShader, so a
     * small result is enough; keeping it small is what makes the blur cheap.
     */
    fun fit(source: Bitmap, targetWidth: Int, targetHeight: Int, maxWidth: Int): Bitmap {
        val scale = max(targetWidth / source.width.toFloat(), targetHeight / source.height.toFloat())
        val sourceWidth = (targetWidth / scale).toInt().coerceIn(1, source.width)
        val sourceHeight = (targetHeight / scale).toInt().coerceIn(1, source.height)
        val x = ((source.width - sourceWidth) / 2).coerceAtLeast(0)
        val y = ((source.height - sourceHeight) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(source, x, y, sourceWidth, sourceHeight)
        val outWidth = maxWidth.coerceAtMost(targetWidth).coerceAtLeast(1)
        val outHeight = (outWidth * targetHeight.toFloat() / targetWidth).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /**
     * [percent] 0 returns the source; 100 is a heavy, Recents-like material blur. The result stays
     * at [BLUR_WIDTH]: it is already soft, so the mask can stretch it to the display without
     * anyone noticing, and the module keeps ~1/9 of the pixels in memory.
     */
    fun materialBlur(source: Bitmap, percent: Int): Bitmap {
        if (percent <= 0) return source
        val width = BLUR_WIDTH
        val height = (source.height * width.toFloat() / source.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val radius = (percent / 100f * 20f).toInt().coerceAtLeast(1)
        val blurred = boxBlur(small, radius)
        if (blurred !== small) small.recycle()
        return blurred
    }

    /** Three box passes approximate a Gaussian closely enough for a background wash. */
    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val channels = IntArray(width * height)
        for (pass in 0 until 3) {
            for (shift in intArrayOf(16, 8, 0)) {
                for (i in pixels.indices) channels[i] = (pixels[i] ushr shift) and 0xFF
                blurPass(channels, width, height, radius, horizontal = true)
                blurPass(channels, width, height, radius, horizontal = false)
                for (i in pixels.indices) {
                    val value = channels[i] shl shift
                    pixels[i] = (pixels[i] and (0xFF shl shift).inv()) or value
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(data: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val window = radius * 2 + 1
        val temp = IntArray(length)
        for (line in 0 until lines) {
            var sum = 0
            for (i in -radius..radius) {
                val index = i.coerceIn(0, length - 1)
                sum += data[if (horizontal) line * width + index else index * width + line]
            }
            for (i in 0 until length) {
                temp[i] = sum / window
                val out = (i - radius).coerceIn(0, length - 1)
                val into = (i + radius + 1).coerceIn(0, length - 1)
                sum += data[if (horizontal) line * width + into else into * width + line]
                sum -= data[if (horizontal) line * width + out else out * width + line]
            }
            for (i in 0 until length) {
                if (horizontal) data[line * width + i] = temp[i] else data[i * width + line] = temp[i]
            }
        }
    }
}
