package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.HashSet;
import java.util.Set;

/** ChestAura：搜索已加载容器，旋转并真实打开；支持 Stealer 联动与取消范围。 */
public final class ChestAuraModule extends GameModule {
    private final BooleanValue chests = add(new BooleanValue("Chests", true));
    private final BooleanValue furnaces = add(new BooleanValue("Furnaces", false));
    private final BooleanValue blastFurnaces = add(new BooleanValue("Blast Furnaces", false));
    private final BooleanValue smokers = add(new BooleanValue("Smokers", false));
    private final BooleanValue brewingStands = add(new BooleanValue("Brewing Stands", false));
    private final BooleanValue swing = add(new BooleanValue("Swing", true));
    private final BooleanValue pauseOnEat = add(new BooleanValue("Pause On Eat", true));
    private final BooleanValue requireStealer = add(new BooleanValue("Require Stealer", true));
    private final BooleanValue ignoreOpened = add(new BooleanValue("Ignore Other Opened", false));
    private final NumberValue range = add(new NumberValue("Range", 4.5, 0, 7, 0.1));
    private final NumberValue throughWallRange = add(new NumberValue("Through Wall Range", 4.5, 0, 7, 0.1));
    private final NumberValue cancelRange = add(new NumberValue("Cancel Range", 0, 0, 20, 0.1));
    private final NumberValue delay = add(new NumberValue("Delay", 400, 0, 1000, 10));
    private final NumberValue turnSpeed = add(new NumberValue("Turn Speed", 180, 0, 180, 1));
    private final BooleanValue disableLobby = add(new BooleanValue("Disable In Hypixel Lobby", false));
    private final Set<String> opened = new HashSet<String>();
    private long next;

    public ChestAuraModule() {
        super("Chest Aura", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(dev.nocturne.client.event.PacketEvent.class, event -> {
            if (event.direction == dev.nocturne.client.event.PacketEvent.Direction.RECEIVE
                    && bridge().isInstance(event.packet, ClassType.CLIENTBOUND_BLOCK_EVENT)
                    && !ignoreOpened.get()) {
                int[] pos = bridge().blockCoordinates(bridge().callMapped(event.packet,
                        ClassType.CLIENTBOUND_BLOCK_EVENT, "getPos"));
                if (pos != null) {
                    opened.add(pos[0] + ":" + pos[1] + ":" + pos[2]);
                }
            }
        });
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < next || pauseOnEat.get() && bridge().usingItem(bridge().player())
                || requireStealer.get() && !EpsilonSupport.enabled("Stealer")
                || disableLobby.get() && lobby()) {
            return;
        }
        if (cancelRange.get() > 0.0 && nearbyPlayer()) {
            actions().closeContainer();
            return;
        }
        Object best = null;
        int[] bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (Object blockEntity : BlockEntityScan.loadedBlockEntities(bridge())) {
            int[] pos = bridge().blockCoordinates(bridge().callMapped(blockEntity,
                    ClassType.BLOCK_ENTITY, "getBlockPos"));
            if (pos == null || !allowed(blockEntity, pos)) {
                continue;
            }
            String key = pos[0] + ":" + pos[1] + ":" + pos[2];
            if (opened.contains(key)) {
                continue;
            }
            double distance = Math.sqrt(Math.pow(pos[0] + 0.5 - state().x(), 2)
                    + Math.pow(pos[1] + 0.5 - state().y(), 2) + Math.pow(pos[2] + 0.5 - state().z(), 2));
            boolean visible = visible(pos);
            if (distance > range.get() || !visible && distance > throughWallRange.get()) {
                continue;
            }
            if (distance < bestDistance) {
                best = blockEntity;
                bestPos = pos;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return;
        }
        EpsilonSupport.aim(bridge(), state(), bestPos[0] + 0.5, bestPos[1] + 0.5,
                bestPos[2] + 0.5, turnSpeed.asFloat(), 5);
        if (actions().placeAt(bestPos[0], bestPos[1], bestPos[2], "up")) {
            if (swing.get()) {
                actions().swing(false, false);
            }
            opened.add(bestPos[0] + ":" + bestPos[1] + ":" + bestPos[2]);
            next = now + delay.asInt();
        }
    }

    private boolean allowed(Object blockEntity, int[] pos) {
        String id = bridge().blockDescription(bridge().blockStateAt(pos[0], pos[1], pos[2]));
        if (id.contains("blast_furnace")) {
            return blastFurnaces.get();
        }
        if (id.contains("smoker")) {
            return smokers.get();
        }
        if (id.contains("brewing_stand")) {
            return brewingStands.get();
        }
        if (bridge().isInstance(blockEntity, ClassType.FURNACE_BLOCK_ENTITY)) {
            return furnaces.get();
        }
        return chests.get() && (bridge().isInstance(blockEntity, ClassType.CHEST_BLOCK_ENTITY)
                || bridge().isInstance(blockEntity, ClassType.ENDER_CHEST_BLOCK_ENTITY)
                || bridge().isInstance(blockEntity, ClassType.BARREL_BLOCK_ENTITY)
                || bridge().isInstance(blockEntity, ClassType.SHULKER_BOX_BLOCK_ENTITY));
    }

    private boolean visible(int[] pos) {
        int[] hit = bridge().hitBlockPosition(actions().pick(range.get(), 1.0f));
        return hit != null && hit[0] == pos[0] && hit[1] == pos[1] && hit[2] == pos[2];
    }

    private boolean nearbyPlayer() {
        final boolean[] near = {false};
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (entity != bridge().player() && bridge().isInstance(entity, ClassType.PLAYER)
                    && bridge().distance(bridge().player(), entity) <= cancelRange.get()) {
                near[0] = true;
            }
        });
        return near[0];
    }

    private boolean lobby() {
        for (String line : bridge().scoreboardLines()) {
            if (line.contains("CLICK TO PLAY")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onStop() {
        opened.clear();
    }
}
