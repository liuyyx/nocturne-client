package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Silent Aim：武器门控、目标分类、FOV/范围旋转与射线命中攻击。 */
public final class SilentAimModule extends GameModule {
    private final BooleanValue weaponOnly = add(new BooleanValue("Weapon Only", false));
    private final BooleanValue player = add(new BooleanValue("Player", true));
    private final BooleanValue mob = add(new BooleanValue("Mob", false));
    private final BooleanValue animal = add(new BooleanValue("Animal", false));
    private final BooleanValue villagers = add(new BooleanValue("Villagers", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", false));
    private final NumberValue range = add(new NumberValue("Range", 3, 1, 6, 0.1));
    private final NumberValue fov = add(new NumberValue("FOV", 360, 10, 360, 10));

    public SilentAimModule() {
        super("Silent Aim", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (weaponOnly.get() && !bridge().itemMatches(bridge().heldItem(bridge().player(), false),
                "sword,axe,spear,trident,mace")) {
            return;
        }
        TargetManager.Options options = EpsilonSupport.targets(range.get(), player.get(),
                mob.get() || animal.get() || villagers.get(), invisible.get(), TargetManager.Sort.ANGLE);
        options.maxAngle = fov.get();
        TargetManager.Target target = client().targets().find(client(), options);
        if (target == null) {
            return;
        }
        EpsilonSupport.aim(bridge(), state(), target.x(), target.aimY(), target.z(), 180, 7);
        Object ray = bridge().hitEntity(actions().pick(range.get(), 1.0f));
        if (ray == target.entity()) {
            actions().attack(ray);
        }
    }
}
