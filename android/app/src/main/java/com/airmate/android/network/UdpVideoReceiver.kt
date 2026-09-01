package com.airmate.android.network

import android.util.Log
import com.airmate.android.FrameLeniency
import com.airmate.android.decoder.LowLatencyDecoder
import com.airmate.android.protocol.StatusMessage
import com.airmate.android.protocol.VideoHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class UdpVideoReceiver(
    private val decoder: LowLatencyDecoder,
    private val port: Int = 48620,
    private val onStatus: (StatusMessage) -> Unit = {}
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val reassembler = FrameReassembler()
    @Volatile private var pairingTarget: InetAddress? = null
    @Volatile private var pairingPort: Int = port

    /** Where the Mac actually is, learned from whatever it last sent us. */
    @Volatile private var macAddress: InetAddress? = null
    @Volatile private var macPort: Int = port

    var receivedFrames = 0L; private set

    var leniency: FrameLeniency = FrameLeniency.ACTUAL
        set(value) {
            field = value
            reassembler.slackFrames = value.slackFrames
            decoder.waitMicros = value.decoderWaitMicros
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
        runCatching { active.send(DatagramPacket(bytes, bytes.size, target, targetPort)) }
            .onFailure { if (running.get()) Log.e(TAG, "control send failed", it) }
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
                        val target = pairingTarget ?: broadcast
                        val targetPort = if (pairingTarget == null) port else pairingPort
                        active.send(DatagramPacket(hello, hello.size, target, targetPort))
                        lastHelloNanos = now
                    }
                    packet.length = datagram.size
                    active.receive(packet)
                    macAddress = packet.address
                    macPort = packet.port

                    val statusMessage = StatusMessage.parse(datagram, packet.length)
                    if (statusMessage != null) {
                        onStatus(statusMessage)
                        continue
                    }

                    val header = VideoHeader.parse(datagram, packet.length) ?: continue
                    reassembler.accept(datagram, packet.length, header)?.let { complete ->
                        receivedFrames++
                        decoder.submit(complete.bytes, complete.length, complete.frameId, header.hevc)
                    }
                } catch (_: SocketTimeoutException) {
                    // Re-broadcast hello after a quiet interval.
                } catch (error: Exception) {
                    if (running.get()) Log.e(TAG, "UDP receive failed", error)
                }
            }
        }
    }

    override fun close() { running.set(false); socket?.close(); thread?.join(1000); thread = null }
    companion object { private const val TAG = "AirMate.Android.Network" }
}
