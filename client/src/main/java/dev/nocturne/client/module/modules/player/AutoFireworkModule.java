package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.NumberValue;

/** AutoFirework：按住内部热键时使用烟花，按配置延迟并恢复原槽。 */
public final class AutoFireworkModule extends GameModule {
    private final KeyValue activate = add(new KeyValue("Activate Key", KeyValue.NONE));
    private final NumberValue delay = add(new NumberValue("Delay", 0, 0, 20, 1));
    private final BooleanValue switchBack = add(new BooleanValue("Switch Back", true));
    private final NumberValue switchDelay = add(new NumberValue("Switch Delay", 0, 0, 20, 1));
    private boolean held;
    private int stage;
    private int wait;
    private int original = -1;
    private int cooldown;

    public AutoFireworkModule() {
        super("Auto Firework", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (activate.bound() && event.keyCode == activate.get()) {
                held = event.pressed;
                if (!held) {
                    restore();
                }
            }
        });
    }

    @Override
    public void onTick() {
        if (cooldown > 0) {
            cooldown--;
        }
        if (!held || cooldown > 0) {
            return;
        }
        if (stage == 0) {
            int slot = EpsilonSupport.findHotbar(bridge(), "firework_rocket");
            if (slot < 0) {
                return;
            }
            original = state().selectedSlot();
            actions().selectSlot(slot, false);
            wait = delay.asInt();
            stage = 1;
        }
        if (stage == 1 && --wait <= 0) {
            actions().useItem();
            wait = switchDelay.asInt();
            stage = 2;
        } else if (stage == 2 && --wait <= 0) {
            restore();
            cooldown = 4;
        }
    }

    private void restore() {
        if (switchBack.get() && original >= 0 && actions() != null) {
            actions().selectSlot(original, false);
        }
        original = -1;
        stage = 0;
    }

    @Override
    protected void onStop() {
        held = false;
        restore();
        cooldown = 0;
    }
}
