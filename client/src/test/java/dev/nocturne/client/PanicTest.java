package dev.nocturne.client;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自销毁（panic）的行为测试。
 *
 * <p>panic 的验收点有三条：所有模块停用、模块驱动闸门关闭、客户端标记为已销毁。清空 bootstrap 层
 * 分发器那一步在测试环境里没有 bootstrap 类（{@code NocturneRuntime} 会静默跳过），所以它由真机
 * 验收覆盖——测试这里钉住的是"即使分发器不可用也必须把模块与闸门关干净"，不能只做一半。
 */
class PanicTest {

    /** 每个用例前后重置进程级单例，避免全局状态在用例间互相污染。 */
    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
    }

    /** 用例结束后同样清空单例。 */
    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    /** panic 必须把每个模块都关掉，并关掉驱动闸门；重复调用幂等。 */
    @Test
    void panicDisablesEveryModuleAndStopsDriving() {
        NocturneClient client = NocturneClient.boot(null);
        Module watermark = client.modules().get("Watermark");
        assertNotNull(watermark, "precondition: Watermark module is registered");
        watermark.setEnabled(true);
        assertTrue(watermark.isEnabled(), "precondition: a module is on");

        client.panic();

        assertTrue(client.isPanicked(), "client must report itself as panicked");
        assertFalse(client.modules().isActive(), "module driving gate must be off after panic");
        for (Module module : client.modules().all()) {
            assertFalse(module.isEnabled(), module.name() + " must be off after panic");
        }

        // 幂等：再调一次不得抛异常，也不得改变结论。
        client.panic();
        assertTrue(client.isPanicked());
    }

    /** Panic 模块必须注册在 MISC 分类下——GUI 里要能点到它。 */
    @Test
    void panicModuleIsRegisteredUnderMisc() {
        NocturneClient client = NocturneClient.boot(null);
        Module panic = client.modules().get("Panic");
        assertNotNull(panic, "Panic module must be registered");
        assertEquals(Category.MISC, panic.category());
    }
}
