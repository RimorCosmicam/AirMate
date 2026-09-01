package com.airmate.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.airmate.android.ui.mont.DiagonalStripes
import com.airmate.android.ui.mont.MontAccent

/**
 * The ground the pairing card stands on.
 *
 * Mustard on black while you are being introduced and while you wait, red on black once a stream
 * that was running has dropped — the colour pair carries the state, which is the only job Mont
 * gives an accent. Green never appears here: by the time AirMate is live this whole surface has
 * been torn down so the decoder has the device to itself.
 */
@Composable
fun StripeBackdrop(
    split: Float = 0f,
    disconnected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val travel by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "stripes"
    )
    DiagonalStripes(
        travel = travel,
        first = if (disconnected) MontAccent.Danger else MontAccent.Mustard,
        second = Color.Black,
        split = split,
        modifier = modifier.fillMaxSize()
    )
}
