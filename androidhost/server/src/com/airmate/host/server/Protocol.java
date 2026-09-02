package com.airmate.host.server;

import java.io.UnsupportedEncodingException;

/**
 * The AirMate wire protocol, host side. See `protocol/PROTOCOL.md`.
 *
 * Deliberately a copy of the shapes the client already reads rather than shared code: this class is
 * compiled into a dex that runs under `app_process`, with no Android app around it and no Kotlin
 * runtime in it, so it cannot import the app's own protocol classes. The three families are small
 * and frozen; the duplication costs less than a shared library would in a process this bare.
 */
public final class Protocol {
    private Protocol() {}

    public static final int VIDEO_MAGIC = 0x414D5631;   // AMV1
    public static final int CONTROL_MAGIC = 0x414D4331; // AMC1
    public static final int STATUS_MAGIC = 0x414D5331;  // AMS1
    public static final int VERSION = 1;

    public static final int VIDEO_HEADER_BYTES = 40;
    public static final int MAX_DATAGRAM_BYTES = 1200;
    public static final int MAX_PAYLOAD_BYTES = MAX_DATAGRAM_BYTES - VIDEO_HEADER_BYTES;

    public static final int FLAG_KEYFRAME = 1;
    public static final int FLAG_CODEC_CONFIG = 2;
    public static final int FLAG_HEVC = 4;

    public static final int CONTROL_HEADER_BYTES = 8;
    public static final int STATUS_BYTES = 20;

    public static final int PORT = 48620;

    public static final byte[] HELLO = hello();

    private static byte[] hello() {
        try {
            return "AMHELLO1".getBytes("US-ASCII");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    // Control message types.
    public static final int TYPE_HELLO = 1;
    public static final int TYPE_START = 2;
    public static final int TYPE_STOP = 3;
    public static final int TYPE_SET_DISPLAY = 4;
    public static final int TYPE_REQUEST_IDR = 5;
    public static final int TYPE_CLICK = 6;
    public static final int TYPE_SCROLL = 7;
    public static final int TYPE_CLIENT_DISPLAY = 8;

    /**
     * Write one video fragment into a caller-owned buffer.
     *
     * The buffer is reused for every fragment of every frame. At 1200 bytes a datagram, a 1080p
     * keyframe is well over a hundred of them and there are sixty frames a second behind it;
     * allocating per fragment would hand the collector thousands of short-lived arrays a second on
     * the one path that must never pause.
     */
    public static int writeVideo(byte[] into, long sessionId, long frameId, long captureNanos,
                                 int fragmentIndex, int fragmentCount, int flags,
                                 byte[] payload, int payloadOffset, int payloadLength) {
        int at = 0;
        at = putInt(into, at, VIDEO_MAGIC);
        into[at++] = (byte) VERSION;
        into[at++] = (byte) flags;
        at = putShort(into, at, VIDEO_HEADER_BYTES);
        at = putLong(into, at, sessionId);
        at = putLong(into, at, frameId);
        at = putLong(into, at, captureNanos);
        at = putShort(into, at, fragmentIndex);
        at = putShort(into, at, fragmentCount);
        at = putShort(into, at, payloadLength);
        at = putShort(into, at, 0);
        System.arraycopy(payload, payloadOffset, into, at, payloadLength);
        return at + payloadLength;
    }

    public static byte[] status(boolean running, boolean hiDPI, boolean authorised,
                                int width, int height, long encodedFrames) {
        byte[] data = new byte[STATUS_BYTES];
        int at = putInt(data, 0, STATUS_MAGIC);
        data[at++] = (byte) VERSION;
        data[at++] = (byte) ((running ? 1 : 0) | (hiDPI ? 2 : 0) | (authorised ? 4 : 0));
        at = putShort(data, at, clampU16(width));
        at = putShort(data, at, clampU16(height));
        at = putShort(data, at, 0);
        putLong(data, at, encodedFrames);
        return data;
    }

    public static boolean isHello(byte[] data, int length) {
        if (length != HELLO.length) return false;
        for (int index = 0; index < HELLO.length; index++) {
            if (data[index] != HELLO[index]) return false;
        }
        return true;
    }

    /** A parsed control message. Null type means the datagram was not one. */
    public static final class Command {
        public int type;
        public int width, height, x, y, dx, dy, phase, maxWidth, maxHeight;
        public boolean hiDPI;

        /**
         * Whether obeying this would change what the host is doing.
         *
         * A hello only names a video destination, which the broadcast already does, so it is always
         * honoured. Everything else is refused unless it came from the paired client.
         */
        public boolean changesState() {
            return type != TYPE_HELLO;
        }
    }

    public static Command parseControl(byte[] data, int length) {
        if (length < CONTROL_HEADER_BYTES) return null;
        if (getInt(data, 0) != CONTROL_MAGIC) return null;
        if ((data[4] & 0xff) != VERSION) return null;
        int payload = getShort(data, 6);
        if (CONTROL_HEADER_BYTES + payload > length) return null;
        int body = CONTROL_HEADER_BYTES;

        Command command = new Command();
        command.type = data[5] & 0xff;
        switch (command.type) {
            case TYPE_HELLO:
            case TYPE_START:
            case TYPE_STOP:
            case TYPE_REQUEST_IDR:
                return command;
            case TYPE_SET_DISPLAY:
                if (payload < 5) return null;
                command.width = getShort(data, body);
                command.height = getShort(data, body + 2);
                if (command.width <= 0 || command.height <= 0) return null;
                command.hiDPI = (data[body + 4] & 1) != 0;
                return command;
            case TYPE_CLICK:
                if (payload < 4) return null;
                command.x = getShort(data, body);
                command.y = getShort(data, body + 2);
                return command;
            case TYPE_SCROLL:
                if (payload < 9) return null;
                command.phase = data[body] & 0xff;
                if (command.phase > 2) return null;
                command.x = getShort(data, body + 1);
                command.y = getShort(data, body + 3);
                command.dx = (short) getShort(data, body + 5);
                command.dy = (short) getShort(data, body + 7);
                return command;
            case TYPE_CLIENT_DISPLAY:
                if (payload < 4) return null;
                command.width = getShort(data, body);
                command.height = getShort(data, body + 2);
                if (command.width <= 0 || command.height <= 0) return null;
                // Older clients send only their panel size and name no decoder ceiling.
                if (payload >= 8) {
                    command.maxWidth = getShort(data, body + 4);
                    command.maxHeight = getShort(data, body + 6);
                }
                return command;
            default:
                return null;
        }
    }

    private static int clampU16(int value) {
        return value < 0 ? 0 : (value > 65535 ? 65535 : value);
    }

    private static int putInt(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
        return at + 4;
    }

    private static int putShort(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 8);
        data[at + 1] = (byte) value;
        return at + 2;
    }

    private static int putLong(byte[] data, int at, long value) {
        for (int index = 0; index < 8; index++) data[at + index] = (byte) (value >>> (56 - 8 * index));
        return at + 8;
    }

    private static int getInt(byte[] data, int at) {
        return (data[at] & 0xff) << 24 | (data[at + 1] & 0xff) << 16
                | (data[at + 2] & 0xff) << 8 | (data[at + 3] & 0xff);
    }

    private static int getShort(byte[] data, int at) {
        return (data[at] & 0xff) << 8 | (data[at + 1] & 0xff);
    }
}
