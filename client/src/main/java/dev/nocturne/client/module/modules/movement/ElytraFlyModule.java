package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.inventory.InventorySupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** ElytraFly：Control 与 Pitch40 两种飞行控制，含装备/烟花/起飞与耐久恢复。 */
public final class ElytraFlyModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Control", "Control", "Pitch40"));
    private final ModeValue swapMode = add(new ModeValue("Swap Mode", "InvSwitch", "Silent", "InvSwitch"));
    private final BooleanValue armored = add(new BooleanValue("Armored", false));
    private final BooleanValue unbreaking = add(new BooleanValue("Unbreaking", true));
    private final NumberValue unbreakingDelay = add(new NumberValue("Unbreaking Delay", 800, 100, 2000, 50));
    private final BooleanValue noSprint = add(new BooleanValue("No Sprint", true));
    private final BooleanValue useFireworks = add(new BooleanValue("Use Fireworks", true));
    private final NumberValue boostDelay = add(new NumberValue("Boost Delay", 20, 2, 50, 1));
    private final NumberValue lowerBounds = add(new NumberValue("Pitch40 Lower Bounds", 180, -128, 1024, 1));
    private final NumberValue rotateUp = add(new NumberValue("Pitch40 Rotate Speed Up", 5.45, 1, 20, 0.05));
    private final NumberValue rotateDown = add(new NumberValue("Pitch40 Rotate Speed Down", 0.90, 0.5, 2, 0.05));
    private final NumberValue packetDelay = add(new NumberValue("Pitch40 Packet Delay", 3, 1, 20, 1));
    private final BooleanValue autoTakeoff = add(new BooleanValue("Pitch40 Auto Takeoff", true));
    private final NumberValue takeoffTarget = add(new NumberValue("Pitch40 Takeoff Target Height", 300, 32, 1024, 1));
    private final BooleanValue autoFirework = add(new BooleanValue("Pitch40 Auto Firework", true));
    private final NumberValue fireworkCooldown = add(new NumberValue("Pitch40 Firework Cooldown", 10, 0, 100, 1));
    private boolean activeControl;
    private boolean ascending;
    private int boostTicks;
    private int packetTicks;
    private long lastUnbreaking;
    private int original = -1;
    private int restoreTicks;

    public ElytraFlyModule() {
        super("Elytra Fly", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, this::input);
        listen(MouseButtonEvent.class, event -> {
            if (event.pressed && event.button == 1 && mode.is("Control")) {
                activeControl = false;
            }
        });
    }

    private void input(MovementInputEvent event) {
        if (mode.is("Control") && noSprint.get() && armored.get()) {
            event.sprint = false;
            actions().setSprinting(false);
        }
        if (autoTakeoff.get() && mode.is("Pitch40") && !fallFlying() && !bridge().inWater(bridge().player())) {
            event.jump = true;
        }
        if (!activeControl) {
            return;
        }
        float yaw = state().yaw();
        if (mode.is("Control")) {
            if (event.forward < 0.0f) {
                yaw += 180.0f;
            }
            if (event.strafe != 0.0f) {
                yaw += event.strafe > 0.0f ? 90.0f : -90.0f;
            }
            float pitch = event.jump && event.sneak ? -3.0f : event.jump ? -45.0f : event.sneak ? 45.0f : -1.9f;
            client().rotations().request(yaw, pitch, 7, 150_000_000L);
        } else {
            float pitch = ascending ? -54.77f : 37.72f;
            float speed = ascending ? rotateUp.asFloat() : rotateDown.asFloat();
            client().rotations().request(yaw,
                    dev.nocturne.client.control.RotationController.approach(state().pitch(), pitch, speed),
                    7, 150_000_000L);
        }
    }

    @Override
    public void onTick() {
        if (original >= 0 && --restoreTicks <= 0) {
            actions().selectSlot(original, swapMode.is("Silent"));
            original = -1;
        }
        if (EpsilonSupport.enabled("ElytraCombat")) {
            activeControl = false;
            return;
        }
        if (!hasElytra()) {
            activeControl = false;
            return;
        }
        if (autoTakeoff.get() && !fallFlying()) {
            if (state().onGround()) {
                actions().setJumping(true);
            } else {
                actions().sendPlayerCommand("START_FALL_FLYING");
            }
        }
        if (fallFlying()) {
            activeControl = true;
        }
        if (mode.is("Pitch40")) {
            if (state().y() <= lowerBounds.get()) {
                ascending = true;
            }
            if (state().y() >= takeoffTarget.get()) {
                ascending = false;
            }
            if (++packetTicks >= packetDelay.asInt()) {
                packetTicks = 0;
                actions().sendRotation(state().yaw(), ascending ? -54.77f : 37.72f, state().onGround());
            }
        }
        if ((useFireworks.get() && mode.is("Control") || autoFirework.get() && mode.is("Pitch40"))
                && ++boostTicks >= boostDelay.asInt() + fireworkCooldown.asInt()) {
            boostTicks = 0;
            useRocket();
        }
        if (unbreaking.get() && System.currentTimeMillis() - lastUnbreaking >= unbreakingDelay.asInt()) {
            lastUnbreaking = System.currentTimeMillis();
            actions().sendPlayerCommand("START_FALL_FLYING");
        }
    }

    private void useRocket() {
        int slot = EpsilonSupport.findHotbar(bridge(), "firework_rocket");
        if (slot < 0 && swapMode.is("InvSwitch")) {
            int inventory = EpsilonSupport.findInventory(bridge(), "firework_rocket");
            if (inventory >= 9) {
                slot = 8;
                actions().clickSlot(InventorySupport.menuSlot(inventory), slot, "swap");
            }
        }
        if (slot < 0) {
            return;
        }
        original = state().selectedSlot();
        actions().selectSlot(slot, swapMode.is("Silent"));
        actions().useItem(true);
        restoreTicks = 1;
    }

    private boolean hasElytra() {
        if (bridge().itemDescription(bridge().equipmentStack(bridge().player(), 2)).contains("elytra")) {
            return true;
        }
        if (!armored.get()) {
            return EpsilonSupport.findInventory(bridge(), "elytra") >= 0;
        }
        int slot = EpsilonSupport.findHotbar(bridge(), "elytra");
        if (slot >= 0) {
            int originalSlot = state().selectedSlot();
            actions().selectSlot(slot, swapMode.is("Silent"));
            actions().useItem();
            actions().selectSlot(originalSlot, swapMode.is("Silent"));
        }
        return slot >= 0;
    }

    private boolean fallFlying() {
        return Boolean.TRUE.equals(bridge().callMapped(bridge().player(), ClassType.LIVING_ENTITY, "isFallFlying"));
    }

    @Override
    protected void onStop() {
        activeControl = false;
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, swapMode.is("Silent"));
        }
        original = -1;
        client().rotations().release(7);
    }
}
