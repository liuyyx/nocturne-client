package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;

/**
 * 在原版快捷栏上叠加滑动选中框；原版选中框不会被隐藏。
 * 上游降级：没有界面矩阵钩子，故不提供 Inventory 缩放动画。
 * 位置按逻辑像素估算，资源包和不同 GUI 布局可能不对齐。
 */
public final class GameAnimationModule extends GameModule implements WorldOverlay {
    private static final long DURATION = 150_000_000L;
    private final BooleanValue hotbar = add(new BooleanValue("Hotbar", true));
    private Object lastPlayer;
    private int target = -1;
    private float start;
    private long started;
    private boolean warned;

    public GameAnimationModule() {
        super("Game Animation", Category.RENDER);
    }

    @Override
    protected void onStop() {
        lastPlayer = null;
        target = -1;
        warned = false;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        Object player = bridge == null ? null : bridge.player();
        if (player == null) {
            lastPlayer = null;
            target = -1;
            return;
        }
        Object inventory = bridge.readField(player, ClassType.PLAYER, "inventory");
        Object selected = bridge.callMapped(inventory, ClassType.INVENTORY, "getSelectedSlot");
        if (!(selected instanceof Number)) selected = bridge.readField(inventory, ClassType.INVENTORY, "selected");
        if (!(selected instanceof Number)) {
            target = -1;
            if (inventory != null && !warned) {
                warned = true;
                System.out.println("[nocturne] Game Animation: hotbar selection unavailable on this version (inventory mapping missing)");
            }
            return;
        }
        int slot = ((Number) selected).intValue();
        if (slot < 0 || slot > 8) { target = -1; return; }
        long now = System.nanoTime();
        if (player != lastPlayer || target < 0) {
            lastPlayer = player;
            target = slot;
            start = slot;
            started = now;
        } else if (target != slot) {
            start = position(now);
            target = slot;
            started = now;
        }
    }

    private float position(long now) {
        float t = Math.max(0f, Math.min(1f, (float) (now - started) / DURATION));
        float remaining = 1f - t;
        return start + (target - start) * (1f - remaining * remaining * remaining);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!hotbar.get() || target < 0 || draw.width() <= 0 || draw.height() <= 0) return;
        float x = (draw.width() - 180f) * .5f + position(System.nanoTime()) * 20f;
        float y = draw.height() - 22f;
        draw.rect(x, y, 20f, 20f, 0x40FFFFFF);
        draw.outline(x, y, 20f, 20f, 1.5f, 0xFFFFFFFF);
    }
}
