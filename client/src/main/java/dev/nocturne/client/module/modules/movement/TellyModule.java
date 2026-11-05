package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Telly：原版 21 帧视角/移动曲线，右键边缘激活并执行真实放置。 */
public final class TellyModule extends GameModule implements WorldOverlay {
    private static final float[] YAW = {91.68f, 98.88f, 78.94f, 37.45f, 1.61f, -21.69f, -33.98f,
            -35.80f, -34.64f, -33.85f, -33.06f, -31.55f, -29.26f, -26.65f, -24.19f, -21.07f,
            -18.84f, -17.06f, -8.87f, 2.61f, 41.94f};
    private static final float[] PITCH = {64.31f, 59.95f, 60.57f, 61.46f, 60.64f, 58.89f, 56.91f,
            56.63f, 58.65f, 61.63f, 64.20f, 66.74f, 68.69f, 70.64f, 73.01f, 75.37f, 77.46f,
            78.56f, 78.90f, 77.22f, 72.25f};
    private static final float[] FORWARD = {1, 1, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, 1};
    private static final float[] STRAFE = {-1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1,
            -1, -1, -1, -1};
    private final BooleanValue autoSwap = add(new BooleanValue("Auto Swap", true));
    private final BooleanValue disableSafeWalk = add(new BooleanValue("Disable SafeWalk", true));
    private final BooleanValue showHitbox = add(new BooleanValue("Show Activation Hitbox", false));
    private final BooleanValue print = add(new BooleanValue("Print", false));
    private final BooleanValue debugLog = add(new BooleanValue("Debug Log", true));
    private final NumberValue activationTime = add(new NumberValue("Activation Time", 1.0, 0.1, 2.0, 0.1));
    private final NumberValue snapDegrees = add(new NumberValue("Snap Degrees", 46.5, 42, 48, 0.1));
    private boolean rightHeld;
    private boolean running;
    private long heldSince;
    private float baseYaw;
    private int phase;
    private int setup;
    private int original = -1;
    private int[] target;
    private boolean disabledSafeWalk;

    public TellyModule() {
        super("Telly", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) {
                rightHeld = event.pressed;
                heldSince = event.pressed ? System.currentTimeMillis() : 0L;
                if (!event.pressed) {
                    running = false;
                }
            }
        });
        listen(MovementInputEvent.class, event -> {
            if (!running || setup > 0) {
                return;
            }
            event.forward = FORWARD[phase];
            event.strafe = STRAFE[phase];
            event.jump = phase >= 1 && phase <= 19;
            event.sprint = phase <= 1;
        });
        Module safeWalk = client().modules().get("SafeWalk");
        if (disableSafeWalk.get() && safeWalk != null && safeWalk.isEnabled()) {
            safeWalk.setEnabled(false);
            disabledSafeWalk = true;
        }
    }

    @Override
    public void onTick() {
        if (!rightHeld || state().player() == null) {
            running = false;
            return;
        }
        if (!running && System.currentTimeMillis() - heldSince >= activationTime.get() * 1000.0) {
            running = true;
            baseYaw = Math.round((state().yaw() - snapDegrees.asFloat()) / 45.0f) * 45.0f
                    + snapDegrees.asFloat();
            phase = 0;
            setup = 6;
            if (print.get()) {
                bridge().localMessage("[Telly] Running");
            }
        }
        if (!running) {
            return;
        }
        if (setup > 0) {
            setup--;
        }
        client().rotations().request(baseYaw + YAW[phase], PITCH[phase], 20, 60_000_000L);
        if (autoSwap.get()) {
            int slot = EpsilonSupport.findHotbar(bridge(), "block");
            if (slot < 0) {
                slot = bestBlock();
            }
            if (slot >= 0) {
                if (original < 0) {
                    original = state().selectedSlot();
                }
                actions().selectSlot(slot, false);
            }
        }
        double radians = Math.toRadians(baseYaw);
        int x = (int) Math.floor(state().x() + Math.sin(radians) * 1.2);
        int y = (int) Math.floor(state().y()) - 1;
        int z = (int) Math.floor(state().z() - Math.cos(radians) * 1.2);
        target = new int[]{x, y, z};
        if (!PlacementSupport.placeNear(bridge(), actions(), x, y, z) && debugLog.get()) {
            System.out.println("[nocturne] Telly placement failed at phase " + phase);
        }
        actions().swing(false, false);
        phase = (phase + 1) % YAW.length;
    }

    private int bestBlock() {
        for (int slot = 0; slot < 9; slot++) {
            Object item = bridge().callMapped(bridge().hotbarStack(slot),
                    dev.nocturne.client.mapping.ClassType.ITEM_STACK, "getItem");
            if (bridge().isInstance(item, dev.nocturne.client.mapping.ClassType.BLOCK_ITEM)) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (showHitbox.get() && target != null) {
            WorldGeometry.block(draw, projection, target[0], target[1], target[2], 1.0f, 0xFFFFB7C5);
        }
    }

    @Override
    protected void onStop() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, false);
        }
        if (disabledSafeWalk) {
            Module safeWalk = client().modules().get("SafeWalk");
            if (safeWalk != null) {
                safeWalk.setEnabled(true);
            }
        }
        disabledSafeWalk = false;
        original = -1;
        running = false;
        rightHeld = false;
        target = null;
        client().rotations().release(20);
    }
}
