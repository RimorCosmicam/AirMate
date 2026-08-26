package com.airmate.android.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoHeaderTest {
    @Test fun parsesNetworkOrderHeader() {
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
            .putInt(VideoHeader.MAGIC).put(1).put(5).putShort(40)
            .putLong(2).putLong(3).putLong(4)
            .putShort(0).putShort(1).putShort(4).putShort(0).putInt(7).array()
        val header = VideoHeader.parse(bytes, bytes.size)
        assertNotNull(header)
        assertEquals(3, header!!.frameId)
        assertTrue(header.keyframe)
        assertTrue(header.hevc)
    }
}

