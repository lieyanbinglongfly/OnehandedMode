package io.githuh.onehandedmode.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.doOnNextLayout
import io.githuh.onehandedmode.R
import io.githuh.onehandedmode.ui.BubbleGlyph
import io.githuh.onehandedmode.ui.MiuixIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Which dashed outlines the mask paints. */
private const val HINT_SIDE = 1
private const val HINT_TOP = 2

/**
 * One full-screen mask plus two transparent touch strips.
 *
 * The mask window is sized to the display once and never touched again: it paints an L-shaped
 * path (display minus the scaled content), so following a drag is a plain repaint. That removes
 * the per-frame WindowManager relayouts, Surface buffer resizes and Compose re-measurements that
 * the previous three-window layout did on every drag frame.
 *
 * The touch strips follow the gap, but only while idle. Once ACTION_DOWN is delivered a strip
 * owns the input stream, so re-laying it out mid-drag buys nothing; they are re-synced once when
 * the finger lifts.
 */
internal class HandySurround(
    context: Context,
    private val shellHandler: Handler,
    private val onExit: () -> Unit,
    private val onSideTap: () -> Unit,
    private val onSwipeStart: () -> Unit,
    private val onSwipe: (Float) -> Unit,
    private val onSwipeEnd: () -> Unit,
    private val onSettings: () -> Unit,
    private val onBack: () -> Unit,
) {
    private val uiHandler = Handler(context.mainLooper)
    private val windowContext = context.createWindowContext(2024, null)
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val resources = context.createPackageContext("io.githuh.onehandedmode", 0).resources
    private val density = context.resources.displayMetrics.density
    private val buttonSize = (35f * density).roundToInt()
    private val edge = 20f * density
    // The button belongs to the bottom of the strip now, so it has to clear the gesture area
    // instead of sharing the vertical centre with the content.
    private val navigationBarHeight = runCatching {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id != 0) context.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)
    private val bottomMargin = navigationBarHeight.coerceAtLeast((28f * density).roundToInt()) +
        (24f * density).roundToInt()
    // A drag has to clear this before it stops being a tap on the side strip.
    private val swipeSlop = 16f * density
    private var prepared = false
    private var showing = false
    // Mask and touch strips live in windows of their own; they are added with the entry
    // animation so the button windows can be stacked above them without a re-add.
    private var windowsAttached = false
    private var right = true
    // Horizontal progress of the whole overlay: 1 = anchored right, 0 = anchored left. Switching
    // sides animates this instead of exiting and re-entering, so everything that follows the
    // content — mask path, bubble, button column — has to be a function of it, not of `right`.
    private var side = 1f
    private var width = 0
    private var height = 0
    private var gapWidth = 0f
    private var gapHeight = 0f
    // Physical display corner radius, in unscaled pixels. The content surface is rounded with the
    // same value, so the mask has to cut its own hole with a corner that scales alongside it —
    // otherwise the four corners of a wallpaper or picture show a hard square edge.
    private var cornerRadius = 0f
    private var entryFinished = false
    // Buttons are revealed once per entry, a little before the framework's entry animation lands.
    private var buttonsRevealed = false
    // Every button is its own fixed-size window. They used to live inside the side strip, so a
    // drag end resized that strip and Compose re-measured the whole button: a visible twitch
    // right after the gesture stopped. A window whose size never changes and that only ever
    // moves keeps the composition untouched.
    //
    // Stacked from the bottom: more, then the panel (settings, exit) when open, and back on top.
    // Back is always there and is pushed upwards while the panel is open.
    private val buttonGap = (10f * density).roundToInt()
    private val moreButton = ButtonSlot(
        "MIUI one-handed more", BubbleGlyph.More, R.string.button_more,
    ) { togglePanel() }
    private val backButton = ButtonSlot(
        "MIUI one-handed back", BubbleGlyph.Back, R.string.button_back,
    ) { shellHandler.post { onBack() } }
    private val settingsButton = ButtonSlot(
        "MIUI one-handed settings", null, R.string.settings,
        vector = MiuixIcons.Settings,
    ) { shellHandler.post { onSettings() } }
    private val exitButton = ButtonSlot(
        "MIUI one-handed exit", BubbleGlyph.Exit, R.string.button_exit,
    ) { shellHandler.post { onExit() } }
    private val slots = arrayOf(moreButton, backButton, settingsButton, exitButton)
    private var panelOpen = false
    // Newest side frame, coalesced while one is already queued; see updateSide().
    private var sidePending = false
    private var sideNextProgress = 1f
    private var sideNextFraction = 0f
    private var backAnimator: ValueAnimator? = null
    // One animator for the whole bubble column while it lands on a new gap; see landColumn().
    private var landAnimator: ValueAnimator? = null
    /**
     * True while a programmatic scale change is playing.
     *
     * The bubbles are held at their old x for its duration so that all four can then travel to
     * the new line on one clock. Letting the two permanent ones follow every frame and moving
     * the panel ones afterwards is what made the group look out of step.
     */
    private var columnFrozen = false
    private var scaleLabel: TextView? = null
    // The drag hint: a dashed outline of the swipe area plus a label. Both are painted by the
    // mask, so following a drag costs no window traffic at all. Resolved once here rather than
    // lazily, so the first drag frame does not allocate a colour scheme inside onDraw.
    private val buttonColor: Int = resolveButtonColor()
    /**
     * Colour of the dashes and their label. On a light surround the button colour is almost
     * invisible, so it flips to near-black as soon as the mask is bright enough for that.
     */
    private var hintColor: Int = buttonColor
    // The side strip's label depends on which gestures actually do something there, and changes
    // once during the entry hint: tap first, drag second.
    private val sideTapText: String =
        runCatching { resources.getString(R.string.side_tap_hint) }.getOrDefault("")
    private val sideDragText: String =
        runCatching { resources.getString(R.string.swipe_hint) }.getOrDefault("")
    private val sideExitText: String =
        runCatching { resources.getString(R.string.side_exit_hint) }.getOrDefault("")
    private var sideHintText: String = sideDragText
    private val topHintText: String =
        runCatching { resources.getString(R.string.tap_top_hint) }.getOrDefault("")
    /** Whether a vertical drag resizes at all; set from the settings on every entry. */
    private var sideDragEnabled = true
    /** Whether a tap on the side strip switches sides; also set on every entry. */
    private var sideTapEnabled = true
    /**
     * Whether the entry hint — the two dashed outlines and their labels — is painted at all.
     * The drag outline is not part of this: it belongs to the gesture, not to the introduction.
     */
    private var hintsEnabled = true
    private var hintAnimator: ValueAnimator? = null
    // Fades the label out, swaps the text, fades it back in. The outline is untouched.
    private var hintTextAnimator: ValueAnimator? = null
    // The entry hint shows both outlines for a couple of seconds, then fades on its own. A drag
    // during that window takes the hint over as the side-only outline.
    private var entryHintActive = false
    private var entryHintSwapTasks: List<Runnable> = emptyList()
    private var entryHintTask: Runnable? = null

    private val maskView = MaskView(windowContext)
    // The top strip keeps the classic "tap outside to exit"; the side strip taps to switch and
    // drags vertically to resize. Both run on the main thread, so the drag callbacks are direct.
    private val topTouch = TouchView(onTapOutside = { shellHandler.post { onExit() } })
    private val sideTouch = TouchView(
        onTapOutside = { shellHandler.post { onSideTap() } },
        onSwipeStart = onSwipeStart,
        onSwipe = onSwipe,
        onSwipeEnd = onSwipeEnd,
    )
    private val maskParams = params("MIUI one-handed mask").apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
    private val topParams = params("MIUI one-handed top")
    private val sideParams = params("MIUI one-handed side")

    fun show(
        screen: ScreenSpec,
        scale: Float,
        anchorRight: Boolean,
        color: Int,
        corner: Float = 0f,
        sideDrag: Boolean = true,
        sideTap: Boolean = true,
        hints: Boolean = true,
    ) {
        runOnUi {
            sideDragEnabled = sideDrag
            sideTapEnabled = sideTap
            hintsEnabled = hints
            sideHintText = sideHintText()
            maskView.setTextAlpha(1f)
            width = screen.width
            height = screen.height
            right = anchorRight
            side = if (anchorRight) 1f else 0f
            cornerRadius = corner
            updateGeometry(scale)
            maskView.alpha = 0f
            maskView.setColor(color, force = true)
            if (!maskView.pictureShown) updateHintColor(color)
            maskView.refresh()
            positionScaleLabel()
            maskParams.width = width
            maskParams.height = height
            applyTouchGeometry()
            entryFinished = false
            prepared = true
            // Stacking order matters: mask first, then the strips, then the buttons on top.
            if (!windowsAttached) {
                windowManager.addView(maskView, maskParams)
                topParams.flags = topParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                sideParams.flags = sideParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.addView(topTouch, topParams)
                windowManager.addView(sideTouch, sideParams)
                windowsAttached = true
            }
            // Build and mount the column now, while the framework is still running its 200 ms
            // entry animation, then fade it in near the end of that window. Waiting for the
            // landing made the buttons feel late, and appearing in one step made it look like a
            // glitch rather than a reveal.
            buttonsRevealed = false
            cancelEntryHint()
            // A quick re-entry can start before the previous mask was torn down; start clean.
            maskView.setHintRegions(0)
            maskView.setHintAlpha(0f, false)
            prepareColumn()
            uiHandler.postDelayed({ revealButtons() }, REVEAL_DELAY_MS)
            // During entry the synchronized color layer fills the exposed display. No window
            // relayouts, buffer resizing or Compose creation are scheduled from animation frames.
        }
    }

    fun onEntryFinished() {
        runOnUi {
            if (!prepared || showing) return@runOnUi
            prepared = false
            panelOpen = false
            // A wallpaper or picture replaces the flat color here; fade it in so the switch from
            // the entry color layer is not a hard cut.
            maskView.animate().cancel()
            maskView.animate().alpha(1f).setDuration(MASK_FADE_MS).start()
            topParams.flags = topParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            sideParams.flags = sideParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(topTouch, topParams)
            windowManager.updateViewLayout(sideTouch, sideParams)
            showing = true
            entryFinished = true
            layoutColumn(animate = false)
            revealButtons()
            // Next frame, not after the mask's own fade: the rings paint in the gap, never over
            // the picture, so there is nothing to flicker — and waiting read as lag. Posting it
            // keeps that out of the transition callback's own critical section.
            uiHandler.post { showEntryHint() }
        }
    }

    /**
     * Fades the two permanent buttons in with a short scale-up. Idempotent: both the scheduled
     * call during entry and the transition callback can reach it, whichever comes first wins.
     */
    private fun revealButtons() {
        // The scheduled call may arrive after the exit has already started.
        if (buttonsRevealed || !windowsAttached || (!prepared && !showing)) return
        buttonsRevealed = true
        moreButton.reveal(0L)
        backButton.reveal(REVEAL_STAGGER_MS)
    }

    fun onExitStarted() {
        runOnUi {
            if (!showing) return@runOnUi
            entryFinished = false
            panelOpen = false
            moreButton.setRotated(false)
            backAnimator?.cancel()
            maskView.animate().cancel()
            maskView.alpha = 0f
            // Keep only the fading buttons above the growing content. The background surface
            // fills the surround, and the windows stop intercepting touches immediately.
            topParams.flags = topParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            sideParams.flags = sideParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(topTouch, topParams)
            windowManager.updateViewLayout(sideTouch, sideParams)
            for (slot in slots) if (slot !== moreButton) slot.fadeOut(100L)
            moreButton.fadeOut(100L) { detachWindows() }
        }
    }

    fun hide() {
        runOnUi {
            prepared = false
            buttonsRevealed = false
            cancelEntryHint()
            if (showing || windowsAttached || slots.any { it.attached }) detachWindows()
        }
    }

    /** Builds the column once and parks it on screen, invisible and untouchable. */
    private fun prepareColumn() {
        for (slot in slots) slot.build()
        moreButton.setRotated(false)
        for (slot in slots) slot.preAttach()
        layoutColumn(animate = false)
    }

    /** Paints (or clears) the wallpaper/picture behind the surround. */
    fun setPicture(picture: Bitmap?) {
        runOnUi {
            maskView.setPicture(picture)
            // A picture has no single color to test, so the hint takes its contrast from a coarse
            // average of the image instead.
            if (picture != null) updateHintColor(averageColor(picture))
        }
    }

    /** Mean color of a bitmap, sampled on a grid — enough to tell a light image from a dark one. */
    private fun averageColor(bitmap: Bitmap): Int {
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 0xFF808080.toInt()
        return (0xFF shl 24) or
            ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }

    /** Applies a new surround color without leaving one-handed mode. */
    fun recolor(color: Int) {
        runOnUi {
            // During entry the mask is still transparent, so recoloring it is free and keeps the
            // first visible frame correct when the color changes mid-animation.
            if (!showing && !prepared) return@runOnUi
            maskView.setColor(color)
            if (maskView.pictureShown.not()) updateHintColor(color)
        }
    }

    /**
     * Follows a live drag. The mask path changes, the bubble moves, and the button column slides
     * along with the widening or narrowing gap so it never ends up on top of the content.
     * Only the two permanent buttons move: a hidden panel button is placed when it opens.
     */
    fun updateScale(scale: Float) {
        runOnUi {
            if (!showing) return@runOnUi
            updateGeometry(scale)
            maskView.refresh()
            // Moving the bubble is a translation change on an already laid out view.
            positionScaleLabel()
            if (!columnFrozen) followGap()
        }
    }

    /**
     * Follows the switch-side animation. Only draw-time geometry changes: the mask rebuilds its
     * path, the bubble and the button column slide along, and nothing is re-laid out or resized.
     * The touch strips stay where they are until the animation lands — the finger that started
     * the switch has already lifted, and moving them every frame would mean two window relayouts
     * per frame for no gain.
     */
    /**
     * One frame of the slide to the other side.
     *
     * [fraction] is the animator's progress from 0 to 1, not the position: the panel pair fades
     * and the back bubble closes the gap the panel had opened over exactly the same curve the
     * content travels on, so the whole group reads as one movement.
     *
     * With the panel closed the column simply follows the centre line. With it open the four
     * bubbles are placed here instead — following and fading in two separate clocks is what made
     * the panel pair vanish on the first frame while the other two moved on alone.
     */
    fun updateSide(progress: Float, fraction: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applySideFrame(progress, fraction)
            return
        }
        // Called from the animator's thread once per display frame. If the UI thread is behind,
        // posting every one of them queues stale frames: the mask and the bubbles then run the
        // whole animation late, in a burst. Keep only the newest values and one pending post.
        sideNextProgress = progress
        sideNextFraction = fraction
        if (sidePending) return
        sidePending = true
        uiHandler.post {
            sidePending = false
            applySideFrame(sideNextProgress, sideNextFraction)
        }
    }

    private fun applySideFrame(progress: Float, fraction: Float) {
        if (!showing) return
        side = progress.coerceIn(0f, 1f)
        maskView.refresh()
        positionScaleLabel()
        if (panelOpen) slideColumnWithPanel(fraction) else followGap()
    }

    /**
     * Places all four bubbles for one frame of the slide, with the panel's pair leaving.
     *
     * The back bubble sits three steps up while the panel is open; here it walks back down to the
     * single-step slot as the pair fades, instead of snapping when the slide lands.
     */
    private fun slideColumnWithPanel(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val x = columnX()
        val step = columnStep()
        val bottom = columnBottom()
        moreButton.glide(x, bottom, 1f, 1f)
        backButton.glide(x, bottom - step * (3f - 2f * f).roundToInt(), 1f, 1f)
        settingsButton.glide(x, bottom - step, 1f - f, 1f)
        exitButton.glide(x, bottom - step * 2, 1f - f, 1f)
    }

    /** Lands on the new side: flips the flag the touch strips use and re-syncs their geometry. */
    fun settleSide(anchorRight: Boolean) {
        runOnUi {
            // Never re-enable the touch strips while the exit fade is running.
            if (!showing || !entryFinished) return@runOnUi
            right = anchorRight
            side = if (anchorRight) 1f else 0f
            if (panelOpen) {
                // The slide carried the pair out of the way; land it, do not re-place it.
                panelOpen = false
                moreButton.setRotated(false)
                settingsButton.hide()
                exitButton.hide()
            }
            exitButton.setOutward(if (anchorRight) -1f else 1f)
            maskView.refresh()
            positionScaleLabel()
            applyTouchGeometry()
            windowManager.updateViewLayout(topTouch, topParams)
            windowManager.updateViewLayout(sideTouch, sideParams)
            layoutColumn(animate = false)
        }
    }

    /**
     * Lands on a new scale set from the settings app.
     *
     * Unlike a drag, nothing here knows when the value stops changing, so this runs once when the
     * animator finishes: the mask, the bubble and the column have already followed every frame,
     * but the two touch strips are windows and still sit on the old gap. Leaving them there is
     * what made half of the side strip unresponsive after a scale change — the strip still
     * covered the gap the previous scale had, and the part over the new gap answered nothing.
     *
     * The panel is closed on the way: it is laid out for the gap it was opened in, and its two
     * buttons are only placed when it opens or closes.
     */
    fun settleScale() {
        runOnUi {
            if (!showing || !entryFinished) {
                columnFrozen = false
                return@runOnUi
            }
            columnFrozen = false
            // Geometry first: the column's new centre line is computed from gapWidth, which the
            // scale animation has just finished writing.
            applyTouchGeometry()
            windowManager.updateViewLayout(topTouch, topParams)
            windowManager.updateViewLayout(sideTouch, sideParams)
            landColumn()
        }
    }

    /** Restores the touch strips to the final gap once the finger lifts. */
    fun endSwipe() {
        runOnUi {
            if (!showing) return@runOnUi
            applyTouchGeometry()
            windowManager.updateViewLayout(topTouch, topParams)
            windowManager.updateViewLayout(sideTouch, sideParams)
            landColumn(SWIPE_LAND_MS)
        }
    }

    /** Collapses the panel, e.g. when a resize drag starts on the strip. */
    fun closePanel() {
        runOnUi { landColumn() }
    }

    /**
     * Holds the bubble column still while a scale animation plays.
     *
     * The mask and the scale bubble follow every frame either way; only the four button windows
     * are held, so that [landColumn] can move all of them from the old line to the new one in a
     * single pass instead of letting two of them arrive early.
     */
    fun freezeColumn(frozen: Boolean) {
        columnFrozen = frozen
    }

    /** Drops the panel where it stands, for callers that are already moving the column. */
    private fun dropPanel() {
        if (!panelOpen) return
        panelOpen = false
        moreButton.setRotated(false)
        settingsButton.hide()
        exitButton.hide()
    }

    /**
     * Lands the column on the current gap in a single movement.
     *
     * A scale change moves the centre line and the pitch, so every slot has somewhere new to go,
     * and an open panel has to leave. Running those as three separate things — snap the two
     * permanent bubbles, fade the two panel bubbles on their own clock, then slide the back
     * button — is what made the group look out of step: the horizontal travel of one pair ended
     * long before the other pair had gone anywhere.
     *
     * One animator drives all four windows, so every horizontal move starts and stops on the same
     * frame, and the panel bubbles travel to the new line while they fade instead of vanishing
     * where the old line used to be.
     */
    private fun landColumn(duration: Long = COLUMN_LAND_MS, fromOpen: Boolean = panelOpen) {
        if (!moreButton.built) return
        // The caller decides: togglePanel() has already flipped panelOpen by the time it calls
        // here, and reading the flag then said "nothing was open" — the settings and exit bubbles
        // stayed on screen in the middle of the column.
        val wasOpen = fromOpen
        panelOpen = false
        moreButton.setRotated(false)

        val x = columnX()
        val step = columnStep()
        val bottom = columnBottom()

        val targets = arrayListOf(
            LandTarget(moreButton, x, bottom, leaves = false),
            LandTarget(backButton, x, bottom - step, leaves = false),
        )
        if (wasOpen) {
            // Out of the touch path at once: only the pixels are still on their way.
            settingsButton.configure(settingsButton.x, settingsButton.y, false)
            exitButton.configure(exitButton.x, exitButton.y, false)
            targets += LandTarget(settingsButton, x, bottom - step, leaves = true)
            targets += LandTarget(exitButton, x, bottom - step * 2, leaves = true)
        }

        backAnimator?.cancel()
        backAnimator = null
        landAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                for (target in targets) target.apply(fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (landAnimator !== animation) return
                    landAnimator = null
                    for (target in targets) target.finish()
                }
            })
            start()
        }
        landAnimator = animator
    }

    /**
     * One slot's part in [landColumn]: where it started, where it ends, and whether it leaves.
     *
     * Reading the start values once, before the animator runs, is what keeps the four windows on
     * the same curve — a slot that re-read its own position every frame would drift behind the
     * others by however long its own relayout took.
     */
    private inner class LandTarget(
        val slot: ButtonSlot,
        val toX: Int,
        val toY: Int,
        val leaves: Boolean,
    ) {
        private val fromX = slot.x
        private val fromY = slot.y
        private val fromAlpha = slot.alpha
        private val fromScale = slot.scale

        fun apply(fraction: Float) {
            val x = (fromX + (toX - fromX) * fraction).roundToInt()
            val y = (fromY + (toY - fromY) * fraction).roundToInt()
            val fade = if (leaves) 1f - fraction else 1f
            val scale = if (leaves) fromScale + (1f - fromScale) * fraction else 1f
            slot.glide(x, y, fromAlpha * fade, scale)
        }

        fun finish() {
            if (leaves) slot.hide() else slot.glide(toX, toY, 1f, 1f)
        }
    }

    /** Opens or closes the settings/exit pair; back is pushed up while it is open. */
    private fun togglePanel() {
        runOnUi {
            if (!showing || !entryFinished) return@runOnUi
            landAnimator?.cancel()
            landAnimator = null
            panelOpen = !panelOpen
            moreButton.setRotated(panelOpen)
            if (panelOpen) {
                // Position first, fade second: on the very first open the two windows are still
                // parked at the origin, and fading before moving would flash them in the corner.
                layoutColumn(animate = true)
                settingsButton.show(150L)
                exitButton.show(150L)
            } else {
                // The same landing the automatic path uses, so a tap on the more button and a
                // scale change look like the same gesture. fromOpen is passed explicitly: the
                // flip above has already cleared panelOpen, and landColumn() reads it to decide
                // whether the two panel bubbles still have to leave.
                landColumn(fromOpen = true)
            }
        }
    }

    /** Places the whole column: x on the strip's centre line, y stacked up from the bottom. */
    private fun layoutColumn(animate: Boolean) {
        if (!moreButton.built) return
        val x = columnX()
        val step = columnStep()
        val bottom = columnBottom()
        moreButton.configure(x, bottom, true)
        val backY = if (panelOpen) bottom - step * 3 else bottom - step
        if (animate) {
            animateBack(x, backY)
        } else {
            backAnimator?.cancel()
            backButton.configure(x, backY, true)
        }
        if (panelOpen) {
            settingsButton.configure(x, bottom - step, true)
            exitButton.configure(x, bottom - step * 2, true)
        }
    }

    /** Horizontal moves only, for the two buttons that are always on screen. */
    private fun followGap() {
        if (!moreButton.built) return
        // A drag owns the geometry from the first frame; a landing still in flight would be
        // writing the same two windows from a stale target on every frame.
        if (landAnimator != null) {
            landAnimator?.cancel()
            landAnimator = null
            dropPanel()
        }
        val x = columnX()
        moreButton.moveTo(x)
        backButton.moveTo(x)
    }

    /** Slides the back button to its new slot; one window animates, nothing else moves. */
    private fun animateBack(x: Int, targetY: Int) {
        backButton.configure(x, backButton.y, true)
        backAnimator?.cancel()
        val from = backButton.y
        if (from == targetY) return
        backAnimator = ValueAnimator.ofInt(from, targetY).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener { backButton.moveTo(backButton.x, it.animatedValue as Int) }
            start()
        }
    }

    /**
     * Centre line of the side strip, the column's shared x. The strip sits on the opposite side
     * of the content, so progress 1 (anchored right) means the strip is at the left edge.
     */
    private fun columnX(): Int {
        val centerX = gapWidth / 2f + (1f - side) * (width - gapWidth)
        return (centerX - buttonSize / 2f).roundToInt()
    }

    /**
     * Vertical pitch of the column. An open panel needs three steps; when the strip is shorter
     * than that the gap gives way first, so the group never runs off the top of the content.
     */
    private fun columnStep(): Int {
        val bottom = height - bottomMargin - buttonSize
        val top = gapHeight.roundToInt() + (4f * density).roundToInt()
        val room = (bottom - top).coerceAtLeast(0)
        val gap = ((room - 3 * buttonSize) / 3).coerceIn((2f * density).roundToInt(), buttonGap)
        return buttonSize + gap
    }

    /**
     * Bottom of the column: pinned above the gesture area. At an extreme scale the four-button
     * stack can be taller than the strip; it then floats over the content instead of being
     * pushed down over the navigation area, where it would swallow the back gesture.
     */
    private fun columnBottom(): Int = height - bottomMargin - buttonSize

    /** Shows the live scale while the side strip is being dragged. */
    fun showScale(percent: Int) {
        runOnUi {
            if (!showing) return@runOnUi
            val label = scaleLabel ?: createScaleLabel()
            val text = "$percent%"
            if (label.text.toString() != text) label.text = text
            // Animate in once per drag; re-issuing it on every frame only churns the animator.
            if (label.alpha < 1f) {
                label.animate().cancel()
                label.animate().alpha(1f).setDuration(90L).start()
            }
        }
    }

    fun hideScale() {
        runOnUi {
            val label = scaleLabel ?: return@runOnUi
            if (label.alpha == 0f) return@runOnUi
            label.animate().cancel()
            label.animate().alpha(0f).setDuration(160L).start()
        }
    }

    /**
     * Outlines both strips that take a tap or a drag for a couple of seconds after the entry.
     * The top one exits, the side one switches sides or resizes.
     */
    /**
     * What the side strip's label says right now. With both side gestures off the strip does
     * nothing at all, so the label explains the only thing left to do there: tap to exit.
     */
    private fun sideHintText(): String = when {
        sideTapEnabled && sideDragEnabled -> sideDragText
        sideDragEnabled -> sideDragText
        sideTapEnabled -> sideTapText
        else -> sideExitText
    }

    /**
     * Picks a readable dash colour for the current surround. Bright surrounds (white, pale
     * greys, light wallpapers) get near-black dashes; the rest keep the button container colour.
     */
    private fun updateHintColor(color: Int) {
        val brightness = 0.299f * ((color shr 16) and 0xFF) +
            0.587f * ((color shr 8) and 0xFF) + 0.114f * (color and 0xFF)
        val next = if (brightness > 168f) 0xCC000000.toInt() else buttonColor
        if (next == hintColor) return
        hintColor = next
        maskView.invalidate()
    }

    private fun showEntryHint() {
        runOnUi {
            if (!showing) return@runOnUi
            // The switch only silences the introduction. The drag outline is issued by the drag
            // itself (showSwipeHint), so resizing still explains where to put the finger.
            if (!hintsEnabled) {
                maskView.setHintRegions(0)
                maskView.setHintAlpha(0f, false)
                return@runOnUi
            }
            entryHintActive = true
            // One beat per gesture the strip actually answers: a tap, a drag, or — when both are
            // switched off — the only thing left, which is tapping the blank area to exit.
            val beats = buildList {
                if (sideTapEnabled) add(sideTapText)
                if (sideDragEnabled) add(sideDragText)
                if (isEmpty()) add(sideExitText)
            }
            sideHintText = beats.first()
            maskView.setTextAlpha(1f)
            maskView.setHintRegions(HINT_TOP or HINT_SIDE)
            animateHint(1f, 120L)
            cancelEntryHintTasks()
            // Each following beat swaps the label; the outlines stay up until the last one ends.
            val swaps = ArrayList<Runnable>()
            for (index in 1 until beats.size) {
                val task = Runnable {
                    if (!entryHintActive) return@Runnable
                    setSideHintText(beats[index])
                }
                swaps.add(task)
                // Start the cross-fade just before the beat ends, so the new label is fully in
                // when its own second begins rather than 300 ms into it.
                val at = (ENTRY_HINT_FIRST_MS * index - HINT_TEXT_FADE_OUT_MS).coerceAtLeast(0L)
                uiHandler.postDelayed(task, at)
            }
            // Both outlines leave together, after the last beat has had its full second.
            val dismiss = Runnable {
                if (!entryHintActive) return@Runnable
                entryHintActive = false
                animateHint(0f, 420L)
            }
            entryHintSwapTasks = swaps
            entryHintTask = dismiss
            uiHandler.postDelayed(dismiss, ENTRY_HINT_FIRST_MS * beats.size)
        }
    }

    /**
     * Replaces the side strip's label without a hard cut: the current text fades out, the next
     * one fades in, and the dashes stay put the whole time.
     */
    private fun setSideHintText(next: String, animated: Boolean = true) {
        if (next == sideHintText) return
        hintTextAnimator?.cancel()
        if (!animated) {
            sideHintText = next
            maskView.setTextAlpha(1f)
            return
        }
        val fadeOut = ValueAnimator.ofFloat(maskView.currentTextAlpha, 0f).apply {
            duration = HINT_TEXT_FADE_OUT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { maskView.setTextAlpha(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sideHintText = next
                    maskView.invalidate()
                    val fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = HINT_TEXT_FADE_IN_MS
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { maskView.setTextAlpha(it.animatedValue as Float) }
                    }
                    hintTextAnimator = fadeIn
                    fadeIn.start()
                }
            })
        }
        hintTextAnimator = fadeOut
        fadeOut.start()
    }

    private fun cancelEntryHintTasks() {
        for (task in entryHintSwapTasks) uiHandler.removeCallbacks(task)
        entryHintTask?.let { uiHandler.removeCallbacks(it) }
        entryHintSwapTasks = emptyList()
        entryHintTask = null
    }

    private fun cancelEntryHint() {
        entryHintActive = false
        cancelEntryHintTasks()
        // Never leave the label half faded because a beat was interrupted mid-transition.
        hintTextAnimator?.cancel()
        hintTextAnimator = null
        maskView.setTextAlpha(1f)
    }

    /**
     * Outlines the area that accepts the drag. On a small scale that area is short and easily
     * missed, so it is marked for as long as the gesture lasts and redrawn as it changes.
     */
    fun showSwipeHint() = runOnUi {
        // A drag owns the hint from here on; the entry hint must not fade it out mid-gesture.
        cancelEntryHint()
        // With resizing switched off a drag does nothing, so there is nothing to outline.
        if (!sideDragEnabled) return@runOnUi
        setSideHintText(sideDragText, animated = false)
        maskView.setHintRegions(HINT_SIDE)
        animateHint(1f, 120L)
    }

    fun hideSwipeHint() = runOnUi {
        cancelEntryHint()
        animateHint(0f, 200L)
    }

    private fun animateHint(target: Float, duration: Long) {
        if (!showing) {
            maskView.setHintAlpha(0f, false)
            return
        }
        val from = maskView.hintAlpha
        if (from == target) return
        hintAnimator?.cancel()
        hintAnimator = ValueAnimator.ofFloat(from, target).apply {
            this.duration = duration
            addUpdateListener { maskView.setHintAlpha(it.animatedValue as Float, true) }
            start()
        }
    }

    /**
     * The settings button is a Miuix IconButton filled with `secondaryContainer`, and the hint
     * uses the same colour. The palette ships inside this module's dex, so it can be evaluated
     * here rather than duplicated or guessed.
     */
    private fun resolveButtonColor(): Int = runCatching {
        val night = (windowContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val scheme = if (night) darkColorScheme() else lightColorScheme()
        scheme.secondaryContainer.toArgb()
    }.getOrDefault(0xFF8E8E93.toInt())

    /**
     * Derives the gap from the scale. Both edges shrink by the same factor, matching the
     * content's uniform matrix, so the mask stays flush with the scaled display.
     */
    private fun updateGeometry(scale: Float) {
        gapHeight = (height * (1f - scale)).roundToInt().toFloat()
        val visualScale = 1f - gapHeight / height
        gapWidth = width * (1f - visualScale)
    }

    private fun applyTouchGeometry() {
        val top = gapHeight.toInt()
        // Deliberately not named `side`: that field is the horizontal progress, 0..1.
        val strip = gapWidth.roundToInt()
        topParams.width = width
        topParams.height = top
        topParams.flags = topParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        sideParams.x = if (right) 0 else width - strip
        sideParams.y = top
        sideParams.width = strip
        sideParams.height = height - top
        sideParams.flags = sideParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    }

    private fun createScaleLabel(): TextView {
        val view = TextView(windowContext).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 17f * density
                setColor(0xCC000000.toInt())
            }
            alpha = 0f
        }
        // A fixed box in the full-screen mask window: moving it only changes a translation,
        // which is a draw-time property, so no window and no layout are involved.
        maskView.addView(
            view,
            FrameLayout.LayoutParams(dp(74), dp(34)).apply { gravity = Gravity.TOP or Gravity.START },
        )
        scaleLabel = view
        positionScaleLabel()
        return view
    }

    /** Sits just inside the top edge of the scaled content, horizontally centred on it. */
    private fun positionScaleLabel() {
        val label = scaleLabel ?: return
        if (width == 0) return
        val params = label.layoutParams as? FrameLayout.LayoutParams ?: return
        val centerX = gapWidth * side + (width - gapWidth) / 2f
        label.translationX = centerX - params.width / 2f
        label.translationY = gapHeight + 10f * density
    }

    private inline fun runOnUi(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else uiHandler.post { block() }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        /** Scale the buttons grow from when they are revealed. */
        const val REVEAL_SCALE = 0.9f
        // Short on purpose: the buttons are already built and parked when this runs, so the fade
        // only has to cover the distance from "there" to "noticed". A long one reads as lag.
        const val REVEAL_DURATION_MS = 110L
        const val REVEAL_STAGGER_MS = 25L
        /** Start of the reveal, measured from the beginning of the entry animation. */
        const val REVEAL_DELAY_MS = 12L
        /** First beat of the entry hint: the tap action on the side strip. */
        const val ENTRY_HINT_FIRST_MS = 1000L
        /** Second beat: the drag action. Both outlines fade out when it ends. */
        const val ENTRY_HINT_SECOND_MS = 1000L
        /** Label swap: the old text leaves, then the new one arrives. */
        const val HINT_TEXT_FADE_OUT_MS = 130L
        const val HINT_TEXT_FADE_IN_MS = 170L
        /**
         * How long the column takes to land on a new gap. Long enough that the horizontal travel
         * reads as a movement rather than a cut, short enough that it still feels like a reaction
         * to the slider.
         */
        const val COLUMN_LAND_MS = 220L
        /** The same landing after a drag; the finger has just left, so it can be a touch quicker. */
        const val SWIPE_LAND_MS = 180L
        /** Fade the mask in once the entry animation has landed. */
        const val MASK_FADE_MS = 140L
    }

    private fun detachWindows() {
        entryFinished = false
        panelOpen = false
        cancelEntryHint()
        maskView.setHintRegions(0)
        maskView.setHintAlpha(0f, false)
        hintAnimator?.cancel()
        hintAnimator = null
        backAnimator?.cancel()
        backAnimator = null
        landAnimator?.cancel()
        landAnimator = null
        maskView.setHintAlpha(0f, false)
        maskView.animate().cancel()
        runCatching { windowManager.removeViewImmediate(maskView) }
        runCatching { windowManager.removeViewImmediate(topTouch) }
        runCatching { windowManager.removeViewImmediate(sideTouch) }
        for (slot in slots) slot.dispose()
        sideTouch.removeAllViews()
        maskView.removeAllViews()
        scaleLabel = null
        showing = false
        windowsAttached = false
    }

    /**
     * One button window. Its size is fixed for its whole life, so moving it never re-measures
     * the Compose content — that is what keeps a drag from making the buttons twitch.
     */
    private inner class ButtonSlot(
        name: String,
        private val glyph: BubbleGlyph?,
        private val labelRes: Int,
        private val vector: ImageVector? = null,
        private val action: () -> Unit,
    ) {
        val params = params(name).apply {
            width = buttonSize
            height = buttonSize
        }
        lateinit var host: FrameLayout
        private var button: MiuixIconButton? = null
        var x = Int.MIN_VALUE
        var y = Int.MIN_VALUE
        var visible = true
        var attached = false
        var built = false

        fun build() {
            if (built) return
            host = FrameLayout(windowContext)
            val label = runCatching { resources.getString(labelRes) }.getOrDefault("")
            val created = MiuixIconButton(
                windowContext, glyph, label, buttonColor, if (right) -1f else 1f, vector, action,
            )
            button = created
            created.view.alpha = 0f
            host.addView(
                created.view,
                FrameLayout.LayoutParams(buttonSize, buttonSize).apply { gravity = Gravity.CENTER },
            )
            x = 0
            y = 0
            built = true
        }

        fun setRotated(rotated: Boolean) {
            button?.setRotated(rotated)
        }

        fun setOutward(value: Float) {
            button?.setOutward(value)
        }

        /** Mounts the window without showing it; the composition starts here. */
        fun preAttach() {
            if (attached) return
            params.x = x
            params.y = y
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.addView(host, params)
            attached = true
            visible = false
            button?.start()
        }

        /**
         * Fades the button in from a slightly smaller size. The window keeps its size, so this is
         * pure draw work inside a window that is already on screen — nothing is measured or
         * relaid out, and the first frame is complete because the view was built during entry.
         */
        fun reveal(delay: Long) {
            if (!attached) return
            visible = true
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(host, params)
            val view = button?.view ?: return
            view.animate().cancel()
            if (view.alpha >= 1f && view.scaleX >= 1f) return
            view.alpha = 0f
            view.scaleX = REVEAL_SCALE
            view.scaleY = REVEAL_SCALE
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delay)
                .setDuration(REVEAL_DURATION_MS)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }

        fun attach(visible: Boolean) {
            this.visible = visible
            params.x = x
            params.y = y
            if (!visible) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.addView(host, params)
            attached = true
            button?.start()
        }

        /** Moves and shows/hides in a single window transaction. */
        fun configure(x: Int, y: Int, visible: Boolean): Boolean {
            if (attached && x == this.x && y == this.y && visible == this.visible) return false
            this.x = x
            this.y = y
            this.visible = visible
            params.x = x
            params.y = y
            val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Until the entry animation lands the windows are only pre-warmed: they must not
            // swallow taps that belong to the shrinking screen.
            params.flags = if (visible && entryFinished) params.flags and flag.inv() else params.flags or flag
            if (attached) windowManager.updateViewLayout(host, params)
            return true
        }

        /** Current opacity, read once when a shared slide starts. */
        val alpha: Float get() = button?.view?.alpha ?: 0f
        /** Current scale, same purpose. */
        val scale: Float get() = button?.view?.scaleX ?: 1f

        /**
         * One frame of a shared slide: window position plus the leave fade.
         *
         * Every slot in [landColumn] is driven by the same animator with the same fraction, and
         * this is the only place a frame is written, so the four windows cannot drift apart.
         */
        fun glide(x: Int, y: Int, alpha: Float, scale: Float) {
            moveTo(x, y)
            val view = button?.view ?: return
            // Only write what actually changed: every property write marks the window's render
            // node dirty, and the two permanent bubbles keep alpha 1 and scale 1 for the whole
            // run. Four windows re-recording per frame for nothing is a measurable cost.
            if (view.alpha != alpha) view.alpha = alpha
            if (view.scaleX != scale) {
                view.scaleX = scale
                view.scaleY = scale
            }
        }

        /** Position change only; used by the back button while the panel animates. */
        fun moveTo(x: Int = this.x, y: Int = this.y) {
            if (x == this.x && y == this.y) return
            this.x = x
            this.y = y
            params.x = x
            params.y = y
            if (attached) windowManager.updateViewLayout(host, params)
        }

        fun show(duration: Long) {
            val view = button?.view ?: return
            view.animate().cancel()
            if (view.alpha > 0f) {
                // Reopened while a collapse was still fading it out: finish both the fade and the
                // shrink it interrupted, or the bubble stays slightly small for good.
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).start()
                return
            }
            // Opening a panel: the same small scale-up as the reveal, so the two agree.
            view.scaleX = REVEAL_SCALE
            view.scaleY = REVEAL_SCALE
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration + 40L)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }

        /** Fades in after the first layout, so a fresh ComposeView is already composed. */
        fun showAfterLayout(duration: Long) {
            val view = button?.view ?: return
            view.animate().cancel()
            view.doOnNextLayout {
                if (showing && entryFinished) view.animate().alpha(1f).setDuration(duration).start()
            }
            view.requestLayout()
        }

        fun fadeOut(duration: Long, end: (() -> Unit)? = null) {
            val view = button?.view ?: return
            view.animate().cancel()
            val animator = view.animate().alpha(0f).setDuration(duration)
            if (end != null) animator.withEndAction(end)
            animator.start()
        }

        fun hide() {
            val view = button?.view
            view?.animate()?.cancel()
            view?.alpha = 0f
            view?.scaleX = 1f
            view?.scaleY = 1f
            configure(x, y, false)
        }

        fun dispose() {
            if (!attached) return
            attached = false
            built = false
            button?.view?.animate()?.cancel()
            button?.dispose()
            button = null
            windowManager.removeViewImmediate(host)
        }
    }

    /** Container for the mask: paints the L-shaped path, the drag hint and the scale bubble. */
    private inner class MaskView(context: Context) : FrameLayout(context) {
        // The hole in the mask follows the content's rounded corner now, so its edge has to be
        // smoothed; a square L-shape did not need it.
        private val paint = Paint().apply { isAntiAlias = true }
        // Wallpapers and picked pictures are painted here instead of in a SurfaceControl buffer
        // layer: a plain View cannot take the display down, and a shader costs one draw call.
        private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val pictureMatrix = Matrix()
        private var picture: Bitmap? = null
        val pictureShown: Boolean get() = picture != null
        // The surround is three plain rects plus four tiny corner wedges instead of one
        // full-screen path with a hole: an even-odd path of that size is rendered with the
        // stencil-and-cover path renderer, two full-screen passes on every frame, which is what
        // made switching sides drop frames. Rect fills are a single cheap pass each.
        private val topBand = RectF()
        private val leftBand = RectF()
        private val rightBand = RectF()
        private val wedges = Array(4) { Path() }
        // Reused by buildWedges(): refreshing runs on every animation frame.
        private val wedgeRect = RectF()
        private var wedgesReady = false
        private val content = RectF()
        private var ready = false
        private var hostWidth = 0f
        private var hostHeight = 0f
        // Last geometry the path was built from. A frame that changes nothing — a settle after a
        // drag that ended exactly on target, for instance — must not repaint the whole display.
        private var builtGapWidth = Float.NaN
        private var builtGapHeight = Float.NaN
        private var builtSide = Float.NaN
        private var builtWidth = 0
        private var builtHeight = 0
        private var matrixWidth = 0f
        private var matrixHeight = 0f
        private val hintRect = RectF()
        // A stroked round rect with a DashPathEffect came out as a filled block on this ROM's
        // renderer, and a plain stroke would be a solid ring. The outline is walked with a
        // PathMeasure and handed to drawLines() as explicit dash segments, so the result is
        // always exactly one dashed ring with nothing filled and no path effect involved.
        private val hintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.BUTT
        }
        private val hintOutline = Path()
        private val hintMeasure = PathMeasure()
        private val hintSample = floatArrayOf(0f, 0f)
        private var hintSegments = FloatArray(0)
        private var hintSegmentCount = 0
        private val dashOn = 6f * density
        private val dashOff = 5f * density
        private val hintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * resources.displayMetrics.scaledDensity
        }
        private val hintInset = 5f * density
        private val hintRadius = 14f * density
        var hintAlpha = 0f
        /**
         * Label opacity, independent of the outline: a beat change fades the old text out and the
         * new one in while the dashes stay exactly as they are.
         */
        private var textAlpha = 1f
        /** Read by the swap animation, which has to start from wherever the label is now. */
        val currentTextAlpha: Float get() = textAlpha
        private var hintRegions = 0

        init {
            // A plain ViewGroup skips onDraw unless asked to draw itself.
            setWillNotDraw(false)
        }

        fun setTextAlpha(value: Float) {
            val next = value.coerceIn(0f, 1f)
            if (next == textAlpha) return
            textAlpha = next
            invalidate()
        }

        fun setHintAlpha(value: Float, redraw: Boolean) {
            hintAlpha = value.coerceIn(0f, 1f)
            if (redraw) invalidate()
        }

        /** Which outlines to paint: [HINT_SIDE], [HINT_TOP] or both. */
        fun setHintRegions(regions: Int) {
            if (hintRegions == regions) return
            hintRegions = regions
            invalidate()
        }

        fun setColor(color: Int, force: Boolean = false) {
            if (!force && paint.color == color) return
            paint.color = color
            invalidate()
        }

        /** Pass a blurred bitmap for wallpaper/picture mode, or null to go back to the flat color. */
        fun setPicture(bitmap: Bitmap?) {
            picture = bitmap
            matrixWidth = 0f
            matrixHeight = 0f
            picturePaint.shader = bitmap?.let {
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            if (hostWidth > 0 && hostHeight > 0) applyPictureMatrix(hostWidth, hostHeight)
            invalidate()
        }

        private fun applyPictureMatrix(w: Float, h: Float) {
            val bitmap = picture ?: return
            val shader = picturePaint.shader ?: return
            if (bitmap.width <= 0 || bitmap.height <= 0) return
            if (w == matrixWidth && h == matrixHeight) return
            matrixWidth = w
            matrixHeight = h
            pictureMatrix.reset()
            pictureMatrix.setScale(w / bitmap.width, h / bitmap.height)
            shader.setLocalMatrix(pictureMatrix)
        }

        fun refresh() {
            val host = this@HandySurround
            if (host.width == 0 || host.height == 0) return
            if (host.width == builtWidth && host.height == builtHeight &&
                host.gapWidth == builtGapWidth && host.gapHeight == builtGapHeight &&
                host.side == builtSide
            ) return
            builtWidth = host.width
            builtHeight = host.height
            builtGapWidth = host.gapWidth
            builtGapHeight = host.gapHeight
            builtSide = host.side
            // View already exposes right/left/width/height as Int properties, so the host
            // fields have to be qualified explicitly.
            val w = host.width.toFloat()
            val h = host.height.toFloat()
            hostWidth = w
            hostHeight = h
            applyPictureMatrix(w, h)
            // gapWidth is the width of the blank strip, which doubles as the content's offset
            // from the edge it is anchored to: 0 when anchored left, gapWidth when anchored right.
            val left = host.gapWidth * host.side
            val contentWidth = w - host.gapWidth
            content.set(left, host.gapHeight, left + contentWidth, h)
            topBand.set(0f, 0f, w, content.top)
            leftBand.set(0f, content.top, content.left, h)
            rightBand.set(content.right, content.top, w, h)
            // The display area is rounded by the framework with the physical corner radius, which
            // its matrix scales down together with the content, so the surround has to reach into
            // the four rounded corners or a wallpaper/picture shows a hard square edge there.
            buildWedges((host.cornerRadius * (contentWidth / w)).coerceAtLeast(0f))
            ready = true
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!ready) return
            val p = if (picture == null) paint else picturePaint
            if (topBand.height() > 0f) canvas.drawRect(topBand, p)
            if (leftBand.width() > 0f) canvas.drawRect(leftBand, p)
            if (rightBand.width() > 0f) canvas.drawRect(rightBand, p)
            if (wedgesReady) for (wedge in wedges) canvas.drawPath(wedge, p)
            if (hintAlpha > 0.01f) drawHint(canvas)
        }

        /** RectF.set() returns Unit, and arcTo() needs the rect itself. */
        private fun wedgeRectOf(l: Float, t: Float, r: Float, b: Float): RectF {
            wedgeRect.set(l, t, r, b)
            return wedgeRect
        }

        /** The sliver between a square content corner and its rounded arc, one per corner. */
        private fun buildWedges(radius: Float) {
            for (wedge in wedges) wedge.reset()
            wedgesReady = radius >= 0.5f
            if (!wedgesReady) return
            val l = content.left
            val t = content.top
            val r = content.right
            val b = content.bottom
            // Top left: arc from the top edge back to the left edge.
            wedges[0].apply {
                moveTo(l + radius, t)
                arcTo(wedgeRectOf(l, t, l + 2 * radius, t + 2 * radius), 270f, -90f, false)
                lineTo(l, t)
                close()
            }
            // Top right.
            wedges[1].apply {
                moveTo(r - radius, t)
                arcTo(wedgeRectOf(r - 2 * radius, t, r, t + 2 * radius), 270f, 90f, false)
                lineTo(r, t)
                close()
            }
            // Bottom left.
            wedges[2].apply {
                moveTo(l + radius, b)
                arcTo(wedgeRectOf(l, b - 2 * radius, l + 2 * radius, b), 90f, 90f, false)
                lineTo(l, b)
                close()
            }
            // Bottom right.
            wedges[3].apply {
                moveTo(r - radius, b)
                arcTo(wedgeRectOf(r - 2 * radius, b - 2 * radius, r, b), 90f, -90f, false)
                lineTo(r, b)
                close()
            }
        }

        /**
         * Dashed outlines of the areas that take a gesture, redrawn with every scale step. The
         * side strip is outlined while a drag runs; both strips are outlined for the first two
         * seconds after entry, which is where the "tap above to exit" reminder lives.
         */
        private fun drawHint(canvas: Canvas) {
            val host = this@HandySurround
            val alpha = (255 * hintAlpha).roundToInt().coerceIn(0, 255)
            if (alpha == 0) return
            hintStroke.color = host.hintColor
            hintStroke.alpha = alpha
            hintLabel.color = host.hintColor
            hintLabel.alpha = (alpha * textAlpha).roundToInt().coerceIn(0, 255)
            // The physical corner radius, minus the inset the outline keeps from the edge. A
            // corner drawn with this radius is concentric with the display corner, so the dashes
            // stay inside the visible area instead of being clipped by the rounded screen.
            // Only a display corner worth the name bends the outline; a flat-cornered panel keeps
            // the small radius so the dashes do not look like an oval.
            val corner = if (host.cornerRadius > hintRadius) {
                (host.cornerRadius - hintInset).coerceAtLeast(hintRadius)
            } else {
                hintRadius
            }
            if (hintRegions and HINT_SIDE != 0) {
                val left = (host.width - host.gapWidth) * (1f - host.side)
                val right = left + host.gapWidth
                // The strip hugs one screen edge; that side's two corners follow the display.
                val atLeftEdge = left < host.width / 2f
                // Only the bottom corners sit on a display corner; the top of this outline
                // meets a straight edge in the middle of the screen.
                drawHintRegion(
                    canvas, left, host.gapHeight, right, host.height.toFloat(), host.sideHintText,
                    tl = hintRadius,
                    tr = hintRadius,
                    br = if (atLeftEdge) hintRadius else corner,
                    bl = if (atLeftEdge) corner else hintRadius,
                    centered = false,
                    contentOnRight = host.side >= 0.5f,
                )
            }
            if (hintRegions and HINT_TOP != 0) {
                // The top band touches the display's top edge only.
                drawHintRegion(
                    canvas, 0f, 0f, host.width.toFloat(), host.gapHeight, host.topHintText,
                    tl = corner, tr = corner, br = hintRadius, bl = hintRadius,
                    centered = true,
                    contentOnRight = false,
                )
            }
        }

        private fun drawHintRegion(
            canvas: Canvas,
            l: Float,
            t: Float,
            r: Float,
            b: Float,
            text: String,
            tl: Float,
            tr: Float,
            br: Float,
            bl: Float,
            centered: Boolean,
            contentOnRight: Boolean,
        ) {
            hintRect.set(l, t, r, b)
            if (hintRect.width() <= hintInset * 2f || hintRect.height() <= hintInset * 2f) return
            // Stroke only, hugging the area that actually accepts the gesture.
            hintRect.inset(hintInset, hintInset)
            // Equal radii must not overlap on very small regions.
            val limit = minOf(hintRect.width(), hintRect.height()) / 2f
            buildRoundedOutline(hintRect, tl.coerceAtMost(limit), tr.coerceAtMost(limit),
                br.coerceAtMost(limit), bl.coerceAtMost(limit))
            buildDashes()
            if (hintSegmentCount == 0) return
            canvas.drawLines(hintSegments, 0, hintSegmentCount, hintStroke)
            drawHintText(canvas, hintRect, text, centered, contentOnRight)
        }

        /**
         * Builds the outline corner by corner. A single rounded rect would round all four
         * corners the same, but only the corners that sit on a display corner have to bend with
         * the screen; the others meet a straight edge and keep the small radius.
         */
        private fun buildRoundedOutline(
            rect: RectF,
            tl: Float,
            tr: Float,
            br: Float,
            bl: Float,
        ) {
            hintOutline.reset()
            val l = rect.left
            val t = rect.top
            val r = rect.right
            val b = rect.bottom
            hintOutline.moveTo(l + tl, t)
            hintOutline.lineTo(r - tr, t)
            if (tr > 0f) hintOutline.arcTo(wedgeRectOf(r - 2 * tr, t, r, t + 2 * tr), 270f, 90f, false)
            hintOutline.lineTo(r, b - br)
            if (br > 0f) hintOutline.arcTo(wedgeRectOf(r - 2 * br, b - 2 * br, r, b), 0f, 90f, false)
            hintOutline.lineTo(l + bl, b)
            if (bl > 0f) hintOutline.arcTo(wedgeRectOf(l, b - 2 * bl, l + 2 * bl, b), 90f, 90f, false)
            hintOutline.lineTo(l, t + tl)
            if (tl > 0f) hintOutline.arcTo(wedgeRectOf(l, t, l + 2 * tl, t + 2 * tl), 180f, 90f, false)
            hintOutline.close()
        }

        /** Walks the rounded outline and emits one line segment per dash. */
        private fun buildDashes() {
            hintMeasure.setPath(hintOutline, false)
            val length = hintMeasure.length
            if (length <= 0f) {
                hintSegmentCount = 0
                return
            }
            val capacity = (((length / (dashOn + dashOff)).toInt() + 2) * 4)
            if (hintSegments.size < capacity) hintSegments = FloatArray(capacity)
            var distance = 0f
            var index = 0
            while (distance < length) {
                val end = (distance + dashOn).coerceAtMost(length)
                if (hintMeasure.getPosTan(distance, hintSample, null)) {
                    hintSegments[index++] = hintSample[0]
                    hintSegments[index++] = hintSample[1]
                }
                if (hintMeasure.getPosTan(end, hintSample, null)) {
                    hintSegments[index++] = hintSample[0]
                    hintSegments[index++] = hintSample[1]
                }
                distance = end + dashOff
            }
            hintSegmentCount = index
        }

        /**
         * Picks the arrangement that fits the current strip: one horizontal line when there is
         * room, otherwise stacked characters, with as many columns as the height allows. The
         * strip is wide and short at 20% and narrow and tall at 90%, so both cases are real.
         *
         * On the side strip the label hugs the edge it shares with the content and sits at the
         * top of the outline. Centring it put the label in the middle of a tall strip, where the
         * bubble column is, and the two overlapped at some scales. The top strip has no bubble
         * and is short, so its label stays centred.
         */
        private fun drawHintText(
            canvas: Canvas,
            rect: RectF,
            text: String,
            centered: Boolean,
            contentOnRight: Boolean,
        ) {
            if (text.isEmpty()) return
            val padding = 4f * density
            val availableWidth = rect.width() - padding * 2f
            val availableHeight = rect.height() - padding * 2f
            if (availableWidth <= 0f || availableHeight <= 0f) return
            val metrics = hintLabel.fontMetrics
            val single = hintLabel.measureText(text)
            if (single <= availableWidth) {
                val x = when {
                    centered -> rect.centerX() - single / 2f
                    contentOnRight -> rect.right - padding - single
                    else -> rect.left + padding
                }
                val baseline = if (centered) {
                    rect.centerY() - (metrics.ascent + metrics.descent) / 2f
                } else {
                    rect.top + padding - metrics.ascent
                }
                canvas.drawText(text, x, baseline, hintLabel)
                return
            }
            val cell = hintLabel.textSize * 1.15f
            val perColumn = (availableHeight / cell).toInt().coerceAtLeast(1)
            val columns = ceil(text.length / perColumn.toFloat()).toInt().coerceAtLeast(1)
            val columnWidth = hintLabel.textSize * 1.25f
            // Vertical Chinese reads right to left, so the first column is the one nearest the
            // content: on the right when the content is to the right, on the left otherwise.
            val blockWidth = columns * columnWidth
            val blockLeft = when {
                centered -> rect.centerX() - blockWidth / 2f
                contentOnRight -> rect.right - padding - blockWidth
                else -> rect.left + padding
            }
            val firstColumn = if (contentOnRight) {
                blockLeft + blockWidth - columnWidth / 2f
            } else {
                blockLeft + columnWidth / 2f
            }
            for (index in 0 until columns) {
                val start = index * perColumn
                val end = (start + perColumn).coerceAtMost(text.length)
                val column = text.substring(start, end)
                val centreX = if (contentOnRight) {
                    firstColumn - index * columnWidth
                } else {
                    firstColumn + index * columnWidth
                }
                var centreY = if (centered) {
                    rect.centerY() - column.length * cell / 2f + cell / 2f
                } else {
                    rect.top + padding + cell / 2f
                }
                for (character in column) {
                    val width = hintLabel.measureText(character.toString())
                    val baseline = centreY - (metrics.ascent + metrics.descent) / 2f
                    canvas.drawText(character.toString(), centreX - width / 2f, baseline, hintLabel)
                    centreY += cell
                }
            }
        }
    }

    private inner class TouchView(
        private val onTapOutside: () -> Unit,
        private val onSwipeStart: (() -> Unit)? = null,
        private val onSwipe: ((Float) -> Unit)? = null,
        private val onSwipeEnd: (() -> Unit)? = null,
    ) : FrameLayout(windowContext) {
        private var multiTouch = false
        private var downY = 0f
        private var dragging = false
        private val content = RectF()

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multiTouch = false
                    dragging = false
                    downY = event.rawY
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    endDrag()
                }
                MotionEvent.ACTION_MOVE -> if (onSwipe != null && !multiTouch) {
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > swipeSlop) {
                        dragging = true
                        onSwipeStart?.invoke()
                    }
                    if (dragging) onSwipe?.invoke(dy)
                }
                MotionEvent.ACTION_UP -> {
                    if (multiTouch) return true
                    if (dragging) {
                        endDrag()
                    } else if (!insideContent(event.rawX, event.rawY)) {
                        onTapOutside()
                    }
                }
                MotionEvent.ACTION_CANCEL -> endDrag()
            }
            return true
        }

        private fun endDrag() {
            if (!dragging) return
            dragging = false
            onSwipeEnd?.invoke()
        }

        /** The scaled content plus a small margin; touches there belong to the content. */
        private fun insideContent(x: Float, y: Float): Boolean {
            val host = this@HandySurround
            if (host.height == 0) return false
            val scale = 1f - host.gapHeight / host.height
            val left = host.gapWidth * host.side
            content.set(left, host.gapHeight, left + host.width * scale, host.height.toFloat())
            content.inset(-edge * scale, -edge * scale)
            return content.contains(x, y)
        }
    }

    private fun params(title: String) = WindowManager.LayoutParams(
        1, 1, 2024,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            0x20000000,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        setTitle(title)
        val privateFlags = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
        privateFlags.setInt(this, privateFlags.getInt(this) or 0x20000000)
    }
}
