package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 每帧把扩展距离内的合法目标写入真实准星命中结果。 */
public final class ReachModule extends GameModule {
    private final BoundsNumberValue range = add(new BoundsNumberValue(
            "Range", new NumberBounds(3.0, 3.1), 3.0, 4.0, 0.01));
    private final NumberValue chance = add(new NumberValue("Chance", 50, 0, 100, 1));
    private final ModeValue chanceMode = add(new ModeValue("Chance mode", "Advanced", "Advanced", "Normal"));
    private final BooleanValue verticalCheck = add(new BooleanValue("Vertical check", false));
    private final BooleanValue onlySprinting = add(new BooleanValue("Only while sprinting", false));
    private final BooleanValue disableInWater = add(new BooleanValue("Disable in water", false));
    private final TargetManager.Options options = new TargetManager.Options();
    private final Random random = new Random();
    private Object activeTarget;

    public ReachModule() {
        super("Reach", Category.COMBAT);
        options.mobs = true;
        options.invisibles = true;
    }

    @Override
    protected void onStart() {
        listen(RenderEvent.class, this::onRender);
        listen(AttackEvent.class, event -> {
            if (event.post) {
                activeTarget = null;
            }
        });
    }

    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present()
                || onlySprinting.get() && !state.sprinting()
                || disableInWater.get() && bridge.inWater(state.player())) {
            return;
        }
        NumberBounds bounds = range.get();
        options.range = bounds.max();
        options.maxAngle = 12.0;
        TargetManager.Target target = client().targets().find(client(), options);
        if (target == null || verticalCheck.get() && Math.abs(target.y() - state.y()) > 3.0) {
            activeTarget = null;
            return;
        }
        if (chanceMode.is("Normal") || activeTarget == null) {
            if (random.nextDouble() * 100.0 >= chance.get()) {
                return;
            }
        }
        activeTarget = target.entity();
        if (target.distance() > bounds.min()) {
            Object hit = bridge.construct(ClassType.ENTITY_HIT_RESULT, activeTarget);
            if (hit != null) {
                bridge.writeField(bridge.minecraft(), ClassType.MINECRAFT, "hitResult", hit);
            }
        }
    }

    @Override
    protected void onStop() {
        activeTarget = null;
        ActionApi actions = actions();
        GameBridge bridge = bridge();
        if (actions != null && bridge != null) {
            bridge.writeField(bridge.minecraft(), ClassType.MINECRAFT, "hitResult", actions.pick(3.0, 1.0f));
        }
    }
}
