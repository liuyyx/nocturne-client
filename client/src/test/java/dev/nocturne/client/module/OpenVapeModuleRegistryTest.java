package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** OpenVape 全量注册的构造、分类与无游戏环境启停验收。 */
class OpenVapeModuleRegistryTest {
    @BeforeEach
    void reset() {
        TestSupport.resetClient();
    }

    @AfterEach
    void clean() {
        TestSupport.resetClient();
    }

    @Test
    void all108OpenVapeModulesAndTwoNocturneExtrasAreRegistered() {
        NocturneClient client = NocturneClient.boot(null);
        assertEquals(110, client.modules().size());
        assertEquals(23, client.modules().byCategory(Category.COMBAT).size());
        assertEquals(9, client.modules().byCategory(Category.MOVEMENT).size());
        assertEquals(38, client.modules().byCategory(Category.RENDER).size());
        assertEquals(11, client.modules().byCategory(Category.WORLD).size());
        assertEquals(11, client.modules().byCategory(Category.INVENTORY).size());
        assertEquals(11, client.modules().byCategory(Category.PLAYER).size());
        assertEquals(7, client.modules().byCategory(Category.MISC).size());
    }

    @Test
    void everyModuleCanStartAndStopOutsideAGameWorld() {
        NocturneClient client = NocturneClient.boot(null);
        for (Module module : client.modules().all()) {
            if ("Panic".equals(module.name())) {
                continue;
            }
            module.setEnabled(true);
            module.setEnabled(false);
            assertFalse(module.isEnabled(), module.name());
        }
    }
}
