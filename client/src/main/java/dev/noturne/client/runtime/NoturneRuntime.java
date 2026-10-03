package dev.noturne.client.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime entry point invoked from injected bytecode.
 *
 * <p>The agent patches the game loop to call {@link #onFrame()} once per frame. That method is on
 * the hot path and in the middle of a JVM method that we did not write, so it is defensive by
 * design: it never throws, never blocks, and does nothing until listeners are registered.
 */
public final class NoturneRuntime {

    private static final List<FrameListener> LISTENERS = new CopyOnWriteArrayList<FrameListener>();
    private static final AtomicBoolean TRACE = new AtomicBoolean(false);
    private static volatile boolean firstFrameLogged;

    private NoturneRuntime() {
    }

    public static void addListener(FrameListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(FrameListener listener) {
        LISTENERS.remove(listener);
    }

    public static int listenerCount() {
        return LISTENERS.size();
    }

    /** Logs a line the first time the hook actually fires; useful to confirm the patch worked. */
    public static void trace(boolean enabled) {
        TRACE.set(enabled);
    }

    /** Called from injected bytecode. Must never throw. */
    public static void onFrame() {
        try {
            if (!firstFrameLogged) {
                firstFrameLogged = true;
                if (TRACE.get()) {
                    System.out.println("[noturne] frame hook is live");
                }
            }
            if (LISTENERS.isEmpty()) {
                return;
            }
            for (int i = 0; i < LISTENERS.size(); i++) {
                try {
                    LISTENERS.get(i).onFrame();
                } catch (Throwable ignored) {
                    // one bad listener must not stop the others or the game
                }
            }
        } catch (Throwable ignored) {
            // never propagate into the patched game method
        }
    }
}
