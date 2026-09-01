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
import com.airmate.android.FrameLeniency
import com.airmate.android.ScreenAxis
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

/** The resolutions the Mac offers. Kept in step with `DisplayConfiguration.resolutions`. */
val RESOLUTIONS = listOf(1280 to 800, 1920 to 1080, 1920 to 1200)

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
    onStartStop: (Boolean) -> Unit,
    onResolution: (Int, Int) -> Unit,
    onHiDPI: (Boolean) -> Unit,
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
                        if (status.hiDPI) " HiDPI" else ""
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
            val oriented = RESOLUTIONS.map { (wide, tall) ->
                if (axis == ScreenAxis.VERTICAL) tall to wide else wide to tall
            }
            val options = (oriented + listOfNotNull(running)).distinct()
            MontChips(
                options = options.map { "${it.first} × ${it.second}" },
                selected = options.indexOfFirst { it == running }
            ) { index ->
                onResolution(options[index].first, options[index].second)
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MontLabel("HIDPI", Modifier.weight(1f), alpha = MontWhite.DIM, size = 11)
                MontToggle(
                    checked = status?.hiDPI == true,
                    onChange = onHiDPI
                )
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
