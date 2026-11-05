package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** AutoWeapon：攻击前按武器类型、耐久和目标护盾选择最佳快捷栏武器。 */
public final class AutoWeaponModule extends GameModule {
    private final BooleanValue pauseOnEat = add(new BooleanValue("Pause On Eat", true));
    private final ModeValue mode = add(new ModeValue("Mode", "Simple", "Simple", "Smart"));
    private final NumberValue targetSlot = add(new NumberValue("Target Slot", 1, 1, 9, 1));
    private final BooleanValue swapBack = add(new BooleanValue("Swap Back", true));
    private final NumberValue swapBackDelay = add(new NumberValue("Swap Back Delay", 200, 0, 500, 10));
    private final BooleanValue durabilitySaver = add(new BooleanValue("Smart Durability Saver", false));
    private final BooleanValue shieldBreaker = add(new BooleanValue("Smart Shield Breaker", true));
    private final BooleanValue swordSwapping = add(new BooleanValue("Sword Swapping", true));
    private final BooleanValue maceSwapping = add(new BooleanValue("Mace Swapping", true));
    private final BooleanValue otherSwapping = add(new BooleanValue("Other Swapping", true));
    private final BooleanValue fireAspect = add(new BooleanValue("Fire Aspect", true));
    private final BooleanValue looting = add(new BooleanValue("Looting", true));
    private final BooleanValue sharpness = add(new BooleanValue("Sharpness", true));
    private final BooleanValue smite = add(new BooleanValue("Smite", true));
    private final BooleanValue arthropods = add(new BooleanValue("Bane of Arthropods", true));
    private final BooleanValue sweeping = add(new BooleanValue("Sweeping Edge", true));
    private final BooleanValue regularMace = add(new BooleanValue("Regular Mace", true));
    private final BooleanValue density = add(new BooleanValue("Density", true));
    private final BooleanValue breach = add(new BooleanValue("Breach", true));
    private final BooleanValue windBurst = add(new BooleanValue("Wind Burst", true));
    private final BooleanValue impaling = add(new BooleanValue("Impaling", true));
    private final BooleanValue onlyWeapon = add(new BooleanValue("Only On Weapon", false));
    private final BooleanValue sword = add(new BooleanValue("Sword", true));
    private final BooleanValue axe = add(new BooleanValue("Axe", true));
    private final BooleanValue pickaxe = add(new BooleanValue("Pickaxe", true));
    private final BooleanValue shovel = add(new BooleanValue("Shovel", true));
    private final BooleanValue hoe = add(new BooleanValue("Hoe", true));
    private final BooleanValue mace = add(new BooleanValue("Mace", true));
    private final BooleanValue trident = add(new BooleanValue("Trident", true));
    private int original = -1;
    private long restoreAt;

    public AutoWeaponModule() {
        super("AutoWeapon", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (event.post || pauseOnEat.get() && bridge().usingItem(bridge().player())
                    || onlyWeapon.get() && score(bridge().hotbarStack(state().selectedSlot()),
                    event.target) == Double.NEGATIVE_INFINITY) {
                return;
            }
            int slot = mode.is("Simple") ? targetSlot.asInt() - 1 : bestSlot(event.target);
            if (slot < 0 || slot == state().selectedSlot()) {
                return;
            }
            original = state().selectedSlot();
            actions().selectSlot(slot, true);
            restoreAt = System.currentTimeMillis() + swapBackDelay.asInt();
        });
    }

    private int bestSlot(Object target) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            Object stack = bridge().hotbarStack(slot);
            double score = score(stack, target);
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private double score(Object stack, Object target) {
        String id = bridge().itemDescription(stack);
        boolean allowed = id.contains("sword") && sword.get() && swordSwapping.get()
                || id.contains("axe") && axe.get() || id.contains("pickaxe") && pickaxe.get()
                || id.contains("shovel") && shovel.get() || id.contains("hoe") && hoe.get()
                || id.contains("mace") && mace.get() && maceSwapping.get()
                || id.contains("trident") && trident.get() && otherSwapping.get();
        if (!allowed) {
            return Double.NEGATIVE_INFINITY;
        }
        double score = id.contains("netherite") ? 6 : id.contains("diamond") ? 5
                : id.contains("iron") ? 4 : id.contains("stone") ? 3 : id.contains("gold") ? 2 : 1;
        if (shieldBreaker.get() && bridge().usingItem(target) && id.contains("axe")) {
            score += 100.0;
        }
        if (durabilitySaver.get() && bridge().itemMaxDamage(stack) > 0) {
            score += (bridge().itemMaxDamage(stack) - bridge().itemDamage(stack))
                    / (double) bridge().itemMaxDamage(stack);
        }
        if (id.contains("sword")) {
            score += enchanted(stack, "fire_aspect", fireAspect.get())
                    + enchanted(stack, "looting", looting.get())
                    + enchanted(stack, "sharpness", sharpness.get())
                    + enchanted(stack, "smite", smite.get())
                    + enchanted(stack, "bane_of_arthropods", arthropods.get())
                    + enchanted(stack, "sweeping_edge", sweeping.get());
        }
        if (id.contains("mace")) {
            score += enchanted(stack, "density", density.get())
                    + enchanted(stack, "breach", breach.get())
                    + enchanted(stack, "wind_burst", windBurst.get());
            if (!regularMace.get() && score < 6.0) {
                return Double.NEGATIVE_INFINITY;
            }
        }
        if (id.contains("trident")) {
            score += enchanted(stack, "impaling", impaling.get());
        }
        return score;
    }

    private double enchanted(Object stack, String name, boolean enabled) {
        if (!enabled) {
            return 0.0;
        }
        Object enchantments = bridge().callMapped(stack,
                dev.nocturne.client.mapping.ClassType.ITEM_STACK, "getEnchantments");
        return String.valueOf(enchantments).toLowerCase(java.util.Locale.ROOT).contains(name) ? 2.0 : 0.0;
    }

    @Override
    public void onTick() {
        if (original >= 0 && System.currentTimeMillis() >= restoreAt) {
            restore();
        }
    }

    private void restore() {
        if (swapBack.get() && original >= 0 && actions() != null) {
            actions().selectSlot(original, true);
        }
        original = -1;
    }

    @Override
    protected void onStop() {
        restore();
    }
}
