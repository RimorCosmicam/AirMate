package com.airmate.host.server;

import android.os.Looper;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AirMate's host, running as the shell user.
 *
 * It lives here rather than in the app because of one permission: only uid 2000 may create a
 * *trusted* virtual display, and only a trusted display is one Samsung's desktop will attach itself
 * to. Everything downstream of that — encoding, the wire protocol, input — could have lived in the
 * app, and is here instead so the whole hot path stays in one process with no surface to hand
 * across a binder.
 *
 * The sequence is: make a display, let One UI put DeX on it, encode what DeX draws, and send it to
 * whichever AirMate client says hello. Nothing in this process draws a desktop of its own.
 */
public final class Server {
    private static final String NAME = "AirMate";

    private final int dpi;
    private final boolean freeform;
    private final boolean preferHevc;
    /** An explicit virtual-display flag word, or zero to let DexDisplay work one out. */
    private final int flagOverride;
    /** What shows wherever the desktop draws nothing, as 0xRRGGBB. */
    private final int background;

    private final ExecutorService session = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AirMate-Session");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random = new Random();

    private Transport transport;
    private Encoder encoder;
    private FrameRepeater repeater;
    private DexDisplay display;
    private Input input;

    private int width;
    private int height;
    private volatile long sessionId;
    private long frameId;
    private volatile boolean wantRunning = true;

    /** The client we have already refitted for, so a repeated announcement is not a restart. */
    private long fittedFor = -1;

    /** Whether a session has ever come up, which decides whether stdin EOF means anything. */
    private volatile boolean everStarted;

    private Server(int width, int height, int dpi, boolean freeform, boolean preferHevc,
                   int flagOverride, int background) {
        this.flagOverride = flagOverride;
        this.background = background;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.freeform = freeform;
        this.preferHevc = preferHevc;
    }

    public static void main(String[] args) throws Exception {
        for (String argument : args) {
            if ("dumpflags".equals(argument)) {
                dumpFlags();
                return;
            }
        }
        int width = 1920, height = 1080, dpi = 160, flagOverride = 0, background = 0;
        boolean freeform = true, hevc = false;
        for (String argument : args) {
            if (argument.startsWith("size=")) {
                String[] parts = argument.substring(5).split("x");
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            } else if (argument.startsWith("dpi=")) {
                dpi = Integer.parseInt(argument.substring(4));
            } else if (argument.startsWith("freeform=")) {
                freeform = !"false".equals(argument.substring(9));
            } else if (argument.startsWith("hevc=")) {
                hevc = "true".equals(argument.substring(5));
            } else if (argument.startsWith("bg=")) {
                background = Integer.parseInt(argument.substring(3), 16);
            } else if (argument.startsWith("flags=")) {
                // Long, then narrowed: the Samsung-private flags run into the sign bit and
                // Integer.decode refuses anything above 0x7fffffff.
                flagOverride = (int) (long) Long.decode(argument.substring(6));
            }
        }
        // app_process hands us a bare thread. The framework wants a main Looper on it, and
        // ActivityThread.systemMain() builds a Handler against whichever thread calls it — so the
        // context has to be warmed here, on this thread, and not later on a worker that has none.
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        FakeContext.get();
        new Server(width, height, dpi, freeform, hevc, flagOverride, background).run();
    }

    /**
     * Every virtual-display flag this device's framework actually defines.
     *
     * Written down from the device rather than from the SDK, because the flags that matter here are
     * hidden, vary by release, and Samsung adds its own. Guessing which one controls a wallpaper is
     * slower than reading the list.
     */
    private static void dumpFlags() {
        java.lang.reflect.Field[] fields =
                android.hardware.display.DisplayManager.class.getDeclaredFields();
        java.util.TreeMap<Integer, String> byValue = new java.util.TreeMap<>();
        for (java.lang.reflect.Field field : fields) {
            if (!field.getName().startsWith("VIRTUAL_DISPLAY_FLAG")) continue;
            try {
                field.setAccessible(true);
                byValue.put(field.getInt(null), field.getName());
            } catch (Exception ignored) {
                // A field that will not read is one we could not have used anyway.
            }
        }
        for (java.util.Map.Entry<Integer, String> entry : byValue.entrySet()) {
            Ln.i("FLAGS", String.format("0x%05x  %s", entry.getKey(), entry.getValue()));
        }
    }

