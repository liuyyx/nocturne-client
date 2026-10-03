package dev.noturne.ui;

import dev.noturne.ui.hud.HudManager;
import dev.noturne.ui.hud.TextElement;
import dev.noturne.ui.render.Color;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudTest {

    @Test
    void rendersOnlyEnabledElementsInOrder() {
        RecordingRenderer renderer = new RecordingRenderer();
        HudManager hud = new HudManager();

        TextElement first = new TextElement("fps", () -> "fps: 120", 14f, Color.WHITE, false);
        TextElement second = new TextElement("coords", () -> "xyz: 1 2 3", 14f, Color.WHITE, false);
        hud.add(first);
        hud.add(second);

        hud.render(renderer);
        assertEquals(1, renderer.count("text:fps: 120"));

        second.setEnabled(false);
        renderer.calls.clear();
        hud.render(renderer);
        assertEquals(1, renderer.count("text:fps: 120"));
        assertEquals(0, renderer.count("text:xyz: 1 2 3"));
    }

    @Test
    void hiddenHudDrawsNothing() {
        RecordingRenderer renderer = new RecordingRenderer();
        HudManager hud = new HudManager();
        hud.add(new TextElement("fps", () -> "fps: 60", 14f, Color.WHITE, false));

        hud.setVisible(false);
        hud.render(renderer);
        assertTrue(renderer.calls.isEmpty());
    }

    @Test
    void textElementReadsLiveValueAndSkipsEmpty() {
        AtomicReference<String> value = new AtomicReference<String>("a");
        TextElement element = new TextElement("t", value::get, 12f, Color.WHITE, false);
        RecordingRenderer renderer = new RecordingRenderer();

        element.render(renderer);
        assertEquals("a", element.currentText());

        value.set("b");
        element.render(renderer);
        assertEquals("b", element.currentText());

        value.set("");
        renderer.calls.clear();
        element.render(renderer);
        assertTrue(renderer.calls.isEmpty(), "empty text must not draw");
    }

    @Test
    void elementsAreAddressableById() {
        HudManager hud = new HudManager();
        TextElement element = new TextElement("fps", () -> "x", 12f, Color.WHITE);
        hud.add(element);

        assertTrue(hud.byId("fps") == element);
        assertNull(hud.byId("missing"));
        assertFalse(hud.elements().isEmpty());
    }
}
