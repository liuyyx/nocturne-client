package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** AutoBuild：右/中键在面前按火箭轮廓自动搭建，支持快速与完美模式。 */
public final class AutoBuildModule extends GameModule {
    private final ModeValue trigger = add(new ModeValue("Trigger", "RIGHT_CLICK", "RIGHT_CLICK", "MIDDLE_CLICK"));
    private final NumberValue rotateSpeed = add(new NumberValue("Rotate Speed", 99, 0, 99, 1));
    private final BooleanValue autoSwitch = add(new BooleanValue("Auto Switch", true));
    private final BooleanValue allowKillAura = add(new BooleanValue("Allow KillAura", false));
    private final BooleanValue allowScaffold = add(new BooleanValue("Allow Scaffold", false));
    private final BooleanValue speedBuild = add(new BooleanValue("Speed Build", false));
    private final BooleanValue throughWall = add(new BooleanValue("Through Wall", false));
    private final BooleanValue silentAim = add(new BooleanValue("Silent Aim", false));
    private final BooleanValue perfect = add(new BooleanValue("Perfect", false));
    private final BooleanValue debug = add(new BooleanValue("Debug", false));
    private final List<int[]> pending = new ArrayList<int[]>();
    private int original = -1;

    public AutoBuildModule() {
        super("Auto Build", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            int wanted = trigger.is("RIGHT_CLICK") ? 1 : 2;
            if (event.pressed && event.button == wanted) {
                begin();
            }
        });
    }

    private void begin() {
        pending.clear();
        double yaw = Math.toRadians(state().yaw());
        int baseX = (int) Math.floor(state().x() - Math.sin(yaw) * 3.0);
        int baseY = (int) Math.floor(state().y());
        int baseZ = (int) Math.floor(state().z() + Math.cos(yaw) * 3.0);
        int[][] body = {{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {0, 3, 0}, {1, 0, 0}, {-1, 0, 0},
                {0, 0, 1}, {0, 0, -1}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {0, 4, 0}, {0, 5, 0}};
        for (int[] offset : body) {
            pending.add(new int[]{baseX + offset[0], baseY + offset[1], baseZ + offset[2]});
        }
        original = state().selectedSlot();
    }

    @Override
    public void onTick() {
        if (pending.isEmpty() || !allowKillAura.get() && EpsilonSupport.enabled("KillAura")
                || !allowScaffold.get() && EpsilonSupport.enabled("Scaffold")) {
            return;
        }
        if (autoSwitch.get()) {
            int slot = blockSlot();
            if (slot < 0) {
                pending.clear();
                return;
            }
            actions().selectSlot(slot, silentAim.get());
        }
        int attempts = speedBuild.get() ? 4 : 1;
        while (attempts-- > 0 && !pending.isEmpty()) {
            int[] pos = pending.get(0);
            if (!throughWall.get() && !visible(pos)) {
                return;
            }
            EpsilonSupport.aim(bridge(), state(), pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                    rotateSpeed.asFloat() + 1.0f, silentAim.get() ? 8 : 4);
            if (perfect.get() && Math.abs(client().rotations().pitch(state().pitch())
                    - dev.nocturne.client.control.RotationController.pitchTo(1.0,
                    pos[1] + 0.5 - state().y() - 1.62)) > 5.0f) {
                return;
            }
            if (PlacementSupport.placeNear(bridge(), actions(), pos[0], pos[1], pos[2])) {
                pending.remove(0);
                if (debug.get()) {
                    System.out.println("[nocturne] AutoBuild remaining=" + pending.size());
                }
            } else {
                return;
            }
        }
        if (pending.isEmpty()) {
            restore();
        }
    }

    private boolean visible(int[] pos) {
        int[] hit = bridge().hitBlockPosition(actions().pick(6.0, 1.0f));
        return hit == null || Math.abs(hit[0] - pos[0]) <= 1 && Math.abs(hit[1] - pos[1]) <= 1
                && Math.abs(hit[2] - pos[2]) <= 1;
    }

    private int blockSlot() {
        for (int slot = 0; slot < 9; slot++) {
            Object item = bridge().callMapped(bridge().hotbarStack(slot),
                    dev.nocturne.client.mapping.ClassType.ITEM_STACK, "getItem");
            if (bridge().isInstance(item, dev.nocturne.client.mapping.ClassType.BLOCK_ITEM)) {
                return slot;
            }
        }
        return -1;
    }

    private void restore() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, silentAim.get());
        }
        original = -1;
    }

    @Override
    protected void onStop() {
        pending.clear();
        restore();
    }
}
