package com.airmate.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControlMessageTest {
    @Test fun buildsSetDisplayInNetworkOrder() {
        assertArrayEquals(
            byteArrayOf(0x41, 0x4D, 0x43, 0x31, 1, 4, 0, 5, 0x07, 0x80.toByte(), 0x04, 0x38, 1),
            ControlMessage.setDisplay(1920, 1080, hiDPI = true)
        )
    }

    @Test fun buildsClickAtNormalisedCoordinates() {
        // Dead centre of the display, whatever its resolution.
        assertArrayEquals(
            byteArrayOf(0x41, 0x4D, 0x43, 0x31, 1, 6, 0, 4, 0x7F, 0xFF.toByte(), 0x7F, 0xFF.toByte()),
            ControlMessage.click(0x7FFF, 0x7FFF)
        )
    }

    @Test fun buildsScrollWithSignedDeltas() {
        val packet = ControlMessage.scroll(ControlMessage.PHASE_CONTINUE, 0, 0, -1, 40)
        assertArrayEquals(
            byteArrayOf(0x41, 0x4D, 0x43, 0x31, 1, 7, 0, 9, 1, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 40),
            packet
        )
    }

    @Test fun clampsScrollDeltasToTheWire() {
        // A fast flick can outrun a signed 16-bit field; it must saturate rather than wrap and
        // send the page flying the other way.
        val packet = ControlMessage.scroll(ControlMessage.PHASE_CONTINUE, 0, 0, 0, 99_999)
        val dy = ((packet[15].toInt() and 0xff) shl 8) or (packet[16].toInt() and 0xff)
        assertEquals(32767, dy)
    }

    @Test fun buildsSimpleMessageWithEmptyPayload() {
        assertArrayEquals(
            byteArrayOf(0x41, 0x4D, 0x43, 0x31, 1, 5, 0, 0),
            ControlMessage.simple(ControlMessage.TYPE_REQUEST_IDR)
        )
    }
}

class StatusMessageTest {
    private fun status(flags: Int, width: Int, height: Int, frames: Long): ByteArray =
        ByteBuffer.allocate(StatusMessage.BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(StatusMessage.MAGIC)
            .put(1)
            .put(flags.toByte())
            .putShort(width.toShort())
            .putShort(height.toShort())
            .putShort(0)
            .putLong(frames)
            .array()

    @Test fun parsesFlagsAndDimensions() {
        val message = StatusMessage.parse(status(0b101, 1280, 800, 42), StatusMessage.BYTES)
        assertEquals(
            StatusMessage(
                running = true,
                hiDPI = false,
                authorised = true,
                width = 1280,
                height = 800,
                encodedFrames = 42
            ),
            message
        )
    }

    @Test fun rejectsShortOrForeignDatagrams() {
        assertNull(StatusMessage.parse(status(1, 1920, 1080, 0), StatusMessage.BYTES - 1))
        val foreign = status(1, 1920, 1080, 0).also { it[0] = 0x42 }
        assertNull(StatusMessage.parse(foreign, StatusMessage.BYTES))
    }
}
