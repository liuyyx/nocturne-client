package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.ScrollEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.CameraOverride;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;
import java.util.UUID;

/**
 * 将移动输入用于独立相机，不改变玩家坐标。
 * 上游降级：Stay Sneaking 无法在不发送潜行输入时保持服务端状态；Show Hands 缺少手部隐藏钩子。
 */
public final class FreeCameraModule extends GameModule implements CameraOverride {
    private final NumberValue speed = add(new NumberValue("Speed", 1, 0, 10, .1));
    private final NumberValue scrollSensitivity = add(new NumberValue("Speed Scroll Sensitivity", 0, 0, 2, .1));
    private final BooleanValue damage = add(new BooleanValue("Toggle On Damage", false));
    private final BooleanValue death = add(new BooleanValue("Toggle On Death", false));
    private final BooleanValue log = add(new BooleanValue("Toggle On Log", true));
    private final BooleanValue reload = add(new BooleanValue("Reload Chunks", true));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", false));
    private final BooleanValue stationary = add(new BooleanValue("Static", true));
    private volatile boolean initialized;
    private volatile double x, y, z;
    private volatile float yaw, pitch;
    private float previousYaw, previousPitch;
    private volatile float forward, strafe;
    private volatile boolean jump, sneak;
    private int lastHurt;
    private boolean warned;

    public FreeCameraModule() { super("Free Camera", Category.RENDER); }

    @Override protected void onStart() {
        initialized = false; forward = 0; strafe = 0; jump = false; sneak = false; lastHurt = 0; warned = false;
        listen(MovementInputEvent.class, this::input);
        listen(ScrollEvent.class, this::scroll);
        listen(PacketEvent.class, this::packet);
        listen(WorldChangeEvent.class, event -> { initialized = false; forward = 0; strafe = 0; jump = false; sneak = false; });
        GameBridge bridge = bridge();
        if (bridge != null && reload.get()) bridge.reloadChunks();
    }

    @Override protected void onStop() {
        initialized = false; forward = 0; strafe = 0; jump = false; sneak = false;
        GameBridge bridge = bridge();
        if (bridge != null && reload.get()) bridge.reloadChunks();
    }

    private void input(MovementInputEvent event) {
        if (!initialized || !GameplayHook.isWired("cameraAligned")) return;
        forward = event.forward; strafe = event.strafe; jump = event.jump; sneak = event.sneak;
        event.forward = 0; event.strafe = 0; event.jump = false; event.sneak = false; event.sprint = false;
    }

    private void scroll(ScrollEvent event) {
        if (initialized && scrollSensitivity.get() > 0) speed.set(speed.get() + event.delta * scrollSensitivity.get());
    }

    private void packet(PacketEvent event) {
        GameBridge bridge = bridge();
        if (!log.get() || event.direction != PacketEvent.Direction.RECEIVE || bridge == null
                || !bridge.isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_INFO_REMOVE)) return;
        UUID uuid = bridge.entityUuid(bridge.player());
        Object ids = bridge.readField(event.packet, ClassType.CLIENTBOUND_PLAYER_INFO_REMOVE, "profileIds");
        if (uuid == null) return;
        if (ids instanceof Iterable) {
            for (Object id : (Iterable<?>) ids) if (uuid.equals(id)) { setEnabled(false); return; }
        } else if (ids instanceof Object[]) {
            for (Object id : (Object[]) ids) if (uuid.equals(id)) { setEnabled(false); return; }
        } else if (uuid.equals(ids)) setEnabled(false);
    }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        if (!GameplayHook.isWired("cameraAligned")) {
            if (!warned) { warned = true; System.out.println("[nocturne] Free Camera: camera aligned hook unavailable on this version"); }
            return;
        }
        Object player = bridge.player();
        if (player == null) { initialized = false; return; }
        int hurt = bridge.hurtTime(player);
        if ((damage.get() && hurt > lastHurt) || (death.get() && bridge.healthOrNull(player) != null && bridge.health(player) <= 0)) {
            setEnabled(false); return;
        }
        lastHurt = hurt;
        if (!initialized) return;
        PlayerState state = state();
        if (state != null && state.present()) {
            if (!stationary.get()) {
                if (rotate.get()) {
                    yaw += state.yaw() - previousYaw;
                    pitch = Math.max(-90, Math.min(90, pitch + state.pitch() - previousPitch));
                } else {
                    yaw = state.yaw();
                    pitch = state.pitch();
                }
            }
            previousYaw = state.yaw(); previousPitch = state.pitch();
        }
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        double pitchRadians = Math.toRadians(pitch), horizontal = Math.cos(pitchRadians);
        double v = speed.get();
        x += (-sin * forward * horizontal + cos * strafe) * v;
        z += (cos * forward * horizontal + sin * strafe) * v;
        y += ((jump ? 1 : 0) - (sneak ? 1 : 0) - Math.sin(pitchRadians) * forward) * v;
    }

    @Override public void applyCamera(Object camera) {
        GameBridge bridge = bridge();
        if (!isEnabled() || bridge == null || camera == null || !GameplayHook.isWired("cameraAligned")
                || bridge.mapping().isMemberAbsent(ClassType.CAMERA, "setPosition")
                || bridge.mapping().isMemberAbsent(ClassType.CAMERA, "setRotation")) return;
        if (!initialized) {
            Object pos = bridge.readField(camera, ClassType.CAMERA, "position");
            Object px = bridge.readField(pos, ClassType.VEC3, "x"), py = bridge.readField(pos, ClassType.VEC3, "y"), pz = bridge.readField(pos, ClassType.VEC3, "z");
            Object yr = bridge.readField(camera, ClassType.CAMERA, "yRot"), xr = bridge.readField(camera, ClassType.CAMERA, "xRot");
            PlayerState state = state();
            if (!(px instanceof Number) || !(py instanceof Number) || !(pz instanceof Number)
                    || !(yr instanceof Number) || !(xr instanceof Number) || state == null || !state.present()) return;
            x = ((Number) px).doubleValue(); y = ((Number) py).doubleValue(); z = ((Number) pz).doubleValue();
            yaw = ((Number) yr).floatValue(); pitch = ((Number) xr).floatValue();
            previousYaw = state.yaw(); previousPitch = state.pitch(); initialized = true;
        }
        Object next = bridge.construct(ClassType.VEC3, x, y, z);
        if (next == null
                || !bridge.callMappedStatus(camera, ClassType.CAMERA, "setPosition", next)
                || !bridge.callMappedStatus(camera, ClassType.CAMERA, "setRotation", yaw, pitch)) initialized = false;
    }
}
