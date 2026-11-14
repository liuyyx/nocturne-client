package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.CameraOverride;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 第三人称距离和相机位置平滑。
 * 上游降级：Better Bob View 需要原版 bob 钩子，故不提供。
 */
public final class CameraClipModule extends GameModule implements CameraOverride {
    private final NumberValue distance = add(new NumberValue("Distance", 3.5, 1, 20, .5));
    private final BooleanValue action = add(new BooleanValue("Action", true));
    private final NumberValue smoothness = add(new NumberValue("Action Smoothness", .3, .1, .95, .01));
    private final NumberValue maxDistance = add(new NumberValue("Action Max Distance", 20, 1, 50, .5));
    private boolean initialized;
    private Object lastCamera;
    private double x, y, z;
    private boolean warned;

    public CameraClipModule() { super("Camera Clip", Category.RENDER); }

    @Override protected void onStart() {
        initialized = false;
        lastCamera = null;
        warned = false;
        listen(WorldChangeEvent.class, event -> { initialized = false; lastCamera = null; });
    }

    @Override protected void onStop() { initialized = false; lastCamera = null; }

    /** 由 cameraZoom 转发；调用方须仅在模块启用时调用。 */
    public float zoom(float original) {
        return GameplayHook.isWired("cameraZoom") ? distance.asFloat() : original;
    }

    @Override public void onTick() {
        if (!warned && (!GameplayHook.isWired("cameraZoom") || !GameplayHook.isWired("cameraAligned"))) {
            warned = true;
            System.out.println("[nocturne] Camera Clip: camera hook unavailable on this version");
        }
    }

    @Override public void applyCamera(Object camera) {
        GameBridge bridge = bridge();
        if (!isEnabled() || bridge == null || camera == null || !GameplayHook.isWired("cameraAligned")
                || !action.get() || bridge.mapping().isMemberAbsent(ClassType.CAMERA, "setPosition")) {
            initialized = false;
            return;
        }
        Object pos = bridge.readField(camera, ClassType.CAMERA, "position");
        Object ox = bridge.readField(pos, ClassType.VEC3, "x");
        Object oy = bridge.readField(pos, ClassType.VEC3, "y");
        Object oz = bridge.readField(pos, ClassType.VEC3, "z");
        if (!(ox instanceof Number) || !(oy instanceof Number) || !(oz instanceof Number)) {
            initialized = false;
            return;
        }
        double tx = ((Number) ox).doubleValue(), ty = ((Number) oy).doubleValue(), tz = ((Number) oz).doubleValue();
        if (!initialized || lastCamera != camera) {
            x = tx; y = ty; z = tz; initialized = true; lastCamera = camera;
            return;
        }
        double dx = tx - x, dy = ty - y, dz = tz - z;
        double factor = smoothness.get() * (1 - Math.exp(-Math.sqrt(dx * dx + dy * dy + dz * dz) / maxDistance.get()));
        double nx = x + dx * factor, ny = y + dy * factor, nz = z + dz * factor;
        Object next = bridge.construct(ClassType.VEC3, nx, ny, nz);
        if (next != null && bridge.callMappedStatus(camera, ClassType.CAMERA, "setPosition", next)) {
            x = nx; y = ny; z = nz;
        } else {
            initialized = false;
        }
    }
}
