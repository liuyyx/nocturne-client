package dev.nocturne.client.runtime;

import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「世界已就绪」的判定。
 *
 * <p>回归：旧实现用 {@code bridge.isResolved()}，主菜单/死亡界面同样能解析出 Minecraft 类，
 * 于是模块闸门永远打开，onSuspend/onResume 从不触发。这里用替身「游戏主类」构造真实
 * {@link GameBridge}，验证只有 player 与 level 同时存在才算在世界里。
 */
class ModuleTickerWorldTest {

    private ClassLoader savedLoader;

    @BeforeEach
    void setUp() {
        // GameBridge 用上下文加载器解析替身「游戏类」；不设置会解析不到 FakeMinecraft。
        savedLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(savedLoader);
        FakeMinecraft.reset();
    }

    @Test
    void mainMenuAndDeathScreenAreNotWorldReady() {
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));
        assertTrue(bridge.resolve(), "fake minecraft class is resolvable");
        assertTrue(bridge.isResolved());
        assertFalse(ModuleTicker.worldReady(bridge), "player == null (main menu)");

        FakeMinecraft.getInstance().player = new Object();
        assertFalse(ModuleTicker.worldReady(bridge), "level == null (dying / switching)");

        FakeMinecraft.getInstance().level = new Object();
        assertTrue(ModuleTicker.worldReady(bridge), "player + level present");
    }

    @Test
    void nullBridgeIsNotWorldReady() {
        assertFalse(ModuleTicker.worldReady(null));
    }
}
