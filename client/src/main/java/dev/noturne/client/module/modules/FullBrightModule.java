package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.NumberValue;

/**
 * Raises the game's brightness setting while armed, and restores it on disarm.
 *
 * <p>Writes the real {@code Options.gamma} field through the mapping, so the effect is identical to
 * the player dragging the brightness slider to the top — just scripted and revertible.
 */
public final class FullBrightModule extends Module {

    private static final double VANILLA_GAMMA = 1.0;

    private final NumberValue gamma = add(new NumberValue("Gamma", 10.0, 1.0, 15.0, 0.5));

    @Override
    public String name() {
        return "FullBright";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected void onEnable() {
        apply(gamma.get());
    }

    @Override
    protected void onDisable() {
        apply(VANILLA_GAMMA);
    }

    /** Re-applies the value; the game may reset options when the world loads. */
    @Override
    public void onTick() {
        apply(gamma.get());
    }

    private void apply(double value) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object minecraft = bridge.minecraft();
        if (minecraft == null) {
            return;
        }
        Object options = bridge.readField(minecraft, ClassType.MINECRAFT, "options");
        if (options == null) {
            return;
        }
        bridge.writeField(options, ClassType.OPTIONS, "gamma", (float) value);
    }

    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
