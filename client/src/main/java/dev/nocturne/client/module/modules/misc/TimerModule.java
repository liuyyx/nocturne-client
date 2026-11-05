package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Timer：Always/Balance/SlowBalance，包含按键与包延迟预算。 */
public final class TimerModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Always", "Always", "Balance", "SlowBalance"));
    private final KeyValue activate = add(new KeyValue("Activate Key", 88));
    private final NumberValue multiplier = add(new NumberValue("Multiplier", 1.8, 0.1, 10, 0.1));
    private final NumberValue maxBalance = add(new NumberValue("Max Balance", 1000, 0, 10000, 1));
    private final NumberValue ticks = add(new NumberValue("Ticks", 13, 1, 20, 1));
    private float currentSpeed = 1.0f;
    private float balance;
    private long balanceTime;
    private boolean held;

    public TimerModule() {
        super("Timer", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (event.keyCode == activate.get()) {
                held = event.pressed;
            }
        });
        listen(PacketEvent.class, event -> {
            if (!mode.is("Balance")) {
                return;
            }
            if (event.direction == PacketEvent.Direction.RECEIVE) {
                balanceTime = Math.min(maxBalance.asInt(), balanceTime + 50L);
            } else if (event.packet != null) {
                balanceTime = Math.max(-3000L, balanceTime - 50L);
            }
        });
    }

    @Override
    public void onTick() {
        if (mode.is("Always")) {
            currentSpeed = multiplier.asFloat();
        } else if (mode.is("Balance")) {
            currentSpeed = held ? 1.0f : multiplier.asFloat();
            if (balanceTime > maxBalance.asInt()) {
                currentSpeed = 1.0f;
            }
        } else {
            if (state().moving()) {
                balance = Math.max(0.0f, balance - 1.0f);
            } else {
                balance = Math.min(ticks.asInt(), balance + 0.8f);
            }
            currentSpeed = balance >= 1.0f ? multiplier.asFloat() : 0.96f;
        }
        bridge().setTimerSpeed(currentSpeed);
    }

    public float speed() {
        return currentSpeed;
    }

    @Override
    protected void onStop() {
        currentSpeed = 1.0f;
        balance = 0.0f;
        balanceTime = 0L;
        held = false;
        if (bridge() != null) {
            bridge().setTimerSpeed(1.0f);
        }
    }
}
