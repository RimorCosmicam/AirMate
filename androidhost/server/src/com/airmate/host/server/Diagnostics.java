package com.airmate.host.server;

/**
 * Counters for the path that must not pause.
 *
 * Plain fields rather than atomics: every one of them has a single writer, and the once-a-second
 * reader would rather see a value one frame stale than make the encoder synchronise to publish it.
 */
public final class Diagnostics {
    private Diagnostics() {}

    public static volatile long encoded;
    public static volatile long droppedNetwork;
    public static volatile long lastClientHelloNanos;

    public static boolean clientConnected() {
        long last = lastClientHelloNanos;
        return last > 0 && System.nanoTime() - last < 3_000_000_000L;
    }
}
