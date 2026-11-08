package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块热键回归：只要有一个 {@link KeyEvent} 到达总线，绑定的模块就必须被切换。
 *
 * <p>1.8.9 的键盘事件源缺失曾让"所有模块热键失效"；事件由轮询回退
 * （{@code KeyPollListener}）补上，这条用例锁住"事件到达后确实会切换"这一半。
 */
class ModuleKeybindsTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
    }

    @Test
    void boundKeyTogglesModuleOnPress() {
        NocturneClient client = NocturneClient.boot(null);
        ProbeModule module = new ProbeModule();
        client.modules().register(module);
        module.bind().set(Integer.valueOf(0x42));
        ModuleKeybinds.install(client);

        client.events().post(new KeyEvent(0x42, true));
        assertTrue(module.isEnabled(), "按下的绑定键必须启用模块");

        client.events().post(new KeyEvent(0x42, false));
        assertTrue(module.isEnabled(), "松开不得反向切换（只在按下沿生效）");

        client.events().post(new KeyEvent(0x42, true));
        assertFalse(module.isEnabled());
    }

    @Test
    void otherKeysDoNothing() {
        NocturneClient client = NocturneClient.boot(null);
        ProbeModule module = new ProbeModule();
        client.modules().register(module);
        module.bind().set(Integer.valueOf(0x42));
        ModuleKeybinds.install(client);

        client.events().post(new KeyEvent(0x43, true));
        assertFalse(module.isEnabled());
    }

    /** 最小模块：只需要名字/分类与可切换的启停。 */
    private static final class ProbeModule extends Module {
        @Override
        public String name() {
            return "Keybind Probe";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }
    }
}
