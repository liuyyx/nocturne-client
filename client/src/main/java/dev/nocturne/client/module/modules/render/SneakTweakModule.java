package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.CameraOverride;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 替换潜行相机眼高；相机 setup 后写入，位置从下一次 setup 开始使用该高度。
 * 上游降级：Smoothing 与 Speed Percentage 依赖 Camera#tick 常量钩子，不提供。
 */
public final class SneakTweakModule extends GameModule implements CameraOverride {
    private final ModeValue height = add(new ModeValue("Sneaking Eye Height", "Default", "Default", "Custom", "Pre_1_14", "Pre_1_9"));
    private final NumberValue custom = add(new NumberValue("Custom Sneaking Eye Height", 1.27, 0, 1.8, .01));
    private final BooleanValue thirdPerson = add(new BooleanValue("Third Person Eye Height", false));
    private Object changedCamera;
    private Object originalHeight, originalOldHeight;
    private boolean warned;

    public SneakTweakModule() { super("Sneak Tweak", Category.RENDER); }

    @Override protected void onStart() {
        warned = false;
        listen(WorldChangeEvent.class, event -> restore());
    }
    @Override protected void onStop() { restore(); }

    @Override public void onTick() {
        if (!warned && !GameplayHook.isWired("cameraAligned")) {
            warned = true;
            System.out.println("[nocturne] Sneak Tweak: camera aligned hook unavailable on this version");
        }
    }

    private void restore() {
        GameBridge bridge = bridge();
        if (bridge != null && changedCamera != null) {
            bridge.writeField(changedCamera, ClassType.CAMERA, "eyeHeight", originalHeight);
            bridge.writeField(changedCamera, ClassType.CAMERA, "eyeHeightOld", originalOldHeight);
        }
        changedCamera = null; originalHeight = null; originalOldHeight = null;
    }

    @Override public void applyCamera(Object camera) {
        GameBridge bridge = bridge();
        if (!isEnabled() || bridge == null || camera == null || !GameplayHook.isWired("cameraAligned")
                || bridge.mapping().isMemberAbsent(ClassType.CAMERA, "eyeHeight")
                || bridge.mapping().isMemberAbsent(ClassType.CAMERA, "eyeHeightOld")
                || height.is("Default") || (!thirdPerson.get() && bridge.perspective() != 0)
                || !Boolean.TRUE.equals(bridge.callMapped(bridge.player(), ClassType.ENTITY, "isCrouching"))) {
            restore();
            return;
        }
        if (changedCamera != camera) {
            restore();
            Object current = bridge.readField(camera, ClassType.CAMERA, "eyeHeight");
            Object old = bridge.readField(camera, ClassType.CAMERA, "eyeHeightOld");
            if (!(current instanceof Number) || !(old instanceof Number)) return;
            changedCamera = camera; originalHeight = current; originalOldHeight = old;
        }
        float value = height.is("Custom") ? custom.asFloat() : height.is("Pre_1_14") ? 1.42f : 1.54f;
        if (!bridge.writeField(camera, ClassType.CAMERA, "eyeHeight", value)
                || !bridge.writeField(camera, ClassType.CAMERA, "eyeHeightOld", value)) restore();
    }
}
