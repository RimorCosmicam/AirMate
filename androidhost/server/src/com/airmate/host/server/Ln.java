package com.airmate.host.server;

/**
 * Logging, staged so every step of the session announces itself.
 *
 * This process has no UI and no debugger attached to it: the log is the only way to tell a display
 * that was never created from one that was created and never drew. Every stage prints, in the
 * order the state machine runs them.
 */
public final class Ln {
    private Ln() {}

    public static void i(String stage, String message) {
        System.out.println("[" + stage + "] " + message);
        System.out.flush();
    }

    public static void e(String stage, String message, Throwable error) {
        System.err.println("[" + stage + "] ERROR " + message);
        if (error != null) error.printStackTrace(System.err);
        System.err.flush();
    }
}
