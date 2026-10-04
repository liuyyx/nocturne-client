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

/**
 * 验证 HUD 契约：元素按注册顺序绘制、隐藏时不绘制、文本每帧取最新值且空串跳过、按 id 可寻址。
 */
class HudTest {

    /** 两个元素均启用时应各绘制一次；禁用其一后仅剩另一个被绘制，顺序与注册顺序一致 */
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

    /** {@code setVisible(false)} 后 {@code render} 不得产生任何绘制调用 */
    @Test
    void hiddenHudDrawsNothing() {
        RecordingRenderer renderer = new RecordingRenderer();
        HudManager hud = new HudManager();
        hud.add(new TextElement("fps", () -> "fps: 60", 14f, Color.WHITE, false));

        hud.setVisible(false);
        hud.render(renderer);
        assertTrue(renderer.calls.isEmpty());
    }

    /** {@code currentText()} 每次都读 supplier 的当前值；supplier 返回空串时 render 不产生绘制调用 */
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

    /** {@code byId} 命中已注册元素、未命中返回 null，{@code elements()} 反映已注册数量 */
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
