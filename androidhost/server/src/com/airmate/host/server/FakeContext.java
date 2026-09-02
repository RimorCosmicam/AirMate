package com.airmate.host.server;

import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Method;

/**
 * A Context for a process that has none.
 *
 * `app_process` starts a bare VM: there is no Application, no ContextImpl, and nothing to hand the
 * framework classes that insist on one. The system context is fetched from ActivityThread the way
 * every shell-side tool does it, then wrapped so the package name we report is the shell's own —
 * the framework checks the caller's package against its uid, and a name that does not belong to
 * uid 2000 is rejected before the call it guards is ever reached.
 */
public final class FakeContext extends ContextWrapper {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static Context instance;

    private FakeContext(Context base) {
        super(base);
    }

    public static synchronized Context get() throws Exception {
        if (instance == null) {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThread.getMethod("systemMain");
            Object thread = systemMain.invoke(null);
            Method getSystemContext = activityThread.getMethod("getSystemContext");
            Context system = (Context) getSystemContext.invoke(thread);
            instance = new FakeContext(system);
        }
        return instance;
    }

    @Override
    public String getPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return SHELL_PACKAGE;
    }
}
