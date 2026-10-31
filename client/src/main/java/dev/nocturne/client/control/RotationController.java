package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.MotionEvent;

/**
 * 唯一的旋转仲裁器。
 *
 * <p>模块只登记“希望朝向哪”，不直接改玩家字段；本类按优先级持有当前请求，并在
 * {@link MotionEvent#pre} 里把角度写入即将提交的位置包。该路径同时改变客户端视角，
 * 因此不宣称“服务端静默旋转”——需要真正分离时必须另开只改包的钩子。
 *
 * <p>请求带 TTL：模块每个 tick 续期一次，模块关闭或停止续期后自动失效，避免旋转卡死。
 */
public final class RotationController {

    private static final long MAX_TTL_NANOS = 2_000_000_000L;

    private volatile Request request;
    private volatile EventBus.Subscription subscription;

    /** 在客户端事件总线上安装 Motion 钩子；重复调用无副作用。 */
    public void install(NocturneClient client) {
        if (client == null || subscription != null) {
            return;
        }
        try {
            subscription = client.events().subscribe(MotionEvent.class, this::onMotion);
        } catch (Throwable t) {
            subscription = null;
        }
    }

    /** 停止接收 Motion 事件并清空请求。 */
    public void uninstall() {
        EventBus.Subscription current = subscription;
        subscription = null;
        request = null;
        if (current != null) {
            NocturneClient client = NocturneClient.get();
            if (client != null) {
                client.events().unsubscribe(current);
            }
        }
    }

    /**
     * 登记一个有期限的朝向请求。
     *
     * @param yaw      目标 yaw，自动归一化到 [-180,180)
     * @param pitch    目标 pitch，钳制到 [-90,90]
     * @param priority 数值越大越优先；同优先级后写入者覆盖
     * @param ttlNanos 有效期，钳制到 1ms..2s
     */
    public void request(float yaw, float pitch, int priority, long ttlNanos) {
        if (!finite(yaw) || !finite(pitch)) {
            return;
        }
        long ttl = Math.max(1_000_000L, Math.min(MAX_TTL_NANOS, ttlNanos));
        Request previous = request;
        if (previous != null && previous.priority > priority && previous.expiresNanos > System.nanoTime()) {
            return;
        }
        request = new Request(wrap(yaw), clampPitch(pitch), priority,
                System.nanoTime() + ttl);
        applyToPlayer();
    }

    /** 把当前请求写到本地玩家；没有动作层时由 Motion 钩子兜底。 */
    private void applyToPlayer() {
        Request current = request;
        NocturneClient client = NocturneClient.get();
        if (current == null || client == null || client.actions() == null) {
            return;
        }
        client.actions().setRotation(current.yaw, current.pitch);
    }

    /** 仅释放自己持有的请求；高优先级请求不会被低优先级模块清掉。 */
    public void release(int priority) {
        Request current = request;
        if (current != null && current.priority == priority) {
            request = null;
        }
    }

    /** 是否已有未过期请求。 */
    public boolean active() {
        Request current = request;
        long now = System.nanoTime();
        if (current == null || current.expiresNanos <= now) {
            request = null;
            return false;
        }
        return true;
    }

    /** 当前请求的 yaw；无请求时返回 {@code base}。 */
    public float yaw(float base) {
        return active() ? request.yaw : base;
    }

    /** 当前请求的 pitch；无请求时返回 {@code base}。 */
    public float pitch(float base) {
        return active() ? request.pitch : base;
    }

    private void onMotion(MotionEvent event) {
        if (event == null || !event.pre) {
            return;
        }
        Request current = request;
        long now = System.nanoTime();
        if (current == null || current.expiresNanos <= now) {
            return;
        }
        event.yaw = current.yaw;
        event.pitch = current.pitch;
    }

    /** 归一化到 [-180,180)。 */
    public static float wrap(float yaw) {
        float value = yaw % 360.0f;
        if (value >= 180.0f) {
            value -= 360.0f;
        } else if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }

    /** 游戏可接受的俯仰范围。 */
    public static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    /**
     * 以受限步长逼近目标角；用于模块自己的平滑，不改变控制器语义。
     *
     * @param current 当前角
     * @param target  目标角
     * @param step    本 tick 允许的最大角度变化
     */
    public static float approach(float current, float target, float step) {
        if (!finite(current) || !finite(target) || step <= 0.0f) {
            return target;
        }
        float delta = wrap(target - current);
        if (Math.abs(delta) <= step) {
            return wrap(target);
        }
        return wrap(current + Math.signum(delta) * step);
    }

    /** 由“从眼睛指向目标”的向量计算 Minecraft 的 yaw。 */
    public static float yawTo(double dx, double dz) {
        return wrap((float) (Math.toDegrees(Math.atan2(-dx, dz))));
    }

    /** 由水平距离与高度差计算 Minecraft 的 pitch。 */
    public static float pitchTo(double horizontal, double dy) {
        return clampPitch((float) (-Math.toDegrees(Math.atan2(dy, Math.max(1.0e-6, horizontal)))));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** 一次请求的不可变快照。 */
    private static final class Request {
        private final float yaw;
        private final float pitch;
        private final int priority;
        private final long expiresNanos;

        private Request(float yaw, float pitch, int priority, long expiresNanos) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.priority = priority;
            this.expiresNanos = expiresNanos;
        }
    }
}
