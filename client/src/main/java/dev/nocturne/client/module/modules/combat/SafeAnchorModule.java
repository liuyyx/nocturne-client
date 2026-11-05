package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** SafeAnchor：Assist/Auto、自适应/掩护、护盾/充能顺序、多阶段超时与健康保护。 */
public final class SafeAnchorModule extends GameModule implements WorldOverlay {
    private final KeyValue trigger = add(new KeyValue("Trigger Key", KeyValue.NONE));
    private final ModeValue mode = add(new ModeValue("Mode", "Auto", "Assist", "Auto"));
    private final ModeValue placeMode = add(new ModeValue("Place Mode", "Adaptive", "Adaptive", "Cover"));
    private final ModeValue order = add(new ModeValue("Order", "ShieldFirst", "ShieldFirst", "ChargeFirst"));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", true));
    private final NumberValue rotationMin = add(new NumberValue("Rotation Speed Min", 15, 1, 360, 1));
    private final NumberValue rotationMax = add(new NumberValue("Rotation Speed Max", 30, 1, 360, 1));
    private final BooleanValue strict = add(new BooleanValue("Strict", true));
    private final BooleanValue breakCrystal = add(new BooleanValue("Break Crystal", false));
    private final BooleanValue swapBack = add(new BooleanValue("SwapBack", true));
    private final NumberValue anchorMin = add(new NumberValue("Anchor Place Min Delay", 0, 0, 50, 1));
    private final NumberValue anchorMax = add(new NumberValue("Anchor Place Max Delay", 2.5, 0, 50, 0.5));
    private final NumberValue shieldMin = add(new NumberValue("Shield Place Min Delay", 0, 0, 50, 1));
    private final NumberValue shieldMax = add(new NumberValue("Shield Place Max Delay", 2.5, 0, 50, 0.5));
    private final BooleanValue multiShield = add(new BooleanValue("Multi Shield", false));
    private final NumberValue multiShieldChance = add(new NumberValue("Multi Shield Chance", 50, 0, 100, 1));
    private final NumberValue maxExtraShields = add(new NumberValue("Max Extra Shields", 1, 1, 5, 1));
    private final NumberValue shieldDistanceMin = add(new NumberValue("Shield Distance Min", 1, 1, 3, 1));
    private final NumberValue shieldDistanceMax = add(new NumberValue("Shield Distance Max", 1, 1, 3, 1));
    private final NumberValue chargeMin = add(new NumberValue("Charge Min Delay", 0, 0, 50, 1));
    private final NumberValue chargeMax = add(new NumberValue("Charge Max Delay", 2.5, 0, 50, 0.5));
    private final NumberValue detonateMin = add(new NumberValue("Detonate Min Delay", 0, 0, 50, 1));
    private final NumberValue detonateMax = add(new NumberValue("Detonate Max Delay", 2.5, 0, 50, 0.5));
    private final NumberValue detonateSlot = add(new NumberValue("Detonate Slot", 1, 1, 9, 1));
    private final NumberValue minHealth = add(new NumberValue("Min Health", 4, 0, 20, 0.5));
    private final BooleanValue prediction = add(new BooleanValue("Prediction", false));
    private final NumberValue predictionStrength = add(new NumberValue("Prediction Strength", 3, 0.5, 20, 0.5));
    private final BooleanValue multiCharge = add(new BooleanValue("Multi Charge", false));
    private final NumberValue multiChargeChance = add(new NumberValue("Multi Charge Chance", 50, 0, 100, 1));
    private final NumberValue minExtraCharges = add(new NumberValue("Min Extra Charges", 1, 1, 3, 1));
    private final NumberValue maxExtraCharges = add(new NumberValue("Max Extra Charges", 1, 1, 3, 1));
    private final BooleanValue render = add(new BooleanValue("Render", true));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xFFFFFFFF));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x32FFFFFF));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 1, 0, 5, 0.1));
    private boolean keyDown;
    private boolean wasKeyDown;
    private int stage;
    private int stageTicks;
    private long nextAction;
    private int original = -1;
    private int[] anchor;
    private int[] shield;
    private int extraRemaining;

    public SafeAnchorModule() {
        super("Safe Anchor", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (trigger.bound() && event.keyCode == trigger.get()) {
                keyDown = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        boolean press = keyDown && !wasKeyDown;
        wasKeyDown = keyDown;
        if (press && stage == 0) {
            if (EpsilonSupport.enabled("AnchorBlast") || EpsilonSupport.enabled("DoubleAnchor")) {
                return;
            }
            original = state().selectedSlot();
            stage = 1;
            stageTicks = 0;
            nextAction = 0L;
            anchor = null;
            shield = null;
        }
        if (stage == 0 || System.currentTimeMillis() < nextAction || state().health() < minHealth.get()) {
            return;
        }
        if (++stageTicks > 60) {
            reset();
            return;
        }
        Object hit = actions().pick(5.0, 1.0f);
        int[] pos = bridge().hitBlockPosition(hit);
        if (pos == null) {
            return;
        }
        if (anchor == null) {
            anchor = pos;
        }
        if (rotate.get()) {
            EpsilonSupport.aim(bridge(), state(), anchor[0] + 0.5, anchor[1] + 0.5, anchor[2] + 0.5,
                    EpsilonSupport.randomInt(rotationMin.asInt(), rotationMax.asInt()), 8);
        }
        if (strict.get() && !bridge().isBlockHit(hit)) {
            return;
        }
        if (breakCrystal.get()) {
            Object crystal = bridge().hitEntity(hit);
            if (crystal != null && bridge().isInstance(crystal, ClassType.END_CRYSTAL)) {
                actions().attack(crystal);
                return;
            }
        }
        switch (stage) {
            case 1:
                if (place("respawn_anchor", anchor)) {
                    advanceFromAnchor();
                }
                break;
            case 2:
                if (order.is("ShieldFirst")) {
                    placeShield();
                } else {
                    charge();
                }
                break;
            case 3:
                if (order.is("ShieldFirst")) {
                    charge();
                } else {
                    placeShield();
                }
                break;
            case 4:
                select(detonateSlot.asInt() - 1);
                actions().placeAt(anchor[0], anchor[1], anchor[2], face(bridge().hitDirection(hit)));
                actions().swing(false, false);
                long delay = randomDelay(detonateMin, detonateMax);
                if (prediction.get()) {
                    double[] velocity = bridge().entityVelocity(bridge().player());
                    delay = Math.max(0L, delay - (long) (Math.hypot(velocity[0], velocity[2])
                            * predictionStrength.get() * 10.0));
                }
                nextAction = System.currentTimeMillis() + delay;
                stage = 5;
                break;
            default:
                reset();
        }
    }

    private void advanceFromAnchor() {
        stage = 2;
        nextAction = System.currentTimeMillis() + randomDelay(anchorMin, anchorMax);
        extraRemaining = 0;
    }

    private void placeShield() {
        int distance = EpsilonSupport.randomInt(shieldDistanceMin.asInt(), shieldDistanceMax.asInt());
        int[] pos = placeMode.is("Cover") ? new int[]{anchor[0], anchor[1] + 1, anchor[2]}
                : new int[]{anchor[0] + distance, anchor[1], anchor[2]};
        if (place("obsidian", pos)) {
            shield = pos;
            if (multiShield.get() && extraRemaining == 0
                    && EpsilonSupport.RANDOM.nextDouble() * 100.0 < multiShieldChance.get()) {
                extraRemaining = maxExtraShields.asInt();
            }
            if (extraRemaining > 0) {
                extraRemaining--;
                stage = 2;
                pos[0]++;
            } else {
                stage = 3;
            }
            nextAction = System.currentTimeMillis() + randomDelay(shieldMin, shieldMax);
        }
    }

    private void charge() {
        int charges = multiCharge.get() && EpsilonSupport.RANDOM.nextDouble() * 100.0 < multiChargeChance.get()
                ? EpsilonSupport.randomInt(minExtraCharges.asInt(), maxExtraCharges.asInt()) + 1 : 1;
        boolean success = true;
        for (int i = 0; i < charges; i++) {
            success &= place("glowstone", anchor);
        }
        if (success) {
            stage = 4;
            nextAction = System.currentTimeMillis() + randomDelay(chargeMin, chargeMax);
        }
    }

    private boolean place(String item, int[] pos) {
        int slot = EpsilonSupport.findHotbar(bridge(), item);
        if (slot < 0) {
            reset();
            return false;
        }
        select(slot);
        String id = bridge().blockDescription(bridge().blockStateAt(pos[0], pos[1], pos[2]));
        boolean success = item.equals("respawn_anchor") && id.contains("respawn_anchor")
                || item.equals("glowstone") && id.contains("respawn_anchor")
                ? actions().placeAt(pos[0], pos[1], pos[2], "up")
                : PlacementSupport.placeNear(bridge(), actions(), pos[0], pos[1], pos[2]);
        if (success) {
            actions().swing(false, false);
        }
        return success;
    }

    private void select(int slot) {
        actions().selectSlot(slot, false);
    }

    private static String face(Object hit) {
        return String.valueOf(hit == null ? "up" : hit).toLowerCase(java.util.Locale.ROOT);
    }

    private long randomDelay(NumberValue min, NumberValue max) {
        return EpsilonSupport.randomLong(min.get().longValue(), max.get().longValue()) * 10L;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!render.get()) {
            return;
        }
        if (anchor != null) {
            WorldGeometry.blockFilled(draw, projection, anchor[0], anchor[1], anchor[2],
                    lineWidth.asFloat(), sideColor.argb(), lineColor.argb());
        }
        if (shield != null) {
            WorldGeometry.block(draw, projection, shield[0], shield[1], shield[2],
                    lineWidth.asFloat(), lineColor.argb());
        }
    }

    private void reset() {
        if (swapBack.get() && original >= 0 && actions() != null) {
            actions().selectSlot(original, false);
        }
        original = -1;
        stage = 0;
        stageTicks = 0;
        anchor = null;
        shield = null;
    }

    public boolean active() {
        return stage != 0;
    }

    @Override
    protected void onStop() {
        keyDown = false;
        wasKeyDown = false;
        reset();
    }
}
