package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 低优先级朝向请求；暂停或关闭后由仲裁器的 200ms TTL 自然失效。
 * 上游降级：仲裁器始终限制俯仰角，故不提供无效的 Safe Pitch 开关；无独立 pre/post 交互事件，
 * 用既有 {@code AttackEvent}/{@code UseItemEvent} 近似判定动作瞬间。
 */
public final class DerpModule extends GameModule {
    private final ModeValue yawMode = add(new ModeValue("Yaw", "Random", "Static", "Offset", "Random", "Jitter", "Spin"));
    private final ModeValue pitchMode = add(new ModeValue("Pitch", "Random", "Static", "Offset", "Random"));
    private final BooleanValue notSprint = add(new BooleanValue("Not During Sprint", true));
    private final NumberValue yawValue = add(new NumberValue("Yaw Value", 0, -180, 180, 1));
    private final NumberValue yawOffset = add(new NumberValue("Yaw Offset", 0, -180, 180, 1));
    private final NumberValue forward = add(new NumberValue("Forward Ticks", 2, 0, 100, 1));
    private final NumberValue backward = add(new NumberValue("Backward Ticks", 2, 0, 100, 1));
    private final NumberValue spinSpeed = add(new NumberValue("Spin Speed", 50, -70, 70, 1));
    private final NumberValue pitchValue = add(new NumberValue("Pitch Value", -90, -180, 180, 1));
    private final NumberValue pitchOffset = add(new NumberValue("Pitch Offset", 0, -180, 180, 1));
    private long actionAt;
    private int yawRemaining, pitchRemaining, jitterTick;
    private float randomYaw, randomPitch, spin;
    private boolean warned;

    public DerpModule() { super("Derp", Category.RENDER); }

    @Override protected void onStart() {
        reset();
        listen(AttackEvent.class, event -> { if (!event.post) actionAt = System.nanoTime(); });
        listen(UseItemEvent.class, event -> { if (!event.post) actionAt = System.nanoTime(); });
        listen(WorldChangeEvent.class, event -> reset());
    }

    private void reset() { actionAt = 0; yawRemaining = pitchRemaining = jitterTick = 0; spin = 0; }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || state == null || !state.present() || client() == null) return;
        Object player = state.player();
        Object using = bridge.callMapped(player, ClassType.LIVING_ENTITY, "isUsingItem");
        if (!(using instanceof Boolean)) using = bridge.callMapped(player, ClassType.PLAYER, "isUsingItem");
        Object sprint = notSprint.get() ? bridge.callMapped(player, ClassType.ENTITY, "isSprinting") : Boolean.FALSE;
        if (!(using instanceof Boolean) || !(sprint instanceof Boolean)) {
            if (!warned) {
                warned = true;
                System.out.println("[nocturne] Derp: using-item or sprint state unavailable; rotation suspended");
            }
            return;
        }
        if (Boolean.TRUE.equals(using) || Boolean.TRUE.equals(sprint)
                || (actionAt != 0 && System.nanoTime() - actionAt < 50_000_000L)) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (--yawRemaining <= 0) { randomYaw = random.nextInt(-180, 180); yawRemaining = random.nextInt(10, 31); }
        if (--pitchRemaining <= 0) { randomPitch = random.nextInt(-90, 91); pitchRemaining = random.nextInt(10, 31); }
        float yaw;
        if (yawMode.is("Static")) yaw = yawValue.asFloat();
        else if (yawMode.is("Offset")) yaw = state.yaw() + yawOffset.asFloat();
        else if (yawMode.is("Jitter")) {
            int a = forward.asInt(), b = backward.asInt(), cycle = a + b;
            yaw = state.yaw() + (cycle == 0 || jitterTick % cycle < a ? 0f : 180f);
            if (cycle > 0) jitterTick = (jitterTick + 1) % cycle;
        } else if (yawMode.is("Spin")) { spin += spinSpeed.asFloat(); spin %= 360f; yaw = spin; }
        else yaw = randomYaw;
        float pitch = pitchMode.is("Static") ? pitchValue.asFloat()
                : pitchMode.is("Offset") ? state.pitch() + pitchOffset.asFloat() : randomPitch;
        client().rotations().request(yaw, pitch, 0, 200_000_000L);
    }
}
