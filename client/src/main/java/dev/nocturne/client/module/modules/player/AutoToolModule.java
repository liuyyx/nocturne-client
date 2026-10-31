package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 准星指向方块时自动切到快捷栏中挖掘速度最快的工具。
 *
 * <p>评分直接调用 {@code ItemStack.getDestroySpeed(BlockState)}，不按物品种类猜。
 * 武器评分、战斗中/使用中过滤尚未有可靠映射面，因此不提供这些开关，避免出现“拨了没反应”。
 */
public final class AutoToolModule extends Module {

    private final BooleanValue instantSwap = add(new BooleanValue("Instant swap", true));
    private final NumberValue swapToDelay = add(new NumberValue(
            "Swap to delay", 50.0, 0.0, 500.0, 50.0));
    private final BooleanValue swapBack = add(new BooleanValue("Swap back", false));
    private final NumberValue swapBackDelay = add(new NumberValue(
            "Swap back delay", 350.0, 50.0, 1000.0, 50.0));
    private final BooleanValue onlyWhileSneaking = add(new BooleanValue("Only while sneaking", false));

    private int originalSlot = -1;
    private long aimStartNanos;
    private long swappedAtNanos;
    private boolean swapped;

    @Override
    public String name() {
        return "AutoTool";
    }

    @Override
    public Category category() {
        return Category.PLAYER;
    }

    @Override
    protected void onDisable() {
        restoreOriginal();
        originalSlot = -1;
        aimStartNanos = 0L;
        swappedAtNanos = 0L;
        swapped = false;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object minecraft = bridge.minecraft();
        Object player = bridge.player();
        if (minecraft == null || player == null) {
            return;
        }
        Object level = bridge.readField(minecraft, ClassType.MINECRAFT, "level");
        Object hit = bridge.readField(minecraft, ClassType.MINECRAFT, "hitResult");
        boolean blockAim = hit != null && bridge.isInstance(hit, ClassType.BLOCK_HIT_RESULT);
        long now = System.nanoTime();
        if (blockAim && onlyWhileSneaking.get()
                && !Boolean.TRUE.equals(bridge.callMapped(player, ClassType.ENTITY, "isShiftKeyDown"))) {
            blockAim = false;
        }
        if (blockAim && level != null) {
            if (aimStartNanos == 0L) {
                aimStartNanos = now;
            }
            if (!instantSwap.get()
                    && now - aimStartNanos < swapToDelay.get().longValue() * 1_000_000L) {
                return;
            }
            selectBestTool(bridge, player, level, hit);
            return;
        }
        aimStartNanos = 0L;
        if (swapBack.get() && swapped
                && now - swappedAtNanos >= swapBackDelay.get().longValue() * 1_000_000L) {
            restoreOriginal();
        }
    }

    private void selectBestTool(GameBridge bridge, Object player, Object level, Object hit) {
        Object pos = bridge.callMapped(hit, ClassType.BLOCK_HIT_RESULT, "getBlockPos");
        if (pos == null) {
            return;
        }
        Object state = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "getBlockState", pos);
        Object inventory = bridge.readField(player, ClassType.PLAYER, "inventory");
        if (state == null || inventory == null) {
            return;
        }
        Object selectedValue = bridge.readField(inventory, ClassType.INVENTORY, "selected");
        if (!(selectedValue instanceof Number)) {
            return;
        }
        int selected = ((Number) selectedValue).intValue();
        double[] scores = new double[9];
        for (int slot = 0; slot < 9; slot++) {
            scores[slot] = score(bridge, inventory, slot, state);
        }
        int bestSlot = bestSlot(scores, selected);
        if (bestSlot == selected || scores[bestSlot] <= 0.0) {
            return;
        }
        if (originalSlot < 0) {
            originalSlot = selected;
        }
        if (bridge.writeField(inventory, ClassType.INVENTORY, "selected", bestSlot)) {
            swapped = true;
            swappedAtNanos = System.nanoTime();
        }
    }

    /** 返回严格高于当前槽的最高分槽；全为 0 或并列时保持当前槽。 */
    static int bestSlot(double[] scores, int current) {
        int best = current;
        for (int slot = 0; slot < scores.length; slot++) {
            if (scores[slot] > scores[best]) {
                best = slot;
            }
        }
        return best;
    }

    private static double score(GameBridge bridge, Object inventory, int slot, Object state) {
        Object stack = bridge.callMapped(inventory, ClassType.INVENTORY, "getItem", slot);
        if (stack == null) {
            return 0.0;
        }
        Object speed = bridge.callMapped(stack, ClassType.ITEM_STACK, "getDestroySpeed", state);
        return speed instanceof Number ? ((Number) speed).doubleValue() : 0.0;
    }

    private void restoreOriginal() {
        if (!swapped || originalSlot < 0) {
            return;
        }
        GameBridge bridge = bridge();
        Object player = bridge == null ? null : bridge.player();
        Object inventory = player == null ? null
                : bridge.readField(player, ClassType.PLAYER, "inventory");
        if (inventory != null) {
            bridge.writeField(inventory, ClassType.INVENTORY, "selected", originalSlot);
        }
        swapped = false;
        swappedAtNanos = 0L;
    }

    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
