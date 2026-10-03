package dev.noturne.client.game;

import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.mapping.Mapping;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection bridge to the running game, driven entirely by a {@link Mapping}.
 *
 * <p>Everything here goes through names supplied by the mapping, so the same code works on an
 * obfuscated 1.8.9 client and on an unobfuscated 26.x build. Nothing is cached across a class
 * loader change: {@link #resolve()} re-runs the lookup when the class is not found.
 */
public final class GameBridge {

    private final Instrumentation instrumentation;
    private final Mapping mapping;

    private Class<?> minecraftClass;
    private Method getInstanceMethod;

    public GameBridge(Instrumentation instrumentation, Mapping mapping) {
        this.instrumentation = instrumentation;
        this.mapping = mapping;
    }

    public Mapping mapping() {
        return mapping;
    }

    /**
     * Candidate runtime names for the main game class, most specific first.
     *
     * <p>The mapped (obfuscated) name wins; the canonical name is kept as a fallback because
     * Forge/SRG and unobfuscated builds expose the readable name.
     */
    public String[] minecraftClassCandidates() {
        String mapped = mapping.className(ClassType.MINECRAFT);
        String canonical = ClassType.MINECRAFT.canonicalName();
        return mapped.equals(canonical) ? new String[]{canonical} : new String[]{mapped, canonical};
    }

    public boolean isResolved() {
        return minecraftClass != null && getInstanceMethod != null;
    }

    /** Locates the game class and its singleton accessor. Safe to call repeatedly. */
    public boolean resolve() {
        if (isResolved()) {
            return true;
        }
        Class<?> found = findLoadedClass(minecraftClassCandidates());
        if (found == null) {
            return false;
        }
        String descriptor = "()L" + found.getName().replace('.', '/') + ";";
        String methodName = mapping.methodName(ClassType.MINECRAFT, "getInstance", descriptor);
        try {
            Method method = found.getDeclaredMethod(methodName);
            method.setAccessible(true);
            this.minecraftClass = found;
            this.getInstanceMethod = method;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Class<?> minecraftClass() {
        return minecraftClass;
    }

    /** The game singleton, or {@code null} when the class cannot be reached yet. */
    public Object minecraft() {
        if (!resolve()) {
            return null;
        }
        try {
            return getInstanceMethod.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The local player, or {@code null} while not in a world. */
    public Object player() {
        Object minecraft = minecraft();
        return minecraft == null ? null : readField(minecraft, ClassType.MINECRAFT, "player");
    }

    public boolean inWorld() {
        return player() != null;
    }

    /**
     * Invokes a mapped method, selecting the overload by the recorded JNI descriptor.
     *
     * <p>The descriptor is what disambiguates obfuscated methods that share a name.
     */
    public Object callMapped(Object target, ClassType owner, String canonicalMethod, Object... args) {
        if (target == null) {
            return null;
        }
        String descriptor = mapping.methodDescriptor(owner, canonicalMethod);
        String methodName = mapping.methodName(owner, canonicalMethod, descriptor == null ? "" : descriptor);
        Method method = findMethod(target.getClass(), methodName, descriptor);
        return Reflect.call(method, target, args);
    }

    private Method findMethod(Class<?> type, String name, String descriptor) {
        Class<?>[] parameters = descriptor == null
                ? new Class<?>[0]
                : JniTypes.parameterTypes(descriptor, type.getClassLoader());
        if (parameters == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Writes a mapped field. Returns true when the write landed. */
    public boolean writeField(Object target, ClassType owner, String canonicalField, Object value) {
        if (target == null) {
            return false;
        }
        String name = mapping.fieldName(owner, canonicalField);
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reads a mapped field, walking up the class hierarchy. */
    public Object readField(Object target, ClassType owner, String canonicalField) {
        if (target == null) {
            return null;
        }
        String name = mapping.fieldName(owner, canonicalField);
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private Class<?> findLoadedClass(String[] names) {
        for (String name : names) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

    private Class<?> findLoadedClass(String name) {
        if (instrumentation != null) {
            for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
                if (candidate.getName().equals(name)) {
                    return candidate;
                }
            }
            return null;
        }
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }
}
