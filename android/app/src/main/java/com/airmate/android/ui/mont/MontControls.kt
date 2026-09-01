package com.airmate.android.ui.mont

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment as UiAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The wordmark: the lightest weight over the heaviest at one size. That contrast is the logo.
 */
@Composable
fun MontWordmark(size: Int = 38) {
    val scaled = (size * LocalMontScale.current).sp
    Row {
        Text("air", color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Thin, fontSize = scaled)
        Text("Mate", color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Black, fontSize = scaled)
    }
}

/**
 * A row states its own state through brightness alone. No chevron, no highlight fill: the active
 * item is simply the bright one.
 */
@Composable
fun MontRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    enabled: Boolean = true,
    active: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp * LocalMontScale.current),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MontLabel(
            label.uppercase(),
            Modifier.weight(1f),
            alpha = when {
                !enabled -> MontWhite.DISABLED
                active -> MontWhite.ACTIVE
                else -> MontWhite.DIM
            }
        )
        trailing?.let { MontLabel(it.uppercase(), alpha = MontWhite.DIM, size = 11) }
    }
}

@Composable
fun MontLabel(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = MontWhite.ACTIVE,
    size: Int = 15
) {
    Text(
        text,
        modifier = modifier,
        color = Color.White.copy(alpha),
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = (size * LocalMontScale.current).sp,
        maxLines = 1
    )
}

/** An explanatory line under a row. */
@Composable
fun MontDetail(text: String, modifier: Modifier = Modifier, alpha: Float = MontWhite.DETAIL) {
    Text(
        text,
        modifier = modifier,
        color = Color.White.copy(alpha),
        fontFamily = Mont,
        fontWeight = FontWeight.Normal,
        fontSize = (11 * LocalMontScale.current).sp,
        lineHeight = (15 * LocalMontScale.current).sp
    )
}

/**
 * A choice. No pill, no border, no fill — selected is bright, unselected is dim, exactly the rule
 * a row follows, because a choice *is* a row that happens to sit beside others.
 */
@Composable
fun MontChips(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val scale = LocalMontScale.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp * scale)) {
        options.forEachIndexed { index, option ->
            Text(
                option.uppercase(),
                modifier = Modifier
                    .clickable { onSelect(index) }
                    .padding(vertical = 4.dp * scale),
                color = Color.White.copy(if (index == selected) MontWhite.ACTIVE else MontWhite.DIM),
                fontFamily = Mont,
                fontWeight = FontWeight.Black,
                fontSize = (11 * scale).sp
            )
        }
    }
}

/**
 * The slider stopped at two positions: a white block filling one half, with the state written in
 * the half it has left.
 *
 * The word names what the control currently is, not what tapping it would do — a switch labelled
 * with its own opposite is a puzzle every single time you meet it.
 */
@Composable
fun MontToggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val slide by animateFloatAsState(if (checked) 0f else 1f, label = "montToggle")
    val alpha = if (enabled) 1f else MontWhite.DISABLED

    val scale = LocalMontScale.current
    Box(
        modifier
            .width(56.dp * scale)
            .height(18.dp * scale)
            .clickable(enabled = enabled) { onChange(!checked) }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val half = size.width * .5f
            drawRect(Color.White.copy(MontWhite.TRACK * alpha), Offset.Zero, size)
            drawRect(Color.White.copy(alpha), Offset(half * slide, 0f), Size(half, size.height))
        }
        Text(
            if (checked) "ON" else "OFF",
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .width(28.dp * scale),
            color = Color.White.copy(alpha),
            fontFamily = Mont,
            fontWeight = FontWeight.Black,
            fontSize = (10 * scale).sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * A Mont card.
 *
 * Square, black at 92%, no border and no shadow — the black is the entire separation. Text hangs
 * off a generous left margin and nothing needs the right one, which is why the padding is
 * asymmetric.
 */
@Composable
fun MontCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalMontScale.current
    Column(
        modifier
            .background(MontSurface)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 22.dp * scale,
                top = 20.dp * scale,
                end = 14.dp * scale,
                bottom = 16.dp * scale
            ),
        verticalArrangement = Arrangement.spacedBy(1.dp * scale),
        content = content
    )
}

/**
 * Where a Mont card stands on a tablet.
 *
 * One place decides how big the card is and how big the language inside it is, so the pairing
 * card, the onboarding card and the control card cannot disagree. The card is about a quarter of
 * the screen tall and is never allowed past six sevenths of it, scrolling inside that bound —
 * a card whose content decides whether it still fits on the display is a card that eventually
 * does not.
 */
@Composable
fun MontStage(
    alignment: UiAlignment,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 560dp is roughly the short edge of the phone the language was drawn on, so a tablet
        // lands somewhere near 1.5x and a small screen stays exactly as Mont specifies.
        val scale = (maxHeight / 560.dp).coerceIn(1f, 2f)
        val cardWidth = (maxWidth * 0.44f).coerceIn(340.dp, 760.dp)
        CompositionLocalProvider(LocalMontScale provides scale) {
            MontCard(
                Modifier
                    .align(alignment)
                    .width(cardWidth)
                    .heightIn(min = maxHeight * 0.25f, max = maxHeight * 0.86f),
                content = content
            )
        }
    }
}
