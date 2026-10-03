package dev.noturne.client.game;

import java.lang.reflect.Method;

/**
 * Small reflective helpers used to reach into the game / LWJGL without a compile-time dependency.
 *
 * <p>Every helper degrades to {@code null} rather than throwing: probing a class or member that a
 * particular game version does not expose is a normal outcome, not an error.
 */
public final class Reflect {

    private Reflect() {
    }

    /** Loads a class through the given loader (usually the game's), or {@code null}. */
    public static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, true, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Declared method with the exact parameter list, or {@code null}. */
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

    /** Invokes a method handle, returning {@code null} on any failure. */
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

    /** Static field value, or {@code null}. */
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
