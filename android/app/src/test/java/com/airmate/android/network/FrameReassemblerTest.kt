package com.airmate.android.network

import com.airmate.android.protocol.VideoHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the reassembler does with a stream that is losing packets, which is the only kind of stream
 * the leniency setting exists for.
 */
class FrameReassemblerTest {

    private fun header(frameId: Long, index: Int, count: Int) = VideoHeader(
        flags = 0,
        sessionId = 7,
        frameId = frameId,
        captureNanos = frameId * 1_000_000,
        fragmentIndex = index,
        fragmentCount = count,
        payloadLength = 10
    )

    private val packet = ByteArray(VideoHeader.BYTES + 10)

    /** Feed a whole frame, optionally missing one fragment. Returns the completed unit, if any. */
    private fun feed(
        reassembler: FrameReassembler,
        frameId: Long,
        fragments: Int,
        drop: Int = -1
    ): FrameReassembler.Complete? {
        var completed: FrameReassembler.Complete? = null
        for (index in 0 until fragments) {
            if (index == drop) continue
            reassembler.accept(packet, packet.size, header(frameId, index, fragments))
                ?.let { completed = it }
        }
        return completed
    }

    @Test
    fun `a clean stream completes every frame`() {
        val reassembler = FrameReassembler()
        for (frame in 1L..5L) {
            assertNotNull("frame $frame should complete", feed(reassembler, frame, fragments = 8))
        }
    }

    @Test
    fun `one lost fragment costs only its own frame`() {
        val reassembler = FrameReassembler()
        assertNotNull(feed(reassembler, 1, fragments = 8))
        assertNull("the damaged frame cannot complete", feed(reassembler, 2, fragments = 8, drop = 3))
        // The frames after it are whole and have nothing to do with the one that was damaged.
        for (frame in 3L..6L) {
            assertNotNull("frame $frame should complete", feed(reassembler, frame, fragments = 8))
        }
    }

    @Test
    fun `leniency does not cost the frames it is meant to save`() {
        val reassembler = FrameReassembler()
        reassembler.slackFrames = 1
        assertNotNull(feed(reassembler, 1, fragments = 8))
        assertNull("the damaged frame cannot complete", feed(reassembler, 2, fragments = 8, drop = 3))
        for (frame in 3L..6L) {
            assertNotNull(
                "frame $frame is whole and must still complete with leniency on",
                feed(reassembler, frame, fragments = 8)
            )
        }
    }

    @Test
    fun `a held frame that finishes late is never handed over out of order`() {
        val reassembler = FrameReassembler()
        reassembler.slackFrames = 1
        assertNotNull(feed(reassembler, 1, fragments = 8))

        // Frame 2 is missing one fragment and is held.
        assertNull(feed(reassembler, 2, fragments = 8, drop = 3))
        // Frame 3 arrives whole and overtakes it.
        assertNotNull(feed(reassembler, 3, fragments = 8))

        // Frame 2 now turns up in full, late. It is complete and useless: every frame is coded
        // against the one before it, so handing it over after frame 3 corrupts what follows.
        val late = feed(reassembler, 2, fragments = 8)
        assertNull("a frame older than the last one decoded must not be handed over", late)
        assertEquals(1L, reassembler.discardedLate)
    }

    @Test
    fun `slack is counted in frames, not in packets`() {
        val reassembler = FrameReassembler()
        reassembler.slackFrames = 1
        assertNotNull(feed(reassembler, 1, fragments = 8))
        // Frame 2 is damaged and is held. Frame 3 is one newer frame — the whole of the slack.
        assertNull(feed(reassembler, 2, fragments = 8, drop = 3))
        feed(reassembler, 3, fragments = 8)
        // Frame 4 is the second newer frame, past the slack, so it must be taken whole.
        assertNotNull("slack of one frame must not eat a second frame", feed(reassembler, 4, fragments = 8))
    }
}
