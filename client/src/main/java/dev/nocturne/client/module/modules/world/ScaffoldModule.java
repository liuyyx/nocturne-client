package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.inventory.InventorySupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

/** Epsilon Scaffold：六主模式、真实支撑面放置、换槽/输入/旋转与放置淡出。 */
public final class ScaffoldModule extends GameModule implements WorldOverlay {
    private final TextValue blacklist = add(new TextValue("Blacklisted Blocks", "chest,ender_chest,shulker,barrel,furnace,anvil,enchanting_table,crafting_table,beacon,bed,button,pressure_plate,door,trapdoor,fence_gate,note_block,tnt,slime_block,pumpkin,obsidian", 2048));
    private final BooleanValue toggleOnTeleport = add(new BooleanValue("Toggle On Teleport", false));
    private final ModeValue mode = mode("Mode", "TellyBridge", "TellyBridge", "GodBridge", "Heypixel", "Hypixel", "Elowen", "RavenB4");
    private final ModeValue swapMode = mode("Swap Mode", "Normal", "None", "Normal", "Silent", "InvSwitch");
    private final BooleanValue swapBack = bool("Swap Back", true);
    private final BooleanValue snap = bool("Snap", false);
    private final BooleanValue fortyFive = bool("45 Degrees", false);
    private final ModeValue rotationMode = mode("Rotation Mode", "Static", "Static", "Hypixel", "Heypixel");
    private final ModeValue raytraceMode = mode("Raytrace Mode", "Normal", "Normal", "Strict");
    private final NumberValue rotationSpeed = number("Rotation Speed", 127, 10, 180, 1);
    private final NumberValue rotationSpeed2 = number("Rotation Speed 2", 36, 10, 180, 1);
    private final NumberValue rotationBackSpeed = number("Rotation Back Speed", 180, 10, 180, 1);
    private final NumberValue tellyTicks = number("Telly Ticks", 1, 0, 6, 1);
    private final NumberValue placeDelay = number("Place Delay", 1, 0, 5, 1);
    private final NumberValue placeDelayRandom = number("Place Delay Random", 2, 0, 5, 1);
    private final BooleanValue emergency = bool("Emergency Placement", true);
    private final ModeValue godVariant = mode("God Bridge Variant", "Classic", "Classic", "Polar");
    private final BooleanValue godJump = bool("Jump", true);
    private final BooleanValue godSneak = bool("Sneak", false);
    private final BooleanValue godStopInput = bool("Stop Input", false);
    private final BooleanValue godStepBack = bool("Step Back", false);
    private final NumberValue forceSneak = number("Force Sneak Below Count", 5, 0, 64, 1);
    private final NumberValue sneakMin = number("Sneak Time Min", 1, 0, 20, 1);
    private final NumberValue sneakMax = number("Sneak Time Max", 1, 0, 20, 1);
    private final ModeValue rotationTiming = mode("Rotation Timing", "Normal", "Normal", "OnTick", "OnTickSnap");
    private final BooleanValue considerInventory = bool("Consider Inventory", false);
    private final NumberValue delayMin = number("Delay Min", 1, 0, 40, 1);
    private final NumberValue delayMax = number("Delay Max", 1, 0, 40, 1);
    private final NumberValue minDist = number("Min Dist", 0, 0, 0.25, 0.01);
    private final BooleanValue ledge = bool("Ledge", true);
    private final BooleanValue sneakBridging = bool("Sneak While Bridging", true);
    private final BooleanValue debugLog = bool("Debug Log", true);
    private final ModeValue swingMode = mode("Swing", "HideForClient", "DoNotHide", "HideForBoth", "HideForClient", "HideForServer");
    private final BooleanValue simulateAttempts = bool("Simulate Placement Attempts", true);
    private final NumberValue clickCpsMin = number("Click CPS Min", 11, 1, 20, 1);
    private final NumberValue clickCpsMax = number("Click CPS Max", 12, 1, 20, 1);
    private final ModeValue clickTechnique = mode("Click Technique", "STABILIZED", "STABILIZED", "NORMAL", "BUTTERFLY", "DOUBLE", "DRAG");
    private final BooleanValue failedOnly = bool("Failed Attempts Only", false);
    private final ModeValue angleSmooth = mode("Angle Smooth", "Sigmoid", "Linear", "Sigmoid");
    private final NumberValue horizontalMin = number("Horizontal Turn Speed Min", 19.8, 1, 180, 0.1);
    private final NumberValue horizontalMax = number("Horizontal Turn Speed Max", 40.5, 1, 180, 0.1);
    private final NumberValue verticalMin = number("Vertical Turn Speed Min", 8.1, 1, 90, 0.1);
    private final NumberValue verticalMax = number("Vertical Turn Speed Max", 30.6, 1, 90, 0.1);
    private final NumberValue steepness = number("Steepness", 10, 1, 30, 0.1);
    private final NumberValue midpoint = number("Midpoint", 0.3, 0, 1, 0.05);
    private final NumberValue resetThreshold = number("Reset Threshold", 1, 0, 20, 0.1);
    private final NumberValue ticksReset = number("Ticks Until Reset", 20, 1, 100, 1);
    private final ModeValue movementCorrection = mode("Movement Correction", "SILENT", "OFF", "SILENT", "STRICT");
    private final BooleanValue eagleEnabled = bool("Eagle Enabled", false);
    private final NumberValue eagleMin = number("Blocks To Eagle Min", 0, 0, 20, 1);
    private final NumberValue eagleMax = number("Blocks To Eagle Max", 0, 0, 20, 1);
    private final NumberValue edgeMin = number("Edge Distance Min", 0.01, 0, 0.5, 0.01);
    private final NumberValue edgeMax = number("Edge Distance Max", 0.05, 0, 0.5, 0.01);
    private final BooleanValue eagleGround = bool("Only On Ground", true);
    private final BooleanValue uitemsTelly = bool("Uitems Telly", true);
    private final NumberValue uitemsTicks = number("Uitems Telly Ticks", 5, 0, 10, 1);
    private final NumberValue uitemsRotation = number("Uitems Rotation Speed", 180, 10, 180, 1);
    private final NumberValue uitemsBack = number("Uitems Rotation Back Speed", 180, 10, 180, 1);
    private final BooleanValue duplicateRot = bool("Duplicate Rot Place", false);
    private final BooleanValue overrideRaycast = bool("Override raycast", true);
    private final BooleanValue interactBefore = bool("Interact before place", false);
    private final BooleanValue uitemsSafeWalk = bool("SafeWalk", true);
    private final ModeValue selfRescue = mode("Self rescue mode", "Disabled", "Disabled", "Normal", "Force");
    private final ModeValue elowenMode = mode("Elowen Mode", "Normal", "Normal", "TellyBridge", "KeepY", "Snap");
    private final ModeValue elowenRotation = mode("Elowen Rotation Mode", "None", "None", "Linear", "Curve", "Aca", "Heypixel");
    private final BooleanValue elowenEagle = bool("Elowen Eagle", false);
    private final BooleanValue elowenSneak = bool("Elowen Sneak", true);
    private final BooleanValue elowenJumpSprint = bool("Elowen Jump Sprint", false);
    private final NumberValue elowenSpeed = number("Elowen Rotation Speed", 50, 30, 180, 1);
    private final NumberValue elowenBack = number("Elowen Back Rotation Speed", 180, 10, 180, 1);
    private final NumberValue elowenDelay = number("Elowen Place Delay", 0, 0, 2, 1);
    private final BooleanValue elowenSwing = bool("Elowen Swing", false);
    private final BooleanValue elowenPacketN = bool("Elowen Packet Order N", false);
    private final BooleanValue elowenCount = bool("Elowen Block Count", true);
    private final NumberValue elowenHudY = number("Elowen HUD Y", -50, -500, 500, 1);
    private final BooleanValue elowenSideway = bool("Elowen Sideway Rotation", false);
    private final ModeValue ravenRotation = mode("Raven Rotation Mode", "Strict", "None", "Strict", "Smooth", "Simple", "Lazy", "Offset");
    private final ModeValue ravenFast = mode("Raven Fast Scaffold", "Disabled", "Disabled", "Normal", "Strict");
    private final ModeValue ravenFastRmb = mode("Raven Fast Scaffold RMB", "Disabled", "Disabled", "Normal", "Strict");
    private final ModeValue ravenMulti = mode("Raven Multi-place", "Disabled", "Disabled", "Normal", "Strict");
    private final ModeValue ravenPrecision = mode("Raven Precision", "High", "Low", "Normal", "High");
    private final NumberValue ravenMotion = number("Raven Motion", 0.95, 0, 1, 0.01);
    private final NumberValue ravenFastMotion = number("Raven Fast Scaffold Motion", 0.95, 0, 1, 0.01);
    private final NumberValue ravenSneakEvery = number("Raven Sneak Every", 0, 0, 20, 1);
    private final NumberValue ravenBoost = number("Raven Speed Boost", 0, 0, 2, 0.05);
    private final BooleanValue ravenLegit = bool("Raven Legit Motion", false);
    private final BooleanValue ravenSafe = bool("Raven Safe Walk", false);
    private final BooleanValue ravenForward = bool("Raven Jump Facing Forward", false);
    private final BooleanValue ravenAutoSwap = bool("Raven Auto Swap", true);
    private final BooleanValue ravenSilentSwing = bool("Raven Silent Swing", false);
    private final BooleanValue ravenSlowEnable = bool("Raven Slow On Enable", false);
    private final BooleanValue ravenSlowPotion = bool("Raven Slow Speed Potion", false);
    private final BooleanValue ravenHighlight = bool("Raven Highlight Blocks", false);
    private final BooleanValue ravenTower = bool("Raven Tower", false);
    private final BooleanValue ravenMovementFix = bool("Raven Movement Fix", true);
    private final BooleanValue swingHand = bool("Swing Hand", true);
    private final BooleanValue render = bool("Render", true);
    private final BooleanValue fade = bool("Fade", true);
    private final NumberValue fadeTime = number("Fade Time", 500, 0, 3000, 50);
    private final BooleanValue shrink = bool("Shrink", false);
    private final ColorValue sideColor = color("Side Color", 0x64FFB7C5);
    private final ColorValue lineColor = color("Line Color", 0xFFFF69B4);
    private int original = -1;
    private int placedTicks;
    private int inputTicks;
    private int sneakTicks;
    private long nextPlace;
    private int[] lastPlaced;
    private long placedAt;
    private boolean inputJump;
    private boolean inputSneak;
    private float inputForward;
    private boolean slowTicks;

