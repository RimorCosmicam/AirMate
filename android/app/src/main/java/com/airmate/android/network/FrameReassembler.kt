package com.airmate.android.network

import com.airmate.android.protocol.VideoHeader

/**
 * Datagrams back into access units.
 *
 * Two slots, not one. A frame that is missing a fragment may be worth holding for a moment in case
 * the fragment is merely late — but the frames arriving while it is held are not the price of that
 * bet. Holding in a single slot meant every packet of the newer frame was thrown away to keep the
 * older one, so the newer frame was damaged in turn, held in turn, and damaged the frame after it:
 * one lost packet put the stream into a state it never came out of. The second slot is what lets an
 * older frame be waited for and a newer one be built at the same time.
 *
 * There is no third slot, and there is no point in one. Nothing here retransmits, so a fragment
 * that has not arrived by the time the next frame is complete is not late, it is gone; waiting
 * longer cannot conjure a packet the host never sends again. One frame of patience covers the
 * reordering a local network actually does.
 */
class FrameReassembler(private val slotBytes: Int = 4 * 1024 * 1024) {

    /**
     * How many newer frames an incomplete access unit survives before it is given up on.
     *
     * Zero is AirMate's original behaviour and the lowest-latency case: the moment a newer frame
     * appears the incomplete one is gone. Above zero one newer frame may be built alongside it,
     * which lets a reordered fragment still complete its frame without costing the frame that
     * overtook it. Counted in frames, as the name says — it used to be counted in packets, which
     * made it about a hundredth of what it claimed.
     */
    @Volatile
    var slackFrames: Int = 0

    /** Access units given up on part-built. The honest measure of loss on this path. */
    @Volatile
    var abandoned = 0L
        private set

    /**
     * What the last abandoned frame was missing.
     *
     * Which fragments are absent says where they went. A contiguous run at the end of a frame is
     * the host giving up on the rest of an access unit when its socket would have blocked — the
     * frame was never fully sent. Gaps scattered through the middle are a link that dropped them
     * in flight. The two have the same symptom here and different cures at the other end.
     */
    @Volatile var lossReceived = 0; private set
    @Volatile var lossExpected = 0; private set
    @Volatile var lossFirstMissing = -1; private set
    @Volatile var lossIsTail = false; private set

    /**
     * Frames that arrived whole but too late to be of any use.
     *
     * A held frame can finish after the frame that overtook it has already gone to the decoder, and
     * handing it over then is worse than dropping it: every frame in this stream is coded against
     * the one before, so decoding them out of order does not lose a picture, it corrupts the ones
     * after it. This is the count of frames the patience bought and could not spend.
     */
    @Volatile
    var discardedLate = 0L
        private set

    /** The newest frame handed to the decoder. Nothing older may follow it. */
    private var lastEmitted = -1L

    data class Complete(
        val bytes: ByteArray,
        val length: Int,
        val frameId: Long,
        val captureNanos: Long,
        val flags: Int
    ) {
        /** Bit 0 of the video header: this access unit can start a picture on its own. */
        val keyframe: Boolean get() = flags and 1 != 0
    }

