package com.airmate.android

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.airmate.android.decoder.LowLatencyDecoder
import com.airmate.android.network.PairingCode
import com.airmate.android.network.UdpVideoReceiver
import com.airmate.android.protocol.ControlMessage
import com.airmate.android.protocol.StatusMessage
import com.airmate.android.ui.AspectSurfaceView
import com.airmate.android.ui.CardEdge
import com.airmate.android.ui.ControlCard
import com.airmate.android.ui.EdgeGestureDetector
import com.airmate.android.ui.OnboardingScreen
import com.airmate.android.ui.PairingScreen
import com.airmate.android.ui.StripeBackdrop
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.roundToInt

/**
 * The whole client.
 *
 * While pairing there is a Mont card over moving stripes. The moment video arrives the stripes
 * part to reveal the stream already running behind them, and then every bit of that — the card,
 * the animation, the Compose view itself — is taken out of the hierarchy, so what remains on the
 * device is a surface and a decoder. An inward swipe from either edge brings the controls back on
 * the side it came from.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var settings: AirMateSettings
    private lateinit var surfaceView: AspectSurfaceView
    private lateinit var root: FrameLayout
    private var overlay: ComposeView? = null
    private lateinit var edgeDetector: EdgeGestureDetector
    private var decoder: LowLatencyDecoder? = null
    private var receiver: UdpVideoReceiver? = null

    private var status by mutableStateOf<StatusMessage?>(null)
    private var streaming by mutableStateOf(false)
    private var everStreamed by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var scanError by mutableStateOf<String?>(null)
    private var cardEdge by mutableStateOf<CardEdge?>(null)
    private var leaving by mutableStateOf(false)
    private var onboarded by mutableStateOf(false)
    private var axis by mutableStateOf(ScreenAxis.HORIZONTAL)
    private var leniency by mutableStateOf(FrameLeniency.ACTUAL)

    private var pairingHost: String? = null
    private var pairingPort = 48620

    /// Which edge the pending back gesture started from, when the system will tell us.
    private var pendingEdge: CardEdge? = null

    /**
     * Back, while the stream is live, opens the controls rather than leaving.
     *
     * Under gesture navigation an inward edge swipe *is* the back gesture, and the system claims it
     * before any view sees it — so the app was being finished by exactly the gesture meant to open
     * its menu. Reserving the edges (below) gets the touch back for the strips we are allowed to
     * claim; this catches every swipe outside them, so the gesture never closes the app by
     * surprise wherever it starts.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            // Only Android 14 and up reports which side the swipe came from.
            pendingEdge = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) {
                CardEdge.RIGHT
            } else {
                CardEdge.LEFT
            }
        }

        override fun handleOnBackPressed() {
            if (cardEdge != null) {
                cardEdge = null
            } else {
                cardEdge = pendingEdge ?: edgeDetector.lastTouchedSide ?: CardEdge.LEFT
            }
            pendingEdge = null
            syncOverlay()
        }
    }

    /** When the Mac last said anything about itself. */
    @Volatile private var lastStatusNanos = 0L

    /** When the stream first looked unwell, or zero while it looks fine. */
    private var troubledSince = 0L

    // Measured rather than promised: the frame-skip setting is only worth choosing between if you
    // can see what it costs and what it saves.
    private var fps by mutableIntStateOf(0)
    private var dropPercent by mutableFloatStateOf(0f)
    private var sampleNanos = 0L
    private var sampleDecoded = 0L
    private var sampleDropped = 0L

    private val watchStream = object : Runnable {
        override fun run() {
            val frames = receiver?.receivedFrames ?: 0
            // Liveness comes from the Mac's own once-a-second status, not from frames arriving.
            // ScreenCaptureKit only produces a frame when the display changes, so a desktop nobody
            // is touching looks exactly like a stream that has died.
            // Only a Mac that *was* talking and then stopped counts as gone. A Mac that has never
            // sent status — an older build, or one whose datagrams are being dropped — must not be
            // declared dead on the strength of a signal that was never there.
            val now = System.nanoTime()
            val everHeard = lastStatusNanos != 0L
            val goneQuiet = everHeard && now - lastStatusNanos >= HOST_SILENCE_NANOS
            val hostRunning = status?.running != false
            val healthy = !goneQuiet && hostRunning

            // Trouble has to persist before anything is torn down. A display being restarted, a
            // dropped status datagram or a single stalled tick would otherwise flash the whole
            // pairing screen over the video and take it away again half a second later, which is
            // far worse to sit in front of than the brief interruption it is reporting.
            if (healthy) troubledSince = 0L else if (troubledSince == 0L) troubledSince = now

            if (frames > 0 && !streaming && healthy) {
                streaming = true
                everStreamed = true
                // The ground only parts once there is something behind it to reveal, and once the
                // reader is done being introduced to it.
                if (onboarded) leaving = true
                syncOverlay()
            } else if (streaming && troubledSince != 0L && now - troubledSince >= HOST_SILENCE_NANOS) {
                streaming = false
                leaving = false
                cardEdge = null
                troubledSince = 0L
                syncOverlay()
            }
            sampleRates()
            root.postDelayed(this, 500)
        }
    }

    /** Decoded frames per second, and the share of frames that never made it, over the last second. */
    private fun sampleRates() {
        val now = System.nanoTime()
        if (sampleNanos == 0L) {
            sampleNanos = now
            return
        }
        val elapsed = now - sampleNanos
        if (elapsed < 1_000_000_000L) return

        val decoded = decoder?.decodedFrames ?: 0
        val dropped = (decoder?.droppedFrames ?: 0) + (receiver?.abandonedFrames ?: 0)
        val decodedDelta = decoded - sampleDecoded
        val droppedDelta = dropped - sampleDropped
        val seconds = elapsed / 1_000_000_000.0

        fps = (decodedDelta / seconds).roundToInt()
        val attempted = decodedDelta + droppedDelta
        dropPercent = if (attempted > 0) droppedDelta * 100f / attempted else 0f

        sampleNanos = now
        sampleDecoded = decoded
        sampleDropped = dropped
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AirMateSettings(this)
        onboarded = settings.onboarded
        axis = settings.axis
        leniency = settings.leniency
        requestedOrientation = axis.requested

        enterImmersiveMode()
        window.decorView.keepScreenOn = true

        surfaceView = AspectSurfaceView(this).also { it.holder.addCallback(this) }
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        edgeDetector = EdgeGestureDetector(this) { edge ->
            if (streaming) {
                cardEdge = edge
                syncOverlay()
            }
        }
        root.setOnTouchListener(edgeDetector)
        onBackPressedDispatcher.addCallback(this, backCallback)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reserveEdges() }
        setContentView(root)
        syncOverlay()
        root.post(watchStream)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Ask the system to leave the edge strips alone.
     *
     * Android caps this at 200dp of height per edge, so it cannot cover the whole side — the back
     * callback handles whatever falls outside. Within these bands the swipe reaches
     * [EdgeGestureDetector] intact, which is the only way to know for certain which side it came
     * from on a device older than Android 14.
     */
    private fun reserveEdges() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val density = resources.displayMetrics.density
        val band = (200 * density).toInt()
        val width = (28 * density).toInt()
        val top = ((root.height - band) / 2).coerceAtLeast(0)
        val bottom = (top + band).coerceAtMost(root.height)
        if (root.width <= 0 || bottom <= top) return
        root.systemGestureExclusionRects = listOf(
            Rect(0, top, width, bottom),
            Rect(root.width - width, top, root.width, bottom)
        )
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // MARK: - The overlay, which exists only when it has something to say

    /**
     * Add or remove the Compose view.
     *
     * This is the whole point of the design: once the stream is live and no card is open there is
     * no Compose view in the hierarchy at all, so nothing composes, animates or invalidates behind
     * the video.
     */
    private fun syncOverlay() {
        // Once past onboarding, back always means "show me the controls" and never "leave" — the
        // way a game pauses rather than quits. Tying this to the stream meant that whenever the
        // video had not started, or was mid-handover, the gesture silently walked out of the app
        // instead, which is the one outcome it must never have.
        backCallback.isEnabled = onboarded
        val wanted = !onboarded || !streaming || leaving || cardEdge != null
        if (wanted && overlay == null) {
            val view = ComposeView(this).apply { setContent { Overlay() } }
            overlay = view
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        } else if (!wanted && overlay != null) {
            root.removeView(overlay)
            overlay?.disposeComposition()
            overlay = null
        }
    }

    @Composable
    private fun Overlay() {
        // The ground parts along the bands' own axis, revealing the stream already running behind
        // it. The card goes first, and faster than the ground it is standing on.
        val journey by animateFloatAsState(
            targetValue = if (leaving) 1f else 0f,
            animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
            label = "journey",
            finishedListener = {
                if (it == 1f) {
                    // The parting is over. Clearing this is what actually takes the overlay off the
                    // device — left set, the ComposeView stayed mounted over the video for the rest
                    // of the session, eating every touch and holding the back callback disabled.
                    // Posted so the view is removed outside the animation's own callback.
                    root.post {
                        leaving = false
                        syncOverlay()
                    }
                }
            }
        )

        Box(Modifier.fillMaxSize()) {
            if (!streaming || leaving) {
                StripeBackdrop(split = journey, disconnected = onboarded && everStreamed && !streaming)
            }
            Box(Modifier.alpha(1f - (journey / 0.22f).coerceAtMost(1f))) {
                val edge = cardEdge
                when {
                    edge != null -> ControlCard(
                        edge = edge,
                        status = status,
                        axis = axis,
                        leniency = leniency,
                        fps = fps,
                        dropPercent = dropPercent,
                        onStartStop = { start ->
                            send(ControlMessage.simple(if (start) ControlMessage.TYPE_START else ControlMessage.TYPE_STOP))
                        },
                        onResolution = { width, height ->
                            send(ControlMessage.setDisplay(width, height, status?.hiDPI ?: true))
                        },
                        onHiDPI = { hiDPI ->
                            val current = status
                            send(
                                ControlMessage.setDisplay(
                                    current?.width ?: 1920,
                                    current?.height ?: 1080,
                                    hiDPI
                                )
                            )
                        },
                        onAxis = ::applyAxis,
                        onLeniency = ::applyLeniency,
                        onRequestKeyframe = { send(ControlMessage.simple(ControlMessage.TYPE_REQUEST_IDR)) },
                        onDismiss = { cardEdge = null; syncOverlay() }
                    )
                    !onboarded -> OnboardingScreen(
                        scanning = scanning,
                        scanError = scanError,
                        onScan = ::scanPairingCode,
                        onFinished = {
                            settings.onboarded = true
                            onboarded = true
                            // If the stream arrived while they were still reading, the ground parts
                            // now rather than never.
                            if (streaming) leaving = true
                            syncOverlay()
                        }
                    )
                    else -> PairingScreen(
                        paired = pairingHost != null || status != null,
                        scanning = scanning,
                        scanError = scanError,
                        disconnected = everStreamed && !streaming,
                        onScan = ::scanPairingCode
                    )
                }
            }
        }
    }

    /**
     * Turn the tablet, and turn the Mac's display to match.
     *
     * Rotating only the tablet leaves a landscape desktop letterboxed into a portrait panel, which
     * is a smaller picture rather than a taller one. The host is asked to turn its display too, by
     * swapping the side lengths it already reported — so this follows whatever size is actually in
     * use rather than assuming one.
     *
     * A host that mirrors a display it did not create ignores this, as it should: that display's
     * shape is not AirMate's to set. The tablet still rotates either way.
     */
    private fun applyAxis(next: ScreenAxis) {
        axis = next
        settings.axis = next
        requestedOrientation = next.requested

        val current = status
        if (current == null) {
            Log.d(TAG, "axis $next: no status from the host yet, tablet rotates alone")
            return
        }
        val long = maxOf(current.width, current.height)
        val short = minOf(current.width, current.height)
        val width = if (next == ScreenAxis.VERTICAL) short else long
        val height = if (next == ScreenAxis.VERTICAL) long else short
        if (width != current.width || height != current.height) {
            send(ControlMessage.setDisplay(width, height, current.hiDPI))
        }
    }

    private fun applyLeniency(next: FrameLeniency) {
        leniency = next
        settings.leniency = next
        receiver?.leniency = next
    }

    private fun send(bytes: ByteArray) = receiver?.sendControl(bytes) ?: Unit

    // MARK: - Pairing

    /**
     * Open the Play services code scanner.
     *
     * The module it needs is optional and is fetched at install time from the manifest's
     * `com.google.mlkit.vision.DEPENDENCIES` — but only for an app installed through Play. AirMate
     * is sideloaded, so that never happens and `startScan` fails immediately without ever opening
     * the camera. Asking for the module explicitly is what makes the scanner work on a build
     * people downloaded themselves.
     */
    private fun scanPairingCode() {
        if (scanning) return
        scanning = true
        scanError = null

        val scanner = GmsBarcodeScanning.getClient(
            this,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )
        ModuleInstall.getClient(this)
            .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            .addOnSuccessListener { startScan(scanner) }
            .addOnFailureListener { error ->
                scanning = false
                scanError = "Scanner unavailable: ${error.message ?: error.javaClass.simpleName}. Same Wi‑Fi still works."
                Log.e(TAG, "Code scanner module install failed", error)
            }
    }

    private fun startScan(scanner: GmsBarcodeScanner) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                scanning = false
                val target = barcode.rawValue?.let(PairingCode::parse)
                if (target == null) {
                    scanError = "That isn't an AirMate pairing code."
                    return@addOnSuccessListener
                }
                scanError = null
                pairingHost = target.host
                pairingPort = target.port
                receiver?.pairWith(target.host, target.port)
            }
            .addOnCanceledListener { scanning = false }
            .addOnFailureListener { error ->
                scanning = false
                // The real reason, not a generic apology. The previous build swallowed this and
                // left nothing to diagnose from.
                scanError = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "startScan failed", error)
            }
    }

    // MARK: - Surface lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {
        val created = LowLatencyDecoder(holder.surface)
        decoder = created
        receiver = UdpVideoReceiver(created, onStatus = { message ->
            lastStatusNanos = System.nanoTime()
            runOnUiThread {
                status = message
                surfaceView.setAspect(message.width, message.height)
            }
        }).also { active ->
            active.leniency = leniency
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
        root.removeCallbacks(watchStream)
        receiver?.close()
        decoder?.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AirMate.Android"
        /** How long the Mac may go quiet before the tablet stops believing in it. */
        const val HOST_SILENCE_NANOS = 4_000_000_000L
    }
}
