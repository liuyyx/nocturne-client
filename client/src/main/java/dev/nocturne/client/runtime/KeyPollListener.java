package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.module.Module;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块热键的**轮询**回退：只在键盘事件源不可用时工作。
 *
 * <p>1.8.9–1.12 的 {@code KeyboardHandler} 在映射表里是 absent（没有可织入的按键回调），
 * 于是 {@link KeyEvent} 从来不投递——所有模块热键形同不存在（真机：只有自绘 GUI 的开关
 * 还能用，因为它自己轮询指针/按键）。这里用 LWJGL2 的 {@code Keyboard.isKeyDown(int)} 每帧
 * 读一次**已绑定模块的那几个键**，做边沿检测后照常投递 {@link KeyEvent}。
 *
 * <p>为什么不直接在 {@code ModuleKeybinds} 里查键：投递路径只有一条（事件总线），轮询源
 * 只负责"把边沿变成事件"，这样按 GUI 打开时的拦截、按键消费、多键绑定等语义全部复用。
 *
 * <p>开关条件按**能力**而不是版本号：一旦账号所在版本真的接通了 {@code key} 钩子
 * （{@link GameplayHook#isWired(String)} 为 true），本监听器立刻静默退出，不会重复触发。
 */
public final class KeyPollListener implements FrameListener {

    /** 轮询节拍：30Hz 足够（模块开关不需要 60Hz），也避免每帧反射。 */
    private static final long POLL_INTERVAL_NANOS = 33_000_000L;

    private final NocturneClient client;
    /** LWJGL2 的 {@code Keyboard.isKeyDown(int)}；解析失败为 null。 */
    private Method isKeyDown;
    /** 已确认没有轮询源（类不存在）：不再重试。 */
    private boolean unavailable;
    /** 运行期调用抛异常（键盘未初始化）：停用并告警一次。 */
    private boolean failed;
    private long lastPollNanos;

    /** 需要轮询的 AWT 键码（来自各模块的绑定），按注册表版本号刷新。 */
    private int[] keys = new int[0];
    private boolean[] down = new boolean[0];
    private long revision = -1L;
    /** 键表上次刷新的时间（纳秒）：绑定变化不递增注册表版本号，只能按时间重新收集。 */
    private long lastRefreshNanos;
    /** 键表刷新间隔：绑定改动最迟一秒内生效，代价可忽略。 */
    private static final long REFRESH_INTERVAL_NANOS = 1_000_000_000L;

    public KeyPollListener(NocturneClient client) {
        this.client = client;
    }

    @Override
    public void onFrame() {
        if (failed || unavailable || client == null) {
            return;
        }
        // 事件源可用时不轮询：否则同一个按键会被两套输入各触发一次。
        if (GameplayHook.isWired("key")) {
            return;
        }
        long now = System.nanoTime();
        if (lastPollNanos != 0L && now - lastPollNanos < POLL_INTERVAL_NANOS) {
            return;
        }
        lastPollNanos = now;
        if (!resolveSource()) {
            return;
        }
        refreshKeys();
        // 原生 Screen 开着时按键属于界面：继续跟踪物理状态的边沿，但不投递 KeyEvent。
        // 只 return 不跟踪的话，界面里按下并保持的绑定键会在关闭那一帧补发一次切换。
        GameBridge bridge = client.gameBridge();
        boolean nativeScreen = bridge != null && bridge.currentScreen() != null;
        for (int i = 0; i < keys.length; i++) {
            int code = KeyMap.lwjgl2(keys[i]);
            if (code < 0) {
                continue;
            }
            boolean pressed;
            try {
                pressed = Boolean.TRUE.equals(isKeyDown.invoke(null, Integer.valueOf(code)));
            } catch (Throwable t) {
                failed = true;
                System.out.println("[nocturne] module key polling disabled: " + t);
                return;
            }
            if (pressed != down[i]) {
                down[i] = pressed;
                if (!nativeScreen) {
                    client.events().post(new KeyEvent(keys[i], pressed));
                }
            }
        }
    }

    /** 解析 LWJGL2 键盘入口；解析不到就永久标记为不可用（只打一条日志）。 */
    private boolean resolveSource() {
        if (isKeyDown != null) {
            return true;
        }
        try {
            Class<?> keyboard;
            try {
                keyboard = Class.forName("org.lwjgl.input.Keyboard");
            } catch (ClassNotFoundException missing) {
                keyboard = Class.forName("org.lwjgl.input.Keyboard", true,
                        ClassLoader.getSystemClassLoader());
            }
            isKeyDown = keyboard.getMethod("isKeyDown", int.class);
            return true;
        } catch (Throwable t) {
            unavailable = true;
            System.out.println("[nocturne] module key polling unavailable (no LWJGL2 keyboard): " + t);
            return false;
        }
    }

    /**
     * 重建待轮询键表。
     *
     * <p>不能只按注册表版本号刷新：版本号只在**注册**新模块时递增，而玩家改绑定（KeyValue.set）
     * 不递增——只在安装时建一次表的话，之后绑定的键永远不会被轮询。改为按时间节流（1s）刷新，
     * 绑定变化最迟一秒内生效。
     */
    private void refreshKeys() {
        long current = client.modules().revision();
        long now = System.nanoTime();
        if (current == revision && lastRefreshNanos != 0L
                && now - lastRefreshNanos < REFRESH_INTERVAL_NANOS) {
            return;
        }
        revision = current;
        lastRefreshNanos = now;
        List<Integer> found = new ArrayList<Integer>();
        for (Module module : client.modules().all()) {
            int key = module.bind().get().intValue();
            // 同一键绑定多个模块只需要轮询一次
            if (key != KeyValue.NONE && !found.contains(Integer.valueOf(key))) {
                found.add(Integer.valueOf(key));
            }
        }
        int[] next = new int[found.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = found.get(i).intValue();
        }
        boolean[] states = new boolean[next.length];
        // 尽量保留已有按钮状态：按索引对齐的部分沿用，避免一次刷新产生假的按下边沿。
        System.arraycopy(down, 0, states, 0, Math.min(down.length, states.length));
        keys = next;
        down = states;
    }
}
