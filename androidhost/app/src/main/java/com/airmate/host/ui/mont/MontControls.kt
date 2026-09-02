package com.airmate.host.ui.mont

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The wordmark: the lightest weight over the heaviest at one size. That contrast is the logo. */
@Composable
fun MontWordmark(size: Int = 34, tail: String = "Mate") {
    val scaled = (size * LocalMontScale.current).sp
    Row {
        Text("air", color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Thin, fontSize = scaled)
        Text(tail, color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Black, fontSize = scaled)
    }
}

/**
 * A row of the interface. Text alone, bright when it can be used and dim when it cannot.
 *
 * There is no box, pill or border here and there is not meant to be one: under Mont the type is
 * what makes a word act as a button, and adding a container back is the one thing the language
 * exists to do without.
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
    size: Int = 15,
    colour: Color = Color.White
) {
    Text(
        text,
        modifier = modifier,
        color = colour.copy(alpha = alpha),
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
        color = Color.White.copy(alpha = alpha),
        fontFamily = Mont,
        fontWeight = FontWeight.Normal,
        fontSize = (11 * LocalMontScale.current).sp,
        lineHeight = (15 * LocalMontScale.current).sp
    )
}

/**
 * A choice. No pill, no border, no fill — selected is bright, unselected is dim, exactly the rule a
 * row follows, because a choice *is* a row that happens to sit beside others.
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
 * A Mont card: square, black at 92%, no border and no shadow.
 *
 * Text hangs off a generous left margin and nothing needs the right one, which is why the padding
 * is asymmetric.
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
 * Where a Mont surface stands, and how big the language inside it is.
 *
 * One place decides both, so no two screens in the app can disagree about them. The scale is
 * measured against the short edge because a Flip's cover screen and its unfolded panel are wildly
 * different sizes and the same figures have to read on both.
 */
@Composable
fun MontStage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val shortEdge = minOf(maxWidth, maxHeight)
        // 560dp is roughly the short edge of the phone the language was drawn on, so a small screen
        // stays exactly as Mont specifies and a larger one grows in proportion rather than in spots.
        val scale = (shortEdge / 560.dp).coerceIn(1f, 1.6f)
        CompositionLocalProvider(LocalMontScale provides scale) {
            MontCard(Modifier.fillMaxSize(), content = content)
        }
    }
}
