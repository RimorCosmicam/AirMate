package com.airmate.android.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * @param surfaceOf The surface to decode into, asked for afresh each time a codec is built.
 *
 * Held rather than asked for, a surface goes stale the moment the view behind it is resized — and
 * a codec configured against a dead surface accepts the configuration and then throws on the first
 * frame, for every frame, forever.
 */
class LowLatencyDecoder(
    private val surfaceOf: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080
) {
    /**
     * MediaCodec may be touched by one thread at a time.
     *
     * Frames arrive on the network thread while the surface callbacks and teardown run on the main
     * one, and releasing a codec underneath a thread sitting in `dequeueInputBuffer` throws
     * IllegalStateException — which then repeats for every frame, because the codec is dead and
     * whatever rebuilt it raced with the next release too.
     */
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var mime: String? = null
    var decodedFrames = 0L; private set
    var droppedFrames = 0L; private set

    /**
     * How long to wait for a decoder input buffer before dropping the frame.
     *
     * Zero is the original behaviour: if hardware decode is busy this instant the access unit is
     * dropped rather than queued in application memory. A short wait trades latency for keeping
     * frames the decoder was only momentarily too busy to take.
     */
    @Volatile var waitMicros: Long = 0

    /**
     * Throw the codec away so the next access unit builds a fresh one.
     *
     * Called when the host starts a new session — a rebuilt display, a resolution change, a HiDPI
     * toggle. The stream that follows has its own parameter sets, and handing those to a codec
     * configured for the previous one is what faults it.
     */
    fun reset() {
        synchronized(lock) { closeLocked() }
    }

    fun submit(bytes: ByteArray, length: Int, frameId: Long, hevc: Boolean): Unit = synchronized(lock) {
        val wantedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (codec == null || mime != wantedMime) {
            try {
                configure(wantedMime)
            } catch (error: Exception) {
                Log.e(TAG, "Could not build a decoder", error)
                closeLocked()
                droppedFrames++
                return
            }
        }
        val active = codec ?: run { droppedFrames++; return }
        try {
            val index = active.dequeueInputBuffer(waitMicros)
            if (index < 0) { droppedFrames++; return }
            val input = active.getInputBuffer(index) ?: run { droppedFrames++; return }
            if (length > input.capacity()) { active.queueInputBuffer(index, 0, 0, 0, 0); droppedFrames++; return }
            input.clear(); input.put(bytes, 0, length)
            active.queueInputBuffer(index, 0, length, frameId * 1_000_000L / 60L, 0)
            drain(active)
        } catch (error: Exception) {
            // Once MediaCodec faults it throws for every frame from then on, so leaving it in place
            // ends video for the rest of the session — which is what turning HiDPI off did. Throw it
            // away; the next access unit builds a new one, and the host's keyframe interval brings
            // the picture back within a couple of seconds.
            Log.e(TAG, "Decoder faulted, rebuilding it", error)
            closeLocked()
            droppedFrames++
        }
    }

    private fun configure(wantedMime: String) {
        closeLocked()
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { candidate ->
            !candidate.isEncoder && candidate.supportedTypes.any { it.equals(wantedMime, true) } &&
                !candidate.name.contains("google", true) && !candidate.name.startsWith("c2.android")
        } ?: run { Log.e(TAG, "No hardware decoder for $wantedMime"); return }
        val surface = surfaceOf() ?: run { Log.e(TAG, "No surface to decode into"); return }
        if (!surface.isValid) { Log.e(TAG, "Surface is no longer valid"); return }
        val selected = MediaCodec.createByCodecName(info.name)
        val format = MediaFormat.createVideoFormat(wantedMime, width, height)
        if (Build.VERSION.SDK_INT >= 30) {
            val capabilities = info.getCapabilitiesForType(wantedMime)
            if (capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        selected.configure(format, surface, null, 0)
        selected.start()
        codec = selected; mime = wantedMime
        Log.i(TAG, "Hardware decoder ${info.name}, lowLatency=${format.containsKey(MediaFormat.KEY_LOW_LATENCY)}")
    }

    private fun drain(active: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = active.dequeueOutputBuffer(info, 0)
            if (index < 0) break
            active.releaseOutputBuffer(index, true)
            decodedFrames++
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        mime = null
    }

    companion object {
        private const val TAG = "AirMate.Android.Decoder"
    }
}