    private inner class Slot {
        val bytes = ByteArray(slotBytes)
        val seen = IntArray(MAX_FRAGMENTS)
        var generation = 1
        var frameId = -1L
        var fragmentCount = 0
        var received = 0
        var finalSize = -1
        var flags = 0
        var captureNanos = 0L

        val busy: Boolean get() = frameId >= 0
        val partial: Boolean get() = received in 1 until fragmentCount
        val whole: Boolean get() = fragmentCount > 0 && received == fragmentCount && finalSize >= 0

        fun begin(header: VideoHeader) {
            if (partial) { abandoned++; recordLoss() }
            generation++
            if (generation == Int.MAX_VALUE) {
                seen.fill(0)
                generation = 1
            }
            frameId = header.frameId
            fragmentCount = header.fragmentCount
            received = 0
            finalSize = -1
            flags = header.flags
            captureNanos = header.captureNanos
        }

        fun clear() {
            if (partial) { abandoned++; recordLoss() }
            frameId = -1
            received = 0
            fragmentCount = 0
            finalSize = -1
        }

        /**
         * Note the shape of the hole this frame is being abandoned with.
         *
         * A frame whose received fragments are exactly 0 until `received` and whose last fragment
         * is absent was cut off rather than damaged: everything up to a point arrived and nothing
         * after it did.
         */
        fun recordLoss() {
            var first = -1
            var last = -1
            for (index in 0 until fragmentCount) {
                if (seen[index] != generation) {
                    if (first < 0) first = index
                    last = index
                }
            }
            lossReceived = received
            lossExpected = fragmentCount
            lossFirstMissing = first
            lossIsTail = first == received && last == fragmentCount - 1
        }

        /** @return true when this fragment was the one the frame was still missing. */
        fun put(packet: ByteArray, header: VideoHeader): Boolean {
            val offset = header.fragmentIndex * VideoHeader.MAX_PAYLOAD
            if (offset + header.payloadLength > slotBytes) return false
            if (seen[header.fragmentIndex] != generation) {
                System.arraycopy(packet, VideoHeader.BYTES, bytes, offset, header.payloadLength)
                seen[header.fragmentIndex] = generation
                received++
                if (header.fragmentIndex == fragmentCount - 1) {
                    finalSize = offset + header.payloadLength
                }
            }
            return whole
        }
    }

    private val slots = arrayOf(Slot(), Slot())
    private var sessionId = 0L

    fun accept(packet: ByteArray, packetLength: Int, header: VideoHeader): Complete? {
        // A new session is a new display, and every fragment held for the old one describes a
        // picture that no longer exists.
        if (header.sessionId != sessionId) {
            sessionId = header.sessionId
            slots.forEach { it.clear() }
            lastEmitted = -1L
        }
        if (header.fragmentIndex >= header.fragmentCount) return null
        if (header.fragmentIndex >= MAX_FRAGMENTS) return null

        val slot = slotFor(header) ?: return null
        if (!slot.put(packet, header)) return null

        // Whole, but overtaken. The decoder has already moved past it.
        if (slot.frameId <= lastEmitted) {
            discardedLate++
            slot.frameId = -1
            slot.received = 0
            slot.fragmentCount = 0
            return null
        }
        lastEmitted = slot.frameId
        val done = Complete(slot.bytes, slot.finalSize, slot.frameId, slot.captureNanos, slot.flags)
        // Anything still being built that is older than the frame just finished is finished with
        // too: it could only ever be decoded before this one, and this one has already gone.
        slots.forEach { other ->
            if (other !== slot && other.busy && other.frameId < slot.frameId) other.clear()
        }
        slot.frameId = -1
        slot.received = 0
        slot.fragmentCount = 0
        return done
    }

    /**
     * The slot this fragment belongs in, or null when it belongs to a frame already given up on.
     */
    private fun slotFor(header: VideoHeader): Slot? {
        slots.firstOrNull { it.busy && it.frameId == header.frameId }?.let { return it }

        // Fragments of a frame that has already been abandoned or emitted. Nothing to add them to.
        if (slots.any { it.busy && it.frameId > header.frameId }) return null

        // Strict: only the newest frame is ever being built, so a newer one replaces whatever is
        // there. This is the original behaviour and the lowest latency AirMate has.
        if (slackFrames <= 0) {
            val only = slots[0]
            slots[1].clear()
            only.begin(header)
            return only
        }

        slots.firstOrNull { !it.busy }?.let {
            it.begin(header)
            return it
        }

        // Both slots are building older frames. The older of the two has now been overtaken twice
        // and is not going to arrive; its slot goes to the frame in hand.
        val oldest = if (slots[0].frameId <= slots[1].frameId) slots[0] else slots[1]
        oldest.begin(header)
        return oldest
    }

    private companion object {
        /** The fragment index is a `u16`, but the protocol caps a frame well below that. */
        const val MAX_FRAGMENTS = 8192
    }
}
