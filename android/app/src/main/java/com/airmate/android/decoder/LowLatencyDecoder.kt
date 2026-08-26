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

    fun submit(bytes: ByteArray, length: Int, frameId: Long, hevc: Boolean) {
        val wantedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (codec == null || mime != wantedMime) configure(wantedMime)
        val active = codec ?: run { droppedFrames++; return }
        val index = active.dequeueInputBuffer(0)
        if (index < 0) { droppedFrames++; return }
        val input = active.getInputBuffer(index) ?: run { droppedFrames++; return }
        if (length > input.capacity()) { active.queueInputBuffer(index, 0, 0, 0, 0); droppedFrames++; return }
        input.clear(); input.put(bytes, 0, length)
        active.queueInputBuffer(index, 0, length, frameId * 1_000_000L / 60L, 0)
        drain(active)
    }

    private fun configure(wantedMime: String) {
        close()
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { candidate ->
            !candidate.isEncoder && candidate.supportedTypes.any { it.equals(wantedMime, true) } &&
                !candidate.name.contains("google", true) && !candidate.name.startsWith("c2.android")
        } ?: run { Log.e(TAG, "No hardware decoder for $wantedMime"); return }
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
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release(); codec = null; mime = null
    }

    companion object { private const val TAG = "AirMate.Android.Decoder" }
}

