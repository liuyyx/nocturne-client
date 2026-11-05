package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** Game Animation：由 GuiOverlay 帧面消费 Hotbar/Inventory 开关并绘制过渡。 */
public final class GameAnimationModule extends GameModule {
    private final BooleanValue hotbar = add(new BooleanValue("Hotbar", true));
    private final BooleanValue inventory = add(new BooleanValue("Inventory", true));

    public GameAnimationModule() {
        super("Game Animation", Category.RENDER);
    }

    public boolean hotbar() {
        return hotbar.get();
    }

    public boolean inventory() {
        return inventory.get();
    }
}
