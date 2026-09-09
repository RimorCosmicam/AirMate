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
 * What to do when the picture cannot arrive both whole and on time.
 *
 * The two answers are genuinely opposed and no single number splits the difference, which is why
 * this is a mode rather than a slider. Reading wants the newest picture and does not care what was
 * skipped to get there; video wants every frame and would rather be a moment behind than miss one.
 *
 * Both ends have to agree. The host decides first — it is the one that throws a frame away when the
 * encoder is behind or the socket is full — so the mode is sent to it rather than kept here.
 */
enum class StreamMode(
    val label: String,
    /** Newer frames an incomplete access unit survives before it is given up on. */
    val slackFrames: Int,
    /** How long to wait for a decoder input buffer before dropping the frame. */
    val decoderWaitMicros: Long,
    /** What the host is told, on the wire. */
    val wire: Int
) {
    /** The newest picture, as soon as it exists. Anything late is already out of date. */
    READING("Reading", 0, 0, 0),

    /**
     * Every frame, even a little behind.
     *
     * Nothing is dropped for being late, only for being later than the pipeline can hold. Motion
     * survives; the cost is that the picture sits a frame or two behind the Mac.
     */
    VIDEO("Video", 1, 12_000, 1)
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

    var mode: StreamMode
        get() = runCatching { StreamMode.valueOf(store.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(StreamMode.READING)
        set(value) = store.edit().putString(KEY_MODE, value.name).apply()

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_AXIS = "axis"
        const val KEY_MODE = "streamMode"
        const val KEY_FITTED = "fittedScreen"
    }
}
