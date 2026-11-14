package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 第一人称手持姿势变换。当前钩子只提供手臂方向，不区分物品与裸臂，故 Arm 变换
 * 与主副手变换叠加作用于同一姿势；使用物品时的格挡不区分武器种类。
 * 上游降级：Swing Mode、Only Weapon、Modify Swing Duration、Swing Duration、
 * Visual Swing On Use、Only On Block、Disable Swap Main、Disable Swap Off 均因缺少
 * 对应挥臂或换手钩子而删除。无法读取主手方向时假定右手为主手。
 * 现有桥的反射和四元数构造会分配对象，不能声称渲染路径零分配。
 */
public final class HandViewModule extends GameModule {
    private final BooleanValue blocking = add(new BooleanValue("Blocking Animation", true));
    private final ModeValue mode = add(new ModeValue("Blocking Mode", "NewExhibition", "V1_7", "Pushdown", "Scale", "Leaked", "Ninja", "NewExhibition", "OldExhibition"));
    private final BooleanValue custom = add(new BooleanValue("Custom Transform", false));
    private final Transform main = new Transform("Main Hand");
    private final Transform off = new Transform("Off Hand");
    private final Transform armTransform = new Transform("Arm");
    private boolean warned;

    public HandViewModule() { super("Hand View", Category.RENDER); }

    @Override protected void onStart() { warned = false; }
    @Override public void onTick() {
        if (!warned && !GameplayHook.isWired("handTransform")) {
            warned = true;
            System.out.println("[nocturne] Hand View: handTransform unavailable on this version (" + GameplayHook.unavailableReason("handTransform") + ")");
        }
    }

    /** 由第一人称手持姿势钩子调用。 */
    public void apply(Object pose, Object arm, float inverseHeight) {
        GameBridge b = bridge();
        if (!isEnabled() || b == null || pose == null || arm == null) return;
        Object player = b.player();
        if (player == null) return;
        int side = arm instanceof Enum && "LEFT".equals(((Enum<?>) arm).name()) ? -1 : 1;
        if (custom.get()) {
            Object mainArm = b.callMapped(player, ClassType.LIVING_ENTITY, "getMainArm");
            boolean isMain = mainArm == null ? side == 1 : mainArm == arm;
            (isMain ? main : off).apply(b, pose);
            armTransform.apply(b, pose);
        }
        if (!blocking.get() || !b.usingItem(player)) return;
        Object attackValue = b.readField(player, ClassType.LIVING_ENTITY, "attackAnim");
        double attack = attackValue instanceof Number ? ((Number) attackValue).doubleValue() : 0.0;
        attack = Math.max(0.0, Math.min(1.0, attack));
        double progress = Math.sin(Math.sqrt(attack) * Math.PI);
        switch (mode.index()) {
            case 0:
                translate(b, pose, -side * 0.1, 0.1, 0); attack(b, pose, side, attack, 0.9); block(b, pose, side, 0, 0, 0, -102.25, 13.365, 78.05); break;
            case 1:
                translate(b, pose, -side * 0.1, 0.1, 0); rotate(b, pose, 0, 0, side * progress * 10); rotate(b, pose, -progress * 35, 0, 0); block(b, pose, side, 0, 0, 0, -102.25, 13.365, 78.05); break;
            case 2:
                translate(b, pose, -side * 0.16, 0.12, 0.16); scale(b, pose, 0.85); attack(b, pose, side, attack, 0.35); block(b, pose, side, 0, 0, 0, -100, 18, 72); break;
            case 3:
                attack(b, pose, side, attack, 0.6); block(b, pose, side, -0.18, 0.18, 0.1, -96, 24, 68); break;
            case 4:
                attack(b, pose, side, attack, 0.25); block(b, pose, side, -0.05, 0.22, 0.2, -88, 35, 82); break;
            case 5:
                sourceBase(b, pose, side, -0.01, attack);
                axis(b, pose, -0.3, 1, 1.3, side * progress * -40);
                axis(b, pose, progress / 2, 0, 4, side * progress * 50);
                axis(b, pose, 1, progress / 2, 0, side * progress * 40);
                axis(b, pose, 0.3, 0.1, 0.4, side * progress * -20.7);
                scale(b, pose, 0.9); sourceBlock(b, pose); break;
            case 6:
                sourceBase(b, pose, side, inverseHeight * 0.6 - 0.07, 1);
                axis(b, pose, progress / 2, 0, 4, -progress * 25);
                axis(b, pose, 1, progress / 2, 0, -progress * 30);
                sourceBlock(b, pose); break;
            default: break;
        }
    }

