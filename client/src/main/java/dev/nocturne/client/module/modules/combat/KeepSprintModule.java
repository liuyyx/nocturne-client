package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 控制攻击后的水平减速与疾跑状态。
 *
 * <p>游戏在攻击时会把水平动量乘 0.6 并取消疾跑。本模块在攻击前记录动量，攻击后确认游戏确实
 * 施加了原版减速，再按配置恢复（可分别对待受击中的目标）。“Buffer Abuse”按上游语义累计攻击
 * 次数，达到上限后逐步回落，期间跳过减速。
 *
 * <p>Scaffold 互斥尚未移植：上游在脚手架激活时不处理攻击减速；当前没有 Scaffold，因此该互斥
 * 条件恒为 false，不额外放一个无效开关。
 */
public final class KeepSprintModule extends Module {

    /** 原版攻击减速系数。 */
    private static final double VANILLA_ATTACK_SLOWDOWN = 0.6;

    private final NumberValue slowDownVelocity = add(new NumberValue(
            "Hit Slow Down During Velocity", 0.6, 0.0, 1.0, 0.05));
    private final NumberValue slowDownNormal = add(new NumberValue(
            "Hit Slow Down Normal", 0.6, 0.0, 1.0, 0.05));
    private final NumberValue bufferDecrease = add(new NumberValue(
            "Buffer Decrease", 1.0, 0.1, 10.0, 0.1));
    private final NumberValue maxBuffer = add(new NumberValue(
            "Max Buffer", 5.0, 1.0, 10.0, 1.0));
    private final BooleanValue sprintSlowDownVelocity = add(new BooleanValue(
            "Velocity Hit Sprint", true));
    private final BooleanValue sprintSlowDownNormal = add(new BooleanValue(
            "Normal Hit Sprint", true));
    private final BooleanValue bufferAbuse = add(new BooleanValue("Buffer Abuse", false));
    private final BooleanValue onlyInAir = add(new BooleanValue("Only In Air", false));

    private volatile EventBus.Subscription attackSubscription;
    private final BufferGate buffer = new BufferGate();
    private double preAttackMotionX;
    private double preAttackMotionZ;
    private boolean attackStateCaptured;

    public KeepSprintModule() {
        bufferDecrease.visibleWhen(bufferAbuse::get);
        maxBuffer.visibleWhen(bufferAbuse::get);
    }

    @Override
    public String name() {
        return "KeepSprint";
    }

    @Override
    public Category category() {
        return Category.COMBAT;
    }

    @Override
    protected void onEnable() {
        resetState();
        subscribe();
    }

    @Override
    protected void onDisable() {
        resetState();
        unsubscribe();
    }

    @Override
    public void onTick() {
        synchronized (this) {
            if (isEnabled() && attackSubscription == null) {
                subscribe();
            }
        }
    }

    private void subscribe() {
        NocturneClient client = NocturneClient.get();
        if (client == null || attackSubscription != null) {
            return;
        }
        try {
            attackSubscription = client.events().subscribe(AttackEvent.class, this::onAttack);
        } catch (Throwable t) {
            attackSubscription = null;
        }
    }

