package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;

/**
 * Text drawing seam.
 *
 * <p>The UI module never links against the game's font renderer; the client supplies an
 * implementation backed by Minecraft's {@code FontRenderer}. Keeping it an interface also lets the
 * geometry layer be tested without a font at all.
 */
public interface TextRenderer {

    void draw(String text, float x, float y, float size, Color color);

    float width(String text, float size);

    float height(float size);

    /** Draws nothing and reports a fixed advance; used before the real font is located. */
    TextRenderer NONE = new TextRenderer() {
        @Override
        public void draw(String text, float x, float y, float size, Color color) {
        }

        @Override
        public float width(String text, float size) {
            return text == null ? 0f : text.length() * size * 0.5f;
        }

        @Override
        public float height(float size) {
            return size;
        }
    };
}
