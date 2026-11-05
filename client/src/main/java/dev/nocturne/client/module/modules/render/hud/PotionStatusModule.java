package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.value.BooleanValue;

import java.util.function.Supplier;

/** 实时列出本地玩家药水效果，并分别控制正面/负面效果。 */
public final class PotionStatusModule extends HudModule {
    private final BooleanValue positive = add(new BooleanValue("Show Positive Effects", true));
    private final BooleanValue negative = add(new BooleanValue("Show Negative Effects", true));
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            NocturneClient client = NocturneClient.get();
            GameBridge bridge = client == null ? null : client.gameBridge();
            Object effects = bridge == null ? null : bridge.activeEffects();
            if (!(effects instanceof Iterable<?>)) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (Object effect : (Iterable<?>) effects) {
                String value = String.valueOf(effect);
                String lower = value.toLowerCase(java.util.Locale.ROOT);
                boolean harmful = lower.contains("harm") || lower.contains("poison")
                        || lower.contains("slow") || lower.contains("weak")
                        || lower.contains("blind") || lower.contains("nausea");
                if (harmful && !negative.get() || !harmful && !positive.get()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(value);
            }
            return text.toString();
        }
    };

    @Override
    public String name() {
        return "Potion Status";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "potion-status";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
