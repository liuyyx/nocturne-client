package dev.noturne.ui.render;

/**
 * Drawing surface the UI is written against.
 *
 * <p>Implementations: the in-game OpenGL backend and a headless recorder used by tests. Keeping
 * the UI on this seam lets the whole component tree be exercised without a Minecraft runtime.
 */
public interface Renderer {

    void rect(float x, float y, float width, float height, Color color);

    void roundedRect(float x, float y, float width, float height, float radius, Color color);

    void outline(float x, float y, float width, float height, float lineWidth, Color color);

    void text(String text, float x, float y, float size, Color color);

    float textWidth(String text, float size);

    float textHeight(float size);

    /** Clips subsequent drawing to the given rectangle until {@link #popClip()}. */
    void pushClip(float x, float y, float width, float height);

    void popClip();
}
