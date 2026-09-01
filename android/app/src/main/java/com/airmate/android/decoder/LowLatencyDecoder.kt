package com.airmate.android.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

class LowLatencyDecoder(private val surface: Surface, private val width: Int = 1920, private val height: Int = 1080) {
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
        close()
    }

    fun submit(bytes: ByteArray, length: Int, frameId: Long, hevc: Boolean) {
        val wantedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (codec == null || mime != wantedMime) {
            try {
                configure(wantedMime)
            } catch (error: Exception) {
                Log.e(TAG, "Could not build a decoder", error)
                close()
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
            close()
            droppedFrames++
        }
    }

    private fun configure(wantedMime: String) {
        close()
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { candidate ->
            !candidate.isEncoder && candidate.supportedTypes.any { it.equals(wantedMime, true) } &&
                !candidate.name.contains("google", true) && !candidate.name.startsWith("c2.android")
        } ?: run { Log.e(TAG, "No hardware decoder for $wantedMime"); return }
        val selected = MediaCodec.createByCodecName(info.name)
        val format = MediaFormat.createVideoFormat(wantedMime, width, height)
        // The stream may come back a different shape after the host rebuilds its display, and a
        // decoder that has not been told to expect that faults instead of adapting. The numbers are
        // a ceiling, not a promise.
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, MAX_DIMENSION)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, MAX_DIMENSION)
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
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release(); codec = null; mime = null
    }

    companion object {
        private const val TAG = "AirMate.Android.Decoder"
        /** Largest side the decoder is told to be ready for, so a reshape does not fault it. */
        private const val MAX_DIMENSION = 4096
    }
}

