package com.airmate.host.ui.mont

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A black card standing on moving mustard.
 *
 * One definition, used by both screens this app has, so the ground cannot be one thing during
 * onboarding and another the moment it finishes. On a phone the card is what makes the language
 * legible: a full-bleed black screen with a few words on it is just a few words on a screen.
 *
 * @param split pulls the stripes apart along their own axis, for leaving.
 * @param cardAlpha fades the card, which goes first and faster than the ground it stands on.
 */
@Composable
fun MontStripedStage(
    modifier: Modifier = Modifier,
    split: Float = 0f,
    cardAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "stage")
    val travel by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "stripes"
    )

    Box(modifier.fillMaxSize().background(Color.Black)) {
        DiagonalStripes(
            travel = travel,
            first = MontAccent.Mustard,
            second = Color.Black,
            split = split,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 18.dp)
                .alpha(cardAlpha)
                .background(MontSurface)
                .padding(start = 22.dp, top = 22.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}
