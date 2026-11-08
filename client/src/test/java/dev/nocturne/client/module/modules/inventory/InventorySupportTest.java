package dev.nocturne.client.module.modules.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 背包下标 → 容器菜单槽的换算。
 *
 * <p>回归：AutoTotem/AutoArmor/InvManager 直接把 {@code findInventory} 返回的背包下标传给
 * {@code clickSlot}，快捷栏物品会被点到 0..8 号（合成/护甲槽），开箱时更会点到箱子槽。
 */
class InventorySupportTest {

    /** 快捷栏第 5 格（下标 5）在菜单末尾：36+5=41。 */
    @Test
    void hotbarMapsToMenuTail() {
        assertEquals(41, InventorySupport.menuSlot(5));
        assertEquals(36, InventorySupport.menuSlot(0));
        assertEquals(44, InventorySupport.menuSlot(8));
    }

    /** 护甲：背包 36=靴、39=头 → 菜单 8..5。 */
    @Test
    void armorSlotsMapInReverseOrder() {
        assertEquals(8, InventorySupport.menuSlot(36));
        assertEquals(7, InventorySupport.menuSlot(37));
        assertEquals(6, InventorySupport.menuSlot(38));
        assertEquals(5, InventorySupport.menuSlot(39));
    }

    /** 主背包下标与菜单槽相同；副手 40 是菜单 45。 */
    @Test
    void mainInventoryAndOffhand() {
        assertEquals(9, InventorySupport.menuSlot(9));
        assertEquals(35, InventorySupport.menuSlot(35));
        assertEquals(45, InventorySupport.menuSlot(InventorySupport.OFFHAND));
    }
}
