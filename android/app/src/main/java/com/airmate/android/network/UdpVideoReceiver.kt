package com.airmate.android.network

import android.util.Log
import com.airmate.android.decoder.LowLatencyDecoder
import com.airmate.android.protocol.VideoHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class UdpVideoReceiver(private val decoder: LowLatencyDecoder, private val port: Int = 48620) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    var receivedFrames = 0L; private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::loop, "AirMate-Network").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val datagram = ByteArray(1200)
        val packet = DatagramPacket(datagram, datagram.size)
        val reassembler = FrameReassembler()
        DatagramSocket(port).use { active ->
            socket = active
            active.receiveBufferSize = 256 * 1024
            active.soTimeout = 1000
            while (running.get()) {
                try {
                    active.broadcast = true
                    val hello = "AMHELLO1".toByteArray(Charsets.US_ASCII)
                    active.send(DatagramPacket(hello, hello.size, InetAddress.getByName("255.255.255.255"), port))
                    packet.length = datagram.size
                    active.receive(packet)
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

