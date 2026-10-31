package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;
import java.util.Random;

/**
 * 对范围内目标按固定攻击频率执行真实攻击动作。
 *
 * <p>目标选择与 AimAssist 共用 {@link TargetManager}；本模块不做旋转，也不伪造点击——
 * 每次攻击都走动作层绑定的 {@code MultiPlayerGameMode#attack}。
 */
public final class KillAuraModule extends Module {

    private static final String DISTANCE = "Distance";
    private static final String ANGLE = "Yaw";
    private static final String HEALTH = "Health";

    private final NumberValue swingRange =
            add(new NumberValue("Swing range", 4.0, 0.0, 6.0, 0.1));
    private final NumberValue attackRange =
            add(new NumberValue("Attack range", 3.5, 0.0, 6.0, 0.1));
    private final BooleanValue requireMouseDown =
            add(new BooleanValue("Require mouse down", false));
    private final NumberValue attacksPerSecond =
            add(new NumberValue("Attacks per Second", 8.0, 1.0, 20.0, 0.5));
    private final NumberValue maxAngle =
            add(new NumberValue("Max angle", 90.0, 1.0, 360.0, 5.0));
    private final NumberValue maxTargets =
            add(new NumberValue("Max targets", 1.0, 1.0, 6.0, 1.0));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue invisibles = add(new BooleanValue("Invisibles", false));
    private final ModeValue targetMode =
            add(new ModeValue("Target mode", DISTANCE, DISTANCE, ANGLE, HEALTH));

    private final TargetManager.Options options = new TargetManager.Options();
    private final Random random = new Random();
    private volatile EventBus.Subscription mouseSubscription;
    private volatile boolean leftHeld;
    private long nextAttackNanos;

    @Override
    public String name() {
        return "KillAura";
    }

    @Override
    public Category category() {
        return Category.COMBAT;
    }

    @Override
    protected void onEnable() {
        leftHeld = false;
        nextAttackNanos = 0L;
        subscribe();
    }

    @Override
    protected void onDisable() {
        unsubscribe();
        leftHeld = false;
        nextAttackNanos = 0L;
    }

    @Override
    public void onTick() {
        if (requireMouseDown.get() && !leftHeld) {
            return;
        }
        NocturneClient client = NocturneClient.get();
        ActionApi actions = client == null ? null : client.actions();
        if (client == null || actions == null || !actions.isAvailable("attack")) {
            return;
        }
        long now = System.nanoTime();
        if (nextAttackNanos != 0L && now < nextAttackNanos) {
            return;
        }
        configureOptions();
        List<TargetManager.Target> targets = client.targets().list(client, options);
        int limit = Math.max(1, maxTargets.get().intValue());
        boolean attacked = false;
        int attempts = 0;
        for (TargetManager.Target target : targets) {
            if (attempts >= limit) {
                break;
            }
            if (target.distance() > attackRange.get()) {
                continue;
            }
            attempts++;
            if (actions.attack(target.entity())) {
                attacked = true;
            }
        }
        if (attacked) {
            nextAttackNanos = now + attackDelayNanos();
        }
    }

    private void configureOptions() {
        options.range = Math.max(swingRange.get(), attackRange.get());
        options.maxAngle = maxAngle.get();
        options.players = players.get();
        options.mobs = mobs.get();
        options.invisibles = invisibles.get();
        options.sort = targetMode.is(ANGLE) ? TargetManager.Sort.ANGLE
                : targetMode.is(HEALTH) ? TargetManager.Sort.HEALTH
                : TargetManager.Sort.DISTANCE;
    }

    private long attackDelayNanos() {
        double aps = Math.max(1.0, attacksPerSecond.get());
        double base = 1_000_000_000.0 / aps;
        // 固定 8 APS 会形成机器节拍；上下 15% 的抖动更接近手动点击。
        return (long) (base * (0.85 + random.nextDouble() * 0.3));
    }

    private void subscribe() {
        NocturneClient client = NocturneClient.get();
        if (client == null || mouseSubscription != null) {
            return;
        }
        try {
            mouseSubscription = client.events().subscribe(MouseButtonEvent.class, this::onMouse);
        } catch (Throwable t) {
            mouseSubscription = null;
        }
    }

    private void onMouse(MouseButtonEvent event) {
        if (event.button == 0) {
            leftHeld = event.pressed;
        }
    }

    private void unsubscribe() {
        EventBus.Subscription subscription = mouseSubscription;
        mouseSubscription = null;
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.events().unsubscribe(subscription);
        }
    }
}
