package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.game.GameBridge;

/** 背包模块共享的槽位换算与轻量物品分类。 */
public final class InventorySupport {
    private InventorySupport() {
    }

    public static int menuSlot(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot <= 8) {
            return 36 + inventorySlot;
        }
        if (inventorySlot >= 36 && inventorySlot <= 39) {
            return 8 - (inventorySlot - 36);
        }
        return inventorySlot;
    }

    public static boolean healing(GameBridge bridge, Object stack) {
        String id = bridge.itemDescription(stack);
        return id.contains("splash_potion") || id.contains("lingering_potion")
                || id.contains("soup") || id.contains("stew");
    }

    public static boolean harmful(GameBridge bridge, Object stack) {
        String text = (bridge.itemDescription(stack) + " " + bridge.itemDisplayName(stack))
                .toLowerCase(java.util.Locale.ROOT);
        return text.contains("harming") || text.contains("weakness")
                || text.contains("poison") || text.contains("slowness");
    }

    public static String harmfulKind(GameBridge bridge, Object stack) {
        String text = (bridge.itemDescription(stack) + " " + bridge.itemDisplayName(stack))
                .toLowerCase(java.util.Locale.ROOT);
        for (String kind : new String[]{"harming", "weakness", "poison", "slowness"}) {
            if (text.contains(kind)) {
                return kind;
            }
        }
        return "";
    }

    /** 0=头盔、1=胸甲、2=护腿、3=靴子；非盔甲为 -1。 */
    public static int armorType(GameBridge bridge, Object stack) {
        String id = bridge.itemDescription(stack);
        if (id.contains("helmet") || id.contains("cap") || id.contains("turtle_helmet")) {
            return 0;
        }
        if (id.contains("chestplate") || id.contains("tunic")) {
            return 1;
        }
        if (id.contains("leggings") || id.contains("pants")) {
            return 2;
        }
        if (id.contains("boots")) {
            return 3;
        }
        return -1;
    }

    public static int armorScore(GameBridge bridge, Object stack) {
        String id = bridge.itemDescription(stack);
        int score = id.contains("netherite") ? 6 : id.contains("diamond") ? 5
                : id.contains("iron") ? 4 : id.contains("chain") ? 3
                : id.contains("gold") ? 2 : id.contains("leather") ? 1 : 0;
        int maxDamage = bridge.itemMaxDamage(stack);
        if (maxDamage > 0) {
            score = score * 1000 + (maxDamage - bridge.itemDamage(stack)) * 100 / maxDamage;
        }
        return score;
    }

    public static String material(GameBridge bridge, Object stack) {
        String id = bridge.itemDescription(stack);
        for (String material : new String[]{"netherite", "diamond", "iron", "chain", "gold", "leather"}) {
            if (id.contains(material)) {
                return material;
            }
        }
        return "";
    }
}
