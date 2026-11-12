package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.ui.FakeBackend;
import dev.nocturne.ui.FakeInput;
import dev.nocturne.ui.render.Renderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fabric mod 没有 bootstrap KeySink 时，后续按键仍必须能开关界面。 */
class GuiOverlayInputTest {
    @AfterEach
    void clearInputBlock() {
        InputBlock.setBlocked(false);
        InputBlock.consumePressed(344, Long.MAX_VALUE);
        InputBlock.consumePressed(340, Long.MAX_VALUE);
        InputBlock.consumePressed(256, Long.MAX_VALUE);
        resetClient();
    }

    @Test
    void recordedToggleStillOpensAfterBootstrapLookupFails() {
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        // 第一帧在没有 KeySink 的环境中尝试解析；此时没有按键。
        overlay.onFrame();
        assertFalse(overlay.isOpen());

        // GLFW 轮询仍为 false，仅靠 mod 的输入钩子记录这次按键。
        InputBlock.recordKey(344, 1);
        overlay.onFrame();
        assertTrue(overlay.isOpen(), "bootstrap 不可用后，本地按键事件仍应打开界面");
    }

    @Test
    void recordedLeftShiftNeverOpensGui() {
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.onFrame();

        InputBlock.recordKey(340, 1); // GLFW_KEY_LEFT_SHIFT
        overlay.onFrame();
        assertFalse(overlay.isOpen(), "左 Shift 不得触发右 Shift 快捷键");

        InputBlock.recordKey(344, 1); // GLFW_KEY_RIGHT_SHIFT
        overlay.onFrame();
        assertTrue(overlay.isOpen(), "右 Shift 仍能正常打开界面");
    }

    @Test
    void recordedEscapeDismissesSearchWithoutPolling() {
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.setOpen(true);
        assertTrue(overlay.gui().keyPressed(KeyEvent.VK_F, 2));
        assertTrue(overlay.gui().keyPressed(KeyEvent.VK_BACK_SPACE, 0), "空搜索框应取得键盘焦点");

        // 只记录事件，不改变 FakeInput 的轮询状态。
        InputBlock.recordKey(256, 1);
        overlay.onFrame();
        assertTrue(overlay.isOpen(), "Esc 应先关闭搜索，而不是关闭整个界面");
        assertFalse(overlay.gui().keyPressed(KeyEvent.VK_BACK_SPACE, 0), "搜索关闭后不应继续接收按键");
    }

    @Test
    void hudLinesDrawWhileClickGuiIsClosed() {
        FakeBackend backend = new FakeBackend(320, 240);
        backend.renderer.textures = false;
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                backend, new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.add("test", () -> "FPS 60");

        overlay.onFrame();

        assertEquals(1, backend.beginCalls);
        assertEquals(1, backend.endCalls);
        assertEquals(1, backend.renderer.ofKind("text").size());
        assertTrue(backend.renderer.ofKind("text").get(0).text.endsWith("60"));
        overlay.remove("test");
        overlay.onFrame();
        assertEquals(1, backend.beginCalls, "空 HUD 不应再开启多余绘制帧");
    }

