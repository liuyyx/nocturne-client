package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Step：仅在前进路径有实心阻挡且上方可进入时抬高本次位移。 */
public final class StepModule extends GameModule {
    private final NumberValue height = add(new NumberValue("Height", 1.0, 0.5, 2.5, 0.5));
    private final BooleanValue pauseOnSneak = add(new BooleanValue("Pause On Sneak", true));
    private final BooleanValue pauseInWater = add(new BooleanValue("Pause In Water", true));
    private final BooleanValue pauseOnFly = add(new BooleanValue("Pause On Fly", true));
    private final BooleanValue pauseOnElytra = add(new BooleanValue("Pause On Elytra", true));

    public StepModule() {
        super("Step", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, event -> {
            if (!state().onGround() || pauseOnSneak.get() && state().shifting()
                    || pauseInWater.get() && bridge().inWater(bridge().player())
                    || pauseOnFly.get() && EpsilonSupport.enabled("Flight")
                    || pauseOnElytra.get() && EpsilonSupport.enabled("ElytraFly")) {
                return;
            }
            double horizontal = Math.hypot(event.x, event.z);
            if (horizontal < 0.001) {
                return;
            }
            double yaw = Math.toRadians(state().yaw());
            int x = (int) Math.floor(state().x() - Math.sin(yaw) * 0.6);
            int y = (int) Math.floor(state().y());
            int z = (int) Math.floor(state().z() + Math.cos(yaw) * 0.6);
            if (!bridge().isAir(bridge().blockStateAt(x, y, z))
                    && clearAbove(height.get(), layer -> bridge().isAir(bridge().blockStateAt(x, y + layer, z)))) {
                event.y = Math.max(event.y, height.get());
            }
        });
    }

    /** 1.5/2.0 高度要占两层/三层，只查 y+1 会在头顶有方块时把玩家塞进墙里。 */
    static int requiredLayers(double height) {
        return Math.max(1, (int) Math.ceil(height));
    }

    static boolean clearAbove(double height, java.util.function.IntPredicate airAt) {
        int layers = requiredLayers(height);
        for (int layer = 1; layer <= layers; layer++) {
            if (!airAt.test(layer)) {
                return false;
            }
        }
        return true;
    }
}
