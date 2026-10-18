package dev.nocturne.agent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 键盘事件探针：把游戏进程收到的 SDL 键盘事件（scancode / down）打到日志。
 *
 * <p><b>为什么需要它</b>：判断"按了某个键没反应"时，只有两条互斥的可能——
 * 事件根本没到游戏（系统/输入法/窗口焦点层被拦），或者到了但我们读数/映射错。
 * 键盘状态轮询（{@code SDL_GetKeyboardState}）看不到前者，只有事件到达点能分开。
 *
 * <p>只在诊断期使用：打印前 {@link #LIMIT} 条，验完即删（连同注册处）。
 */
public final class KeyHook {

    /** 最多打印多少条，避免刷屏。 */
    private static final int LIMIT = 30;

    /** 已打印条数。 */
    private static final AtomicInteger SEEN = new AtomicInteger();

    /** {@code SDL_Event.key()}。 */
    private static volatile Method keyMethod;
    /** {@code SDL_KeyboardEvent.scancode()}。 */
    private static volatile Method scancodeMethod;
    /** {@code SDL_KeyboardEvent.down()}。 */
    private static volatile Method downMethod;
    /** 是否已解析。 */
    private static volatile boolean resolved;

    private KeyHook() {
    }

    /**
     * 钩子入口。
     *
     * @param event {@code SDL_Event} 实例
     */
    public static void onEvent(Object event) {
        if (event == null || SEEN.get() >= LIMIT) {
            return;
        }
        try {
            if (!resolved) {
                resolve(event.getClass());
            }
            Method key = keyMethod;
            Method scancode = scancodeMethod;
            Method down = downMethod;
            if (key == null || scancode == null || down == null || SEEN.get() >= LIMIT) {
                return;
            }
            Object keyboardEvent = key.invoke(event);
            if (keyboardEvent == null) {
                return;
            }
            int code = ((Number) scancode.invoke(keyboardEvent)).intValue();
            boolean pressed = (Boolean) down.invoke(keyboardEvent);
            if (SEEN.incrementAndGet() <= LIMIT) {
                System.out.println("[nocturne] key event: scancode=" + code + " down=" + pressed);
            }
        } catch (Throwable ignored) {
            // 诊断钩子绝不能影响游戏的事件处理。
        }
    }

    /**
     * 解析反射句柄。
     *
     * @param eventClass {@code SDL_Event} 的运行期类
     */
    private static void resolve(Class<?> eventClass) {
        try {
            Method key = eventClass.getMethod("key");
            Class<?> keyboardClass = key.getReturnType();
            keyMethod = key;
            scancodeMethod = keyboardClass.getMethod("scancode");
            downMethod = keyboardClass.getMethod("down");
        } catch (Throwable t) {
            // 句柄留空，onEvent 直接返回
        }
        resolved = true;
    }
}
