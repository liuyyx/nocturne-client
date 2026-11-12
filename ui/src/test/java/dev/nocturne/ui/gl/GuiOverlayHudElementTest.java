package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.ui.FakeBackend;
import dev.nocturne.ui.FakeInput;
import dev.nocturne.ui.RecordingRenderer.DrawCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用现有无头绘制后端走完整帧入口，验证复合 HUD 不依赖 ClickGUI 打开。 */
class GuiOverlayHudElementTest {
    @BeforeEach
    void isolate() throws Exception {
        resetGlobals();
    }

    @AfterEach
    void cleanUp() throws Exception {
        resetGlobals();
    }

    @Test
    void closedGuiDrawsElementsAndRemovalReturnsToIdle() {
        FakeBackend backend = new FakeBackend(320, 240);
        GuiOverlay overlay = overlay(backend);
        RecordingElement element = new RecordingElement("only", HudAnchor.TOP_LEFT, 0);
        overlay.addElement(element);
        assertTrue(overlay.hasElement("only"));
        assertTrue(GuiOverlay.needsFrame(false, false, false, true));

        overlay.onFrame();

        assertFalse(overlay.isOpen());
        assertEquals(1, backend.beginCalls);
        assertEquals(1, backend.endCalls);
        assertEquals(Arrays.asList("only"), texts(backend));
        DrawCall text = backend.renderer.ofKind("text").get(0);
        assertEquals(6f, text.x, 0.001f);
        assertEquals(6f, text.y, 0.001f);
        overlay.removeElement("only");
        assertFalse(overlay.hasElement("only"));
        overlay.onFrame();
        assertEquals(1, backend.beginCalls, "最后一个元素移除后不应继续开启空帧");
        assertEquals(1, backend.endCalls);
    }

    @Test
    void orderSortIsStableAndEveryElementIsMeasuredOncePerFrame() {
        FakeBackend backend = new FakeBackend(320, 240);
        GuiOverlay overlay = overlay(backend);
        RecordingElement late = new RecordingElement("late", HudAnchor.TOP_LEFT, 30);
        RecordingElement first = new RecordingElement("first", HudAnchor.TOP_LEFT, 10);
        RecordingElement second = new RecordingElement("second", HudAnchor.TOP_LEFT, 10);
        RecordingElement early = new RecordingElement("early", HudAnchor.TOP_LEFT, -1);
        for (RecordingElement element : new RecordingElement[]{late, first, second, early}) {
            overlay.addElement(element);
        }

        overlay.onFrame();

        assertEquals(Arrays.asList("early", "first", "second", "late"), texts(backend));
        List<DrawCall> calls = backend.renderer.ofKind("text");
        for (int i = 0; i < calls.size(); i++) {
            assertEquals(6f, calls.get(i).x, 0.001f);
            assertEquals(6f + i * 16f, calls.get(i).y, 0.001f);
        }
        backend.renderer.clear();
        overlay.onFrame();
        assertEquals(Arrays.asList("early", "first", "second", "late"), texts(backend));
        for (RecordingElement element : new RecordingElement[]{late, first, second, early}) {
            assertEquals(2, element.widthCalls, element.id());
            assertEquals(2, element.heightCalls, element.id());
            assertEquals(2, element.drawCalls, element.id());
        }
    }

    @Test
    void sameIdReplacementDrawsOnlyTheReplacementWithoutChangingTieOrder() {
        FakeBackend backend = new FakeBackend(320, 240);
        GuiOverlay overlay = overlay(backend);
        RecordingElement original = new RecordingElement("same", HudAnchor.TOP_LEFT, 0);
        RecordingElement neighbor = new RecordingElement("neighbor", HudAnchor.TOP_LEFT, 0);
        RecordingElement replacement = new RecordingElement("same", HudAnchor.TOP_LEFT, 0);
        replacement.text = "replacement";
        overlay.addElement(original);
        overlay.addElement(neighbor);
        overlay.addElement(replacement);
        overlay.addElement(replacement);

        overlay.onFrame();

        assertEquals(Arrays.asList("replacement", "neighbor"), texts(backend));
        assertEquals(0, original.drawCalls);
        assertEquals(1, replacement.drawCalls);
        overlay.removeElement("same");
        backend.renderer.clear();
        overlay.onFrame();
        assertEquals(Arrays.asList("neighbor"), texts(backend));
        assertEquals(6f, backend.renderer.ofKind("text").get(0).y, 0.001f);
    }

