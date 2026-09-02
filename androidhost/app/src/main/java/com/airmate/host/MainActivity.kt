package com.airmate.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airmate.host.ui.PairingOverlay
import com.airmate.host.ui.PairingStrip
import com.airmate.host.ui.Requirement
import com.airmate.host.ui.Welcome
import com.airmate.host.ui.mont.MontDetail
import com.airmate.host.ui.mont.MontChips
import com.airmate.host.ui.mont.MontRow
import com.airmate.host.ui.mont.MontStripedStage
import com.airmate.host.ui.mont.MontWordmark

/**
 * The host's own window.
 *
 * Two screens and nothing else: the things that have to be granted before any of this can work, and
 * then a single row that starts and stops the desktop. Everything the host does after that happens
 * on the tablet, which is where the user is looking.
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: HostController

    /** What went wrong the last time the pairing strip was asked for, if anything. */
    private var overlayFailure by mutableStateOf("")

    private var code by mutableStateOf("")
    private var port by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = HostController(applicationContext)
        setContent { Root(controller) }
    }

    override fun onDestroy() {
        PairingOverlay.hide()
        // The controller is deliberately not released. The desktop should survive this window being
        // closed, the same way it survives the phone going into a pocket; it ends when asked to.
        super.onDestroy()
    }

    @Composable
    private fun Root(controller: HostController) {
        var accessibility by remember { mutableStateOf(HostAccessibilityService.granted(this)) }
        var paired by remember { mutableStateOf(controller.paired) }
        var welcomed by remember { mutableStateOf(welcomeSeen) }

        // Both of these are switched on in Settings, which means the user is always in another app
        // when they change. A value sampled once at composition is a value that never changes.
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accessibility = HostAccessibilityService.granted(this@MainActivity)
                    paired = controller.paired
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        val state by controller.state.collectAsState()

        if (!welcomed || !accessibility || !paired) {
            Welcome(
                requirements = listOf(
                    Requirement(
                        label = "Accessibility",
                        detail = "The pairing code lives in Android's own settings screen. " +
                            "AirMate floats its code field over that screen, and only an " +
                            "accessibility service may open a window there.",
                        granted = accessibility,
                        action = "Allow"
                    ),
                    Requirement(
                        label = "Wireless debugging",
                        detail = "Only the shell user may create the display DeX runs on. " +
                            "Pairing once is how AirMate becomes it, on this phone alone.",
                        granted = paired,
                        action = "Pair"
                    )
                ),
                status = overlayFailure.ifBlank { state.message },
                onGrant = { index ->
                    when (index) {
                        0 -> openAccessibilitySettings()
                        else -> openPairing()
                    }
                },
                onFinished = {
                    welcomeSeen = true
                    welcomed = true
                }
            )
        } else {
            HostCard(controller)
        }
    }

    /**
     * The running host, as two facts.
     *
     * One row that starts or stops the desktop, and the sizes it can run at. Nothing else: the
     * display id, the frame counters and the host's own log are all things the machine knows and
     * nobody asked to see, and a card that lists them is a card you have to read rather than use.
     *
     * It stands on the same mustard the onboarding does, because on a phone the card is what makes
     * the language readable — full-bleed black with a few words on it is just words on a screen.
     */
    @Composable
    private fun HostCard(controller: HostController) {
        val state by controller.state.collectAsState()
        MontStripedStage {
            MontWordmark(tail = "Mate")

            // Offered only while there is something to show them on. Changing the size rebuilds the
            // display, and its whole effect is on a screen that is not there yet.
            if (state.running) {
                val choices = state.choices
                MontChips(
                    options = choices.map { "${it.first} × ${it.second}" },
                    selected = choices.indexOf(state.size)
                ) { index ->
                    val (width, height) = choices[index]
                    controller.setResolution(width, height)
                }
                MontRow(label = "Stop the desktop") { controller.stop() }
            } else {
                MontRow(label = "Start the desktop", enabled = !state.busy) { controller.start() }
            }

            // One line, and only when it is carrying something the user has to act on.
            if (state.stage == HostStage.FAILED) MontDetail(state.message)
        }
    }

    /**
     * Send the user to wireless debugging with the code field going along.
     *
     * The strip is floated *before* the intent, so it is already there when Settings arrives rather
     * than appearing over it a moment later. It is floated on this window's own display, because a
     * folded Flip is showing its cover screen and the default display is the one that is switched
     * off.
     */
    private fun openPairing() {
        overlayFailure = ""
        code = ""
        port = ""
        val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val failure = PairingOverlay.show(displayId) {
            val live by controller.state.collectAsState()
            PairingStrip(
                code = code,
                port = port,
                discoveredPort = live.pairingPort,
                status = when {
                    live.stage == HostStage.PAIRING -> "Pairing…"
                    live.stage == HostStage.FAILED -> live.message
                    live.pairingPort != null -> "Found the pairing port. Type the six digits."
                    else -> "Tap “Pair device with pairing code”."
                },
                busy = live.busy,
                onCode = { entered -> code = entered.filter(Char::isDigit).take(6) },
                onPort = { entered -> port = entered.filter(Char::isDigit).take(5) },
                onPair = {
                    val chosen = port.toIntOrNull() ?: live.pairingPort ?: return@PairingStrip
                    controller.pair(chosen, code) { runOnUiThread { returnFromPairing(displayId) } }
                },
                onClose = { PairingOverlay.hide() }
            )
        }
        if (failure != null) {
            // Said out loud rather than silently redirected. Sending the user to a settings screen
            // they did not ask for, to fix something they were not told about, is how this looked
            // like the pair button simply opening the wrong page.
            overlayFailure = "The pairing field could not open: $failure"
            return
        }
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    /**
     * Put the app back in front, on the screen the user was using.
     *
     * `REORDER_TO_FRONT` rather than a fresh launch: this activity is still alive behind Settings,
     * and starting another copy of it would throw away everything on screen — including the state
     * that has just changed and is the reason for coming back at all.
     */
    private fun returnFromPairing(displayId: Int) {
        PairingOverlay.hide()
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                ),
                options.toBundle()
            )
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private var welcomeSeen: Boolean
        get() = preferences.getBoolean(WELCOME, false)
        set(value) { preferences.edit().putBoolean(WELCOME, value).apply() }

    private val preferences
        get() = getSharedPreferences("airmate_host", Context.MODE_PRIVATE)

    private companion object {
        const val WELCOME = "welcome_seen"
    }
}
