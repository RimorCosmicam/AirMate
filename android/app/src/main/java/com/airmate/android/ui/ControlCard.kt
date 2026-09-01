package com.airmate.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.airmate.android.FrameLeniency
import com.airmate.android.ScreenAxis
import com.airmate.android.decoder.DecoderLimits
import com.airmate.android.protocol.StatusMessage
import com.airmate.android.ui.mont.MontStage
import com.airmate.android.ui.mont.MontChips
import com.airmate.android.ui.mont.MontDetail
import com.airmate.android.ui.mont.MontLabel
import com.airmate.android.ui.mont.MontRow
import com.airmate.android.ui.mont.MontToggle
import com.airmate.android.ui.mont.MontWhite

/** Which side the swipe came from, and therefore which side the card opens on. */
enum class CardEdge { LEFT, RIGHT }

/** Fallback sizes, used only before this screen has measured itself. */
val RESOLUTIONS = listOf(1280 to 800, 1920 to 1080, 1920 to 1200)

/**
 * Five sizes at this screen's own shape, evenly spaced from all of it down to half.
 *
 * Derived rather than listed, because a fixed list is a list of other devices' shapes and anything
 * that is not this screen's aspect ratio letterboxes. Sides are rounded to even numbers, which
 * every video encoder wants and which moves the shape by less than a pixel.
 */
fun resolutionsFor(panel: Pair<Int, Int>): List<Pair<Int, Int>> =
    listOf(1.0, 0.875, 0.75, 0.625, 0.5)
        .map { scale -> even(panel.first * scale) to even(panel.second * scale) }
        .filter { it.first >= 640 && it.second >= 480 }
        // Only sizes this device can actually decode. A screen larger than its own decoder is
        // ordinary — offering its native size then hands the hardware something it answers with
        // OMX_ErrorHardware, which kills the codec and every frame after it.
        .filter { DecoderLimits.supports(it.first, it.second) }
        .distinct()

private fun even(value: Double): Int = (value.roundToInt() / 2) * 2

/**
 * The Mac's own options, on the tablet.
 *
 * Opens on the edge the swipe came from, so the card is under the hand that asked for it. Anything
 * that changes the Mac is refused until someone authorises this tablet in the Mac's window; the
 * card says so rather than appearing to work.
 */
@Composable
fun ControlCard(
    edge: CardEdge,
    status: StatusMessage?,
    axis: ScreenAxis,
    leniency: FrameLeniency,
    fps: Int,
    dropPercent: Float,
    panel: Pair<Int, Int>?,
    onStartStop: (Boolean) -> Unit,
    onResolution: (Int, Int) -> Unit,
    onAxis: (ScreenAxis) -> Unit,
    onLeniency: (FrameLeniency) -> Unit,
    onRequestKeyframe: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val authorised = status?.authorised == true

    Box(modifier.fillMaxSize()) {
        // Tapping away closes, so the card never traps the screen.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
                .background(Color.Transparent)
        )

        MontStage(if (edge == CardEdge.LEFT) Alignment.CenterStart else Alignment.CenterEnd) {
            MontLabel("THIS MAC", alpha = MontWhite.DIM, size = 11)
            MontDetail(
                when {
                    status == null -> "No word from the Mac yet."
                    status.running -> "Display running at ${status.width} × ${status.height}" +
                        ""
                    else -> "Display stopped."
                }
            )
            if (status != null && !authorised) {
                Spacer(Modifier.height(6.dp))
                MontDetail(
                    "The Mac has to allow this tablet first. Change anything here and it will ask you there.",
                    alpha = MontWhite.DETAIL
                )
            }

            Spacer(Modifier.height(14.dp))

            MontRow(
                label = if (status?.running == true) "Stop display" else "Start display"
            ) { onStartStop(status?.running != true) }

            Spacer(Modifier.height(10.dp))
            MontLabel("RESOLUTION", alpha = MontWhite.DIM, size = 11)
            // Turned to match the axis, because a portrait display cannot be chosen from a list of
            // landscape sizes — and whatever is actually running is always in the list, so the row
            // shows where you are rather than highlighting nothing.
            val running = status?.let { it.width to it.height }
            val sizes = panel?.let(::resolutionsFor)
                ?: RESOLUTIONS.map { (wide, tall) ->
                    if (axis == ScreenAxis.VERTICAL) tall to wide else wide to tall
                }
            // Whatever is running is always present, so the row shows where you are rather than
            // highlighting nothing when the host is on a size this screen would not suggest.
            val options = (sizes + listOfNotNull(running)).distinct()
            MontChips(
                options = options.map { "${it.first} × ${it.second}" },
                selected = options.indexOfFirst { it == running }
            ) { index ->
                onResolution(options[index].first, options[index].second)
            }

            Spacer(Modifier.height(12.dp))
            MontLabel("ROTATION", alpha = MontWhite.DIM, size = 11)
            MontDetail("Turns on its own within the axis you pick. The Mac builds a display the same way up, so anything open on it moves to your main screen.")
            MontChips(
                options = ScreenAxis.entries.map { it.label },
                selected = axis.ordinal
            ) { onAxis(ScreenAxis.entries[it]) }

            Spacer(Modifier.height(12.dp))
            MontLabel("FRAME SKIP", alpha = MontWhite.DIM, size = 11)
            MontDetail("How long to wait for a late frame before giving up on it. What you pay is latency; what you keep is below.")
            MontChips(
                options = FrameLeniency.entries.map { it.label },
                selected = leniency.ordinal
            ) { onLeniency(FrameLeniency.entries[it]) }

            // The numbers the setting above is meant to move. Without them it is a choice between
            // four words.
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MontLabel("FPS", alpha = MontWhite.DIM, size = 10)
                    MontLabel(if (fps > 0) "$fps" else "—", size = 15)
                }
                Column(Modifier.weight(1f)) {
                    MontLabel("DROPPED", alpha = MontWhite.DIM, size = 10)
                    MontLabel(
                        if (fps > 0) String.format("%.1f%%", dropPercent) else "—",
                        alpha = if (dropPercent >= 5f) MontWhite.ACTIVE else MontWhite.DETAIL,
                        size = 15
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MontRow(label = "Ask for a fresh keyframe", active = false) {
                onRequestKeyframe()
            }
            MontRow(label = "Close", active = false, onClick = onDismiss)
        }
    }
}
