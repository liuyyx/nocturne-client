package dev.nocturne.agent;

import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.client.runtime.ScrollState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 原生界面出现后，HUD 让位不能把输入永久留在阻断状态。 */
class GuiDrawHookInputTest {
    public static final class FakeMinecraft {
        public static final FakeMinecraft INSTANCE = new FakeMinecraft();
        public Object screen;

        public static FakeMinecraft getInstance() {
            return INSTANCE;
        }
    }

    @Test
    void nativeScreenReleasesInputAndDiscardsPendingScroll() throws Exception {
        Field getter = GuiDrawHook.class.getDeclaredField("minecraftGetInstance");
        Field screen = GuiDrawHook.class.getDeclaredField("minecraftScreenField");
        Field resolved = GuiDrawHook.class.getDeclaredField("screenProbeResolved");
        getter.setAccessible(true);
        screen.setAccessible(true);
        resolved.setAccessible(true);
        Object oldGetter = getter.get(null);
        Object oldScreen = screen.get(null);
        boolean oldResolved = resolved.getBoolean(null);
        boolean oldBlocked = InputBlock.shouldBlock();
        double oldScroll = ScrollState.take();
        try {
            getter.set(null, FakeMinecraft.class.getMethod("getInstance"));
            screen.set(null, FakeMinecraft.class.getField("screen"));
            resolved.setBoolean(null, true);
            FakeMinecraft.INSTANCE.screen = new Object();
            InputBlock.setBlocked(true);
            ScrollState.accumulate(2.0);

            GuiDrawHook.onHudDraw(null);

            assertFalse(InputBlock.shouldBlock(), "原生界面必须能收到点击");
            assertEquals(0.0, ScrollState.take(), "原生界面期间的滚轮不能补发给自绘 GUI");
        } finally {
            FakeMinecraft.INSTANCE.screen = null;
            getter.set(null, oldGetter);
            screen.set(null, oldScreen);
            resolved.setBoolean(null, oldResolved);
            InputBlock.setBlocked(oldBlocked);
            ScrollState.reset();
            ScrollState.accumulate(oldScroll);
        }
    }
}
