package com.airmate.host.server;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * One GPU blit standing between the desktop and the encoder, so a still desktop still sends frames.
 *
 * Android's surface encoders take a frame only when the compositor produces one, and a desktop
 * nobody is touching produces none — so a client that connects to a motionless screen has nothing
 * to decode and shows black until something moves. `KEY_REPEAT_PREVIOUS_FRAME_AFTER` exists for
 * exactly this and is set, but the Exynos Codec2 encoder ignores it: measured idle output was zero
 * to two frames a second where the key promises ten.
 *
 * So the repeat is done here instead. The display draws into a texture rather than into the encoder,
 * and this thread draws that texture into the encoder — the moment a new frame arrives, and again
 * every hundred milliseconds if none does. A repeated frame costs the encoder almost nothing, since
 * every macroblock in it is unchanged and is coded as skipped.
 *
 * If any of this fails to start, the caller wires the display straight to the encoder as before and
 * loses only the repeat.
 */
public final class FrameRepeater {
    /** How long a motionless desktop may go without sending anything. */
    private static final long IDLE_INTERVAL_NANOS = 100_000_000L;

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec4 position;\n"
            + "attribute vec4 texCoordinate;\n"
            + "uniform mat4 textureMatrix;\n"
            + "varying vec2 coordinate;\n"
            + "void main() {\n"
            + "    gl_Position = position;\n"
            + "    coordinate = (textureMatrix * texCoordinate).xy;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 coordinate;\n"
            + "uniform samplerExternalOES texture;\n"
            + "void main() {\n"
            + "    gl_FragColor = texture2D(texture, coordinate);\n"
            + "}\n";

    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    private final int width;
    private final int height;
    /** What shows through wherever the desktop draws nothing. */
    private final float[] background;
    private final Surface encoderSurface;
    private final Thread thread;

    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private volatile boolean running = true;
    private volatile Surface displaySurface;
    private volatile Throwable startupFailure;
    private final Object ready = new Object();
    private boolean started;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture surfaceTexture;
    private int program;
    private int textureId;
    private final float[] textureMatrix = new float[16];

    private FrameRepeater(int width, int height, Surface encoderSurface, int backgroundRgb) {
        this.background = new float[] {
                ((backgroundRgb >> 16) & 0xff) / 255f,
                ((backgroundRgb >> 8) & 0xff) / 255f,
                (backgroundRgb & 0xff) / 255f
        };
        this.width = width;
        this.height = height;
        this.encoderSurface = encoderSurface;
        this.thread = new Thread(this::loop, "AirMate-Repeat");
        this.thread.setDaemon(true);
    }

    /**
     * Start the pass, or throw if this device will not give us a GL context.
     *
     * Everything happens on the render thread — an EGL context belongs to the thread that made it —
     * so the caller waits here until that thread has either published a surface or failed.
     */
    public static FrameRepeater start(int width, int height, Surface encoderSurface,
                                      int backgroundRgb) throws Exception {
        FrameRepeater repeater = new FrameRepeater(width, height, encoderSurface, backgroundRgb);
        repeater.thread.start();
        synchronized (repeater.ready) {
            while (!repeater.started) repeater.ready.wait(5000);
        }
        if (repeater.startupFailure != null) {
            repeater.close();
            throw new IllegalStateException("frame repeater did not start", repeater.startupFailure);
        }
        Ln.i("VIDEO", "Frame repeater on: idle desktops resend every "
                + IDLE_INTERVAL_NANOS / 1_000_000 + " ms");
        return repeater;
    }

    /** The surface the virtual display should draw into. */
    public Surface displaySurface() {
        return displaySurface;
    }

    private void loop() {
        try {
            setUp();
            synchronized (ready) {
                started = true;
                ready.notifyAll();
            }
        } catch (Throwable failure) {
            startupFailure = failure;
            synchronized (ready) {
                started = true;
                ready.notifyAll();
            }
            return;
        }

        while (running) {
            boolean fresh;
            synchronized (frameLock) {
                if (!frameAvailable) {
                    try {
                        frameLock.wait(IDLE_INTERVAL_NANOS / 1_000_000);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                }
                fresh = frameAvailable;
                frameAvailable = false;
            }
            if (!running) return;
            try {
                // A fresh frame is consumed; an idle tick redraws whatever the texture already
                // holds, which is the last thing the desktop drew.
                if (fresh) {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(textureMatrix);
                }
                draw();
            } catch (Exception error) {
                if (running) Ln.e("VIDEO", "repeat pass failed", error);
                return;
            }
        }
    }

    private void setUp() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("no EGL display");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize failed");
        }
        int[] attributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                // Without this the encoder will not accept buffers from this surface.
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] found = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, found, 0) || found[0] == 0) {
            throw new IllegalStateException("no recordable EGL config");
        }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext failed");
        }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface,
                new int[] { EGL14.EGL_NONE }, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("eglMakeCurrent failed");
        }

        program = link();
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(width, height);
        surfaceTexture.setOnFrameAvailableListener(texture -> {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        });
        // Identity until the first frame names its own orientation, so an idle tick that lands
        // before any frame does draws something rather than garbage.
        android.opengl.Matrix.setIdentityM(textureMatrix, 0);
        displaySurface = new Surface(surfaceTexture);
    }

    private void draw() {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(background[0], background[1], background[2], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        FloatBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(QUAD).position(0);

        int position = GLES20.glGetAttribLocation(program, "position");
        int coordinate = GLES20.glGetAttribLocation(program, "texCoordinate");
        vertices.position(0);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(position);
        vertices.position(2);
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(coordinate);

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"), 1, false,
                textureMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "texture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // The encoder stamps its output with this, and the client measures latency from it.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime());
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private static int link() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("shader link failed: " + log);
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }

    public void close() {
        running = false;
        synchronized (frameLock) { frameLock.notifyAll(); }
        try { thread.interrupt(); } catch (Exception ignored) {}
        try { thread.join(1000); } catch (Exception ignored) {}
        Surface surface = displaySurface;
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) {}
        }
        if (surfaceTexture != null) {
            try { surfaceTexture.release(); } catch (Exception ignored) {}
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            } catch (Exception ignored) {}
        }
    }
}
