package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.FakeLegacyOptions;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.FakeOptions;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FullBrightModule} 的跨版本 gamma 写入测试（H-33 / M-132）。
 *
 * <p>{@code gamma} 的运行时类型随版本不同：1.8.9 是 {@code float}，1.21/26.x 是 {@code OptionInstance}
 * 对象。旧实现一律按 Float 写入，向对象型字段写 Float 会抛 {@code IllegalArgumentException} 被反射层
 * 吞掉、模块静默失效。这里分别用两种替身 Options 验证写入与还原路径。
 */
class FullBrightModuleTest {

    private ClassLoader savedTccl;

    @BeforeEach
    void setUp() {
        savedTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        Thread.currentThread().setContextClassLoader(savedTccl);
    }

    /** 现代版本：必须改写 OptionInstance 内部的数值，而不是向对象字段写 Float */
    @Test
    void writesModernGammaThroughTheOptionInstance() {
        FakeOptions options = new FakeOptions(0.5);
        FakeMinecraft.instance.options = options;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        FullBrightModule module = new FullBrightModule();
        module.setEnabled(true);
        assertEquals(10.0, options.gamma.value, 1e-9,
                "onEnable must push the configured brightness into the OptionInstance");

        module.onTick();
        assertEquals(10.0, options.gamma.value, 1e-9, "onTick re-applies after world loads");

        module.setEnabled(false);
        assertEquals(0.5, options.gamma.value, 1e-9,
                "onDisable must restore the pre-enable brightness, not hardcode 1.0");
    }

    /** 1.8.9：gamma 是 float 字段，走类型匹配的直接写入与还原 */
    @Test
    void writesLegacyFloatGammaAndRestoresTheOriginal() {
        FakeLegacyOptions options = new FakeLegacyOptions();
        options.gamma = 0.5f;
        FakeMinecraft.instance.options = options;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        FullBrightModule module = new FullBrightModule();
        module.setEnabled(true);
        assertEquals(10f, options.gamma, 1e-6f);

        module.onTick();
        assertEquals(10f, options.gamma, 1e-6f);

        module.setEnabled(false);
        assertEquals(0.5f, options.gamma, 1e-6f, "the player's own brightness must be restored");
    }
}
