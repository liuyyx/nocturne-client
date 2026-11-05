package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** 从本地玩家移除选定负面药水效果；不会调用 removeAllEffects。 */
public final class AntiDebuffModule extends GameModule {
    private final BooleanValue nausea = add(new BooleanValue("Remove Nausea", true));
    private final BooleanValue blindness = add(new BooleanValue("Remove Blindness", true));
    private final BooleanValue slowness = add(new BooleanValue("Remove Slowness", true));
    private final BooleanValue removeEffects = add(new BooleanValue("Remove Effects", false));

    public AntiDebuffModule() {
        super("AntiDebuff", Category.RENDER);
    }

    @Override
    public void onTick() {
        if (nausea.get()) {
            bridge().removeEffect("NAUSEA");
        }
        if (removeEffects.get() && blindness.get()) {
            bridge().removeEffect("BLINDNESS");
        }
        if (removeEffects.get() && slowness.get()) {
            bridge().removeEffect("SLOWNESS");
        }
    }
}
