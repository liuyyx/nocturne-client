package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Auto Armor：附魔优先级、鞘翅策略、旧版装备、Strict 与移动/背包暂停。 */
public final class AutoArmorModule extends GameModule {
    private final ModeValue head = add(new ModeValue("Head", "Protection", "Blast", "Protection"));
    private final ModeValue body = add(new ModeValue("Body", "Protection", "Blast", "Protection"));
    private final ModeValue tights = add(new ModeValue("Tights", "Protection", "Blast", "Protection"));
    private final ModeValue feet = add(new ModeValue("Feet", "Protection", "Blast", "Protection"));
    private final ModeValue elytraPriority = add(new ModeValue("Elytra Priority", "Ignore", "None", "Always", "ElytraPlus", "Ignore"));
    private final NumberValue delay = add(new NumberValue("Delay", 5, 0, 10, 1));
    private final BooleanValue oldVersion = add(new BooleanValue("Old Version", false));
    private final BooleanValue pauseInventory = add(new BooleanValue("Pause Inventory", false));
    private final BooleanValue noMove = add(new BooleanValue("No Move", false));
    private final BooleanValue ignoreCurse = add(new BooleanValue("Ignore Curse", true));
    private final BooleanValue strict = add(new BooleanValue("Strict", false));
    private int armorType;
    private int ticks;

    public AutoArmorModule() {
        super("Auto Armor", Category.PLAYER);
    }

    @Override
    public void onTick() {
        if (pauseInventory.get() && bridge().containerOpen() || noMove.get() && state().moving()
                || ++ticks < delay.asInt()) {
            return;
        }
        ticks = 0;
        if (armorType >= 4) {
            armorType = 0;
            return;
        }
        int currentSlot = 39 - armorType;
        Object current = bridge().inventoryStack(currentSlot);
        int bestSlot = -1;
        double bestScore = score(current, armorType);
        for (int slot = 0; slot < 36; slot++) {
            Object candidate = bridge().inventoryStack(slot);
            if (InventorySupport.armorType(bridge(), candidate) != armorType
                    && !bridge().itemDescription(candidate).contains("elytra")) {
                continue;
            }
            double score = score(candidate, armorType);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) {
            if (strict.get()) {
                actions().sendPlayerCommand("STOP_SPRINTING");
            }
            if (oldVersion.get() && bestSlot < 9) {
                int original = state().selectedSlot();
                actions().selectSlot(bestSlot, false);
                actions().useItem();
                actions().selectSlot(original, false);
            } else {
                actions().clickSlot(bestSlot, 0, "quick_move");
            }
            actions().closeContainer();
        }
        armorType++;
    }

    private double score(Object stack, int type) {
        if (bridge().isEmptyStack(stack)) {
            return Double.NEGATIVE_INFINITY;
        }
        String id = bridge().itemDescription(stack);
        boolean elytra = id.contains("elytra");
        if (elytra) {
            if (elytraPriority.is("Ignore") || type != 1) return Double.NEGATIVE_INFINITY;
            if (elytraPriority.is("None")) return -1000.0;
            if (elytraPriority.is("Always")) return 100000.0;
            return 50000.0 + durability(stack);
        }
        if (ignoreCurse.get() && enchantments(stack).contains("binding_curse")) {
            return Double.NEGATIVE_INFINITY;
        }
        ModeValue priority = type == 0 ? head : type == 1 ? body : type == 2 ? tights : feet;
        double score = InventorySupport.armorScore(bridge(), stack) + durability(stack) * 0.01;
        if (priority.is("Protection") && enchantments(stack).contains("protection")) {
            score += 2000.0;
        }
        if (priority.is("Blast") && enchantments(stack).contains("blast_protection")) {
            score += 4000.0;
        }
        return score;
    }

    private double durability(Object stack) {
        int max = bridge().itemMaxDamage(stack);
        return max <= 0 ? 0.0 : (max - bridge().itemDamage(stack)) * 100.0 / max;
    }

    private String enchantments(Object stack) {
        return String.valueOf(bridge().callMapped(stack,
                dev.nocturne.client.mapping.ClassType.ITEM_STACK, "getEnchantments"))
                .toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    protected void onStart() {
        armorType = 0;
        ticks = 0;
    }
}
