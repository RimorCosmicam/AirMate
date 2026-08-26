package com.airmate.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VideoHeader(
    val flags: Int, val sessionId: Long, val frameId: Long, val captureNanos: Long,
    val fragmentIndex: Int, val fragmentCount: Int, val payloadLength: Int
) {
    val keyframe get() = flags and 1 != 0
    val hevc get() = flags and 4 != 0

    companion object {
        const val BYTES = 40
        const val MAGIC = 0x414d5631
        const val MAX_PAYLOAD = 1160

        fun parse(data: ByteArray, length: Int): VideoHeader? {
            if (length < BYTES) return null
            val b = ByteBuffer.wrap(data, 0, BYTES).order(ByteOrder.BIG_ENDIAN)
            if (b.int != MAGIC || b.get().toInt() and 0xff != 1) return null
            val flags = b.get().toInt() and 0xff
            if (b.short.toInt() and 0xffff != BYTES) return null
            val header = VideoHeader(flags, b.long, b.long, b.long,
                b.short.toInt() and 0xffff, b.short.toInt() and 0xffff,
                b.short.toInt() and 0xffff)
            b.short
            if (header.fragmentCount !in 1..8192 || header.fragmentIndex >= header.fragmentCount ||
                header.payloadLength > MAX_PAYLOAD || BYTES + header.payloadLength > length) return null
            return header
        }
    }
}

