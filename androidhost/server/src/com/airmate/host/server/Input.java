package com.airmate.host.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reading mode: taps and drags from the tablet, landing on the desktop display.
 *
 * The vocabulary is the client's, which is tap and scroll and nothing else — no drag, no right
 * click, no modifier. That is what the AirMate client sends today, and a host that invented gestures
 * the client cannot produce would be answering a question nobody asked.
 *
 * Unlike the Mac there is no cursor to borrow and put back. A tap here is a finger on that display,
 * which is what the display expects, so nothing has to be restored afterwards.
 *
 * The events go through the `input` command rather than through injected MotionEvents. That costs a
 * process per gesture and is provisional: it is the version that cannot break, while direct
 * injection needs private framework calls that have moved twice in three releases. Video is the
 * milestone this build is for; this is here so the display is not read-only while we get there.
 */
public final class Input {
    private static final int FULL = 65535;

    private final int displayId;
    private final int width;
    private final int height;
    /**
     * One thread, so a flick cannot outrun the shell.
     *
     * The network thread must never wait on a process spawn, and two `input` commands racing each
     * other land in an order neither of them chose.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AirMate-Input");
        thread.setDaemon(true);
        return thread;
    });

    private int anchorX, anchorY, travelX, travelY;
    private boolean scrolling;

    public Input(int displayId, int width, int height) {
        this.displayId = displayId;
        this.width = width;
        this.height = height;
    }

    public void click(int normalisedX, int normalisedY) {
        int x = toPixelsX(normalisedX);
        int y = toPixelsY(normalisedY);
        run("input -d " + displayId + " tap " + x + " " + y);
    }

    /**
     * A scroll, assembled from the run of deltas and issued once.
     *
     * The client sends a begin, a stream of deltas in display pixels, and an end. Turning each delta
     * into its own command would spawn a process per pixel of a flick; instead the travel is summed
     * and sent as the single drag it always was.
     */
    public void scroll(int phase, int normalisedX, int normalisedY, int dx, int dy) {
        switch (phase) {
            case 0:
                anchorX = toPixelsX(normalisedX);
                anchorY = toPixelsY(normalisedY);
                travelX = 0;
                travelY = 0;
                scrolling = true;
                return;
            case 1:
                if (scrolling) {
                    travelX += dx;
                    travelY += dy;
                }
                return;
            default:
                if (!scrolling) return;
                scrolling = false;
                if (travelX == 0 && travelY == 0) return;
                int endX = clamp(anchorX + travelX, 0, width - 1);
                int endY = clamp(anchorY + travelY, 0, height - 1);
                run("input -d " + displayId + " swipe "
                        + anchorX + " " + anchorY + " " + endX + " " + endY + " 120");
        }
    }

    /** Drop a half-finished gesture, so a client that vanishes mid-flick leaves nothing pending. */
    public void reset() {
        scrolling = false;
        travelX = 0;
        travelY = 0;
    }

    public void close() {
        worker.shutdownNow();
    }

    private void run(String command) {
        worker.execute(() -> {
            try {
                new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start().waitFor();
            } catch (Exception error) {
                Ln.e("INPUT", command, error);
            }
        });
    }

    // Normalised in, display pixels out: the client never has to know this display's resolution.
    private int toPixelsX(int normalised) {
        return clamp((int) ((long) normalised * width / FULL), 0, width - 1);
    }

    private int toPixelsY(int normalised) {
        return clamp((int) ((long) normalised * height / FULL), 0, height - 1);
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
