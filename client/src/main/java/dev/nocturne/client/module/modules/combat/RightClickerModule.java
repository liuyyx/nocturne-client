package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 按住右键时按随机 CPS 调用真实右键入口。 */
public final class RightClickerModule extends GameModule {
    private final BooleanValue holdToClick = add(new BooleanValue("Hold to Click", true));
    private final BooleanValue sneakOnly = add(new BooleanValue("Sneak Only", false));
    private final BoundsNumberValue cps = add(new BoundsNumberValue("CPS", new NumberBounds(7, 13), 1, 20, 1));
    private final ModeValue randomization = add(new ModeValue(
            "Randomization", "Extra+", "Normal", "Extra", "Extra+"));
    private final NumberValue startDelay = add(new NumberValue("Start Delay", 0, 0, 1000, 10));
    private final Random random = new Random();
    private boolean rightHeld;
    private long heldSince;
    private long nextClick;

    public RightClickerModule() {
        super("RightClicker", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) {
                rightHeld = event.pressed;
                heldSince = System.nanoTime();
                nextClick = 0L;
            }
        });
    }

    @Override
    protected void onStop() {
        rightHeld = false;
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        if (actions == null || holdToClick.get() && !rightHeld || sneakOnly.get() && !state().shifting()
                || System.nanoTime() - heldSince < startDelay.asInt() * 1_000_000L) {
            return;
        }
        long now = System.nanoTime();
        if (nextClick != 0L && now < nextClick) {
            return;
        }
        actions.clickUse();
        NumberBounds bounds = cps.get();
        double rate = bounds.min() + random.nextDouble() * (bounds.max() - bounds.min());
        double factor = randomization.is("Normal") ? 1.0
                : randomization.is("Extra") ? 0.9 + random.nextDouble() * 0.2
                : 0.8 + random.nextDouble() * 0.4;
        nextClick = now + (long) (1_000_000_000.0 / rate * factor);
    }
}
