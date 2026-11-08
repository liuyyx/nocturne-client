package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;

import java.util.ArrayDeque;
import java.util.Random;

/** 一次性使用热栏中的治疗药水或汤，结束后恢复原槽。 */
public final class ThrowpotModule extends GameModule {
    /** 原版喝汤需要 32 tick；提前切槽会让使用被中断。 */
    private static final int SOUP_TICKS = 32;
    /** 读取不到使用进度时的兜底 tick 数，避免永远等不到完成。 */
    private static final int SOUP_TIMEOUT_TICKS = 60;
    private final ModeValue type = add(new ModeValue("Type", "Both", "Both", "Pots", "Soup"));
    private final ModeValue mode = add(new ModeValue("Mode", "Dynamic", "Dynamic", "Single"));
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(80, 115), 0, 200, 1));
    private final ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
    private final Random random = new Random();
    private int savedSlot;
    private long nextUse;
    private int age;
    private boolean soupAwaiting;
    private boolean sawUsing;
    private int soupWaited;

    public ThrowpotModule() {
        super("Throwpot", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        queue.clear();
        savedSlot = state().selectedSlot();
        age = 0;
        soupAwaiting = false;
        sawUsing = false;
        soupWaited = 0;
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            String id = bridge.itemDescription(bridge.hotbarStack(slot));
            boolean soup = id.contains("soup") || id.contains("stew");
            if (!(soup || id.contains("splash_potion"))
                    || type.is("Pots") && soup || type.is("Soup") && !soup) {
                continue;
            }
            queue.add(slot);
            if (mode.is("Single")) {
                break;
            }
        }
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        if (actions == null) {
            return;
        }
        age++;
        GameBridge bridge = bridge();
        if (soupAwaiting) {
            int using = bridge == null ? -1 : bridge.usingTicks(bridge.player());
            soupWaited++;
            if (!soupDone(using, sawUsing, soupWaited)) {
                sawUsing = sawUsing || using > 0;
                return;
            }
            soupAwaiting = false;
        }
        long now = System.currentTimeMillis();
        if (now < nextUse) {
            return;
        }
        Integer slot = queue.poll();
        if (slot == null) {
            actions.selectSlot(savedSlot, false);
            if (closeOnEmpty(age)) {
                if (bridge != null) {
                    bridge.localMessage("Throwpot: 没有可用的药水或汤");
                }
                setEnabled(false);
            }
            return;
        }
        actions.selectSlot(slot, false);
        actions.useItem(false);
        if (isSoup(bridge, slot)) {
            soupAwaiting = true;
            sawUsing = false;
            soupWaited = 0;
            nextUse = 0L;
            return;
        }
        NumberBounds bounds = delay.get();
        nextUse = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
    }

    /** usingTicks>=32 或已观察到使用后归零才算喝完；读不到进度时用超时兜底。 */
    static boolean soupDone(int usingTicks, boolean sawUsing, int waitedTicks) {
        return usingTicks >= SOUP_TICKS || sawUsing && usingTicks <= 0
                || waitedTicks >= SOUP_TIMEOUT_TICKS;
    }

    /** 队列为空的第一 tick 不关闭，避免开局槽位信息尚未同步就退出。 */
    static boolean closeOnEmpty(int age) {
        return age > 1;
    }

    private static boolean isSoup(GameBridge bridge, int slot) {
        if (bridge == null) {
            return false;
        }
        String id = bridge.itemDescription(bridge.hotbarStack(slot));
        return id.contains("soup") || id.contains("stew");
    }

    @Override
    protected void onStop() {
        ActionApi actions = actions();
        if (actions != null && savedSlot >= 0) {
            actions.selectSlot(savedSlot, false);
        }
        queue.clear();
        soupAwaiting = false;
        sawUsing = false;
        soupWaited = 0;
    }
}
