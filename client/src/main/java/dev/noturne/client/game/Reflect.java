package dev.noturne.client.game;

import java.lang.reflect.Method;

/**
 * 少量反射辅助方法，用于在无编译期依赖的前提下访问游戏 / LWJGL。
 *
 * <p>所有辅助方法失败时都退化为返回 {@code null} 而不抛异常：某个特定游戏版本没有暴露被探测的类或成员
 * 是正常结果，不是错误。
 */
public final class Reflect {

    /** 工具类，禁止实例化。 */
    private Reflect() {
    }

    /** 通过指定类加载器（通常是游戏的）加载类；失败返回 {@code null}。 */
    public static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, true, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 精确形参列表的 declared 方法（已置为可访问）；未找到返回 {@code null}。 */
    public static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用方法句柄；句柄为 {@code null} 或调用失败时返回 {@code null}。 */
    public static Object call(Method method, Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取静态字段值；字段缺失或不可访问时返回 {@code null}。 */
    public static Object staticField(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
