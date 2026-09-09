package com.airmate.android.network

import android.util.Log
import com.airmate.android.StreamMode
import com.airmate.android.decoder.LowLatencyDecoder
import com.airmate.android.protocol.ControlMessage
import com.airmate.android.protocol.StatusMessage
import com.airmate.android.protocol.VideoHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UdpVideoReceiver(
    private val decoder: LowLatencyDecoder,
    private val port: Int = 48620,
    private val onStatus: (StatusMessage) -> Unit = {}
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    // Control messages are sent from button taps, which happen on the main thread — and Android
    // throws NetworkOnMainThreadException for a socket write there. The failure was swallowed, so
    // every command the tablet "sent" went nowhere and the Mac was never asked for permission.
    private val controlThread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AirMate-Control").apply { isDaemon = true }
    }
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val reassembler = FrameReassembler()
    @Volatile private var pairingTarget: InetAddress? = null
    @Volatile private var pairingPort: Int = port

    /** Where the Mac actually is, learned from whatever it last sent us. */
    @Volatile private var macAddress: InetAddress? = null
    @Volatile private var macPort: Int = port

    /** A host that has been heard from, and the last thing it said about itself. */
    data class Source(
        val address: InetAddress,
        val port: Int,
        val lastHeardNanos: Long,
        val status: StatusMessage?
    ) {
        val label: String get() = address.hostAddress ?: address.toString()
    }

    private val heard = LinkedHashMap<InetAddress, Source>()

    /** Every host heard from lately. Published whole so the interface never reads a half-built map. */
    @Volatile
    var sources: List<Source> = emptyList()
        private set

    /** Called on the network thread whenever the set of hosts changes. */
    var onSources: (List<Source>) -> Unit = {}

    /** The one host being listened to. Everything from any other address is counted and dropped. */
    @Volatile
    var selected: InetAddress? = null
        private set

    /** Broadcast again until this moment, to find hosts we have not greeted. */
    @Volatile
    private var discoverUntilNanos = Long.MAX_VALUE

    var receivedFrames = 0L; private set

    /**
     * Frames the host numbered but never put on the wire.
     *
     * Frame ids increase by one within a session, so a gap in them is a frame that was dropped
     * before it left the Mac — replaced in the encoder while an older one was still going out, or
     * given up on before its first fragment. It is the only loss this end cannot see any other way,
     * and the only one no amount of patience here can recover.
     */
    var skippedByHost = 0L; private set

    private var highestFrameId = -1L

    /** The session the decoder is currently configured for. */
    private var decodingSession = 0L

    /** Frames the reassembler gave up on, part-built. */
    val abandonedFrames: Long get() = reassembler.abandoned

    /** Whole frames thrown away for arriving after the frame that overtook them. */
    val discardedLate: Long get() = reassembler.discardedLate

    /** The abandoned count when we last noticed it change, so a new loss can be told from an old. */
    private var lastAbandoned = 0L

    /** When we last asked for a keyframe, on the monotonic clock. */
    private var lastKeyframeRequestNanos = 0L

    /** True from the moment a frame is lost until a keyframe arrives to start the picture again. */
    @Volatile private var awaitingKeyframe = false

    /** Frames skipped because they could only have been decoded against something we never had. */
    var skippedAwaitingKeyframe = 0L; private set

    var mode: StreamMode = StreamMode.READING
        set(value) {
            field = value
            reassembler.slackFrames = value.slackFrames
            decoder.waitMicros = value.decoderWaitMicros
        }

    /**
     * Listen to this host and to nothing else.
     *
     * Two hosts answering the same broadcast is not a rare accident — it is what happens the moment
     * a second machine on the network has AirMate on it. Both then stream at this tablet at once,
     * and because every datagram carries its own session id, each one tore down the decoder the
     * other had just built. Nothing decoded at all, and the bandwidth of two streams arrived to
     * make sure of it.
     */
    fun select(address: InetAddress) {
        if (selected == address) return
        val source = synchronized(heard) { heard[address] }
        selected = address
        macAddress = address
        macPort = source?.port ?: port
        // The picture on screen belongs to the host we are leaving, and the stream that follows has
        // its own parameter sets. Handing those to the codec configured for the old one faults it.
        decodingSession = 0
        decoder.reset()
        Log.i(TAG, "listening to $address")
    }

    /**
     * Look for hosts again for a few seconds.
     *
     * Once a host is chosen the hello goes to it alone, so nothing else is ever invited to start
     * sending. That is the point — an uninvited second stream is the bug — but it also means a host
     * switched on later cannot be found. Opening the card asks again.
     */
    fun rescan() {
        discoverUntilNanos = System.nanoTime() + DISCOVERY_NANOS
    }

    fun pairWith(host: String, port: Int) {
        pairingTarget = InetAddress.getByName(host)
        pairingPort = port
    }

    /**
     * Send a control datagram to the Mac.
     *
     * Silently does nothing until the Mac has been heard from, since there is nowhere to send it,
     * and the Mac itself ignores anything that changes state until a human there has authorised
     * this device. Both are ordinary outcomes, not errors.
     */
    fun sendControl(bytes: ByteArray) {
        val target = macAddress ?: pairingTarget ?: return
        val active = socket ?: return
        val targetPort = if (macAddress != null) macPort else pairingPort
        Log.d(TAG, "control type=${bytes.getOrNull(5)} -> $target:$targetPort")
        controlThread.execute {
            runCatching { active.send(DatagramPacket(bytes, bytes.size, target, targetPort)) }
                .onFailure { if (running.get()) Log.e(TAG, "control send failed", it) }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::loop, "AirMate-Network").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val datagram = ByteArray(1200)
        val packet = DatagramPacket(datagram, datagram.size)
        val hello = "AMHELLO1".toByteArray(Charsets.US_ASCII)
        val broadcast = InetAddress.getByName("255.255.255.255")
        var lastHelloNanos = 0L
        DatagramSocket(port).use { active ->
            socket = active
            active.receiveBufferSize = 256 * 1024
            active.soTimeout = 100
            active.broadcast = true
            while (running.get()) {
                try {
                    val now = System.nanoTime()
                    if (now - lastHelloNanos >= 1_000_000_000L) {
                        // Greeting the whole subnet is what invites a second host to stream at us,
                        // so it is done only while looking for one. Once a host is chosen the hello
                        // is addressed to it, and nothing else is asked to send anything.
                        val chosen = selected
                        val discovering = now < discoverUntilNanos
                        val target = when {
                            pairingTarget != null -> pairingTarget!!
                            chosen != null && !discovering -> chosen
                            else -> broadcast
                        }
                        val targetPort = when {
                            pairingTarget != null -> pairingPort
                            chosen != null && !discovering -> macPort
                            else -> port
                        }
                        active.send(DatagramPacket(hello, hello.size, target, targetPort))
                        lastHelloNanos = now
                    }
                    packet.length = datagram.size
                    active.receive(packet)

                    // The host's address is taken only from something the host demonstrably sent.
                    // Adopting the sender of any datagram at all meant our own broadcast hello,
                    // arriving back on this socket, could name the tablet itself as the host — and
                    // every command after that was addressed to nobody and quietly lost.
                    val statusMessage = StatusMessage.parse(datagram, packet.length)
                    val header =
                        if (statusMessage == null) VideoHeader.parse(datagram, packet.length) else null
                    if (statusMessage == null && header == null) continue

                    // Anything that speaks AirMate is a host worth listing, whether or not it is the
                    // one being listened to.
                    note(packet.address, packet.port, statusMessage, now)
                    if (selected == null) select(packet.address)
                    if (packet.address != selected) continue

                    macPort = packet.port
                    if (statusMessage != null) {
                        onStatus(statusMessage)
                        continue
                    }
                    header ?: continue

                    // A new session is a new display: rebuilt, resized, or its HiDPI changed. The
                    // stream that follows carries its own parameter sets, and feeding those to a
                    // codec configured for the previous one faults it for good — which is why the
                    // picture froze until the app was restarted.
                    if (header.sessionId != decodingSession) {
                        decodingSession = header.sessionId
                        highestFrameId = -1L
                        decoder.reset()
                    }
                    if (header.frameId > highestFrameId) {
                        if (highestFrameId >= 0) {
                            skippedByHost += header.frameId - highestFrameId - 1
                        }
                        highestFrameId = header.frameId
                    }
                    reassembler.accept(datagram, packet.length, header)?.let { complete ->
                        receivedFrames++
                        // Everything after a lost frame is coded against a picture this decoder
                        // never received, so decoding it does not recover the stream, it draws the
                        // error into every frame until the next keyframe. That is the artifacting:
                        // not the frame that went missing, but all the ones after it that were
                        // decoded anyway. Wait for a keyframe instead — one has already been asked
                        // for — and hold the last good picture until it comes.
                        if (awaitingKeyframe && !complete.keyframe) {
                            skippedAwaitingKeyframe++
                        } else {
                            awaitingKeyframe = false
                            decoder.submit(complete.bytes, complete.length, complete.frameId, header.hevc)
                        }
                    }
                    requestKeyframeAfterLoss()
                } catch (_: SocketTimeoutException) {
                    // Re-broadcast hello after a quiet interval.
                } catch (error: Exception) {
                    if (running.get()) Log.e(TAG, "UDP receive failed", error)
                }
            }
        }
    }

    /**
     * Record a host, and forget the ones that have gone quiet.
     *
     * A host that has said nothing for five seconds is either off or off the network, and leaving
     * it in the list offers the user a choice that cannot work.
     */
    private fun note(address: InetAddress, port: Int, status: StatusMessage?, now: Long) {
        val changed = synchronized(heard) {
            val before = heard.keys.toList()
            val existing = heard[address]
            heard[address] = Source(address, port, now, status ?: existing?.status)
            heard.entries.removeAll { now - it.value.lastHeardNanos > SOURCE_TIMEOUT_NANOS }
            heard.keys.toList() != before
        }
        val listed = synchronized(heard) { heard.values.toList() }
        sources = listed
        if (changed) {
            Log.i(TAG, "hosts: " + listed.joinToString { it.label })
            onSources(listed)
        }
    }

    /**
     * Ask for a keyframe as soon as one is lost, rather than waiting for the next scheduled one.
     *
     * Everything after a missing fragment refers to a picture this decoder never received, so from
     * the moment a frame is abandoned there is nothing further it can draw. The host's own keyframe
     * interval is two seconds, and until now that was exactly how long the picture stayed frozen
     * after a single lost packet — which is why the freezes were so much longer than any of the
     * mode settings could explain.
     *
     * Rate-limited, because a keyframe is many times the size of the frames around it. Asking for
     * one on every lost packet would answer a congested link by putting more on it, and the second
     * keyframe would be lost the same way the first was.
     */
    private fun requestKeyframeAfterLoss() {
        val abandoned = reassembler.abandoned
        if (abandoned == lastAbandoned) return
        lastAbandoned = abandoned
        awaitingKeyframe = true
        Log.i(
            TAG,
            "lost frame: had ${reassembler.lossReceived}/${reassembler.lossExpected} fragments, " +
                "first missing ${reassembler.lossFirstMissing}, " +
                (if (reassembler.lossIsTail) "cut off at the end" else "gaps in the middle")
        )
        val now = System.nanoTime()
        if (now - lastKeyframeRequestNanos < KEYFRAME_REQUEST_INTERVAL_NANOS) return
        lastKeyframeRequestNanos = now
        sendControl(ControlMessage.simple(ControlMessage.TYPE_REQUEST_IDR))
    }

    override fun close() {
        running.set(false)
        controlThread.shutdownNow()
        socket?.close()
        thread?.join(1000)
        thread = null
    }
    companion object {
        private const val TAG = "AirMate.Android.Network"

        /** At most one keyframe request in this window, however much is being lost. */
        private const val KEYFRAME_REQUEST_INTERVAL_NANOS = 250_000_000L

        /** A host silent for this long has gone, and is taken off the list. */
        private const val SOURCE_TIMEOUT_NANOS = 5_000_000_000L

        /** How long a rescan keeps greeting the whole subnet. */
        private const val DISCOVERY_NANOS = 4_000_000_000L
    }
}