    private void unsubscribe() {
        EventBus.Subscription subscription = attackSubscription;
        attackSubscription = null;
        if (subscription == null) {
            return;
        }
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.events().unsubscribe(subscription);
        }
    }

    private void onAttack(AttackEvent event) {
        if (!event.post) {
            capturePreAttack();
        } else {
            applyPostAttack();
        }
    }

    private void capturePreAttack() {
        attackStateCaptured = false;
        GameBridge bridge = bridge();
        Object player = bridge == null ? null : bridge.player();
        if (player == null) {
            return;
        }
        double[] motion = readHorizontalMotion(bridge, player);
        if (motion == null) {
            return;
        }
        preAttackMotionX = motion[0];
        preAttackMotionZ = motion[1];
        attackStateCaptured = true;
    }

    private void applyPostAttack() {
        if (!attackStateCaptured) {
            return;
        }
        attackStateCaptured = false;
        GameBridge bridge = bridge();
        Object player = bridge == null ? null : bridge.player();
        if (player == null) {
            return;
        }
        Number health = number(bridge.callMapped(player, ClassType.LOCAL_PLAYER, "getHealth"));
        if (health == null || health.doubleValue() <= 0.0) {
            return;
        }
        double[] motion = readHorizontalMotion(bridge, player);
        if (motion == null
                || Double.compare(motion[0], preAttackMotionX * VANILLA_ATTACK_SLOWDOWN) != 0
                || Double.compare(motion[1], preAttackMotionZ * VANILLA_ATTACK_SLOWDOWN) != 0) {
            return;
        }
        if (onlyInAir.get() && Boolean.TRUE.equals(
                bridge.readField(player, ClassType.ENTITY, "onGround"))) {
            return;
        }
        if (!shouldApplyConfiguredSlowdown()) {
            return;
        }
        Number hurtTime = number(bridge.readField(player, ClassType.LIVING_ENTITY, "hurtTime"));
        boolean takingVelocity = hurtTime != null && hurtTime.intValue() > 0;
        double slowDown = takingVelocity ? slowDownVelocity.get() : slowDownNormal.get();
        boolean sprint = takingVelocity ? sprintSlowDownVelocity.get() : sprintSlowDownNormal.get();
        if (writeHorizontalMotion(bridge, player, preAttackMotionX * slowDown,
                preAttackMotionZ * slowDown)) {
            bridge.callMapped(player, ClassType.ENTITY, "setSprinting", sprint);
        }
    }

    private boolean shouldApplyConfiguredSlowdown() {
        return buffer.allow(bufferAbuse.get(), maxBuffer.get(), bufferDecrease.get());
    }

    /** 读取水平动量；现代走 getDeltaMovement，旧版退回 motionX/motionZ。 */
    private static double[] readHorizontalMotion(GameBridge bridge, Object player) {
        Object delta = bridge.callMapped(player, ClassType.ENTITY, "getDeltaMovement");
        if (delta != null) {
            Number x = number(bridge.readField(delta, ClassType.VEC3, "x"));
            Number z = number(bridge.readField(delta, ClassType.VEC3, "z"));
            return x == null || z == null ? null : new double[]{x.doubleValue(), z.doubleValue()};
        }
        Number x = number(bridge.readField(player, ClassType.ENTITY, "motionX"));
        Number z = number(bridge.readField(player, ClassType.ENTITY, "motionZ"));
        return x == null || z == null ? null : new double[]{x.doubleValue(), z.doubleValue()};
    }

    /** 写入水平动量；旧版字段可写时保持 motionY 不变，现代则调用三分量 setter。 */
    private static boolean writeHorizontalMotion(GameBridge bridge, Object player,
                                                 double x, double z) {
        if (bridge.writeField(player, ClassType.ENTITY, "motionX", x)) {
            return bridge.writeField(player, ClassType.ENTITY, "motionZ", z);
        }
        Object delta = bridge.callMapped(player, ClassType.ENTITY, "getDeltaMovement");
        if (delta == null) {
            return false;
        }
        Number y = number(bridge.readField(delta, ClassType.VEC3, "y"));
        if (y == null) {
            return false;
        }
        bridge.callMapped(player, ClassType.ENTITY, "setDeltaMovement", x, y.doubleValue(), z);
        return true;
    }

    private void resetState() {
        preAttackMotionX = 0.0;
        preAttackMotionZ = 0.0;
        buffer.reset();
        attackStateCaptured = false;
    }

    /** 与上游一致的攻击计数缓冲：先允许 N 次，再逐步回落到 0，回落期间拒绝减速。 */
    static final class BufferGate {
        private double combo;
        private boolean resetting;

        boolean allow(boolean abuse, double maxBuffer, double decrease) {
            if (!abuse) {
                combo = 0.0;
                return true;
            }
            if (combo < maxBuffer && !resetting) {
                combo += 1.0;
                return true;
            }
            if (combo > 0.0) {
                combo = Math.max(0.0, combo - decrease);
                resetting = true;
                return false;
            }
            resetting = false;
            return true;
        }

        void reset() {
            combo = 0.0;
            resetting = false;
        }
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
