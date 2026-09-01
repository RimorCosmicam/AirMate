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

    /**
     * How many newer frames an incomplete access unit survives before it is given up on.
     *
     * Zero is AirMate's original behaviour and the lowest-latency case: the moment a newer frame
     * appears the incomplete one is gone. Above zero the slot is held while newer fragments are
     * ignored, which lets a reordered or slightly late fragment still complete its frame at the
     * cost of the frames arriving meanwhile.
     */
    @Volatile var slackFrames: Int = 0

    private var newerSightings = 0

    data class Complete(val bytes: ByteArray, val length: Int, val frameId: Long, val captureNanos: Long, val flags: Int)

    fun accept(packet: ByteArray, packetLength: Int, header: VideoHeader): Complete? {
        if (header.sessionId != sessionId) {
            reset(header)
        } else if (header.frameId > frameId) {
            if (holdsForLateFragments()) return null
            reset(header)
        }
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

    /** True while an access unit that is part-way there is still worth waiting for. */
    private fun holdsForLateFragments(): Boolean {
        if (slackFrames <= 0) return false
        if (receivedCount <= 0 || receivedCount >= fragmentCount) return false
        if (newerSightings >= slackFrames) return false
        newerSightings++
        return true
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
        newerSightings = 0
    }
}
