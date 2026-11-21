package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.value.BooleanValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 死亡界面关闭激活闸门时，Free Camera 的 Toggle On Death 仍必须执行。 */
class FreeCameraDeathTest {
    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
        GameplayHook.markWired("cameraAligned");
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @Test
    void deathOptionDisablesModuleWhileRegistryGateIsClosed() throws Exception {
        NocturneClient client = NocturneClient.boot(null);
        FakeMinecraft.instance.player = new DeadPlayer();
        FakeMinecraft.instance.level = new Object();
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
        FreeCameraModule module = (FreeCameraModule) client.modules().get("Free Camera");
        assertTrue(module != null, "客户端默认注册 Free Camera");
        set(module, "death", true);
        module.setEnabled(true);

        ModuleRegistry registry = client.modules();
        registry.setActive(false);
        registry.tick();

        assertFalse(module.isEnabled(), "死亡 tick 必须执行 Toggle On Death，而不是被闸门跳过");
    }

    public static final class DeadPlayer {
        public float getHealth() {
            return 0.0f;
        }
    }

    private static void set(FreeCameraModule module, String name, boolean value) throws Exception {
        Field field = FreeCameraModule.class.getDeclaredField(name);
        field.setAccessible(true);
        ((BooleanValue) field.get(module)).set(value);
    }
}
