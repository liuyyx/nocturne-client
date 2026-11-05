package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** GreatWall：GreatWall/Sy/Clutch 三模式手势激活连搭。 */
public final class GreatWallModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "GreatWall", "GreatWall", "Sy", "Clutch"));
    private final BooleanValue extraClicks = add(new BooleanValue("Extra Clicks", false));
    private final BooleanValue autoSwitch = add(new BooleanValue("Auto Switch", true));
    private final BooleanValue showCount = add(new BooleanValue("Show Count", true));
    private final BooleanValue hideOnLook = add(new BooleanValue("Hide On Look", true));
    private final NumberValue activationTime = add(new NumberValue("Activation Time", 0.5, 0, 1, 0.05));
    private final BooleanValue debug = add(new BooleanValue("Debug", false));
    private boolean sneaking;
    private boolean rightHeld;
    private long gestureSince;
    private boolean active;
    private int count;
    private int original = -1;

    public GreatWallModule() {
        super("GreatWall", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            sneaking = event.sneak;
            if (active) {
                event.forward = -1.0f;
                event.strafe = mode.is("Sy") ? -1.0f : 0.0f;
                event.jump = mode.is("GreatWall");
            }
        });
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) {
                rightHeld = event.pressed;
                gestureSince = event.pressed ? System.currentTimeMillis() : 0L;
                if (!event.pressed) {
                    active = false;
                }
            }
        });
    }

    @Override
    public void onTick() {
        if (!rightHeld || !sneaking) {
            active = false;
            return;
        }
        if (!active && System.currentTimeMillis() - gestureSince >= activationTime.get() * 1000.0) {
            active = true;
            count = 0;
        }
        if (!active) {
            return;
        }
        if (autoSwitch.get()) {
            int slot = blockSlot();
            if (slot < 0) {
                active = false;
                return;
            }
            if (original < 0) {
                original = state().selectedSlot();
            }
            actions().selectSlot(slot, false);
        }
        double yaw = Math.toRadians(state().yaw());
        double distance = mode.is("Clutch") ? 1.6 : mode.is("Sy") ? 0.8 : 1.2;
        int x = (int) Math.floor(state().x() + Math.sin(yaw) * distance);
        int y = (int) Math.floor(state().y()) - 1;
        int z = (int) Math.floor(state().z() - Math.cos(yaw) * distance);
        client().rotations().request(state().yaw() + 180.0f, 78.0f, 20, 150_000_000L);
        if (PlacementSupport.placeNear(bridge(), actions(), x, y, z)) {
            count++;
            if (debug.get()) {
                System.out.println("[nocturne] GreatWall placed " + count);
            }
        }
        if (mode.is("Clutch") && extraClicks.get()) {
            PlacementSupport.placeNear(bridge(), actions(), x, y + 1, z);
        }
        actions().swing(false, false);
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

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (showCount.get() && active && !(hideOnLook.get() && bridge().currentScreen() != null)) {
            String text = String.valueOf(count);
            draw.text(text, draw.width() * 0.5f - draw.textWidth(text) * 0.5f,
                    draw.height() * 0.65f, 0xFFFFB7C5);
        }
    }

    @Override
    protected void onStop() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, false);
        }
        original = -1;
        active = false;
        rightHeld = false;
    }
}
