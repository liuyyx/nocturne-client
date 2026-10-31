package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 把准星平滑拉向当前最优目标。
 *
 * <p>旋转只经过 {@link RotationController}，不直接写玩家字段；目标筛选复用
 * {@link TargetManager}，不在这里另造一套角度/距离规则。
 */
public final class AimAssistModule extends Module {

    private static final String ANGLE = "Yaw";
    private static final String DISTANCE = "Distance";
    private static final String HEALTH = "Health";
    private static final int PRIORITY = 10;

    private final BooleanValue requireMouseDown = add(new BooleanValue("Require mouse down", true));
    private final BooleanValue aimVertically = add(new BooleanValue("Aim vertically", false));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue invisibles = add(new BooleanValue("Invisibles", false));
    private final NumberValue horizontalSpeed =
            add(new NumberValue("Horizontal speed", 5.0, 1.0, 10.0, 0.5));
    private final NumberValue verticalSpeed =
            add(new NumberValue("Vertical speed", 5.0, 1.0, 10.0, 0.5));
    private final NumberValue maxAngle =
            add(new NumberValue("Max angle", 180.0, 1.0, 360.0, 1.0));
    private final NumberValue distance =
            add(new NumberValue("Distance", 5.0, 1.0, 8.0, 0.1));
    private final ModeValue targetMode =
            add(new ModeValue("Target mode", ANGLE, ANGLE, DISTANCE, HEALTH));

    private final TargetManager.Options options = new TargetManager.Options();
    private volatile EventBus.Subscription mouseSubscription;
    private volatile boolean leftHeld;
    /** 上一次实际写入的朝向，避免每 tick 都从玩家字段重新起步。 */
    private float appliedYaw;
    private float appliedPitch;
    private boolean applied;

    @Override
    public String name() {
        return "AimAssist";
    }

    @Override
    public Category category() {
        return Category.COMBAT;
    }

    @Override
    protected void onEnable() {
        leftHeld = false;
        applied = false;
        subscribe();
    }

    @Override
    protected void onDisable() {
        unsubscribe();
        leftHeld = false;
        applied = false;
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.rotations().release(PRIORITY);
        }
    }

    @Override
    public void onTick() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        RotationController rotations = client.rotations();
        if (requireMouseDown.get() && !leftHeld) {
            rotations.release(PRIORITY);
            applied = false;
            return;
        }
        PlayerState state = client.actions() == null
                ? PlayerState.empty() : client.actions().readState();
        if (!state.present()) {
            rotations.release(PRIORITY);
            applied = false;
            return;
        }
        configureOptions();
        TargetManager.Target target = client.targets().find(client, options);
        if (target == null) {
            rotations.release(PRIORITY);
            applied = false;
            return;
        }
        double dx = target.x() - state.x();
        double dz = target.z() - state.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = RotationController.yawTo(dx, dz);
        float targetPitch = aimVertically.get()
                ? RotationController.pitchTo(horizontal,
                        target.aimY() - (state.y() + 1.62))
                : state.pitch();
        if (!applied) {
            appliedYaw = state.yaw();
            appliedPitch = state.pitch();
            applied = true;
        }
        appliedYaw = RotationController.approach(appliedYaw, targetYaw,
                (float) horizontalSpeed.get().doubleValue());
        appliedPitch = RotationController.approach(appliedPitch, targetPitch,
                (float) verticalSpeed.get().doubleValue());
        rotations.request(appliedYaw, appliedPitch, PRIORITY, 150_000_000L);
    }

    private void configureOptions() {
        options.range = distance.get();
        options.maxAngle = maxAngle.get();
        options.players = players.get();
        options.mobs = mobs.get();
        options.invisibles = invisibles.get();
        options.sort = targetMode.is(DISTANCE) ? TargetManager.Sort.DISTANCE
                : targetMode.is(HEALTH) ? TargetManager.Sort.HEALTH
                : TargetManager.Sort.ANGLE;
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
