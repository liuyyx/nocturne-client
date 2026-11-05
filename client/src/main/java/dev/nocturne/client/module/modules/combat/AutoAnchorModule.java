package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 重生锚放置、充能、引爆的简化三阶段宏。 */
public final class AutoAnchorModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "On bind", "On bind", "On place"));
    private final BooleanValue doubleAnchor = add(new BooleanValue("Double anchor", false));
    private final BooleanValue aimAssist = add(new BooleanValue("Aim assist", true));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", true));
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 12, 1, 15, 0.5));
    private final NumberValue delay = add(new NumberValue("Delay", 50, 0, 500, 10));
    private boolean rightHeld;
    private int stage;
    private long nextAction;

    public AutoAnchorModule() {
        super("AutoAnchor", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        stage = 0;
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) {
                rightHeld = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (actions == null || bridge == null || !state.present() || mode.is("On place") && !rightHeld) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAction) {
            return;
        }
        Object hit = actions.pick(4.5, 1.0f);
        int[] pos = bridge.hitBlockPosition(hit);
        if (pos == null) {
            return;
        }
        if (aimAssist.get()) {
            double dx = pos[0] + 0.5 - state.x();
            double dz = pos[2] + 0.5 - state.z();
            float yaw = RotationController.approach(state.yaw(), RotationController.yawTo(dx, dz), aimSpeed.asFloat());
            float pitch = RotationController.approach(state.pitch(), RotationController.pitchTo(
                    Math.sqrt(dx * dx + dz * dz), pos[1] + 0.5 - state.y() - 1.62), aimSpeed.asFloat());
            if (silentAim.get()) {
                actions.sendRotation(yaw, pitch, state.onGround());
            } else {
                actions.setRotation(yaw, pitch);
            }
        }
        String target = bridge.blockDescription(bridge.hitBlockState(hit));
        int anchor = find(bridge, "respawn_anchor");
        int glowstone = find(bridge, "glowstone");
        if (!target.contains("respawn_anchor")) {
            if (anchor >= 0) {
                actions.selectSlot(anchor, false);
                actions.clickUse();
            }
        } else if (stage == 0 && glowstone >= 0) {
            actions.selectSlot(glowstone, false);
            actions.clickUse();
            stage = 1;
        } else {
            actions.clickUse();
            stage = 0;
            if (!doubleAnchor.get() && mode.is("On bind")) {
                setEnabled(false);
            }
        }
        nextAction = now + delay.asInt();
    }

    private int find(GameBridge bridge, String id) {
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.itemDescription(bridge.hotbarStack(slot)).contains(id)) {
                return slot;
            }
        }
        return -1;
    }
}
