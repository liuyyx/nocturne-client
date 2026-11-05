package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(client.modules().size() >= 110, "module count: " + client.modules().size());
        assertTrue(client.modules().byCategory(Category.COMBAT).size() >= 23, "combat=" + client.modules().byCategory(Category.COMBAT).size());
        assertTrue(client.modules().byCategory(Category.MOVEMENT).size() >= 9, "movement=" + client.modules().byCategory(Category.MOVEMENT).size());
        assertTrue(client.modules().byCategory(Category.RENDER).size() >= 38, "render=" + client.modules().byCategory(Category.RENDER).size());
        assertTrue(client.modules().byCategory(Category.WORLD).size() >= 1, "world=" + client.modules().byCategory(Category.WORLD).size());
        assertTrue(client.modules().byCategory(Category.INVENTORY).size() >= 1, "inventory=" + client.modules().byCategory(Category.INVENTORY).size());
        assertTrue(client.modules().byCategory(Category.PLAYER).size() >= 1, "player=" + client.modules().byCategory(Category.PLAYER).size());
        assertTrue(client.modules().byCategory(Category.MISC).size() >= 1, "misc=" + client.modules().byCategory(Category.MISC).size());
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
