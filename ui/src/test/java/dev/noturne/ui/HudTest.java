package dev.noturne.ui;

import dev.noturne.ui.hud.HudManager;
import dev.noturne.ui.hud.TextElement;
import dev.noturne.ui.render.Color;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 HUD 契约：元素按注册顺序绘制（不止计数，还要看顺序）、隐藏时不绘制、
 * 文本每帧取最新值且空串跳过、同 id 注册替换、渲染中增删元素不抛并发异常。
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
        // L-15 回归：必须断言绘制顺序，把 render 循环反转（后注册先画）必须失败
        assertTrue(renderer.calls.indexOf("text:fps: 120")
                        < renderer.calls.indexOf("text:xyz: 1 2 3"),
                "elements must render in registration order");

        second.setEnabled(false);
        renderer.clear();
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
        renderer.clear();
        element.render(renderer);
        assertTrue(renderer.calls.isEmpty(), "empty text must not draw");
    }

    /** {@code byId} 命中已注册元素、未命中返回 null，{@code elements()} 反映已注册数量 */
    @Test
    void elementsAreAddressableById() {
        HudManager hud = new HudManager();
        TextElement element = new TextElement("fps", () -> "x", 12f, Color.WHITE);
        hud.add(element);

        assertSame(element, hud.byId("fps"));
        assertNull(hud.byId("missing"));
        assertFalse(hud.elements().isEmpty());
    }

    /**
     * L-45 回归：同 id 重复 {@code add} 必须替换而不是叠加，否则同一读数会被画两行。
     */
    @Test
    void addingTheSameIdTwiceReplacesInsteadOfStacking() {
        RecordingRenderer renderer = new RecordingRenderer();
        HudManager hud = new HudManager();
        hud.add(new TextElement("fps", () -> "old", 14f, Color.WHITE, false));
        TextElement replacement = new TextElement("fps", () -> "new", 14f, Color.WHITE, false);
        hud.add(replacement);

        assertEquals(1, hud.elements().size());
        assertSame(replacement, hud.byId("fps"));

        hud.render(renderer);
        assertEquals(1, renderer.count("text:new"));
        assertEquals(0, renderer.count("text:old"));
    }

    /**
     * M-65 回归：渲染期间 supplier 回调增删元素不得抛 {@code ConcurrentModificationException}。
     *
     * <p>渲染使用元素快照；本次新增的元素（id=b）本帧不绘制，但下一帧应出现。
     */
    @Test
    void elementsMayBeAddedDuringRender() {
        RecordingRenderer renderer = new RecordingRenderer();
        HudManager hud = new HudManager();
        final boolean[] added = {false};
        TextElement mutator = new TextElement("a", () -> {
            if (!added[0]) {
                added[0] = true;
                hud.add(new TextElement("b", () -> "b-text", 14f, Color.WHITE, false));
            }
            return "a-text";
        }, 14f, Color.WHITE, false);
        hud.add(mutator);

        hud.render(renderer);   // 不得抛异常
        assertEquals(1, renderer.count("text:a-text"));
        assertEquals(0, renderer.count("text:b-text"), "mid-frame additions join the next frame");

        renderer.clear();
        hud.render(renderer);
        assertEquals(1, renderer.count("text:a-text"));
        assertEquals(1, renderer.count("text:b-text"));
    }
}
