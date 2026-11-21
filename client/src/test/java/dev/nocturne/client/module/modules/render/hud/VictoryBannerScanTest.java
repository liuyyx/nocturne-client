package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一次攻击后的目标观察不得退化成永久每 tick 全实体扫描。 */
class VictoryBannerScanTest {
    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
    }

    @Test
    void existingTargetIsNotRescannedEveryTick() throws Exception {
        NocturneClient client = NocturneClient.boot(null);
        VictoryBannerElementModule module = (VictoryBannerElementModule)
                client.modules().get("Victory Banner");
        Object world = new Object();
        Object target = new Object();
        set(module, "world", world);
        set(module, "target", target);
        set(module, "targetId", 7);

        // 没有新攻击时，即使上次观察成功，也不能再次扫描实体列表。
        module.onTick();
        module.onTick();

        assertEquals(-1, getInt(module, "targetId"), "一次观察后必须清除扫描触发状态");
        assertEquals(null, get(module, "target"), "目标引用不得为下一次无攻击 tick 保留");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int getInt(Object target, String name) throws Exception {
        return ((Integer) get(target, name)).intValue();
    }
}
