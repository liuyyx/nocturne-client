package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stealer：随机槽序 quick-move 有用物品，空箱按延迟自动关闭。 */
public final class ChestStealModule extends GameModule {
    private final NumberValue minDelay = add(new NumberValue("Min Delay", 110, 0, 1000, 50));
    private final NumberValue maxDelay = add(new NumberValue("Max Delay", 140, 0, 1000, 50));
    private final BooleanValue autoClose = add(new BooleanValue("Auto Close", true));
    private final NumberValue closeDelay = add(new NumberValue("Close Delay", 100, 0, 1000, 10));
    private final BooleanValue enderChest = add(new BooleanValue("Ender Chest", false));
    private final List<Integer> order = new ArrayList<Integer>();
    private long nextClick;
    private long emptySince;
    private String lastUseBlock = "";

    public ChestStealModule() {
        super("Stealer", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        order.clear();
        emptySince = 0L;
        nextClick = 0L;
        lastUseBlock = "";
        // 打开容器前记下准星方块：末影箱必须靠方块 id 认，中文客户端标题不含 "ender"。
        listen(UseItemEvent.class, event -> {
            if (event.post) {
                return;
            }
            String block = hitBlockDescription();
            if (!block.isEmpty()) {
                lastUseBlock = block;
            }
        });
    }

    @Override
    public void onTick() {
        if (!bridge().containerOpen()) {
            order.clear();
            emptySince = 0L;
            lastUseBlock = "";
            return;
        }
        if (!enderChest.get() && isEnderContainer(blockAtHit(), bridge().screenTitle(),
                bridge().currentScreen() == null ? "" : bridge().currentScreen().getClass().getName())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextClick) {
            return;
        }
        int size = Math.max(0, bridge().containerSize() - 36);
        if (order.size() != size) {
            order.clear();
            for (int slot = 0; slot < size; slot++) {
                order.add(slot);
            }
            Collections.shuffle(order, EpsilonSupport.RANDOM);
        }
        while (!order.isEmpty()) {
            int slot = order.remove(0);
            Object stack = bridge().containerStack(slot);
            if (useful(stack)) {
                actions().clickSlot(slot, 0, "quick_move");
                nextClick = now + EpsilonSupport.randomLong(minDelay.asInt(), maxDelay.asInt());
                emptySince = 0L;
                return;
            }
        }
        if (emptySince == 0L) {
            emptySince = now;
        }
        if (autoClose.get() && now - emptySince >= closeDelay.asInt()) {
            actions().closeContainer();
            emptySince = 0L;
        }
    }

    /** 末影箱判定：方块 id 最可靠；其次可读环境下的 EnderChestScreen 类名；英文标题仅作兜底。 */
    static boolean isEnderContainer(String blockDescription, String title, String screenClass) {
        if (blockDescription != null
                && blockDescription.toLowerCase(java.util.Locale.ROOT).contains("ender_chest")) {
            return true;
        }
        if (screenClass != null
                && screenClass.toLowerCase(java.util.Locale.ROOT).contains("enderchest")) {
            return true;
        }
        return title != null && title.toLowerCase(java.util.Locale.ROOT).contains("ender");
    }

    private String blockAtHit() {
        if (!lastUseBlock.isEmpty()) {
            return lastUseBlock;
        }
        return hitBlockDescription();
    }

    private String hitBlockDescription() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null) {
            return "";
        }
        int[] pos = bridge.hitBlockPosition(actions.pick(5.0, 1.0f));
        return pos == null ? "" : bridge.blockDescription(bridge.blockStateAt(pos[0], pos[1], pos[2]));
    }

    private boolean useful(Object stack) {
        if (bridge().isEmptyStack(stack)) {
            return false;
        }
        String id = bridge().itemDescription(stack);
        return !id.matches(".*(dirt|gravel|cobblestone|rotten_flesh|poisonous_potato).*");
    }

    public boolean working() {
        return isEnabled() && bridge().containerOpen();
    }
}
