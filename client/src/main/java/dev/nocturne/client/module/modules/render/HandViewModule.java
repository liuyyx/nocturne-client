package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Hand View：主/副手/手臂自定义变换、七种格挡姿势、Flux 与换手动画抑制。 */
public final class HandViewModule extends GameModule {
    private final BooleanValue disableSwapMain = add(new BooleanValue("Disable Swap Main", true));
    private final BooleanValue disableSwapOff = add(new BooleanValue("Disable Swap Off", true));
    private final BooleanValue blockingAnimation = add(new BooleanValue("Blocking Animation", true));
    private final ModeValue blockingMode = add(new ModeValue("Blocking Mode", "NewExhibition", "V1_7", "Pushdown", "Scale", "Leaked", "Ninja", "NewExhibition", "OldExhibition"));
    private final ModeValue swingMode = add(new ModeValue("Swing Mode", "Vanilla", "Vanilla", "Flux"));
    private final BooleanValue onlyWeapon = add(new BooleanValue("Only Weapon", true));
    private final BooleanValue modifySwingDuration = add(new BooleanValue("Modify Swing Duration", false));
    private final NumberValue swingDuration = add(new NumberValue("Swing Duration", 6, 0, 20, 1));
    private final BooleanValue visualSwingOnUse = add(new BooleanValue("Visual Swing On Use", true));
    private final BooleanValue onlyOnBlock = add(new BooleanValue("Only On Block", true));
    private final BooleanValue customTransform = add(new BooleanValue("Custom Transform", false));
    private final TransformGroup main = new TransformGroup("Main Hand");
    private final TransformGroup off = new TransformGroup("Off Hand");
    private final TransformGroup arm = new TransformGroup("Arm");
    private long swingUntil;

    public HandViewModule() {
        super("Hand View", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (!event.post) {
                swingUntil = System.currentTimeMillis() + (modifySwingDuration.get() ? swingDuration.asInt() * 50L : 120L);
            }
        });
        listen(UseItemEvent.class, event -> {
            if (!event.post && visualSwingOnUse.get() && (!onlyOnBlock.get() || holdingBlock())) {
                swingUntil = System.currentTimeMillis() + 120L;
            }
        });
    }

    public void apply(Object pose, Object hand, float inverseHeight) {
        if (pose == null) {
            return;
        }
        if (customTransform.get()) {
            TransformGroup group = bridge().isEmptyStack(bridge().heldItem(bridge().player(), false)) ? arm
                    : String.valueOf(hand).equalsIgnoreCase("LEFT") ? off : main;
            bridge().transformPose(pose, group.x.get(), group.y.get(), group.z.get(),
                    group.sx.get(), group.sy.get(), group.sz.get(), group.rx.get(), group.ry.get(), group.rz.get());
        }
        if (blockingAnimation.get() && blocking()) {
            double[] transform = blockingTransform();
            bridge().transformPose(pose, transform[0], transform[1], transform[2],
                    transform[3], transform[4], transform[5], transform[6], transform[7], transform[8]);
        }
        if (swingMode.is("Flux") && System.currentTimeMillis() < swingUntil
                && (!onlyWeapon.get() || weapon())) {
            bridge().transformPose(pose, 0.0, -0.15, 0.1, 1.0, 1.0, 1.0, 20.0, -15.0, 10.0);
        }
    }

    private boolean blocking() {
        return bridge().usingItem(bridge().player()) && weapon() || EpsilonSupport.enabled("KillAura");
    }

    private boolean weapon() {
        String id = bridge().itemDescription(bridge().heldItem(bridge().player(), false));
        return id.contains("sword") || id.contains("axe") || id.contains("spear") || id.contains("trident");
    }

    private boolean holdingBlock() {
        Object item = bridge().callMapped(bridge().heldItem(bridge().player(), false), ClassType.ITEM_STACK, "getItem");
        return bridge().isInstance(item, ClassType.BLOCK_ITEM);
    }

    private double[] blockingTransform() {
        if (blockingMode.is("V1_7")) return new double[]{0.0, -0.1, 0.05, 1, 1, 1, 0, 30, 0};
        if (blockingMode.is("Pushdown")) return new double[]{0.0, -0.25, 0.0, 1, 1, 1, 15, 0, 0};
        if (blockingMode.is("Scale")) return new double[]{0.0, 0.0, 0.0, 0.8, 0.8, 0.8, 0, 20, 0};
        if (blockingMode.is("Leaked")) return new double[]{0.05, -0.1, 0.1, 1, 1, 1, 25, -20, 10};
        if (blockingMode.is("Ninja")) return new double[]{-0.1, 0.0, 0.1, 1, 1, 1, -20, 30, 0};
        if (blockingMode.is("OldExhibition")) return new double[]{0.0, -0.15, 0.1, 1, 1, 1, 30, 15, -10};
        return new double[]{0.0, -0.12, 0.08, 1, 1, 1, 25, -25, 10};
    }

    @Override
    public void onTick() {
        Object renderer = bridge().readField(bridge().minecraft(), ClassType.MINECRAFT, "itemInHandRenderer");
        if (renderer == null) {
            return;
        }
        if (disableSwapMain.get()) {
            bridge().writeField(renderer, ClassType.ITEM_IN_HAND_RENDERER, "mainHandHeight", 1.0f);
            bridge().writeField(renderer, ClassType.ITEM_IN_HAND_RENDERER, "mainHandItem",
                    bridge().heldItem(bridge().player(), false));
        }
        if (disableSwapOff.get()) {
            bridge().writeField(renderer, ClassType.ITEM_IN_HAND_RENDERER, "offHandHeight", 1.0f);
            bridge().writeField(renderer, ClassType.ITEM_IN_HAND_RENDERER, "offHandItem",
                    bridge().heldItem(bridge().player(), true));
        }
    }

    /** 三组 9 项设置，名称保持 Epsilon 前缀。 */
    private final class TransformGroup {
        private NumberValue x;
        private NumberValue y;
        private NumberValue z;
        private NumberValue sx;
        private NumberValue sy;
        private NumberValue sz;
        private NumberValue rx;
        private NumberValue ry;
        private NumberValue rz;

        private TransformGroup(String prefix) {
            x = number(prefix + " Position X", 0, -3, 3, 0.1);
            y = number(prefix + " Position Y", 0, -3, 3, 0.1);
            z = number(prefix + " Position Z", 0, -3, 3, 0.1);
            sx = number(prefix + " Scale X", 1, 0, 5, 0.1);
            sy = number(prefix + " Scale Y", 1, 0, 5, 0.1);
            sz = number(prefix + " Scale Z", 1, 0, 5, 0.1);
            rx = number(prefix + " Rotation X", 0, -180, 180, 1);
            ry = number(prefix + " Rotation Y", 0, -180, 180, 1);
            rz = number(prefix + " Rotation Z", 0, -180, 180, 1);
        }

        private NumberValue number(String name, double def, double min, double max, double step) {
            return HandViewModule.this.add(new NumberValue(name, def, min, max, step));
        }
    }
}
