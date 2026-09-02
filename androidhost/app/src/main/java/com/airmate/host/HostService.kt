package com.airmate.host

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * A placeholder for the notification that keeps a session alive in the background.
 *
 * The desktop currently lives for as long as the ADB stream does, which is as long as this process
 * does. That is fine while the app is open and wrong the moment Android decides to reclaim it, so
 * the session belongs in a foreground service — this is where it will go.
 */
class HostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
