package com.airmate.android

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.airmate.android.decoder.LowLatencyDecoder
import com.airmate.android.network.UdpVideoReceiver
import com.airmate.android.network.PairingCode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var waitingPanel: LinearLayout
    private lateinit var statusLabel: TextView
    private var decoder: LowLatencyDecoder? = null
    private var receiver: UdpVideoReceiver? = null
    private var pairingHost: String? = null
    private var pairingPort = 48620

    private val updateConnectionState = object : Runnable {
        override fun run() {
            val connected = (receiver?.receivedFrames ?: 0) > 0
            waitingPanel.visibility = if (connected) View.GONE else View.VISIBLE
            statusLabel.text = if (pairingHost == null) {
                "Open AirMate on your Mac, or scan its pairing code."
            } else {
                "Connecting to your Mac…"
            }
            waitingPanel.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        window.decorView.keepScreenOn = true
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }

        val title = TextView(this).apply {
            text = "AirMate"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        statusLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFB8BDC7.toInt())
            gravity = Gravity.CENTER
        }
        val scanButton = Button(this).apply {
            text = "Scan Mac Pairing Code"
            setOnClickListener { scanPairingCode() }
        }
        waitingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 40, 56, 40)
            setBackgroundColor(0xE612151B.toInt())
            addView(title)
            addView(statusLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 })
            addView(scanButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 26 })
        }

        val root = FrameLayout(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(waitingPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        waitingPanel.post(updateConnectionState)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val target = barcode.rawValue?.let(PairingCode::parse)
                if (target == null) {
                    Toast.makeText(this, "That isn’t an AirMate pairing code.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                pairingHost = target.host
                pairingPort = target.port
                receiver?.pairWith(target.host, target.port)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn’t open the QR scanner.", Toast.LENGTH_LONG).show()
            }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = LowLatencyDecoder(holder.surface)
        receiver = UdpVideoReceiver(decoder!!).also { active ->
            pairingHost?.let { active.pairWith(it, pairingPort) }
            active.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        receiver?.close()
        receiver = null
        decoder?.close()
        decoder = null
    }

    override fun onDestroy() {
        waitingPanel.removeCallbacks(updateConnectionState)
        receiver?.close()
        decoder?.close()
        super.onDestroy()
    }
}
