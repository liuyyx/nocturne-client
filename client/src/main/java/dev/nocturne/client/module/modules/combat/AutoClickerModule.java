package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Auto Clicker：左右键独立 CPS、抖动、Burst/Decay、双击与 Miss。 */
public final class AutoClickerModule extends GameModule {
    private final ModeValue button = add(new ModeValue("Button", "Both", "Left", "Right", "Both"));
    private final NumberValue leftMin = add(new NumberValue("Left Min CPS", 8, 1, 520, 1));
    private final NumberValue leftMax = add(new NumberValue("Left Max CPS", 12, 1, 520, 1));
    private final BooleanValue leftJitter = add(new BooleanValue("Left Jitter", false));
    private final NumberValue rightMin = add(new NumberValue("Right Min CPS", 8, 1, 520, 1));
    private final NumberValue rightMax = add(new NumberValue("Right Max CPS", 12, 1, 520, 1));
    private final BooleanValue rightJitter = add(new BooleanValue("Right Jitter", false));
    private final BooleanValue enableBurst = add(new BooleanValue("Enable Burst", true));
    private final NumberValue burstChance = add(new NumberValue("Burst Chance", 15, 1, 100, 1));
    private final NumberValue burstDuration = add(new NumberValue("Burst Duration", 800, 100, 3000, 50));
    private final NumberValue burstMultiplier = add(new NumberValue("Burst CPS Multiplier", 1.8, 1.1, 3, 0.1));
    private final NumberValue decayDuration = add(new NumberValue("Decay Duration", 1200, 500, 5000, 50));
    private final NumberValue decayCurve = add(new NumberValue("Decay Curve", 1.5, 1, 3, 0.1));
    private final BooleanValue enableDouble = add(new BooleanValue("Enable Double Click", true));
    private final NumberValue doubleChance = add(new NumberValue("Double Click Chance", 8, 1, 50, 1));
    private final NumberValue doubleDelay = add(new NumberValue("Double Click Delay", 40, 10, 100, 1));
    private final BooleanValue enableMiss = add(new BooleanValue("Enable Miss", true));
    private final NumberValue missChance = add(new NumberValue("Miss Chance", 5, 1, 30, 1));
    private final ButtonState left = new ButtonState();
    private final ButtonState right = new ButtonState();

    public AutoClickerModule() {
        super("Auto Clicker", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (button.is("Left") || button.is("Both")) {
            tick(left, true, leftMin.get(), leftMax.get(), leftJitter.get());
        }
        if (button.is("Right") || button.is("Both")) {
            tick(right, false, rightMin.get(), rightMax.get(), rightJitter.get());
        }
    }

    private void tick(ButtonState state, boolean left, double min, double max, boolean jitter) {
        long now = System.nanoTime();
        if (now < state.next) {
            if (state.doubleAt != 0L && now >= state.doubleAt) {
                click(left);
                state.doubleAt = 0L;
            }
            return;
        }
        double cps = EpsilonSupport.randomLong((long) min, (long) max);
        if (enableBurst.get() && state.burstEnd == 0L
                && EpsilonSupport.RANDOM.nextDouble() * 100.0 < burstChance.get()) {
            state.burstEnd = now + (long) (burstDuration.get() * 1_000_000.0);
            state.decayEnd = state.burstEnd + (long) (decayDuration.get() * 1_000_000.0);
        }
        if (now < state.burstEnd) {
            cps *= burstMultiplier.get();
        } else if (now < state.decayEnd) {
            double progress = (state.decayEnd - now) / (double) (state.decayEnd - state.burstEnd);
            cps *= 1.0 + (burstMultiplier.get() - 1.0) * Math.pow(progress, decayCurve.get());
        } else {
            state.burstEnd = 0L;
            state.decayEnd = 0L;
        }
        if (enableMiss.get() && EpsilonSupport.RANDOM.nextDouble() * 100.0 < missChance.get()) {
            state.next = now + (long) (1_000_000_000.0 / cps);
            return;
        }
        click(left);
        if (enableDouble.get() && EpsilonSupport.RANDOM.nextDouble() * 100.0 < doubleChance.get()) {
            state.doubleAt = now + doubleDelay.asInt() * 1_000_000L;
        }
        double delay = 1_000_000_000.0 / Math.max(1.0, cps);
        if (jitter) {
            delay *= 0.85 + EpsilonSupport.RANDOM.nextDouble() * 0.3;
        }
        state.next = now + (long) delay;
    }

    private void click(boolean left) {
        if (left) {
            actions().click();
        } else {
            actions().clickUse();
        }
    }

    @Override
    protected void onStop() {
        left.next = right.next = 0L;
        left.doubleAt = right.doubleAt = 0L;
        left.burstEnd = right.burstEnd = 0L;
    }

    private static final class ButtonState {
        private long next;
        private long doubleAt;
        private long burstEnd;
        private long decayEnd;
    }
}