    public ScaffoldModule() {
        super("Scaffold", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            inputForward = event.forward;
            inputTicks++;
            if (mode.is("GodBridge")) {
                if (godStopInput.get()) {
                    event.forward = 0.0f;
                    event.strafe = 0.0f;
                }
                if (godStepBack.get()) {
                    event.forward = -1.0f;
                }
                if (godJump.get() && event.forward > 0.0f) {
                    event.jump = true;
                }
                if (godSneak.get() || sneakBridging.get() && sneakTicks > 0) {
                    event.sneak = true;
                    sneakTicks--;
                }
            }
            if (mode.is("Elowen")) {
                event.sneak |= elowenSneak.get() && edgeNear();
                event.jump |= elowenJumpSprint.get();
                event.sprint |= elowenJumpSprint.get();
            }
            if ((mode.is("Heypixel") || mode.is("Hypixel")) && uitemsSafeWalk.get()) {
                event.sneak |= edgeNear();
            }
            if (mode.is("RavenB4") && ravenSafe.get()) {
                event.sneak |= edgeNear();
            }
            inputJump = event.jump;
            inputSneak = event.sneak;
        });
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextPlace || state().player() == null) {
            return;
        }
        if (slowTicks) {
            bridge().setTimerSpeed(0.5f);
            slowTicks = false;
        }
        int slot = blockSlot();
        if (slot < 0) {
            restore();
            return;
        }
        if (original < 0) {
            original = state().selectedSlot();
            slowTicks = mode.is("RavenB4") && ravenSlowEnable.get();
        }
        actions().selectSlot(slot, swapMode.is("Silent"));
        double[] target = target();
        if (target == null) {
            return;
        }
        int x = (int) Math.floor(target[0]);
        int y = (int) Math.floor(target[1]);
        int z = (int) Math.floor(target[2]);
        aim(x, y, z);
        if (interactBefore.get() && (mode.is("Heypixel") || mode.is("Hypixel"))) {
            actions().useItem();
        }
        boolean placed = PlacementSupport.placeNear(bridge(), actions(), x, y, z);
        if (!placed && emergency.get()) {
            int below = (int) Math.floor(state().y()) - 1;
            placed = PlacementSupport.placeNear(bridge(), actions(),
                    (int) Math.floor(state().x()), below, (int) Math.floor(state().z()));
        }
        if (!placed) {
            if (simulateAttempts.get() && debugLog.get()) {
                System.out.println("[nocturne] Scaffold placement attempt failed");
            }
            return;
        }
        placedTicks++;
        lastPlaced = new int[]{x, y, z};
        placedAt = now;
        if (swingHand.get() && !mode.is("Elowen") || elowenSwing.get()) {
            actions().swing(false, swingMode.is("HideForClient") || swingMode.is("HideForBoth")
                    || mode.is("RavenB4") && ravenSilentSwing.get());
        }
        if (swapBack.get()) {
            restore();
        }
        int base = mode.is("Elowen") ? elowenDelay.asInt() : placeDelay.asInt();
        int random = mode.is("GodBridge") ? EpsilonSupport.randomInt(delayMin.asInt(), delayMax.asInt())
                : EpsilonSupport.randomInt(0, placeDelayRandom.asInt());
        nextPlace = now + (base + random) * 50L;
        if (mode.is("GodBridge") && placedTicks >= forceSneak.asInt()) {
            sneakTicks = EpsilonSupport.randomInt(sneakMin.asInt(), sneakMax.asInt());
            placedTicks = 0;
        }
    }

    private double[] target() {
        double yaw = Math.toRadians(state().yaw());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        if (mode.is("Elowen") && elowenSideway.get()) {
            double old = forwardX;
            forwardX = forwardZ;
            forwardZ = -old;
        }
        double distance = mode.is("TellyBridge") || mode.is("Elowen") && elowenMode.is("TellyBridge") ? 1.2 : 1.0;
        double y = state().y() - 1.0;
        if (mode.is("Elowen") && elowenMode.is("KeepY")) {
            y = Math.floor(state().y()) - 1.0;
        }
        if (mode.is("RavenB4") && ravenTower.get() && inputJump) {
            y = state().y();
            forwardX = 0.0;
            forwardZ = 0.0;
        }
        double x = state().x() + forwardX * distance;
        double z = state().z() + forwardZ * distance;
        if (Math.abs(x - Math.floor(x) - 0.5) < minDist.get() || Math.abs(z - Math.floor(z) - 0.5) < minDist.get()) {
            return null;
        }
        return new double[]{x, y, z};
    }

    private void aim(int x, int y, int z) {
        double yaw = Math.toRadians(state().yaw());
        float targetYaw = state().yaw() + 180.0f;
        if (snap.get() || fortyFive.get() || mode.is("Elowen") && elowenMode.is("Snap")) {
            targetYaw = Math.round(targetYaw / 45.0f) * 45.0f;
        }
        if (mode.is("RavenB4") && ravenForward.get() && inputJump) {
            targetYaw = state().yaw();
        }
        double dx = x + 0.5 - state().x();
        double dz = z + 0.5 - state().z();
        float pitch = dev.nocturne.client.control.RotationController.pitchTo(Math.hypot(dx, dz),
                y + 1.0 - state().y() - 1.62);
        float speed = rotationSpeed.asFloat();
        if (mode.is("Elowen")) {
            speed = elowenSpeed.asFloat();
        } else if (mode.is("Heypixel") || mode.is("Hypixel")) {
            speed = uitemsRotation.asFloat();
        } else if (mode.is("GodBridge")) {
            float t = (float) Math.min(1.0, Math.abs(dev.nocturne.client.control.RotationController.wrap(
                    targetYaw - state().yaw())) / 180.0);
            float sigmoid = (float) (1.0 / (1.0 + Math.exp(-steepness.get() * (t - midpoint.get()))));
            speed = angleSmooth.is("Linear") ? horizontalMin.asFloat()
                    : horizontalMin.asFloat() + (float) ((horizontalMax.get() - horizontalMin.get()) * sigmoid);
        }
        if (rotationTiming.is("OnTickSnap")) {
            speed = 180.0f;
        }
        client().rotations().request(dev.nocturne.client.control.RotationController.approach(
                state().yaw(), targetYaw, speed), pitch, 20, 200_000_000L);
    }

    private int blockSlot() {
        if (swapMode.is("None")) {
            return state().selectedSlot();
        }
        int slot = EpsilonSupport.findHotbar(bridge(), "block");
        for (int i = 0; i < 9; i++) {
            Object stack = bridge().hotbarStack(i);
            Object item = bridge().callMapped(stack, ClassType.ITEM_STACK, "getItem");
            if (!bridge().isInstance(item, ClassType.BLOCK_ITEM)) {
                continue;
            }
            String id = bridge().itemDescription(stack);
            if (!blacklist.get().toLowerCase(java.util.Locale.ROOT).contains(id.replace("block.minecraft.", ""))) {
                return i;
            }
        }
        if (swapMode.is("InvSwitch") || considerInventory.get()) {
            int inventory = EpsilonSupport.findInventory(bridge(), "block");
            if (inventory >= 9) {
                int target = 8;
                actions().clickSlot(InventorySupport.menuSlot(inventory), target, "swap");
                return target;
            }
        }
        return slot;
    }

    private boolean edgeNear() {
        Object box = bridge().callMapped(bridge().player(), ClassType.ENTITY, "getBoundingBox");
        return bridge().noCollision(bridge().player(), bridge().moveBox(box, 0, -0.5, 0));
    }

    private void restore() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, swapMode.is("Silent"));
        }
        original = -1;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!render.get() && !(mode.is("RavenB4") && ravenHighlight.get()) || lastPlaced == null) {
            return;
        }
        float remaining = fade.get() ? Math.max(0.0f, 1.0f - (System.currentTimeMillis() - placedAt)
                / Math.max(1.0f, fadeTime.asFloat())) : 1.0f;
        if (remaining <= 0.0f) {
            lastPlaced = null;
            return;
        }
        double inset = shrink.get() ? (1.0 - remaining) * 0.5 : 0.0;
        WorldGeometry.box(draw, projection, lastPlaced[0] + inset, lastPlaced[1] + inset,
                lastPlaced[2] + inset, lastPlaced[0] + 1.0 - inset, lastPlaced[1] + 1.0 - inset,
                lastPlaced[2] + 1.0 - inset, 1.0f, alpha(lineColor.argb(), remaining));
        if ((sideColor.argb() >>> 24) > 0) {
            WorldGeometry.blockFilled(draw, projection, lastPlaced[0], lastPlaced[1], lastPlaced[2],
                    1.0f, alpha(sideColor.argb(), remaining), alpha(lineColor.argb(), remaining));
        }
    }

    private static int alpha(int color, float factor) {
        return color & 0xFFFFFF | (int) ((color >>> 24) * factor) << 24;
    }

    @Override
    protected void onStop() {
        restore();
        if (bridge() != null) {
            bridge().setTimerSpeed(1.0f);
        }
        if (client() != null && client().rotations() != null) {
            client().rotations().release(20);
        }
        lastPlaced = null;
    }

    private BooleanValue bool(String name, boolean value) {
        return add(new BooleanValue(name, value));
    }

    private NumberValue number(String name, double value, double min, double max, double step) {
        return add(new NumberValue(name, value, min, max, step));
    }

    private ModeValue mode(String name, String value, String... options) {
        return add(new ModeValue(name, value, options));
    }

    private ColorValue color(String name, int value) {
        return add(new ColorValue(name, value));
    }
}
