package com.airmate.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airmate.android.ui.mont.MontStage
import com.airmate.android.ui.mont.MontDetail
import com.airmate.android.ui.mont.MontRow
import com.airmate.android.ui.mont.MontWhite
import com.airmate.android.ui.mont.MontWordmark

/**
 * Waiting for the Mac.
 *
 * The card is anchored to the left and vertically centred rather than running the full width.
 * Mont's full-width rule was written for a cover display a few centimetres across; on a ten-inch
 * tablet in landscape the same rule produces a black band with a line of text adrift in the middle
 * of it, which is the opposite of what the rule was for.
 */
@Composable
fun PairingScreen(
    paired: Boolean,
    scanning: Boolean,
    scanError: String?,
    disconnected: Boolean,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    MontStage(Alignment.CenterStart, modifier) {
        MontWordmark()
        Spacer(Modifier.height(10.dp))
        MontDetail(
            when {
                disconnected -> "The stream stopped. Looking for the Mac again…"
                paired -> "Found the Mac. Waiting for it to start the display…"
                else -> "Looking for a Mac running AirMate on this network…"
            }
        )
        Spacer(Modifier.height(14.dp))
        MontRow(
            label = if (scanning) "Opening scanner…" else "Scan the Mac's code",
            trailing = "Optional",
            enabled = !scanning,
            active = false,
            onClick = onScan
        )
        scanError?.let { MontDetail(it, alpha = MontWhite.DIM) }
    }
}
