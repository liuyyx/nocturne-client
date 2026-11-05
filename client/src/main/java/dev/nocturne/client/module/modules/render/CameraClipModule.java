package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Camera Clip：第三人称距离与 Action 平滑相机距离。 */
public final class CameraClipModule extends GameModule {
    private final NumberValue distance = add(new NumberValue("Distance", 3.5, 1, 20, 0.5));
    private final BooleanValue action = add(new BooleanValue("Action", true));
    private final NumberValue actionSmoothness = add(new NumberValue("Action Smoothness", 0.3, 0.1, 0.95, 0.01));
    private final NumberValue actionMaxDistance = add(new NumberValue("Action Max Distance", 20, 1, 50, 0.5));
    private final BooleanValue betterBobView = add(new BooleanValue("Better Bob View", true));
    private float current = -1.0f;

    public CameraClipModule() {
        super("Camera Clip", Category.RENDER);
    }

    public float zoom(float original) {
        float target = Math.min(distance.asFloat(), actionMaxDistance.asFloat());
        if (current < 0.0f || !action.get()) {
            current = original;
        }
        float smoothness = betterBobView.get() ? actionSmoothness.asFloat() : 1.0f;
        current += (target - current) * smoothness;
        return current;
    }

    @Override
    protected void onStop() {
        current = -1.0f;
    }
}
