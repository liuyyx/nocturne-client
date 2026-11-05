package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.Random;

/** 在玩家四周与头顶逐格放置方块，形成基础 Block-In 围栏。 */
public final class BlockInModule extends GameModule {
    private static final int[][] OFFSETS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", false));
    private final BooleanValue sneak = add(new BooleanValue("Sneak", false));
    private final ModeValue blockPriority = add(new ModeValue(
            "Block priority", "Lowest cost", "Lowest cost", "Hardest"));
    private final BoundsNumberValue placeDelay = add(new BoundsNumberValue(
            "Place delay", new NumberBounds(0, 0), 0, 250, 10));
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 12, 1, 25, 1));
    private final BooleanValue returnSlot = add(new BooleanValue("Return to last slot", true));
    private final BooleanValue useBlacklist = add(new BooleanValue("Use blacklist", true));
    private final TextValue blacklist = add(new TextValue("Blacklist",
            "tnt,spawner,command_block,enchanting_table,anvil,ice,glass_pane,iron_bars", 512));
    private final Random random = new Random();
    private int cursor;
    private int savedSlot;
    private long nextPlace;

    public BlockInModule() {
        super("BlockIn", Category.WORLD);
    }

    @Override
    protected void onStart() {
        cursor = 0;
        savedSlot = state().selectedSlot();
        listen(MovementInputEvent.class, event -> {
            if (sneak.get()) {
                event.sneak = true;
            }
        });
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextPlace) {
            return;
        }
        if (cursor >= OFFSETS.length) {
            if (returnSlot.get()) {
                actions.selectSlot(savedSlot, silentAim.get());
            }
            setEnabled(false);
            return;
        }
        int slot = bestBlock(bridge);
        if (slot < 0) {
            setEnabled(false);
            return;
        }
        int x = (int) Math.floor(state.x());
        int y = (int) Math.floor(state.y());
        int z = (int) Math.floor(state.z());
        int[] offset = OFFSETS[cursor++];
        actions.selectSlot(slot, silentAim.get());
        float yaw = RotationController.approach(state.yaw(), RotationController.yawTo(
                offset[0], offset[2]), aimSpeed.asFloat());
        float pitch = offset[1] > 0 ? -90.0f : 45.0f;
        if (silentAim.get()) {
            actions.sendRotation(yaw, pitch, state.onGround());
        } else {
            actions.setRotation(yaw, pitch);
        }
        PlacementSupport.placeNear(bridge, actions, x + offset[0], y + offset[1], z + offset[2]);
        NumberBounds delay = placeDelay.get();
        nextPlace = now + (long) (delay.min() + random.nextDouble() * (delay.max() - delay.min()));
    }

    @Override
    protected void onStop() {
        if (returnSlot.get() && savedSlot >= 0 && actions() != null) {
            actions().selectSlot(savedSlot, silentAim.get());
        }
    }

    private int bestBlock(GameBridge bridge) {
        int best = -1;
        double bestScore = -1.0;
        for (int slot = 0; slot <= 8; slot++) {
            Object stack = bridge.hotbarStack(slot);
            if (bridge.isEmptyStack(stack) || !bridge.itemMatches(stack, "blocks")
                    || useBlacklist.get() && bridge.itemMatches(stack, blacklist.get())) {
                continue;
            }
            String id = bridge.itemDescription(stack);
            double score = blockPriority.is("Hardest")
                    ? (id.contains("obsidian") ? 10.0 : id.contains("stone") ? 5.0 : 1.0)
                    : (id.contains("wool") ? 10.0 : id.contains("stone") ? 8.0
                    : id.contains("planks") ? 6.0 : 1.0);
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }
}