    private void run() throws Exception {
        Ln.i("HOST", "AirMate host starting: " + width + "x" + height + "/" + dpi
                + (freeform ? " freeform" : " fullscreen"));

        transport = new Transport(new Transport.Listener() {
            @Override
            public void onCommand(Protocol.Command command) {
                session.execute(() -> perform(command));
            }

            @Override
            public void onClientChanged() {
                // A client arriving mid-GOP has nothing it can decode and no way to ask for more.
                session.execute(() -> {
                    if (encoder != null) encoder.requestKeyframe();
                });
            }
        });
        Thread network = new Thread(transport, "AirMate-Network");
        network.setDaemon(true);
        network.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopSession));
        watchLauncher();

        session.execute(this::startSession);

        Thread status = new Thread(this::statusLoop, "AirMate-Status");
        status.setDaemon(true);
        status.start();

        // The main thread stays on its Looper so the framework handlers built above keep running.
        Looper.loop();
    }

    /**
     * Leave when whoever started us does.
     *
     * A shell process outlives the `adb shell` that launched it: closing the connection does not
     * signal it, so every abandoned run keeps its virtual display and its hardware encoder for as
     * long as the phone stays up. A handful of those and `MediaCodec.start` begins failing, because
     * the device has only a few encoders and they are all held by processes nobody can see.
     *
     * End-of-input on stdin is how the launcher going away announces itself. It is ignored before
     * the first session starts, because a launcher that redirects stdin from nowhere reports EOF
     * immediately and would otherwise shut us down a moment after boot.
     */
    private void watchLauncher() {
        Thread watchdog = new Thread(() -> {
            try {
                while (System.in.read() != -1) {
                    // Anything sent to us is ignored; only the end of the stream means anything.
                }
            } catch (Exception ignored) {
                // A broken pipe says the same thing EOF does.
            }
            if (!everStarted) {
                Ln.i("HOST", "no control stream; staying up until killed");
                return;
            }
            Ln.i("HOST", "launcher went away; releasing the display and exiting");
            stopSession();
            System.exit(0);
        }, "AirMate-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Status once a second, which is the only thing the client can describe a stopped host from. */
    private void statusLoop() {
        long previous = 0;
        int tick = 0;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException stopped) {
                return;
            }
            transport.sendStatus(display != null, false, width, height, Diagnostics.encoded);
            // Every fifth tick, in frames per second rather than a running total: "no picture" has
            // several causes that look identical from here, and the rate tells them apart — nothing
            // encoded is a dead display, encoded but dropped is a socket that will not take them.
            if (++tick % 5 == 0) {
                long encoded = Diagnostics.encoded;
                Ln.i("VIDEO", "encoded=" + encoded + " (" + (encoded - previous) / 5 + "/s)"
                        + " droppedNetwork=" + Diagnostics.droppedNetwork
                        + " client=" + (transport.paired() ? "paired" : "none"));
                previous = encoded;
            }
        }
    }

    private void startSession() {
        if (display != null) return;
        try {
            sessionId = random.nextLong();
            frameId = 0;
            encoder = new Encoder(width, height, preferHevc, this::onAccessUnit);

            // The display normally draws straight into the encoder. It draws into the repeat pass
            // instead when there is one, so a motionless desktop keeps sending. Falling back is
            // safe and is exactly the behaviour that shipped before the pass existed: a still
            // desktop simply stops sending until something on it moves.
            android.view.Surface target = encoder.surface();
            try {
                repeater = FrameRepeater.start(width, height, encoder.surface(), background);
                target = repeater.displaySurface();
            } catch (Throwable failure) {
                Ln.e("VIDEO", "no frame repeater; a still desktop will stop sending", failure);
                repeater = null;
            }

            display = DexDisplay.create(NAME, width, height, dpi, target, flagOverride);
            if (freeform) display.enableFreeform();
            input = new Input(display.id(), width, height);
            everStarted = true;
            Ln.i("HOST", "SESSION READY — waiting for an AirMate client to say hello");
        } catch (Throwable error) {
            Ln.e("HOST", "could not start the session", error);
            stopSession();
        }
    }

    private void stopSession() {
        // Torn down in the order things depend on each other: the display draws into the encoder's
        // surface, so the display goes first or the encoder is released underneath a live producer.
        if (input != null) { input.close(); input = null; }
        if (display != null) { display.release(); display = null; }
        if (repeater != null) { repeater.close(); repeater = null; }
        if (encoder != null) { encoder.close(); encoder = null; }
        Ln.i("HOST", "session stopped");
    }

    private void perform(Protocol.Command command) {
        switch (command.type) {
            case Protocol.TYPE_START:
                wantRunning = true;
                startSession();
                break;
            case Protocol.TYPE_STOP:
                wantRunning = false;
                stopSession();
                break;
            case Protocol.TYPE_REQUEST_IDR:
                if (encoder != null) encoder.requestKeyframe();
                break;
            case Protocol.TYPE_SET_DISPLAY:
                if (command.width == width && command.height == height) return;
                Ln.i("HOST", "resizing to " + command.width + "x" + command.height);
                stopSession();
                width = command.width;
                height = command.height;
                if (wantRunning) startSession();
                break;
            case Protocol.TYPE_CLICK:
                if (input != null) input.click(command.x, command.y);
                break;
            case Protocol.TYPE_SCROLL:
                if (input != null) input.scroll(command.phase, command.x, command.y, command.dx, command.dy);
                break;
            case Protocol.TYPE_CLIENT_DISPLAY:
                onClientDisplay(command);
                break;
            default:
                break;
        }
    }

    /**
     * What the client is, and whether we are sending it something it can actually decode.
     *
     * A size above the client's decoder ceiling is not a worse picture, it is no picture: the
     * hardware answers with an error and the codec dies for the rest of the session. So this is the
     * one case where the host overrides the size it was asked for — never to improve it, only when
     * the alternative is a black screen.
     */
    private void onClientDisplay(Protocol.Command command) {
        Ln.i("HOST", "client panel " + command.width + "x" + command.height
                + ", decoder ceiling " + command.maxWidth + "x" + command.maxHeight);
        if (command.maxWidth <= 0 || command.maxHeight <= 0) return;
        if (width <= command.maxWidth && height <= command.maxHeight) return;
        if (fittedFor == command.width * 100000L + command.height) return;

        int[] best = fit(command.width, command.height, command.maxWidth, command.maxHeight);
        if (best == null) {
            Ln.e("HOST", "no size fits a " + command.maxWidth + "x" + command.maxHeight
                    + " decoder; leaving " + width + "x" + height + " alone", null);
            return;
        }
        // Remembered so a client that repeats itself once a second does not restart the desktop
        // once a second with it.
        fittedFor = command.width * 100000L + command.height;
        Ln.i("HOST", width + "x" + height + " is past this client's decoder; refitting to "
                + best[0] + "x" + best[1]);
        stopSession();
        width = best[0];
        height = best[1];
        if (wantRunning) startSession();
    }

    /**
     * The largest size at the client's own shape that its decoder will accept.
     *
     * The same derivation the client makes in `resolutionsFor`, so the size chosen here is one the
     * client would itself have offered. Sides snap to multiples of sixteen because hardware
     * decoders refuse anything else however far inside their stated limits it is — 1800 x 1080 is
     * refused by a decoder that accepts the larger 1808 x 1088.
     */
    private static int[] fit(int panelWidth, int panelHeight, int maxWidth, int maxHeight) {
        double[] scales = {1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4};
        for (double scale : scales) {
            int candidateWidth = snap(panelWidth * scale);
            int candidateHeight = snap(panelHeight * scale);
            if (candidateWidth < 640 || candidateHeight < 480) continue;
            if (candidateWidth <= maxWidth && candidateHeight <= maxHeight) {
                return new int[] { candidateWidth, candidateHeight };
            }
        }
        return null;
    }

    private static int snap(double value) {
        return Math.max(16, (int) Math.round(value / 16) * 16);
    }

    private void onAccessUnit(byte[] data, int length, long captureNanos, boolean keyframe, boolean hevc) {
        transport.sendVideo(data, length, sessionId, ++frameId, captureNanos, keyframe, hevc);
    }
}
