package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/** Multi Aura：按随机 CPS 对本轮全部合法目标各执行一次真实攻击。 */
public final class MultiAuraModule extends GameModule {
    private final NumberValue range = add(new NumberValue("Range", 5, 1, 6, 0.05));
    private final NumberValue minCps = add(new NumberValue("Min CPS", 10, 1, 20, 1));
    private final NumberValue maxCps = add(new NumberValue("Max CPS", 12, 1, 20, 1));
    private final NumberValue fov = add(new NumberValue("FOV", 360, 30, 360, 10));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private final BooleanValue pauseContainers = add(new BooleanValue("Pause On Containers", false));
    private final BooleanValue player = add(new BooleanValue("Player", true));
    private final BooleanValue mob = add(new BooleanValue("Mob", true));
    private final BooleanValue animal = add(new BooleanValue("Animal", true));
    private final BooleanValue villager = add(new BooleanValue("Villager", false));
    private final BooleanValue ambient = add(new BooleanValue("Ambient", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", true));
    private double attacks;

    public MultiAuraModule() {
        super("Multi Aura", Category.COMBAT);
    }

    @Override
    protected void onStop() {
        attacks = 0.0;
    }

    @Override
    public void onTick() {
        if (pauseContainers.get() && bridge().currentScreen() != null || bridge().usingItem(bridge().player())) {
            return;
        }
        attacks += EpsilonSupport.randomInt(minCps.asInt(), maxCps.asInt()) / 20.0;
        if (attacks < 1.0) {
            return;
        }
        TargetManager.Options options = EpsilonSupport.targets(range.get(), player.get(),
                mob.get() || animal.get() || villager.get() || ambient.get() || water.get() || others.get(),
                invisible.get(), TargetManager.Sort.DISTANCE);
        options.maxAngle = fov.get();
        List<TargetManager.Target> targets = client().targets().list(client(), options);
        while (attacks >= 1.0 && !targets.isEmpty()) {
            for (TargetManager.Target target : targets) {
                actions().attack(target.entity());
            }
            attacks -= 1.0;
        }
        if (!targets.isEmpty()) {
            actions().swing(false, !swingHand.get());
        }
    }
}
