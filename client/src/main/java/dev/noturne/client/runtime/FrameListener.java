package dev.noturne.client.runtime;

/**
 * Per-frame callback.
 *
 * <p>Implemented by the UI layer and invoked from bytecode that the agent injects into the game
 * loop, so implementations must be cheap and must not allocate on the hot path. Any exception is
 * swallowed by {@link NoturneRuntime} — a broken overlay must never kill the game.
 */
public interface FrameListener {

    void onFrame();
}
