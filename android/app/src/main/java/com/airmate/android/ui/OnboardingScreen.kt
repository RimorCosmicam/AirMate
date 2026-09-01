package com.airmate.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airmate.android.ui.mont.MontStage
import com.airmate.android.ui.mont.MontDetail
import com.airmate.android.ui.mont.MontLabel
import com.airmate.android.ui.mont.MontRow
import com.airmate.android.ui.mont.MontWhite
import com.airmate.android.ui.mont.MontWordmark

/**
 * The first run, as one card.
 *
 * What AirMate is, how to pair, and — the step that cannot be dropped — the swipe. Once pairing
 * finishes this entire surface is torn down so the decoder owns the device, which leaves the edge
 * swipe as the only way back into the app. A gesture that is the sole route to every control has
 * to be taught before it is needed.
 *
 * The card is a single surface throughout: it changes what it holds and resizes to fit, rather
 * than one screen vanishing and another taking its place. The box is the thing being followed, so
 * it should never be the thing that blinks.
 */
@Composable
fun OnboardingScreen(
    scanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableIntStateOf(0) }

    MontStage(Alignment.CenterStart, modifier) {
        MontWordmark()
        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 120)) togetherWith fadeOut(tween(140)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(360, easing = FastOutSlowInEasing) })
            },
            label = "onboardingStep"
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                when (current) {
                    0 -> IntroStep { step = 1 }
                    1 -> PairStep(scanning, scanError, onScan) { step = 2 }
                    else -> GestureStep(onFinished)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.IntroStep(onNext: () -> Unit) {
    MontDetail("A second screen for your Mac, over your own Wi‑Fi. Nothing to sign in to.")
    Spacer(Modifier.height(10.dp))
    MontLabel("BEFORE YOU START", alpha = MontWhite.DIM, size = 11)
    TourLine("ON THE MAC", "Open AirMate and leave it running")
    TourLine("ON THIS TABLET", "Join the same Wi‑Fi network")
    Spacer(Modifier.height(6.dp))
    MontRow("Next", onClick = onNext)
}

@Composable
private fun ColumnScope.PairStep(
    scanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
    onNext: () -> Unit
) {
    MontDetail("Being on the same network is enough — AirMate finds the Mac on its own. Scanning its code just skips the search.")
    Spacer(Modifier.height(10.dp))
    MontRow(
        label = if (scanning) "Opening scanner…" else "Scan the Mac's code",
        trailing = "Optional",
        enabled = !scanning,
        onClick = onScan
    )
    scanError?.let {
        MontDetail(it, alpha = MontWhite.DIM)
        Spacer(Modifier.height(4.dp))
    }
    MontRow("Next", onClick = onNext)
}

@Composable
private fun ColumnScope.GestureStep(onFinished: () -> Unit) {
    MontLabel("ONE GESTURE", alpha = MontWhite.DIM, size = 11)
    MontDetail("Once the screen is live, AirMate gets out of the way entirely. This is how it comes back.")
    Spacer(Modifier.height(10.dp))
    TourLine("SWIPE IN", "From either side edge")
    TourLine("THE SIDE YOU USED", "Is the side the controls open on")
    TourLine("EVERYTHING ELSE", "Resolution, HiDPI, rotation, stop")
    Spacer(Modifier.height(8.dp))
    MontRow("Got it", onClick = onFinished)
}

@Composable
private fun TourLine(gesture: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        MontLabel(gesture, Modifier.weight(.44f), size = 12)
        MontDetail(meaning, Modifier.weight(.56f))
    }
}
