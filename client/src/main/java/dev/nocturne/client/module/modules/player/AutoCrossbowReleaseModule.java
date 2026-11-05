package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** AutoCrossbowRelease：弩达到完整蓄力后释放原生使用键。 */
public final class AutoCrossbowReleaseModule extends GameModule {
    public AutoCrossbowReleaseModule() {
        super("AutoCrossbowRelease", Category.PLAYER);
    }

    @Override
    public void onTick() {
        if (!bridge().usingItem(bridge().player())) {
            return;
        }
        Object stack = bridge().usingItemStack(bridge().player());
        Object duration = bridge().callMapped(stack,
                dev.nocturne.client.mapping.ClassType.ITEM_STACK, "getUseDuration");
        if (bridge().itemDescription(stack).contains("crossbow") && duration instanceof Number
                && bridge().usingTicks(bridge().player()) >= ((Number) duration).intValue() + 1) {
            bridge().setUseKey(false);
        }
    }

    @Override
    protected void onStop() {
        if (bridge() != null) {
            bridge().setUseKey(false);
        }
    }
}
