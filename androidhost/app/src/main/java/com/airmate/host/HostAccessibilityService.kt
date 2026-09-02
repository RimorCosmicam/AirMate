package com.airmate.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The service that owns a window over Settings, and delivers touches onto the desktop.
 *
 * Two jobs, and neither of them is reading the screen. It never asks for window content: the
 * manifest withholds `canRetrieveWindowContent`, so this cannot see what you are doing even though
 * accessibility services generally can.
 *
 * Pairing needs it because Android's six-digit code lives in its own Settings activity. Opening
 * that puts this app in the background, so a pairing sheet drawn in our own window is a sheet you
 * cannot see at the moment you need it. An accessibility overlay sits above Settings instead.
 */
class HostAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * A tap, on a named display.
     *
     * `setDisplayId` is the whole reason gestures are dispatched from here rather than from the
     * host process: without it every synthesised touch lands on the phone's own screen instead of
     * the desktop the tablet is looking at.
     */
    fun tap(displayId: Int, x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(displayId, path, 0L, TAP_MILLIS)
    }

    /** A drag, which on a touch display is what a scroll is. */
    fun drag(displayId: Int, fromX: Float, fromY: Float, toX: Float, toY: Float, millis: Long) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        dispatch(displayId, path, 0L, millis.coerceIn(MIN_DRAG_MILLIS, MAX_DRAG_MILLIS))
    }

    private fun dispatch(displayId: Int, path: Path, start: Long, duration: Long) {
        runCatching {
            val gesture = GestureDescription.Builder()
                .setDisplayId(displayId)
                .addStroke(GestureDescription.StrokeDescription(path, start, duration))
                .build()
            dispatchGesture(gesture, null, null)
        }.onFailure { Log.w(TAG, "gesture on display $displayId was refused", it) }
    }

    companion object {
        private const val TAG = "AirMate.Host.A11y"
        private const val TAP_MILLIS = 40L
        private const val MIN_DRAG_MILLIS = 40L
        private const val MAX_DRAG_MILLIS = 400L

        /** The running service, or null when it is enabled but has not been bound yet. */
        @Volatile
        var instance: HostAccessibilityService? = null
            private set

        /**
         * Whether the user has granted this, according to the setting that records it.
         *
         * Asked of Settings rather than of [instance], because the two answer different questions.
         * The system binds the service a moment after the app process starts, so a screen that
         * reads [instance] to decide whether the grant exists says NOT GRANTED for the first
         * fraction of a second of every launch — and after a force-stop, for as long as it takes
         * Android to get round to rebinding.
         */
        fun granted(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = android.content.ComponentName(
                context, HostAccessibilityService::class.java
            )
            return enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it) == target
            }
        }
    }
}
