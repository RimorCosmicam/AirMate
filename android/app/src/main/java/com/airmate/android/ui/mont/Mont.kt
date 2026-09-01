package com.airmate.android.ui.mont

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airmate.android.R

/**
 * Mont, the typeface, across five weights.
 *
 * Compose picks the nearest supplied weight for anything not listed, so SemiBold has to ship even
 * though the interface rarely names it: without it Medium collapses onto Regular and headings stop
 * reading as headings. Black is not an emphasis weight here — it is the default, which is what
 * lets a plain word act as a button without a box around it.
 */
val Mont = FontFamily(
    Font(R.font.mont_thin, FontWeight.Thin),
    Font(R.font.mont_light, FontWeight.Light),
    Font(R.font.mont_regular, FontWeight.Normal),
    Font(R.font.mont_semibold, FontWeight.SemiBold),
    Font(R.font.mont_black, FontWeight.Black)
)

/**
 * The Mont surface: black, with whatever is behind it faintly present through the last eight
 * percent. One definition, shared with the macOS half and with MiniMate, so the language cannot
 * drift apart between them.
 */
const val MONT_SURFACE_ALPHA = .92f

val MontSurface = Color.Black.copy(alpha = MONT_SURFACE_ALPHA)

/** How far a top-anchored Mont surface holds off the edge. */
val MONT_TOP_INSET = 44.dp

/** White carries all the hierarchy, through opacity alone. */
object MontWhite {
    const val ACTIVE = 1f
    const val PRIMARY = .92f
    const val DETAIL = .62f
    const val DIM = .58f
    const val DISABLED = .35f
    const val TRACK = .09f
}

/** One accent at a time, never as decoration, always carrying a state. */
object MontAccent {
    val Mustard = Color(0xFFD8A628)
    val Live = Color(0xFF2E9E5B)
    val Danger = Color(0xFFC0392B)
}

/**
 * How much bigger everything is than the cover display Mont was drawn for.
 *
 * The scale exists because the language's figures — 15sp rows, a 34dp band, 22dp of left padding —
 * were chosen for a screen a few centimetres across held at arm's length. Reused unchanged on a
 * ten-inch tablet they are technically correct and read as a postage stamp in the corner. Every
 * size in the language is multiplied by this rather than being re-specified, so the proportions
 * that make Mont look like Mont survive the change of screen.
 */
val LocalMontScale = compositionLocalOf { 1f }
