package dev.noturne.ui.gl;

import dev.noturne.ui.render.Renderer;

/**
 * A drawable backend: the {@link Renderer} surface plus per-frame setup/teardown.
 *
 * <p>Two implementations ship today — {@link GlRenderer} for the fixed-function pipeline
 * (Minecraft ≤ 1.12, LWJGL2) and {@link ModernRenderer} for the OpenGL 3.2 core profile
 * (1.13+ / 26.x, LWJGL3). A Vulkan backend slots in behind the same interface once the game's
 * Vulkan device is reachable, which is why the overlay talks to this type rather than to a
 * concrete renderer.
 */
public interface UiBackend extends Renderer {

    /** Applies per-frame GL state. Called once before the component tree is drawn. */
    void beginFrame();

    /** Releases the state set by {@link #beginFrame()}. Called once after drawing. */
    void endFrame();

    /** Short name for logs and diagnostics, e.g. {@code "gl-fixed"} or {@code "gl-core"}. */
    String backendName();
}
