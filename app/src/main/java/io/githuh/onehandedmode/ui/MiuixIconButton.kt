package io.githuh.onehandedmode.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.compose.ui.graphics.vector.ImageVector

/** Which drawing the bubble shows. */
internal enum class BubbleGlyph { More, Back, Exit }

/**
 * One 35 dp circular icon button, drawn by a plain [View].
 *
 * It used to be a Compose `IconButton` in its own window. Compose needs a composition pass
 * before anything appears, and the button windows are created while the entry animation is
 * running, so the very first frame after landing was often empty — the "slow bubble" the user
 * saw. A canvas paints on the first frame, with no composition, no lifecycle owner and no
 * snapshot state involved.
 *
 * The three glyphs are drawn rather than loaded from the icon pack, because each of them carries
 * meaning the stock icons do not:
 *
 * - **More** is a horizontal ellipsis that turns 90° clockwise into a vertical one when the
 *   panel opens, so the button itself shows the state;
 * - **Exit** is the share glyph turned onto its side — a box with an arrow leaving it through a
 *   gap in one side, the arrow pointing away from the scaled screen;
 * - **Back** is a single obtuse angle, the shape the system uses for its gesture hint.
 */
internal class MiuixIconButton(
    context: Context,
    glyph: BubbleGlyph?,
    description: String,
    background: Int,
    /** -1 when the content is on the right, so an exit arrow points left; +1 otherwise. */
    outward: Float,
    /** Icon-pack vector for glyphs that do not need a custom shape (settings). */
    vector: ImageVector? = null,
    onClick: () -> Unit,
) {
    val view: GlyphView = GlyphView(context, glyph, description, background, outward, vector, onClick)

    /** Nothing to warm up any more; kept so the window code does not have to care. */
    fun start() = Unit

    fun dispose() = Unit

    /** Rotates the ellipsis a quarter turn; ignored by the other glyphs. */
    fun setRotated(rotated: Boolean) = view.setRotated(rotated)

    /** Flips an exit arrow when the one-handed content moves to the other side. */
    fun setOutward(value: Float) = view.setOutward(value)

    internal class GlyphView(
        context: Context,
        private val glyph: BubbleGlyph?,
        description: String,
        background: Int,
        outward: Float,
        vector: ImageVector?,
        onClick: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = background
            style = Paint.Style.FILL
        }
        // Same contrast rule Miuix applies to content on `secondaryContainer`.
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (luminance(background) > 0.55f) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconPaint.color
            style = Paint.Style.STROKE
            strokeWidth = 1.9f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val iconSize = 22f * density
        private val dotRadius = 1.9f * density
        private var direction = if (outward < 0f) -1f else 1f
        private var rotation = 0f
        private var rotationAnimator: ValueAnimator? = null
        private val glyphPath = Path()
        private val vectorGeometry = vector?.toIconGeometry()

        init {
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        fun setRotated(rotated: Boolean) {
            val target = if (rotated) 90f else 0f
            if (target == rotation) return
            rotationAnimator?.cancel()
            rotationAnimator = ValueAnimator.ofFloat(rotation, target).apply {
                duration = 220L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    rotation = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun setOutward(value: Float) {
            val next = if (value < 0f) -1f else 1f
            if (next == direction) return
            direction = next
            invalidate()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // A short press scale instead of Miuix's spring: drawing it here keeps the view's
            // alpha animation (used to fade the button in and out) untouched.
            val scale = if (isPressed) 0.92f else 1f
            canvas.save()
            if (scale != 1f) canvas.scale(scale, scale, w / 2f, h / 2f)
            canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, fillPaint)
            val cx = w / 2f
            val cy = h / 2f
            val packed = vectorGeometry
            if (packed != null) {
                canvas.save()
                canvas.translate(cx - iconSize / 2f, cy - iconSize / 2f)
                canvas.scale(iconSize / packed.viewportWidth, iconSize / packed.viewportHeight)
                for (path in packed.paths) canvas.drawPath(path, iconPaint)
                canvas.restore()
            } else {
                when (glyph) {
                    BubbleGlyph.More -> drawMore(canvas, cx, cy)
                    BubbleGlyph.Back -> drawBack(canvas, cx, cy)
                    BubbleGlyph.Exit -> drawExit(canvas, cx, cy)
                    null -> Unit
                }
            }
            canvas.restore()
        }

        private fun drawMore(canvas: Canvas, cx: Float, cy: Float) {
            val step = iconSize / 4.2f
            canvas.save()
            canvas.rotate(rotation, cx, cy)
            for (index in -1..1) {
                canvas.drawCircle(cx + index * step, cy, dotRadius, iconPaint)
            }
            canvas.restore()
        }

        /**
         * One obtuse angle, opening to the right, like the system's back-gesture hint: the arms
         * run less far than they spread, so the corner at the tip is wider than a right angle.
         */
        private fun drawBack(canvas: Canvas, cx: Float, cy: Float) {
            val half = iconSize * 0.28f
            val run = iconSize * 0.22f
            val tipX = cx - iconSize * 0.20f
            glyphPath.reset()
            glyphPath.moveTo(tipX + run, cy - half)
            glyphPath.lineTo(tipX, cy)
            glyphPath.lineTo(tipX + run, cy + half)
            canvas.drawPath(glyphPath, strokePaint)
        }

        /**
         * The share glyph rotated onto its side: an open box with an arrow leaving it through the
         * gap, pointing away from the scaled screen.
         */
        private fun drawExit(canvas: Canvas, cx: Float, cy: Float) {
            val half = iconSize * 0.25f
            val gap = iconSize * 0.21f
            val corner = iconSize * 0.09f
            val nearX = cx + direction * half
            val farX = cx - direction * half
            val top = cy - half
            val bottom = cy + half
            val inward = -direction * corner

            glyphPath.reset()
            glyphPath.moveTo(nearX, cy - gap)
            glyphPath.lineTo(nearX, top + corner)
            glyphPath.quadTo(nearX, top, nearX + inward, top)
            glyphPath.lineTo(farX - inward, top)
            glyphPath.quadTo(farX, top, farX, top + corner)
            glyphPath.lineTo(farX, bottom - corner)
            glyphPath.quadTo(farX, bottom, farX - inward, bottom)
            glyphPath.lineTo(nearX + inward, bottom)
            glyphPath.quadTo(nearX, bottom, nearX, bottom - corner)
            glyphPath.lineTo(nearX, cy + gap)
            canvas.drawPath(glyphPath, strokePaint)

            val tipX = cx + direction * (iconSize * 0.47f)
            val shaftStart = cx - direction * (iconSize * 0.04f)
            val head = iconSize * 0.13f
            glyphPath.reset()
            glyphPath.moveTo(shaftStart, cy)
            glyphPath.lineTo(tipX, cy)
            glyphPath.moveTo(tipX - direction * head, cy - head)
            glyphPath.lineTo(tipX, cy)
            glyphPath.lineTo(tipX - direction * head, cy + head)
            canvas.drawPath(glyphPath, strokePaint)
        }
    }
}

/** Perceived brightness, used to pick a readable icon color on the button fill. */
private fun luminance(color: Int): Float {
    val r = (color shr 16 and 0xFF) / 255f
    val g = (color shr 8 and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b
}
