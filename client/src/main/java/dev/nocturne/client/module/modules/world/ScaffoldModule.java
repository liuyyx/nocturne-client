package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 在玩家前方脚下自动垫方块。
 *
 * <p>这是上游 Scaffold 的“可运行子集”：选一个方块物品、找到空中落点下方的支持方块、
 * 把朝向请求交给 {@link RotationController}，再通过动作层对新版本/旧版统一的右键入口放置。
 * 不做 GodBridge/TellyBridge 的移动接管，也不伪造“已放置成功”——动作层返回的只是游戏调用
 * 是否发生，服务端是否接受由游戏自己决定。
 */
public final class ScaffoldModule extends Module {

    private static final int ROTATION_PRIORITY = 20;

    private final BooleanValue rotate = add(new BooleanValue("Rotate", true));
    private final NumberValue delay =
            add(new NumberValue("Delay", 100.0, 0.0, 500.0, 10.0));

    private long nextPlaceNanos;

    @Override
    public String name() {
        return "Scaffold";
    }

    @Override
    public Category category() {
        return Category.WORLD;
    }

    @Override
    protected void onEnable() {
        nextPlaceNanos = 0L;
    }

    @Override
    protected void onDisable() {
        nextPlaceNanos = 0L;
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.rotations().release(ROTATION_PRIORITY);
        }
    }

    @Override
    public void onTick() {
        NocturneClient client = NocturneClient.get();
        ActionApi actions = client == null ? null : client.actions();
        GameBridge bridge = client == null ? null : client.gameBridge();
        if (actions == null || bridge == null || !actions.isAvailable("place")) {
            return;
        }
        long now = System.nanoTime();
        if (nextPlaceNanos != 0L && now < nextPlaceNanos) {
            return;
        }
        PlayerState state = actions.readState();
        if (!state.present()) {
            return;
        }
        if (!ensureBlockSelected(bridge)) {
            return;
        }
        double radians = Math.toRadians(state.yaw());
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        int targetX = floor(state.x() + forwardX * 1.2);
        int targetY = floor(state.y()) - 1;
        int targetZ = floor(state.z() + forwardZ * 1.2);
        if (!bridge.isAir(bridge.blockStateAt(targetX, targetY, targetZ))) {
            return;
        }
        int supportY = targetY - 1;
        if (bridge.isAir(bridge.blockStateAt(targetX, supportY, targetZ))) {
            return;
        }
        if (rotate.get()) {
            aimAt(bridge, client.rotations(), state, targetX, supportY, targetZ);
        }
        if (actions.placeAt(targetX, supportY, targetZ, "up")) {
            nextPlaceNanos = now + Math.max(0L, (long) (delay.get() * 1_000_000.0));
        }
    }

    /** 把准星请求到支持方块上表面中心；实际放置使用独立命中向量，不依赖本帧鼠标位置。 */
    private void aimAt(GameBridge bridge, RotationController rotations, PlayerState state,
                       int x, int supportY, int z) {
        double dx = x + 0.5 - state.x();
        double dz = z + 0.5 - state.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = supportY + 1.0 - (state.y() + 1.62);
        rotations.request(RotationController.yawTo(dx, dz),
                RotationController.pitchTo(horizontal, dy),
                ROTATION_PRIORITY, 200_000_000L);
    }

    /**
     * 保证快捷栏当前槽是方块物品；当前槽已是方块时不动，避免每 tick 来回切槽。
     *
     * @return 找到并选中方块时 true
     */
    private boolean ensureBlockSelected(GameBridge bridge) {
        Object player = bridge.player();
        if (player == null) {
            return false;
        }
        Object inventory = bridge.readField(player, ClassType.PLAYER, "inventory");
        if (inventory == null) {
            return false;
        }
        Object selectedValue = bridge.readField(inventory, ClassType.INVENTORY, "selected");
        int selected = selectedValue instanceof Number ? ((Number) selectedValue).intValue() : -1;
        if (selected >= 0 && selected <= 8 && isBlock(bridge, inventory, selected)) {
            return true;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (!isBlock(bridge, inventory, slot)) {
                continue;
            }
            return bridge.writeField(inventory, ClassType.INVENTORY, "selected", slot);
        }
        return false;
    }

    private static boolean isBlock(GameBridge bridge, Object inventory, int slot) {
        Object stack = bridge.callMapped(inventory, ClassType.INVENTORY, "getItem", slot);
        if (stack == null) {
            return false;
        }
        Object item = bridge.callMapped(stack, ClassType.ITEM_STACK, "getItem");
        return item != null && bridge.isInstance(item, ClassType.BLOCK_ITEM);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
