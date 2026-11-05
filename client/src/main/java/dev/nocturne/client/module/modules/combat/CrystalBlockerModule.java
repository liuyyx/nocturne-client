package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** CrystalBlocker：在来袭水晶与本地玩家之间放置实体方块，阻断爆炸视线。 */
public final class CrystalBlockerModule extends GameModule implements WorldOverlay {
    private final NumberValue range = add(new NumberValue("Range", 4, 1, 6, 0.1));
    private final ModeValue rotate = add(new ModeValue("Rotate", "Silent", "None", "Normal", "Silent"));
    private final ModeValue switchMode = add(new ModeValue("Switch", "Visible", "Visible", "Silent"));
    private final NumberValue delay = add(new NumberValue("Delay", 2, 0, 20, 1));
    private final NumberValue swapBackDelay = add(new NumberValue("Swap Back Delay", 0, 0, 20, 1));
    private final NumberValue rotationSpeed = add(new NumberValue("Rotation Speed", 180, 10, 180, 10));
    private int cooldown;
    private int restore;
    private int original = -1;
    private int[] last;

    public CrystalBlockerModule() {
        super("CrystalBlocker", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (original >= 0 && --restore <= 0) {
            actions().selectSlot(original, switchMode.is("Silent"));
            original = -1;
        }
        if (cooldown-- > 0) {
            return;
        }
        final Object[] nearest = {null};
        final double[] distance = {range.get()};
        dev.nocturne.client.module.modules.render.RenderSupport.forEachEntity(bridge(), entity -> {
            if (bridge().isInstance(entity, dev.nocturne.client.mapping.ClassType.END_CRYSTAL)) {
                double current = bridge().distance(bridge().player(), entity);
                if (current < distance[0]) {
                    nearest[0] = entity;
                    distance[0] = current;
                }
            }
        });
        if (nearest[0] == null) {
            return;
        }
        double[] pos = bridge().entityPosition(nearest[0]);
        if (pos == null) {
            return;
        }
        int x = (int) Math.floor((pos[0] + state().x()) * 0.5);
        int y = (int) Math.floor((pos[1] + state().y() + 1.0) * 0.5);
        int z = (int) Math.floor((pos[2] + state().z()) * 0.5);
        int slot = EpsilonSupport.findHotbar(bridge(), "obsidian", "block");
        if (slot < 0) {
            return;
        }
        original = state().selectedSlot();
        actions().selectSlot(slot, switchMode.is("Silent"));
        if (!rotate.is("None")) {
            EpsilonSupport.aim(bridge(), state(), x + 0.5, y + 0.5, z + 0.5,
                    rotationSpeed.asFloat(), 5);
        }
        if (PlacementSupport.placeNear(bridge(), actions(), x, y, z)) {
            last = new int[]{x, y, z};
            cooldown = delay.asInt();
            restore = swapBackDelay.asInt() + 1;
        } else {
            actions().selectSlot(original, switchMode.is("Silent"));
            original = -1;
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (last != null) {
            WorldGeometry.block(draw, projection, last[0], last[1], last[2], 1.0f, 0xFFB4FF8C);
        }
    }
}
