package com.airmate.android.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * An inward swipe from either side edge.
 *
 * Deliberately ours rather than the system back gesture. `OnBackPressedDispatcher` only reports
 * which edge a swipe came from through predictive back on API 34 and up, and this app supports 26;
 * on top of that, under `IMMERSIVE_STICKY` the first system edge swipe is swallowed revealing the
 * bars instead of reaching the app. A detector we own works everywhere, knows the edge for free,
 * and costs nothing here because the streaming surface has no other use for touches.
 */
class EdgeGestureDetector(
    context: Context,
    private val onEdgeSwipe: (CardEdge) -> Unit
) : View.OnTouchListener {
    private val density = context.resources.displayMetrics.density
    private val edgeWidth = 28f * density
    private val trigger = 56f * density

    private var startX = 0f
    private var startY = 0f
    private var edge: CardEdge? = null

    /**
     * Which half of the screen was touched last, whether or not that touch became our gesture.
     *
     * Below Android 14 the system does not say which edge a back gesture came from, and outside
     * the strips we are allowed to reserve it takes the touch before we see the whole of it. The
     * press that begins it usually still arrives, though, so this is what the card falls back on
     * to open under the hand that asked rather than always on the same side.
     */
    var lastTouchedSide: CardEdge? = null
        private set

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastTouchedSide = if (event.x < view.width / 2f) CardEdge.LEFT else CardEdge.RIGHT
                edge = when {
                    event.x <= edgeWidth -> CardEdge.LEFT
                    event.x >= view.width - edgeWidth -> CardEdge.RIGHT
                    else -> null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val from = edge ?: return true
                val travelled = if (from == CardEdge.LEFT) event.x - startX else startX - event.x
                // Mostly sideways, and far enough to be meant. Without the vertical test a scroll
                // that begins near the bezel opens the card.
                if (travelled > trigger && abs(event.y - startY) < travelled) {
                    edge = null
                    onEdgeSwipe(from)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edge = null
        }
        return true
    }
}
