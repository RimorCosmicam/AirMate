package com.airmate.android.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.airmate.android.protocol.ControlMessage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the streamed picture actually sits, and how big the Mac thinks it is.
 *
 * The surface is letterboxed inside the tablet, so a touch has to be measured against the picture
 * rather than the screen — otherwise every tap lands slightly wrong, and by more the further it is
 * from the centre.
 */
data class TouchSurface(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int
)

/**
 * Reading mode: taps and drags on the tablet, as a pointer on the Mac.
 *
 * Tap to click, drag to scroll, and nothing else — the vocabulary of reading rather than of
 * pointing. Nothing is held down and dragged, because the Mac puts the cursor back where it found
 * it after every gesture, and a drag that outlived the gesture would leave a button pressed on a
 * screen nobody is looking at.
 *
 * Scrolling is the gesture that has to feel right, so deltas are sent in the display's own pixels
 * and the page moves exactly as far as the finger did.
 */
class TouchInput(
    context: Context,
    private val surface: () -> TouchSurface?,
    private val send: (ByteArray) -> Unit
) : View.OnTouchListener {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getTapTimeout() + ViewConfiguration.getDoubleTapTimeout() / 2

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var scrolling = false
    private var abandoned = false

    /**
     * Which half of the screen was touched last.
     *
     * Below Android 14 the system does not report which edge a back gesture came from, so this is
     * what decides the side the control card opens on.
     */
    var lastTouchedSide: CardEdge? = null
        private set

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val stage = surface() ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                downAt = event.eventTime
                lastTouchedSide = if (event.x < view.width / 2f) CardEdge.LEFT else CardEdge.RIGHT
                scrolling = false
                abandoned = !inside(stage, event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (abandoned) return true
                val travelled = maxOf(abs(event.x - downX), abs(event.y - downY))
                if (!scrolling && travelled <= slop) return true

                val dx = displayDelta(event.x - lastX, stage.width, stage.displayWidth)
                val dy = displayDelta(event.y - lastY, stage.height, stage.displayHeight)
                lastX = event.x; lastY = event.y
                if (dx == 0 && dy == 0 && scrolling) return true

                if (!scrolling) {
                    scrolling = true
                    // Anchored where the finger went down, not where it is now: the host moves the
                    // cursor there once, and a flick should scroll the thing you started on.
                    val (x, y) = normalised(stage, downX, downY)
                    send(ControlMessage.scroll(ControlMessage.PHASE_BEGIN, x, y, 0, 0))
                }
                val (x, y) = normalised(stage, event.x, event.y)
                send(ControlMessage.scroll(ControlMessage.PHASE_CONTINUE, x, y, dx, dy))
            }

            MotionEvent.ACTION_UP -> {
                if (abandoned) return true
                val (x, y) = normalised(stage, downX, downY)
                when {
                    scrolling -> send(ControlMessage.scroll(ControlMessage.PHASE_END, x, y, 0, 0))
                    event.eventTime - downAt > tapTimeout -> Unit // A long press is not a click.
                    else -> send(ControlMessage.click(x, y))
                }
                scrolling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (scrolling) {
                    val (x, y) = normalised(stage, downX, downY)
                    // Always closed, so the Mac never strands its cursor on the tablet's display.
                    send(ControlMessage.scroll(ControlMessage.PHASE_END, x, y, 0, 0))
                }
                scrolling = false
            }
        }
        return true
    }

    private fun inside(stage: TouchSurface, x: Float, y: Float) =
        x >= stage.left && x < stage.left + stage.width &&
            y >= stage.top && y < stage.top + stage.height

    private fun normalised(stage: TouchSurface, x: Float, y: Float): Pair<Int, Int> {
        if (stage.width <= 0 || stage.height <= 0) return 0 to 0
        val nx = ((x - stage.left) / stage.width * FULL).roundToInt().coerceIn(0, FULL)
        val ny = ((y - stage.top) / stage.height * FULL).roundToInt().coerceIn(0, FULL)
        return nx to ny
    }

    private fun displayDelta(pixels: Float, on: Int, of: Int): Int =
        if (on <= 0) 0 else (pixels / on * of).roundToInt()

    private companion object {
        /** Normalised coordinates run the full width of a `u16`. */
        const val FULL = 65535
    }
}
