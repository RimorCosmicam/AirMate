package com.airmate.host.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airmate.host.ui.mont.MontStripedStage
import com.airmate.host.ui.mont.Mont
import com.airmate.host.ui.mont.MONT_SURFACE_ALPHA
import com.airmate.host.ui.mont.MontAccent
import com.airmate.host.ui.mont.MontWhite

/** One thing the host needs, and whether it has it yet. */
data class Requirement(
    val label: String,
    val detail: String,
    val granted: Boolean,
    val action: String
)

/**
 * The first run, as one card over mustard.
 *
 * What the host needs, in the order it needs it, and then out of the way. There is no header
 * saying which panel this is and no progress dots: the list is short enough to read, and `ALL DONE`
 * ending it is the same shape every other commitment in the language takes. Mustard stripes behind
 * it — this is the moment that colour exists for, and when the card is finished with they part
 * along their own axis to reveal what was always running behind them.
 */
@Composable
fun Welcome(
    requirements: List<Requirement>,
    status: String,
    onGrant: (Int) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var leaving by remember { mutableStateOf(false) }
    val journey by animateFloatAsState(
        targetValue = if (leaving) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "journey",
        finishedListener = { if (it == 1f) onFinished() }
    )
    val settled = requirements.all { it.granted }

    // The same ground the running card stands on, so finishing onboarding does not change the
    // scenery — only what is standing on it.
    MontStripedStage(
        modifier = modifier,
        split = journey,
        // The card goes first, and faster than the ground it is standing on.
        cardAlpha = 1f - (journey / 0.22f).coerceAtMost(1f)
    ) {
            Column {
                Text("air", color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Thin, fontSize = 40.sp)
                Text("Mate", color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Black, fontSize = 40.sp)
            }
            Detail("Samsung DeX on this phone, on the tablet you already own.")
            Spacer(Modifier.height(4.dp))

            requirements.forEachIndexed { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !item.granted) { onGrant(index) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(item.label.uppercase(), if (item.granted) MontWhite.ACTIVE else 0.55f, 12)
                        Detail(item.detail)
                    }
                    Label(
                        if (item.granted) "GRANTED" else item.action.uppercase(),
                        if (item.granted) 0.55f else MontWhite.ACTIVE,
                        11
                    )
                }
            }

            if (status.isNotBlank()) Detail(status)

            // Dim until there is nothing left to grant, so it reads as the end of the list rather
            // than a way past it.
            Text(
                "ALL DONE",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = settled) { leaving = true }
                    .padding(vertical = 6.dp),
                color = Color.White.copy(if (settled) MontWhite.ACTIVE else 0.30f),
                fontFamily = Mont,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp
            )
    }
}

@Composable
private fun Label(text: String, alpha: Float, size: Int) {
    Text(
        text,
        color = Color.White.copy(alpha),
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = size.sp
    )
}

@Composable
private fun Detail(text: String) {
    Text(
        text,
        color = Color.White.copy(MontWhite.DETAIL),
        fontFamily = Mont,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
}