    @Test
    void nativeScreenClickIsSuppressedUntilRelease() throws Exception {
        FakeInput input = new FakeInput();
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), input, GuiOverlay.KEY_RIGHT_SHIFT);
        RecordingGui recording = new RecordingGui();
        Field gui = GuiOverlay.class.getDeclaredField("gui");
        gui.setAccessible(true);
        gui.set(overlay, recording);
        overlay.setOpen(true);
        Field handoff = GuiOverlay.class.getDeclaredField("nativeMouseHandoff");
        handoff.setAccessible(true);
        handoff.setBoolean(overlay, true);

        input.buttons.add(0);
        overlay.onFrame();
        input.x = 20;
        overlay.onFrame();
        assertEquals(0, recording.clicks, "原生返回按钮的按下不能变成 GUI 新点击");
        assertEquals(0, recording.drags, "按住期间的移动也不能穿透成拖拽");

        input.buttons.clear();
        overlay.onFrame();
        assertEquals(0, recording.releases, "不能补发没有对应 GUI 按下的释放");

        input.buttons.add(0);
        overlay.onFrame();
        assertEquals(1, recording.clicks, "松开后的全新点击必须恢复");
    }

    @Test
    void scaleKeysAreLeftToFocusedTextInput() throws Exception {
        FakeInput input = new FakeInput();
        FakeBackend backend = new FakeBackend(0, 0);
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                backend, input, GuiOverlay.KEY_RIGHT_SHIFT);
        RecordingGui recording = new RecordingGui();
        Field gui = GuiOverlay.class.getDeclaredField("gui");
        gui.setAccessible(true);
        gui.set(overlay, recording);
        overlay.setOpen(true);

        // 文本输入占用键盘时 = 是输入内容。
        recording.blockScaleKeys = true;
        input.keys.add(61); // '='
        overlay.onFrame();
        assertEquals(0, backend.scaleOverrideCalls, "输入框聚焦时 = 不得改缩放");

        // 松开输入后按键仍按着：只更新按下沿，不能在交还焦点时补一档。
        recording.blockScaleKeys = false;
        overlay.onFrame();
        assertEquals(0, backend.scaleOverrideCalls, "一直按住的键不能补一档");

        input.keys.remove(61);
        overlay.onFrame();
        input.keys.add(61);
        overlay.onFrame();
        assertEquals(1, backend.scaleOverrideCalls, "新的按下沿才应缩放");
        assertEquals(2, backend.scaleValue);
    }

    /**
     * 原生 Screen（聊天/背包/暂停）打开时 HUD 不能被输入让步逻辑一起砍掉：
     * 早退只交接鼠标状态，绘制仍要走 drawFrame。
     */
    @Test
    void hudStillDrawsWhileNativeScreenIsOpen() {
        NocturneClient client = NocturneClient.boot(null);
        ScreenHost host = new ScreenHost();
        host.screen = new Object();
        ScreenHost.instance = host;
        client.setGameBridge(new GameBridge(null, new HostMapping()));

        FakeBackend backend = new FakeBackend(320, 240);
        backend.renderer.textures = false;
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(), backend,
                new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.add("test", () -> "FPS 60");

        overlay.onFrame();

        assertFalse(overlay.isOpen(), "原生界面期间自绘 GUI 必须关闭");
        assertEquals(1, backend.beginCalls, "原生界面期间仍必须开启绘制帧");
        assertTrue(backend.renderer.ofKind("text").size() > 0, "HUD 行必须画出来");
        resetClient();
    }

    /** 清理进程级单例，避免默认模块污染后续用例。 */
    private static void resetClient() {
        try {
            Field instance = NocturneClient.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Throwable ignored) {
            // 反射不可用时只能依赖下一个用例自行 boot
        }
        ScreenHost.instance = null;
    }

    /** 替身 Minecraft：只提供一个非 static 的 Screen 字段。 */
    public static final class ScreenHost {
        public static ScreenHost instance;

        public Object screen;
        public Object gui;

        public static ScreenHost getInstance() {
            return instance;
        }
    }

    /** 只把 MINECRAFT 指向 ScreenHost 的映射。 */
    private static final class HostMapping implements Mapping {
        @Override
        public String describe() {
            return "host";
        }

        @Override
        public String className(ClassType type) {
            return type == ClassType.MINECRAFT ? ScreenHost.class.getName() : type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }

    /** 只记录 OverlayGui 收到的事件，避免把点击坐标和实际模块布局耦合进交接测试。 */
    private static final class RecordingGui implements OverlayGui {
        private boolean open;
        private int clicks;
        private int drags;
        private int releases;
        /** 模拟"搜索框/绑定行占用键盘"。 */
        private boolean blockScaleKeys;

        @Override
        public boolean blocksScaleKeys() {
            return blockScaleKeys;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void setOpen(boolean open) {
            this.open = open;
        }

        @Override
        public void toggle() {
            open = !open;
        }

        @Override
        public void setViewport(int width, int height) {
        }

        @Override
        public void update(long nowMs, double mouseX, double mouseY) {
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            clicks++;
            return true;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            releases++;
            return true;
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            drags++;
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int modifiers) {
            return true;
        }

        @Override
        public boolean charTyped(char c) {
            return true;
        }

        @Override
        public void cancelInteractions() {
        }

        @Override
        public void render(Renderer renderer) {
        }
    }
}
