package dev.nocturne.agent;

import dev.nocturne.client.runtime.ScrollState;

import java.lang.reflect.Method;

/**
 * SDL 滚轮事件钩子：从 {@code Window#handleEvent(SDL_Event)} 的实参里取出滚轮增量。
 *
 * <p>为什么从这里取：SDL 的滚轮只能从事件里拿（没有轮询接口），而 {@code Window.handleEvent} 是游戏
 * 处理每个 SDL 事件的入口，形参就是事件对象本身。钩子把增量交给 bootstrap 层的
 * {@link ScrollState}，界面每帧取走——两层分属不同类加载器，状态必须放 bootstrap 层。
 *
 * <p>全程反射：本模块编译期不依赖 LWJGL，目标 JVM 里存在哪一代绑定由游戏决定。事件类型常量也从
 * LWJGL 的 {@code SDLEvents} 里取，取不到时回退 SDL3 的固定值（{@code SDL_EVENT_MOUSE_WHEEL = 0x403}）。
 *
 * <p>钩子在游戏的每个事件上被调用，因此这里必须够便宜：一次解析后全部缓存；异常一律吞掉——绝不能让
 * 事件处理因为钩子出问题。
 */
public final class ScrollHook {

    /** SDL3 的滚轮事件类型（常量取不到时的回退值）。 */
    private static final int MOUSE_WHEEL_FALLBACK = 0x403;

    /** 事件类型读取方法。 */
    private static volatile Method typeMethod;
    /** 滚轮子结构读取方法。 */
    private static volatile Method wheelMethod;
    /** 滚轮纵向增量读取方法。 */
    private static volatile Method wheelYMethod;
    /** 滚轮事件类型常量。 */
    private static volatile int wheelEventType = MOUSE_WHEEL_FALLBACK;
    /** 是否已解析过（失败也不再重试）。 */
    private static volatile boolean resolved;

    /** 首条滚轮诊断是否已打印（只印一次，避免刷屏）。 */
    private static boolean loggedOnce;

    private ScrollHook() {
    }

    /**
     * 钩子入口：处理一个 SDL 事件对象。
     *
     * @param event {@code SDL_Event} 实例；非滚轮事件直接返回
     */
    public static void onEvent(Object event) {
        if (event == null) {
            return;
        }
        try {
            if (!resolved) {
                resolve(event.getClass());
            }
            Method type = typeMethod;
            Method wheel = wheelMethod;
            Method wheelY = wheelYMethod;
            if (type == null || wheel == null || wheelY == null) {
                return;
            }
            Object eventType = type.invoke(event);
            if (!(eventType instanceof Integer) || ((Integer) eventType).intValue() != wheelEventType) {
                return;
            }
            Object wheelEvent = wheel.invoke(event);
            if (wheelEvent == null) {
                return;
            }
            Object delta = wheelY.invoke(wheelEvent);
            if (delta instanceof Number) {
                double value = ((Number) delta).doubleValue();
                if (!loggedOnce && value != 0d) {
                    loggedOnce = true;
                    // 现场诊断：滚轮是事件驱动的，这条线是"钩子有没有吃到事件"的唯一痕迹。
                    System.out.println("[nocturne] scroll wheel event seen: y=" + value);
                }
                ScrollState.accumulate(value);
            }
        } catch (Throwable ignored) {
            // 事件钩子出问题绝不能影响游戏的事件处理。
        }
    }

    /**
     * 解析一次反射句柄与事件类型常量。
     *
     * @param eventClass {@code SDL_Event} 的运行期类
     */
    private static void resolve(Class<?> eventClass) {
        try {
            Method type = eventClass.getMethod("type");
            Method wheel = eventClass.getMethod("wheel");
            Class<?> wheelClass = wheel.getReturnType();
            Method wheelY = wheelClass.getMethod("y");
            typeMethod = type;
            wheelMethod = wheel;
            wheelYMethod = wheelY;
            wheelEventType = resolveWheelEventType();
        } catch (Throwable t) {
            // 句柄留空，onEvent 会直接返回
        }
        resolved = true;
    }

    /**
     * 取 {@code SDL_EVENT_MOUSE_WHEEL} 常量。
     *
     * @return 事件类型值；LWJGL 的常量类不可用时回退 SDL3 的固定值
     */
    private static int resolveWheelEventType() {
        try {
            Class<?> events = Class.forName("org.lwjgl.sdl.SDLEvents");
            Object value = events.getField("SDL_EVENT_MOUSE_WHEEL").get(null);
            if (value instanceof Integer) {
                return ((Integer) value).intValue();
            }
        } catch (Throwable ignored) {
            // 回退到固定值
        }
        return MOUSE_WHEEL_FALLBACK;
    }
}
