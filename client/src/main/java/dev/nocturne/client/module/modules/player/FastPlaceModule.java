package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 按配置压缩原版右键延迟；可只作用于方块或投掷物。 */
public final class FastPlaceModule extends Module {

    private static final String ALL = "All";
    private static final String BLOCKS = "Blocks";
    private static final String PROJECTILES = "Projectiles";

    private final ModeValue heldItemMode = add(new ModeValue(
            "Held Item", ALL, ALL, BLOCKS, PROJECTILES));
    private final NumberValue delay = add(new NumberValue("Delay", 1.0, 0.0, 4.0, 1.0));

    @Override
    public String name() {
        return "FastPlace";
    }

    @Override
    public Category category() {
        return Category.PLAYER;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object minescraft = bridge.minecraft();
        Object player = bridge.player();
        if (minescraft == null || player == null) {
            return;
        }
        Object main = bridge.callMapped(player, ClassType.LIVING_ENTITY, "getMainHandItem");
        Object off = bridge.callMapped(player, ClassType.LIVING_ENTITY, "getOffhandItem");
        if (!matches(bridge, main) && !matches(bridge, off)) {
            return;
        }
        Object current = bridge.readField(minescraft, ClassType.MINECRAFT, "rightClickDelay");
        if (current instanceof Number
                && ((Number) current).intValue() > delay.asInt()) {
            bridge.writeField(minescraft, ClassType.MINECRAFT, "rightClickDelay", delay.asInt());
        }
    }

    private boolean matches(GameBridge bridge, Object stack) {
        if (stack == null || heldItemMode.is(ALL)) {
            return stack != null;
        }
        Object item = bridge.callMapped(stack, ClassType.ITEM_STACK, "getItem");
        if (item == null) {
            return false;
        }
        if (heldItemMode.is(BLOCKS)) {
            return bridge.isInstance(item, ClassType.BLOCK_ITEM);
        }
        return bridge.isInstance(item, ClassType.SNOWBALL_ITEM)
                || bridge.isInstance(item, ClassType.EGG_ITEM);
    }

    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
