package com.airmate.android

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airmate.android.decoder.LowLatencyDecoder
import com.airmate.android.network.UdpVideoReceiver

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView
    private var decoder: LowLatencyDecoder? = null
    private var receiver: UdpVideoReceiver? = null
    private val updateStats = object : Runnable {
        override fun run() {
            val r = receiver; val d = decoder
            overlay.text = "AirMate • 1920×1080 • HEVC\nReceived ${r?.receivedFrames ?: 0} • Decoded ${d?.decodedFrames ?: 0} • Dropped ${d?.droppedFrames ?: 0}"
            overlay.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.keepScreenOn = true
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x66000000); textSize = 13f
            setPadding(18, 12, 18, 12); text = "AirMate • waiting for Mac"
        }
        setContentView(FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START })
        })
        overlay.post(updateStats)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = LowLatencyDecoder(holder.surface)
        receiver = UdpVideoReceiver(decoder!!).also { it.start() }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { receiver?.close(); receiver = null; decoder?.close(); decoder = null }
    override fun onDestroy() { overlay.removeCallbacks(updateStats); receiver?.close(); decoder?.close(); super.onDestroy() }
}
