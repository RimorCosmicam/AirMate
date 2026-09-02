package com.airmate.host.server;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * The display Samsung's own desktop attaches itself to.
 *
 * Nothing here draws a desktop or imitates one. It creates an ordinary Android virtual display with
 * system decorations, and One UI does the rest on its own: its SecondaryLauncher and DexTaskbar
 * appear on any trusted display large enough to hold them. That is the whole trick — the desktop is
 * Samsung's, and this only makes the screen for it to live on.
 *
 * The display must be *trusted* for that to happen, and only uid 2000 may ask for one, which is why
 * this class runs inside a shell process rather than inside the app.
 */
public final class DexDisplay {
    private final VirtualDisplay display;
    private final int displayId;

    private DexDisplay(VirtualDisplay display, int displayId) {
        this.display = display;
        this.displayId = displayId;
    }

    public int id() {
        return displayId;
    }

    /**
     * Build the display and hand it a surface to draw into.
     *
     * `DisplayManager`'s constructor is private and its instance normally comes from a Context that
     * a shell process does not have, so it is built directly. This is the same door scrcpy goes
     * through, and it is a door: `ADD_TRUSTED_DISPLAY` is held by the shell uid only, and Google
     * removed it in Android 15 QPR2 before restoring it in 16 with a note saying it may go again.
     */
    public static DexDisplay create(String name, int width, int height, int dpi, Surface surface)
            throws Exception {
        return create(name, width, height, dpi, surface, 0);
    }

    /**
     * @param flagOverride the exact flag word to use, or zero to work one out.
     *
     * The override exists because these flags are the only lever we have over what One UI decides to
     * put on the display, and the difference between a desktop with a wallpaper and one without is a
     * single bit somewhere in here. Being able to try a combination without a rebuild turns an
     * afternoon of guessing into a couple of minutes of measuring.
     */
    public static DexDisplay create(String name, int width, int height, int dpi, Surface surface,
                                    int flagOverride) throws Exception {
        Constructor<DisplayManager> constructor =
                DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        DisplayManager manager = constructor.newInstance(FakeContext.get());

        int flags = flagOverride != 0 ? flagOverride : flags();
        Ln.i("DEX", "Creating virtual display " + width + "x" + height + "/" + dpi
                + " flags=0x" + Integer.toHexString(flags));
        VirtualDisplay created =
                manager.createVirtualDisplay(name, width, height, dpi, surface, flags);
        if (created == null) throw new IllegalStateException("createVirtualDisplay returned null");
        int id = created.getDisplay().getDisplayId();
        Ln.i("DEX", "Display created, displayId = " + id);
        // What the framework kept, which is not always what was asked for.
        Ln.i("DEX", "Display reports: " + created.getDisplay());
        return new DexDisplay(created, id);
    }

    /**
     * Put the display into freeform windowing.
     *
     * This is not what summons DeX — the desktop is already there the moment the display exists.
     * It is what decides whether the apps on that desktop are windows or are each full screen, and
     * a desktop whose every app is full screen is a launcher, not a desktop.
     *
     * Note that the display's own `mWindowingMode` keeps reading `fullscreen` afterwards; the mode
     * that changed is the one new tasks inherit, so it shows up on a launched task and nowhere else.
     */
    public void enableFreeform() {
        String command = "wm set-display-windowing-mode -d " + displayId + " 5";
        Ln.i("DEX", command);
        try {
            Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            int status = process.waitFor();
            if (status != 0) Ln.e("DEX", "freeform request exited " + status, null);
        } catch (Exception error) {
            Ln.e("DEX", "could not set freeform windowing mode", error);
        }
    }

    public void release() {
        try {
            display.release();
        } catch (Exception ignored) {
            // The display goes with the process anyway; a failure here has nothing left to break.
        }
    }

    /**
     * The flags, read off the framework rather than written down.
     *
     * Their numeric values are stable in practice but they are still hidden constants, and a field
     * that has been renamed should cost us that one capability rather than the whole display.
     */
    private static int flags() {
        int flags = flag("VIRTUAL_DISPLAY_FLAG_PUBLIC", 1 << 0)
                | flag("VIRTUAL_DISPLAY_FLAG_PRESENTATION", 1 << 1)
                | flag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", 1 << 3)
                | flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 1 << 6)
                | flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", 1 << 7)
                | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 1 << 8)
                // Without this One UI puts nothing on the display at all: no launcher, no taskbar,
                // no desktop. It is the flag that turns a surface into a screen.
                | flag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS", 1 << 9);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            flags |= flag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 << 10)
                    | flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", 1 << 11)
                    | flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", 1 << 12)
                    | flag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED", 1 << 13);
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            flags |= flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 1 << 14)
                    | flag("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP", 1 << 15);
        }
        return flags;
    }

    private static int flag(String name, int fallback) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
