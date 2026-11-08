package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** Inv Manager：按槽位整理武器/方块/工具/投掷物，管理副手、保留数量与 Silent 关闭包。 */
public final class InventoryManagerModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Silent", "Inventory", "Silent"));
    private final BooleanValue pauseOnEat = add(new BooleanValue("Pause On Eat", true));
    private final BooleanValue pauseOnKillAura = add(new BooleanValue("Pause On KillAura", true));
    private final BooleanValue pauseOnScaffold = add(new BooleanValue("Pause On Scaffold", true));
    private final NumberValue minDelay = add(new NumberValue("Min Delay", 95, 0, 1000, 50));
    private final NumberValue maxDelay = add(new NumberValue("Max Delay", 135, 0, 1000, 50));
    private final ModeValue offhandItem = add(new ModeValue("Offhand Item", "None", "None", "GoldenApple", "Projectile", "FishingRod", "Block"));
    private final BooleanValue autoArmor = add(new BooleanValue("Auto Armor", true));
    private final BooleanValue switchSword = add(new BooleanValue("Switch Sword", true));
    private final NumberValue swordSlot = add(new NumberValue("Sword Slot", 1, 1, 9, 1));
    private final BooleanValue switchBlock = add(new BooleanValue("Switch Block", true));
    private final NumberValue blockSlot = add(new NumberValue("Block Slot", 2, 1, 9, 1));
    public final NumberValue maxBlockSize = add(new NumberValue("Max Block Size", 256, 64, 512, 64));
    private final BooleanValue switchPickaxe = add(new BooleanValue("Switch Pickaxe", true));
    private final NumberValue pickaxeSlot = add(new NumberValue("Pickaxe Slot", 3, 1, 9, 1));
    private final BooleanValue switchAxe = add(new BooleanValue("Switch Axe", true));
    private final NumberValue axeSlot = add(new NumberValue("Axe Slot", 4, 1, 9, 1));
    private final BooleanValue switchBow = add(new BooleanValue("Switch Bow or Crossbow", true));
    private final NumberValue bowSlot = add(new NumberValue("Bow Slot", 5, 1, 9, 1));
    private final ModeValue bowPriority = add(new ModeValue("Bow Priority", "Crossbow", "Crossbow", "PowerBow", "PunchBow"));
    public final NumberValue maxArrowSize = add(new NumberValue("Max Arrow Size", 256, 64, 512, 64));
    private final BooleanValue switchWaterBucket = add(new BooleanValue("Switch Water Bucket", true));
    private final NumberValue waterBucketSlot = add(new NumberValue("Water Bucket Slot", 6, 1, 9, 1));
    private final BooleanValue switchEnderPearl = add(new BooleanValue("Switch Ender Pearl", true));
    private final NumberValue enderPearlSlot = add(new NumberValue("Ender Pearl Slot", 7, 1, 9, 1));
    private final BooleanValue switchFireball = add(new BooleanValue("Switch Fireball", true));
    private final NumberValue fireballSlot = add(new NumberValue("Fireball Slot", 8, 1, 9, 1));
    private final BooleanValue switchGoldenApple = add(new BooleanValue("Switch Golden Apple", true));
    private final NumberValue goldenAppleSlot = add(new NumberValue("Golden Apple Slot", 9, 1, 9, 1));
    private final BooleanValue throwItems = add(new BooleanValue("Throw Items", true));
    public final NumberValue waterBucketCount = add(new NumberValue("Keep Water Buckets", 1, 0, 5, 1));
    public final NumberValue lavaBucketCount = add(new NumberValue("Keep Lava Buckets", 1, 0, 5, 1));
    public final BooleanValue keepProjectile = add(new BooleanValue("Keep Eggs & Snowballs", true));
    private final BooleanValue switchProjectile = add(new BooleanValue("Switch Eggs & Snowballs", false));
    private final NumberValue projectileSlot = add(new NumberValue("Eggs & Snowballs Slot", 9, 1, 9, 1));
    public final NumberValue maxProjectileSize = add(new NumberValue("Max Eggs & Snowballs Size", 64, 16, 256, 16));
    private final BooleanValue switchRod = add(new BooleanValue("Switch Rod", false));
    private final NumberValue rodSlot = add(new NumberValue("Rod Slot", 9, 1, 9, 1));
    private final List<Rule> rules = new ArrayList<Rule>();
    private long nextAction;
    private boolean armorWasEnabled;
    private boolean waitingSprint;

    public InventoryManagerModule() {
        super("Inv Manager", Category.PLAYER);
        rules.add(new Rule(switchSword, swordSlot, "sword"));
        rules.add(new Rule(switchBlock, blockSlot, "block"));
        rules.add(new Rule(switchPickaxe, pickaxeSlot, "pickaxe"));
        rules.add(new Rule(switchAxe, axeSlot, "axe"));
        rules.add(new Rule(switchBow, bowSlot, "bow,crossbow"));
        rules.add(new Rule(switchWaterBucket, waterBucketSlot, "water_bucket"));
        rules.add(new Rule(switchEnderPearl, enderPearlSlot, "ender_pearl"));
        rules.add(new Rule(switchFireball, fireballSlot, "fire_charge,fireball"));
        rules.add(new Rule(switchGoldenApple, goldenAppleSlot, "golden_apple"));
        rules.add(new Rule(switchProjectile, projectileSlot, "egg,snowball"));
        rules.add(new Rule(switchRod, rodSlot, "fishing_rod"));
    }

    @Override
    protected void onStart() {
        Module armor = EpsilonSupport.module("Auto Armor");
        armorWasEnabled = armor != null && armor.isEnabled();
        if (autoArmor.get() && armor != null) {
            armor.setEnabled(true);
        }
        nextAction = 0L;
        waitingSprint = false;
    }

    @Override
    public void onTick() {
        // 容器打开时槽位语义全变（0..containerSize-36 是箱子槽），一律不动也不关闭容器。
        if (bridge().containerOpen()) {
            return;
        }
        if (pauseOnEat.get() && bridge().usingItem(bridge().player())
                || pauseOnKillAura.get() && EpsilonSupport.enabled("Kill Aura")
                || pauseOnScaffold.get() && EpsilonSupport.enabled("Scaffold")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAction) {
            return;
        }
        if (waitingSprint) {
            actions().sendPlayerCommand("STOP_SPRINTING");
            waitingSprint = false;
            return;
        }
        if (!offhandItem.is("None") && prepareOffhand()) {
            actionDone(now);
            return;
        }
        for (Rule rule : rules) {
            if (!rule.enabled.get()) {
                continue;
            }
            int target = rule.slot.asInt() - 1;
            String wanted = rule == rules.get(4) ? bowPatterns() : rule.items;
            if (bridge().itemMatches(bridge().hotbarStack(target), wanted)) {
                continue;
            }
            int source = EpsilonSupport.findInventory(bridge(), wanted.split(","));
            if (source >= 0 && source != target) {
                actions().clickSlot(InventorySupport.menuSlot(source), target, "swap");
                actionDone(now);
                return;
            }
        }
        if (throwItems.get()) {
            boolean trimmed = trim("water_bucket", waterBucketCount.asInt(), now)
                    || trim("lava_bucket", lavaBucketCount.asInt(), now)
                    || trim("arrow", maxArrowSize.asInt(), now)
                    || trim("block", maxBlockSize.asInt(), now)
                    || trim("egg,snowball", maxProjectileSize.asInt(), now);
        }
    }

    private boolean prepareOffhand() {
        String wanted = offhandItem.is("GoldenApple") ? "golden_apple"
                : offhandItem.is("Projectile") ? "egg,snowball"
                : offhandItem.is("FishingRod") ? "fishing_rod" : "block";
        if (bridge().itemMatches(bridge().heldItem(bridge().player(), true), wanted)) {
            return false;
        }
        int source = EpsilonSupport.findInventory(bridge(), wanted.split(","));
        if (source < 0) {
            return false;
        }
        // 副手只能"捡起再放下"：SWAP 的按钮是快捷栏 0..8，点不到 45 号槽。
        actions().clickSlot(InventorySupport.menuSlot(source), 0, "pickup");
        actions().clickSlot(InventorySupport.menuSlot(InventorySupport.OFFHAND), 0, "pickup");
        return true;
    }

    private boolean trim(String items, int maximum, long now) {
        if (items.startsWith("egg") && !keepProjectile.get()) {
            return false;
        }
        int count = 0;
        int excessSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            Object stack = bridge().inventoryStack(slot);
            if (!bridge().itemMatches(stack, items)) {
                continue;
            }
            count += bridge().itemCount(stack);
            if (count > maximum) {
                excessSlot = slot;
            }
        }
        if (excessSlot < 0) {
            return false;
        }
        actions().clickSlot(InventorySupport.menuSlot(excessSlot), 1, "throw");
        actionDone(now);
        return true;
    }

    private String bowPatterns() {
        return bowPriority.is("Crossbow") ? "crossbow,bow" : "bow,crossbow";
    }

    private void actionDone(long now) {
        nextAction = now + EpsilonSupport.randomLong(minDelay.asInt(), maxDelay.asInt());
        // Silent 只改槽位：不再附带 STOP_SPRINTING 这类客户端可见副作用。
        waitingSprint = mode.is("Inventory");
    }

    @Override
    protected void onStop() {
        Module armor = EpsilonSupport.module("Auto Armor");
        if (autoArmor.get() && armor != null && !armorWasEnabled) {
            armor.setEnabled(false);
        }
        waitingSprint = false;
    }

    private static final class Rule {
        private final BooleanValue enabled;
        private final NumberValue slot;
        private final String items;

        private Rule(BooleanValue enabled, NumberValue slot, String items) {
            this.enabled = enabled;
            this.slot = slot;
            this.items = items;
        }
    }
}
