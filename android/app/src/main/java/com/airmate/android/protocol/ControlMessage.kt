package com.airmate.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control messages this client sends to drive the Mac. See `protocol/PROTOCOL.md`.
 *
 * Everything except [hello] changes the Mac's state and is refused until a human has authorised
 * this device in the Mac's own window, so a command going unanswered is a normal outcome rather
 * than an error.
 */
object ControlMessage {
    const val MAGIC = 0x414D4331
    const val VERSION = 1
    const val HEADER_BYTES = 8

    const val TYPE_HELLO = 1
    const val TYPE_START = 2
    const val TYPE_STOP = 3
    const val TYPE_SET_DISPLAY = 4
    const val TYPE_REQUEST_IDR = 5
    const val TYPE_POINTER = 6
    const val TYPE_SCROLL = 7

    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2

    const val PHASE_BEGIN = 0
    const val PHASE_CONTINUE = 1
    const val PHASE_END = 2

    fun simple(type: Int): ByteArray = build(type, ByteArray(0))

    fun setDisplay(width: Int, height: Int, hiDPI: Boolean): ByteArray {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .put(if (hiDPI) 1 else 0)
            .array()
        return build(TYPE_SET_DISPLAY, payload)
    }

    /**
     * A click at a point on the streamed display.
     *
     * [x] and [y] are normalised across the display, `0` to `65535`, so neither side has to agree
     * on pixels, points, or whether the display is HiDPI.
     */
    fun pointer(button: Int, x: Int, y: Int): ByteArray {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put(button.toByte())
            .putShort(x.toShort())
            .putShort(y.toShort())
            .array()
        return build(TYPE_POINTER, payload)
    }

    /**
     * A scroll gesture, in the streamed display's pixels, positive down and right.
     *
     * The phase lets the host move the pointer to the gesture once and put it back once, instead of
     * teleporting it on every delta of a flick.
     */
    fun scroll(phase: Int, x: Int, y: Int, dx: Int, dy: Int): ByteArray {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(phase.toByte())
            .putShort(x.toShort())
            .putShort(y.toShort())
            .putShort(dx.coerceIn(-32768, 32767).toShort())
            .putShort(dy.coerceIn(-32768, 32767).toShort())
            .array()
        return build(TYPE_SCROLL, payload)
    }

    private fun build(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(type.toByte())
            .putShort(payload.size.toShort())
            .put(payload)
            .array()
}

/**
 * What the Mac says about itself, once a second.
 *
 * This is the only way the control card can describe a display that is stopped: with no video
 * flowing there is nothing else arriving to read a state from.
 */
data class StatusMessage(
    val running: Boolean,
    val hiDPI: Boolean,
    val authorised: Boolean,
    val width: Int,
    val height: Int,
    val encodedFrames: Long
) {
    companion object {
        const val MAGIC = 0x414D5331
        const val BYTES = 20

        fun parse(data: ByteArray, length: Int): StatusMessage? {
            if (length < BYTES) return null
            val buffer = ByteBuffer.wrap(data, 0, BYTES).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC || buffer.get().toInt() and 0xff != 1) return null
            val flags = buffer.get().toInt() and 0xff
            val width = buffer.short.toInt() and 0xffff
            val height = buffer.short.toInt() and 0xffff
            buffer.short
            return StatusMessage(
                running = flags and 1 != 0,
                hiDPI = flags and 2 != 0,
                authorised = flags and 4 != 0,
                width = width,
                height = height,
                encodedFrames = buffer.long
            )
        }
    }
}
