package com.airmate.android.decoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * What this device's hardware decoder will actually accept.
 *
 * Asking for a size it cannot decode does not fail politely: the component reports
 * `OMX_ErrorHardware`, the codec dies, and every frame after that throws. On a panel larger than
 * the decoder — a 2000 × 1200 screen in front of a decoder that stops at 1080p, which is common —
 * offering the screen's own size guarantees exactly that.
 */
object DecoderLimits {
    private var cached: MediaCodecInfo.VideoCapabilities? = null
    private var looked = false

    private fun capabilities(): MediaCodecInfo.VideoCapabilities? {
        if (looked) return cached
        looked = true
        cached = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstNotNullOfOrNull { info ->
                if (info.isEncoder) return@firstNotNullOfOrNull null
                val mime = info.supportedTypes.firstOrNull {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
                } ?: return@firstNotNullOfOrNull null
                if (info.name.contains("google", true) || info.name.startsWith("c2.android")) {
                    return@firstNotNullOfOrNull null
                }
                info.getCapabilitiesForType(mime).videoCapabilities
            }
        }.getOrNull()
        cached?.let {
            Log.i(TAG, "Decoder accepts up to ${it.supportedWidths.upper} × ${it.supportedHeights.upper}")
        }
        return cached
    }

    /** True when the hardware will decode this size. Unknown capabilities are taken as a yes. */
    fun supports(width: Int, height: Int): Boolean {
        val caps = capabilities() ?: return true
        return runCatching { caps.isSizeSupported(width, height) }.getOrDefault(true)
    }

    private const val TAG = "AirMate.Android.Decoder"
}
