package com.airmate.android.network

import com.airmate.android.protocol.VideoHeader

class FrameReassembler(private val maximumBytes: Int = 8 * 1024 * 1024) {
    private val bytes = ByteArray(maximumBytes)
    private val fragmentGeneration = IntArray(8192)
    private var generation = 1
    private var sessionId = 0L
    private var frameId = -1L
    private var fragmentCount = 0
    private var receivedCount = 0
    private var finalSize = -1
    private var flags = 0

    data class Complete(val bytes: ByteArray, val length: Int, val frameId: Long, val captureNanos: Long, val flags: Int)

    fun accept(packet: ByteArray, packetLength: Int, header: VideoHeader): Complete? {
        if (header.sessionId != sessionId || header.frameId > frameId) reset(header)
        if (header.sessionId != sessionId || header.frameId != frameId) return null
        val offset = header.fragmentIndex * VideoHeader.MAX_PAYLOAD
        if (offset + header.payloadLength > maximumBytes) return null
        if (fragmentGeneration[header.fragmentIndex] != generation) {
            System.arraycopy(packet, VideoHeader.BYTES, bytes, offset, header.payloadLength)
            fragmentGeneration[header.fragmentIndex] = generation
            receivedCount++
            if (header.fragmentIndex == fragmentCount - 1) finalSize = offset + header.payloadLength
        }
        if (receivedCount == fragmentCount && finalSize >= 0) {
            val result = Complete(bytes, finalSize, frameId, header.captureNanos, flags)
            frameId = -1
            return result
        }
        return null
    }

    private fun reset(header: VideoHeader) {
        generation++
        if (generation == Int.MAX_VALUE) { fragmentGeneration.fill(0); generation = 1 }
        sessionId = header.sessionId
        frameId = header.frameId
        fragmentCount = header.fragmentCount
        receivedCount = 0
        finalSize = -1
        flags = header.flags
    }
}