    @Test
    void bottomAnchorStacksTowardTheCenterAndTracksViewportResize() {
        FakeBackend backend = new FakeBackend(320, 240);
        GuiOverlay overlay = overlay(backend);
        RecordingElement first = new RecordingElement("first", HudAnchor.BOTTOM_RIGHT, 0);
        RecordingElement second = new RecordingElement("second", HudAnchor.BOTTOM_RIGHT, 1);
        overlay.addElement(second);
        overlay.addElement(first);
        overlay.onFrame();

        List<DrawCall> calls = backend.renderer.ofKind("text");
        assertEquals(Arrays.asList("first", "second"), texts(backend));
        assertEquals(274f, calls.get(0).x, 0.001f);
        assertEquals(222f, calls.get(0).y, 0.001f);
        assertEquals(206f, calls.get(1).y, 0.001f);
        backend.width = 200;
        backend.height = 100;
        backend.renderer.clear();
        overlay.onFrame();
        calls = backend.renderer.ofKind("text");
        assertEquals(154f, calls.get(0).x, 0.001f);
        assertEquals(82f, calls.get(0).y, 0.001f);
        assertEquals(66f, calls.get(1).y, 0.001f);
    }

    @Test
    void widthHeightAndDrawFailuresDoNotBlockOtherElementsOrFrameCleanup() {
        FakeBackend backend = new FakeBackend(320, 240);
        GuiOverlay overlay = overlay(backend);
        RecordingElement widthFailure = new RecordingElement("width-failure", HudAnchor.TOP_LEFT, 0);
        RecordingElement heightFailure = new RecordingElement("height-failure", HudAnchor.TOP_LEFT, 1);
        RecordingElement drawFailure = new RecordingElement("draw-failure", HudAnchor.TOP_LEFT, 2);
        RecordingElement healthy = new RecordingElement("healthy", HudAnchor.TOP_LEFT, 3);
        widthFailure.failure = 1;
        heightFailure.failure = 2;
        drawFailure.failure = 3;
        for (RecordingElement element : new RecordingElement[]{
                widthFailure, heightFailure, drawFailure, healthy}) {
            overlay.addElement(element);
        }

        overlay.onFrame();
        overlay.onFrame();

        assertEquals(Arrays.asList("healthy", "healthy"), texts(backend));
        assertEquals(2, healthy.widthCalls);
        assertEquals(2, healthy.heightCalls);
        assertEquals(2, healthy.drawCalls);
        assertEquals(2, drawFailure.drawCalls);
        assertEquals(0, widthFailure.drawCalls);
        assertEquals(0, heightFailure.drawCalls);
        assertEquals(2, backend.beginCalls);
        assertEquals(2, backend.endCalls);
    }

    private static GuiOverlay overlay(FakeBackend backend) {
        // 布局测试使用无纹理分支，以文字调用记录观察元素位置；图集路径由字体测试与真机验收覆盖。
        backend.renderer.textures = false;
        return new GuiOverlay(new ModuleRegistry(), backend, new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
    }

    private static List<String> texts(FakeBackend backend) {
        List<String> result = new ArrayList<String>();
        for (DrawCall call : backend.renderer.ofKind("text")) {
            result.add(call.text);
        }
        return result;
    }

    private static void resetGlobals() throws Exception {
        NocturneClient client = NocturneClient.get();
        if (client != null && client.moduleConfig() != null) {
            client.moduleConfig().close();
        }
        Field instance = NocturneClient.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        InputBlock.setBlocked(false);
        for (int key : new int[]{344, 340, 260, 256}) {
            InputBlock.recordKey(key, 0);
            InputBlock.consumePressed(key, Long.MAX_VALUE);
        }
    }

    private static final class RecordingElement implements HudElement {
        private final String id;
        private final HudAnchor anchor;
        private final int order;
        String text;
        int widthCalls;
        int heightCalls;
        int drawCalls;
        int failure;

        RecordingElement(String id, HudAnchor anchor, int order) {
            this.id = id;
            this.anchor = anchor;
            this.order = order;
            this.text = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public HudAnchor anchor() {
            return anchor;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public float width(OverlayDraw draw) {
            widthCalls++;
            if (failure == 1) {
                throw new AssertionError("预期的宽度测量失败");
            }
            return 40f;
        }

        @Override
        public float height(OverlayDraw draw) {
            heightCalls++;
            if (failure == 2) {
                throw new AssertionError("预期的高度测量失败");
            }
            return 12f;
        }

        @Override
        public void draw(OverlayDraw draw, float x, float y) {
            drawCalls++;
            if (failure == 3) {
                throw new AssertionError("预期的元素绘制失败");
            }
            draw.text(text, x, y, 10f, 0xFFFFFFFF);
        }
    }
}
