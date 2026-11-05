package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.value.BooleanValue;

import java.util.function.Supplier;

/** 显示四件当前盔甲及剩余耐久百分比。 */
public final class ArmorStatusModule extends HudModule {
    private final BooleanValue compact = add(new BooleanValue("Compact", false));
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            NocturneClient client = NocturneClient.get();
            GameBridge bridge = client == null ? null : client.gameBridge();
            if (bridge == null || bridge.player() == null) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (int slot = 39; slot >= 36; slot--) {
                Object stack = bridge.inventoryStack(slot);
                if (bridge.isEmptyStack(stack)) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(compact.get() ? " " : " | ");
                }
                if (!compact.get()) {
                    text.append(bridge.itemDisplayName(stack)).append(' ');
                }
                int max = bridge.itemMaxDamage(stack);
                int percent = max <= 0 ? 100 : (max - bridge.itemDamage(stack)) * 100 / max;
                text.append(percent).append('%');
            }
            return text.toString();
        }
    };

    @Override
    public String name() {
        return "Armor Status";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "armor-status";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