    private static void translate(GameBridge b, Object p, double x, double y, double z) { b.transformPose(p, x, y, z, 1, 1, 1, 0, 0, 0); }
    private static void scale(GameBridge b, Object p, double s) { b.transformPose(p, 0, 0, 0, s, s, s, 0, 0, 0); }
    private static void rotate(GameBridge b, Object p, double x, double y, double z) { b.transformPose(p, 0, 0, 0, 1, 1, 1, x, y, z); }
    private static void axis(GameBridge b, Object p, double x, double y, double z, double degrees) {
        if (degrees == 0) return;
        double length = Math.sqrt(x*x + y*y + z*z);
        if (length == 0) return;
        Object angle = b.construct(ClassType.AXIS_ANGLE4F, (float) Math.toRadians(degrees), (float) (x/length), (float) (y/length), (float) (z/length));
        Object quaternion = b.construct(ClassType.QUATERNIONF, angle);
        if (quaternion != null && !b.callMappedStatus(p, ClassType.POSE_STACK, "rotate", quaternion)) b.callMappedStatus(p, ClassType.POSE_STACK, "mulPose", quaternion);
    }
    private static void attack(GameBridge b, Object p, int side, double a, double s) {
        double y = Math.sin(a*a*Math.PI), xz = Math.sin(Math.sqrt(a)*Math.PI);
        rotate(b, p, 0, side*(45-y*20*s), 0); rotate(b, p, 0, 0, -side*xz*20*s); rotate(b, p, -xz*80*s, 0, 0); rotate(b, p, 0, -side*45, 0);
    }
    private static void block(GameBridge b, Object p, int side, double x, double y, double z, double rx, double ry, double rz) {
        translate(b, p, side*x, y, z); rotate(b, p, rx, side*ry, side*rz);
    }
    private static void sourceBase(GameBridge b, Object p, int side, double y, double a) {
        translate(b, p, side*0.29, y, -0.18); rotate(b, p, 0, 45, 0);
        double ySwing = Math.sin(a*a*Math.PI), swing = Math.sin(Math.sqrt(a)*Math.PI);
        rotate(b, p, 0, -side*ySwing*20, 0); rotate(b, p, 0, 0, -side*swing*20); rotate(b, p, -side*swing*80, 0, 0);
    }
    private static void sourceBlock(GameBridge b, Object p) {
        translate(b, p, -0.5, 0.2, 0); rotate(b, p, 0, 30, 0); rotate(b, p, -80, 0, 0); rotate(b, p, 0, 60, 0);
    }
    private final class Transform {
        final NumberValue sx, sy, sz, px, py, pz, rx, ry, rz;
        Transform(String prefix) {
            sx = value(prefix + " Scale X", 1, 0, 5, 0.1); sy = value(prefix + " Scale Y", 1, 0, 5, 0.1); sz = value(prefix + " Scale Z", 1, 0, 5, 0.1);
            px = value(prefix + " Position X", 0, -3, 3, 0.1); py = value(prefix + " Position Y", 0, -3, 3, 0.1); pz = value(prefix + " Position Z", 0, -3, 3, 0.1);
            rx = value(prefix + " Rotation X", 0, -180, 180, 1); ry = value(prefix + " Rotation Y", 0, -180, 180, 1); rz = value(prefix + " Rotation Z", 0, -180, 180, 1);
        }
        void apply(GameBridge b, Object p) {
            rotate(b, p, rx.get(), ry.get(), rz.get());
            b.transformPose(p, 0, 0, 0, sx.get(), sy.get(), sz.get(), 0, 0, 0);
            translate(b, p, px.get(), py.get(), pz.get());
        }
    }
    private NumberValue value(String name, double initial, double min, double max, double step) { return add(new NumberValue(name, initial, min, max, step)); }
}
