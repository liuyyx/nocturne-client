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

    /** 钩子是否可用（null=尚未判定，模块可能在 bridge 就绪前启动）。 */
    private Boolean available;
    /** 不可用原因只提示一次。 */
    private boolean unavailableLogged;

    @Override
    protected void onStart() {
        available = null;
        unavailableLogged = false;
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

    /**
     * 可用性 = ASM delta 钩子已接线 || {@link dev.nocturne.client.game.GameBridge#setTimerSpeed}
     * 试写成功。1.8.9 没有 timer 钩子但 Timer.timerSpeed 可写，必须走字段路径工作。
     *
     * <p>bridge 尚未就绪时返回 false 但**不缓存**，等 bridge 就绪后再判定。
     */
    private boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached.booleanValue();
        }
        if (dev.nocturne.client.bootstrap.GameplayHook.isWired("timer")) {
            available = Boolean.TRUE;
            return true;
        }
        if (bridge() == null) {
            return false;
        }
        // 试写中性值 1.0：失败说明该版本没有可影响速度的字段，模块必须显式降级。
        boolean written = bridge().setTimerSpeed(1.0f);
        available = Boolean.valueOf(written);
        if (!written && !unavailableLogged) {
            unavailableLogged = true;
            String reason = dev.nocturne.client.bootstrap.GameplayHook.unavailableReason("timer");
            System.out.println("[nocturne] Timer: 该版本既没有 timer 钩子，也没有可写的计时倍率字段"
                    + (reason == null ? "" : "（" + reason + "）")
                    + "，模块切换不会改变游戏速度");
        }
        return written;
    }

    @Override
    public void onTick() {
        if (!available()) {
            return;
        }
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
        if (bridge() != null && !bridge().setTimerSpeed(currentSpeed)) {
            // 运行期写入失败（版本/加载器差异）：立即降级，不继续每 tick 假装改速度。
            available = Boolean.FALSE;
            if (!unavailableLogged) {
                unavailableLogged = true;
                System.out.println("[nocturne] Timer: 计时倍率写入失败，已停用（mapping="
                        + bridge().mapping().describe() + "）");
            }
        }
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
