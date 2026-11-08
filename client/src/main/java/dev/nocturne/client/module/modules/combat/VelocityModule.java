package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Velocity：Cancel/Reduce/Delay/JumpReset 四模式击退控制。 */
public final class VelocityModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Cancel", "Cancel", "Reduce", "Delay", "JumpReset"));
    private final BooleanValue serverMotion = add(new BooleanValue("Server Motion", true));
    private final BooleanValue explosion = add(new BooleanValue("Explosion", true));
    private final BooleanValue explosionOnlyBlock = add(new BooleanValue("Explosion Only Block", false));
    private final BooleanValue noWaterPush = add(new BooleanValue("No Water Push", true));
    private final NumberValue attackCounts = add(new NumberValue("Attack Counts", 1, 1, 5, 1));
    private final NumberValue sprintTicks = add(new NumberValue("Sprint Ticks", 3, 1, 5, 1));
    private final NumberValue maxDelay = add(new NumberValue("Max Delay", 1000, 0, 1000, 50));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", false));
    private final NumberValue delayTicks = add(new NumberValue("Delay Ticks", 3, 1, 5, 1));
    private final BooleanValue delayJumpReset = add(new BooleanValue("Jump Reset", false));
    private final NumberValue jumpChance = add(new NumberValue("Jump Reset Chance", 100, 0, 100, 1));
    private final BooleanValue jumpByHits = add(new BooleanValue("Jump By Received Hits", false));
    private final NumberValue hitsMin = add(new NumberValue("Hits Until Jump Min", 2, 0, 10, 1));
    private final NumberValue hitsMax = add(new NumberValue("Hits Until Jump Max", 2, 0, 10, 1));
    private final BooleanValue jumpByDelay = add(new BooleanValue("Jump By Delay", true));
    private final NumberValue untilMin = add(new NumberValue("Until Jump Min", 0, 0, 20, 1));
    private final NumberValue untilMax = add(new NumberValue("Until Jump Max", 0, 0, 20, 1));
    private double[] pending;
    private int pendingTicks;
    private long pendingAt;
    private int attacks;
    private int hits;
    private int hitsNeeded = 2;
    private int jumpTicks;

    public VelocityModule() {
        super("Velocity", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, this::packet);
        listen(AttackEvent.class, event -> {
            if (!event.post && mode.is("Reduce") && pending != null && ++attacks >= attackCounts.asInt()) {
                applyPending();
                attacks = 0;
                if (swingHand.get()) {
                    actions().swing(false, false);
                }
            }
        });
        listen(MovementInputEvent.class, event -> {
            if (jumpTicks > 0 && mode.is("JumpReset") || delayJumpReset.get() && pending != null) {
                event.jump = true;
                jumpTicks--;
            }
        });
    }

    private void packet(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE) {
            return;
        }
        boolean motion = bridge().isLocalVelocity(event.packet);
        boolean explosionPacket = bridge().isInstance(event.packet, ClassType.CLIENTBOUND_EXPLOSION);
        if (!motion && !explosionPacket || motion && !serverMotion.get()
                || explosionPacket && !explosion.get()) {
            return;
        }
        if (explosionOnlyBlock.get() && explosionPacket && !bridge().isBlockHit(actions().pick(5.0, 1.0f))) {
            return;
        }
        if (mode.is("Cancel")) {
            event.cancelled = true;
            return;
        }
        if (!motion) {
            // 爆炸包没有速度字段，Delay/Reduce 无法延迟真实击退；伪造 {0,0,0} 等于直接吞掉击退。
            if (cancelExplosion(mode.get())) {
                event.cancelled = true;
            }
            return;
        }
        int[] data = bridge().velocityData(event.packet);
        if (data == null) {
            return;
        }
        if (mode.is("JumpReset")) {
            if (EpsilonSupport.RANDOM.nextDouble() * 100.0 < jumpChance.get()) {
                hits++;
                if (jumpByHits.get() && hits < hitsNeeded) {
                    return;
                }
                hits = 0;
                hitsNeeded = EpsilonSupport.randomInt(hitsMin.asInt(), hitsMax.asInt());
                jumpTicks = jumpByDelay.get() ? EpsilonSupport.randomInt(untilMin.asInt(), untilMax.asInt()) + 1 : 1;
            }
            return;
        }
        event.cancelled = true;
        pending = decodeMotion(data);
        pendingTicks = mode.is("Reduce") ? sprintTicks.asInt() : delayTicks.asInt();
        pendingAt = System.currentTimeMillis();
    }

    @Override
    public void onTick() {
        if (pending != null) {
            if (pendingTicks > 0) {
                pendingTicks--;
            }
            if (pendingTicks <= 0 || System.currentTimeMillis() - pendingAt >= maxDelay.asInt()) {
                applyPending();
            }
        }
        cancelWaterPush();
    }

    /** 水体推动是每 tick 叠加的水平位移，必须在无 pending 时也持续抵消。 */
    private void cancelWaterPush() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (!noWaterPush.get() || bridge == null || actions == null) {
            return;
        }
        Object player = bridge.player();
        if (player == null || !bridge.inWater(player)) {
            return;
        }
        double[] motion = waterMotion(bridge.entityVelocity(player)[1]);
        actions.setMotion(motion[0], motion[1], motion[2]);
    }

    /** 击退包速度以 1/8000 缩放；保留原始方向与垂直分量供延迟应用。 */
    static double[] decodeMotion(int[] data) {
        return new double[]{data[1] / 8000.0, data[2] / 8000.0, data[3] / 8000.0};
    }

    /** 只清零水平速度，垂直分量交给游戏（水中下沉/上浮不是推动）。 */
    static double[] waterMotion(double vertical) {
        return new double[]{0.0, vertical, 0.0};
    }

    /** 爆炸包只在 Cancel 模式被取消；Delay/Reduce 不得用零速度冒充延迟。 */
    static boolean cancelExplosion(String mode) {
        return "Cancel".equals(mode);
    }

    private void applyPending() {
        if (pending != null && actions() != null) {
            actions().setMotion(pending[0], pending[1], pending[2]);
            if (mode.is("Reduce")) {
                actions().setSprinting(true);
            }
        }
        pending = null;
    }

    @Override
    protected void onStop() {
        pending = null;
        attacks = 0;
        hits = 0;
        jumpTicks = 0;
    }
}
