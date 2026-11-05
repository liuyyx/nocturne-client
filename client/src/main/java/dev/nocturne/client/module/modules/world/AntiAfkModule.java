package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 按低频随机朝向与跳跃避免挂机检测，可选择保持在原点附近。 */
public final class AntiAfkModule extends GameModule {
    private final NumberValue startDelay = add(new NumberValue("Start delay", 30, 10, 200, 1));
    private final NumberValue frequency = add(new NumberValue("Frequency", 0.2, 0.1, 20, 0.1));
    private final BooleanValue rotation = add(new BooleanValue("Rotation", true));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", false));
    private final NumberValue maxYaw = add(new NumberValue("Max yaw change", 10, 1, 180, 1));
    private final NumberValue maxPitch = add(new NumberValue("Max pitch change", 5, 1, 90, 1));
    private final BooleanValue keepClose = add(new BooleanValue("Keep close", false));
    private final Random random = new Random();
    private long started;
    private long nextAction;
    private double originX;
    private double originZ;
    private boolean releaseJump;

    public AntiAfkModule() {
        super("AntiAFK", Category.WORLD);
    }

    @Override
    protected void onStart() {
        started = System.currentTimeMillis();
        nextAction = 0L;
        PlayerState state = state();
        originX = state.x();
        originZ = state.z();
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        PlayerState state = state();
        if (actions == null || !state.present()) {
            return;
        }
        if (releaseJump) {
            actions.setJumping(false);
            releaseJump = false;
        }
        long now = System.currentTimeMillis();
        if (now - started < startDelay.asInt() * 1000L || now < nextAction) {
            return;
        }
        if (rotation.get()) {
            float yaw = state.yaw() + (float) ((random.nextDouble() * 2.0 - 1.0) * maxYaw.get());
            float pitch = state.pitch() + (float) ((random.nextDouble() * 2.0 - 1.0) * maxPitch.get());
            if (keepClose.get() && Math.hypot(state.x() - originX, state.z() - originZ) > 2.0) {
                yaw = RotationController.yawTo(originX - state.x(), originZ - state.z());
                pitch = 0.0f;
            }
            if (silentAim.get()) {
                actions.sendRotation(yaw, pitch, state.onGround());
            } else {
                actions.setRotation(yaw, pitch);
            }
        }
        releaseJump = actions.setJumping(true);
        nextAction = now + (long) (1000.0 / frequency.get());
    }

    @Override
    protected void onStop() {
        if (releaseJump && actions() != null) {
            actions().setJumping(false);
        }
        releaseJump = false;
    }
}
