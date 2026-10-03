package dev.noturne.ui;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.List;

/** Headless {@link Renderer} that records what the component tree asked to draw. */
public final class RecordingRenderer implements Renderer {

    public final List<String> calls = new ArrayList<String>();

    public int count(String kind) {
        int n = 0;
        for (String call : calls) {
            if (call.equals(kind)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        calls.add("rect");
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        calls.add("roundedRect");
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        calls.add("outline");
    }

    @Override
    public void text(String text, float x, float y, float size, Color color) {
        calls.add("text:" + text);
    }

    @Override
    public float textWidth(String text, float size) {
        return text.length() * size * 0.5f;
    }

    @Override
    public float textHeight(float size) {
        return size;
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        calls.add("pushClip");
    }

    @Override
    public void popClip() {
        calls.add("popClip");
    }
}
