package com.airmate.host.ui.mont

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.airmate.host.R

/**
 * Poppins, the typeface, across the weights this app uses.
 *
 * Black is not an emphasis weight here — it is the default, which is what lets a plain word act as
 * a button without a box drawn around it. SemiBold ships even though little names it: Compose picks
 * the nearest supplied weight, and without it Medium collapses onto Regular.
 */
val Mont = FontFamily(
    Font(R.font.poppins_thin, FontWeight.Thin),
    Font(R.font.poppins_light, FontWeight.Light),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_black, FontWeight.Black)
)

/**
 * The Mont surface: black, with whatever is behind it faintly present through the last eight
 * percent. The same value the macOS window, the AirMate client and MiniMate use, so the language
 * cannot drift apart across them.
 */
const val MONT_SURFACE_ALPHA = .92f

val MontSurface = Color.Black.copy(alpha = MONT_SURFACE_ALPHA)

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
 * Every size in the language is multiplied by this rather than re-specified, so the proportions
 * that make Mont look like Mont survive a change of screen.
 */
val LocalMontScale = compositionLocalOf { 1f }
