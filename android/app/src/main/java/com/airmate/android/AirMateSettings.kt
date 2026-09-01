package com.airmate.android

import android.content.Context
import android.content.pm.ActivityInfo

/**
 * Which way round the tablet is allowed to turn.
 *
 * Both values let Android rotate on its own, but only within one axis, so the picture follows the
 * tablet when it is flipped end to end and never half-turns into the other shape. The Mac keeps
 * streaming one resolution either way — nothing here renegotiates the display.
 */
enum class ScreenAxis(val label: String, val requested: Int) {
    HORIZONTAL("Horizontal", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    VERTICAL("Vertical", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
}

/**
 * How hard the client tries not to drop a frame.
 *
 * `ACTUAL` is what AirMate has always done and is the lowest-latency case: an incomplete access
 * unit is abandoned the moment a newer one appears, and a frame is dropped outright if the decoder
 * has no input buffer free this instant. Every other setting buys tolerance with latency, so the
 * scale only goes upward from here.
 */
enum class FrameLeniency(
    val label: String,
    /** Newer frames an incomplete access unit survives before it is given up on. */
    val slackFrames: Int,
    /** How long to wait for a decoder input buffer before dropping the frame. */
    val decoderWaitMicros: Long
) {
    ACTUAL("Default", 0, 0),
    ONE("+16 ms", 1, 2_000),
    TWO("+33 ms", 2, 4_000),
    FOUR("+66 ms", 4, 8_000)
}

/**
 * The handful of things the client remembers between runs. Deliberately small — everything else
 * about a session is the Mac's to know.
 */
class AirMateSettings(context: Context) {
    private val store = context.getSharedPreferences("airmate", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = store.getBoolean(KEY_ONBOARDED, false)
        set(value) = store.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var axis: ScreenAxis
        get() = runCatching { ScreenAxis.valueOf(store.getString(KEY_AXIS, null) ?: "") }
            .getOrDefault(ScreenAxis.HORIZONTAL)
        set(value) = store.edit().putString(KEY_AXIS, value.name).apply()

    /**
     * Whether this tablet has already asked the host to match its screen.
     *
     * Matching rebuilds the host's display, so it is done once, on the first connection a tablet
     * ever makes, when there is nothing open on that display to disturb.
     */
    var fittedScreen: Boolean
        get() = store.getBoolean(KEY_FITTED, false)
        set(value) = store.edit().putBoolean(KEY_FITTED, value).apply()

    var leniency: FrameLeniency
        get() = runCatching { FrameLeniency.valueOf(store.getString(KEY_LENIENCY, null) ?: "") }
            .getOrDefault(FrameLeniency.ACTUAL)
        set(value) = store.edit().putString(KEY_LENIENCY, value.name).apply()

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_AXIS = "axis"
        const val KEY_LENIENCY = "leniency"
        const val KEY_FITTED = "fittedScreen"
    }
}
