package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.NumberValue;

/** Key Pearl：内部按键触发珍珠投掷，含暂停进食、延迟与换槽恢复。 */
public final class KeyPearlModule extends GameModule {
    private final KeyValue activate = add(new KeyValue("Activate Key", KeyValue.NONE));
    private final BooleanValue pauseOnEat = add(new BooleanValue("Pause On Eat", true));
    private final NumberValue delay = add(new NumberValue("Delay", 0, 0, 20, 1));
    private final BooleanValue switchBack = add(new BooleanValue("Switch Back", true));
    private final NumberValue switchDelay = add(new NumberValue("Switch Delay", 0, 0, 20, 1));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private boolean held;
    private int stage;
    private int wait;
    private int original = -1;
    private float yaw;
    private float pitch;

    public KeyPearlModule() {
        super("Key Pearl", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> update(event.keyCode, event.pressed));
        listen(MouseButtonEvent.class, event -> update(event.button, event.pressed));
    }

    private void update(int key, boolean pressed) {
        if (activate.bound() && key == activate.get()) {
            held = pressed;
            if (!pressed) {
                restore();
            }
        }
    }

    @Override
    public void onTick() {
        if (!held || pauseOnEat.get() && bridge().usingItem(bridge().player())) {
            return;
        }
        if (stage == 0) {
            int slot = EpsilonSupport.findHotbar(bridge(), "ender_pearl");
            if (slot < 0) {
                return;
            }
            original = state().selectedSlot();
            yaw = state().yaw();
            pitch = state().pitch();
            actions().selectSlot(slot, false);
            wait = delay.asInt();
            stage = 1;
        }
        if (stage == 1 && --wait <= 0) {
            actions().setRotation(yaw, pitch);
            actions().useItem();
            actions().swing(false, !swingHand.get());
            wait = switchDelay.asInt();
            stage = 2;
        } else if (stage == 2 && --wait <= 0) {
            restore();
            held = false;
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
    }
}
