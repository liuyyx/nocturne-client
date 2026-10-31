package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.MotionEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;

/**
 * 下落时把提交给服务端的 onGround 置为 true，避免落地伤害。
 *
 * <p>只改 Motion 提交值，不伪造位置或取消移动。创造/飞行、鞘翅滑翔时不介入；
 * 上游的 AntiCheat 双包模式尚未移植，因此不提供该开关。
 */
public final class NoFallModule extends Module {

    private static final double TRIGGER_DISTANCE = 2.224;

    private volatile EventBus.Subscription motionSubscription;

    @Override
    public String name() {
        return "NoFall";
    }

    @Override
    public Category category() {
        return Category.PLAYER;
    }

    @Override
    protected void onEnable() {
        subscribe();
    }

    @Override
    protected void onDisable() {
        unsubscribe();
    }

    @Override
    public void onTick() {
        synchronized (this) {
            if (isEnabled() && motionSubscription == null) {
                subscribe();
            }
        }
    }

    private void subscribe() {
        NocturneClient client = NocturneClient.get();
        if (client == null || motionSubscription != null) {
            return;
        }
        try {
            motionSubscription = client.events().subscribe(MotionEvent.class, this::onMotion);
        } catch (Throwable t) {
            motionSubscription = null;
        }
    }

    private void unsubscribe() {
        EventBus.Subscription subscription = motionSubscription;
        motionSubscription = null;
        if (subscription == null) {
            return;
        }
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.events().unsubscribe(subscription);
        }
    }

    private void onMotion(MotionEvent event) {
        if (!event.pre || event.onGround) {
            return;
        }
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object player = bridge.player();
        Object minecraft = bridge.minecraft();
        Object level = minecraft == null ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
        if (player == null || level == null) {
            return;
        }
        boolean gliding = Boolean.TRUE.equals(
                bridge.callMapped(player, ClassType.LIVING_ENTITY, "isFallFlying"));
        BooleanState abilities = abilities(bridge, player);
        Number fallDistance = number(bridge.readField(player, ClassType.ENTITY, "fallDistance"));
        Number motionY = verticalMotion(bridge, player);
        if (fallDistance == null || motionY == null) {
            return;
        }
        if (shouldOverride(gliding, abilities.flying, abilities.instabuild,
                fallDistance.doubleValue(), motionY.doubleValue())) {
            event.onGround = true;
        }
    }

    /** 纯条件判断：滑翔、创造/飞行、下落距离与垂直速度。 */
    static boolean shouldOverride(boolean fallFlying, boolean flying, boolean instabuild,
                                  double fallDistance, double motionY) {
        return !fallFlying && !flying && !instabuild
                && fallDistance > TRIGGER_DISTANCE && motionY < 0.0;
    }

    private static BooleanState abilities(GameBridge bridge, Object player) {
        Object abilities = bridge.callMapped(player, ClassType.PLAYER, "getAbilities");
        if (abilities == null) {
            abilities = bridge.readField(player, ClassType.PLAYER, "capabilities");
        }
        if (abilities == null) {
            return new BooleanState(false, false);
        }
        return new BooleanState(
                Boolean.TRUE.equals(bridge.readField(abilities, ClassType.ABILITIES, "flying")),
                Boolean.TRUE.equals(bridge.readField(abilities, ClassType.ABILITIES, "instabuild")));
    }

    private static final class BooleanState {
        private final boolean flying;
        private final boolean instabuild;

        private BooleanState(boolean flying, boolean instabuild) {
            this.flying = flying;
            this.instabuild = instabuild;
        }
    }

    private static Number verticalMotion(GameBridge bridge, Object player) {
        Object delta = bridge.callMapped(player, ClassType.ENTITY, "getDeltaMovement");
        if (delta != null) {
            return number(bridge.readField(delta, ClassType.VEC3, "y"));
        }
        return number(bridge.readField(player, ClassType.ENTITY, "motionY"));
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
