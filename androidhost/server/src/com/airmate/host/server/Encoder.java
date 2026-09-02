package com.airmate.host.server;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * The desktop, compressed, straight off the compositor.
 *
 * There is no capture stage and no pixel buffer: the encoder's own input surface *is* what the
 * virtual display draws into, so frames go from the compositor to the hardware encoder without ever
 * being read back into memory. The Mac has to copy an IOSurface to get here; Android hands it over.
 */
public final class Encoder {
    public interface Sink {
        void onAccessUnit(byte[] data, int length, long captureNanos, boolean keyframe, boolean hevc);
    }

    private static final String H264 = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;

    private final MediaCodec codec;
    private final Surface surface;
    private final Sink sink;
    private final boolean hevc;
    private final Thread drainThread;

    /**
     * The parameter sets, kept so every keyframe can carry them.
     *
     * MediaCodec emits them once, in a buffer of their own, before the first frame. A client that
     * joins later — which is every client, since the display starts before anyone is watching —
     * would otherwise receive keyframes it has no way to configure a decoder from.
     */
    private byte[] parameterSets = new byte[0];

    private byte[] scratch = new byte[256 * 1024];
    private volatile boolean running = true;

    public Encoder(int width, int height, boolean preferHevc, Sink sink) throws Exception {
        this.sink = sink;
        String mime = preferHevc && supports(HEVC) ? HEVC : H264;
        this.hevc = HEVC.equals(mime);

        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // Scaled with the picture rather than fixed: twelve megabits looks fine at 720p and starves
        // 1080p, which has more than twice the pixels to spend it on.
        long pixels = (long) width * height;
        int bitrate = (int) Math.min(40_000_000L, Math.max(12_000_000L, pixels * 60 * 18 / 100));
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // A desktop nobody is touching produces no frames at all, and a client that joined during
        // the quiet has nothing to decode. This makes the encoder re-emit the last picture instead,
        // which is the Android answer to a problem the Mac solves by asking for a still.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        format.setInteger(MediaFormat.KEY_LATENCY, 0);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);

        // Built into locals first and only published on success. A codec that fails to configure
        // or start is still a hardware component that has been handed out, and if it is never
        // assigned to the field there is nothing left holding it for anyone to release — the device
        // has only a handful of encoders and a few failed starts exhaust them all.
        MediaCodec built = MediaCodec.createEncoderByType(mime);
        Surface input = null;
        try {
            built.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = built.createInputSurface();
            built.start();
        } catch (Throwable failure) {
            if (input != null) {
                try { input.release(); } catch (Exception ignored) {}
            }
            try { built.release(); } catch (Exception ignored) {}
            throw failure;
        }
        codec = built;
        surface = input;
        Ln.i("VIDEO", "Encoder = " + codec.getName() + " (" + mime + ")");
        Ln.i("VIDEO", "Resolution = " + width + "x" + height + ", bitrate = " + bitrate / 1_000_000 + " Mb/s");

        drainThread = new Thread(this::drain, "AirMate-Encoder");
        drainThread.start();
    }

    /** The surface the virtual display draws into. */
    public Surface surface() {
        return surface;
    }

    public boolean isHevc() {
        return hevc;
    }

    /**
     * Ask for the next frame to be an IDR.
     *
     * There is no retransmission, so a client that has missed part of a reference frame stays broken
     * until the next scheduled keyframe — up to two seconds. This is how it asks for one sooner.
     */
    public void requestKeyframe() {
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(parameters);
        } catch (Exception error) {
            Ln.e("VIDEO", "could not request a keyframe", error);
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int index = codec.dequeueOutputBuffer(info, 100_000L);
                if (index < 0) continue;
                ByteBuffer output = codec.getOutputBuffer(index);
                if (output != null && info.size > 0) emit(output, info);
                codec.releaseOutputBuffer(index, false);
            } catch (IllegalStateException stopped) {
                if (running) Ln.e("VIDEO", "encoder faulted", stopped);
                return;
            } catch (Exception error) {
                if (running) Ln.e("VIDEO", "drain failed", error);
                return;
            }
        }
    }

    private void emit(ByteBuffer output, MediaCodec.BufferInfo info) {
        output.position(info.offset);
        output.limit(info.offset + info.size);

        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            parameterSets = new byte[info.size];
            output.get(parameterSets);
            Ln.i("VIDEO", "Parameter sets: " + parameterSets.length + " bytes");
            return;
        }

        boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        int prefix = keyframe ? parameterSets.length : 0;
        int total = prefix + info.size;
        if (scratch.length < total) scratch = new byte[Math.max(total, scratch.length * 2)];
        if (prefix > 0) System.arraycopy(parameterSets, 0, scratch, 0, prefix);
        output.get(scratch, prefix, info.size);

        Diagnostics.encoded++;
        // The encoder's own presentation time, in nanoseconds: it is the surface timestamp the
        // compositor stamped the frame with, which is what the client wants to measure latency from.
        sink.onAccessUnit(scratch, total, info.presentationTimeUs * 1000L, keyframe, hevc);
    }

    public void close() {
        running = false;
        try { drainThread.interrupt(); } catch (Exception ignored) {}
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
        try { surface.release(); } catch (Exception ignored) {}
    }

    /**
     * Whether this device has an encoder for a codec, asked without building one.
     *
     * `createEncoderByType` would answer the same question by allocating a hardware component we
     * would then have to remember to release, on a device that has very few of them.
     */
    private static boolean supports(String mime) {
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Exception ignored) {
            // An unreadable codec list is not a reason to refuse the codec we already default to.
        }
        return false;
    }
}
