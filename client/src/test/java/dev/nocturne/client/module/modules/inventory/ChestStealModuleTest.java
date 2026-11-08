package dev.nocturne.client.module.modules.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stealer 的末影箱判定不能依赖本地化标题。 */
class ChestStealModuleTest {

    @Test
    void blockIdIdentifiesEnderChestOnChineseClients() {
        assertTrue(ChestStealModule.isEnderContainer("block.minecraft.ender_chest", "末影箱", ""));
    }

    @Test
    void screenClassIsAcceptedWhenReadable() {
        assertTrue(ChestStealModule.isEnderContainer("", "末影箱",
                "net.minecraft.client.gui.screens.inventory.EnderChestScreen"));
    }

    @Test
    void englishTitleRemainsAFallback() {
        assertTrue(ChestStealModule.isEnderContainer("", "Ender Chest", ""));
    }

    @Test
    void regularChestWithChineseTitleIsNotEnder() {
        assertFalse(ChestStealModule.isEnderContainer("block.minecraft.chest", "箱子", "ChestScreen"));
    }
}
