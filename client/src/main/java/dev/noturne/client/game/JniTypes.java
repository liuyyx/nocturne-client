package dev.noturne.client.game;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JNI descriptor parser.
 *
 * <p>Obfuscation collapses many methods onto the same short name ({@code a}, {@code b}, …), so the
 * only reliable way to select one reflectively is by parameter types. The mapping table stores the
 * JNI descriptor; this turns it into the {@code Class[]} that {@code getDeclaredMethod} needs.
 */
public final class JniTypes {

    private JniTypes() {
    }

    /**
     * Parses the parameter list of a method descriptor.
     *
     * @return parameter classes, or {@code null} when the descriptor is malformed or a referenced
     *         class cannot be resolved
     */
    public static Class<?>[] parameterTypes(String descriptor, ClassLoader loader) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return null;
        }
        List<Class<?>> parameters = new ArrayList<Class<?>>();
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            int[] cursor = new int[]{index};
            Class<?> type = parseType(descriptor, index, loader, cursor);
            if (type == null) {
                return null;
            }
            parameters.add(type);
            index = cursor[0];
        }
        if (index >= descriptor.length()) {
            return null; // unterminated
        }
        return parameters.toArray(new Class<?>[0]);
    }

    /** Parses the return type of a method descriptor, or {@code null}. */
    public static Class<?> returnType(String descriptor, ClassLoader loader) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return null;
        }
        int close = descriptor.indexOf(')');
        if (close < 0 || close + 1 >= descriptor.length()) {
            return null;
        }
        int[] cursor = new int[]{close + 1};
        return parseType(descriptor, close + 1, loader, cursor);
    }

    private static Class<?> parseType(String descriptor, int index, ClassLoader loader, int[] cursor) {
        if (index >= descriptor.length()) {
            return null;
        }
        char kind = descriptor.charAt(index);
        switch (kind) {
            case 'Z':
                cursor[0] = index + 1;
                return boolean.class;
            case 'B':
                cursor[0] = index + 1;
                return byte.class;
            case 'C':
                cursor[0] = index + 1;
                return char.class;
            case 'S':
                cursor[0] = index + 1;
                return short.class;
            case 'I':
                cursor[0] = index + 1;
                return int.class;
            case 'J':
                cursor[0] = index + 1;
                return long.class;
            case 'F':
                cursor[0] = index + 1;
                return float.class;
            case 'D':
                cursor[0] = index + 1;
                return double.class;
            case 'V':
                cursor[0] = index + 1;
                return void.class;
            case 'L': {
                int end = descriptor.indexOf(';', index);
                if (end < 0) {
                    return null;
                }
                cursor[0] = end + 1;
                return load(descriptor.substring(index + 1, end).replace('/', '.'), loader);
            }
            case '[': {
                Class<?> component = parseType(descriptor, index + 1, loader, cursor);
                return component == null ? null : Array.newInstance(component, 0).getClass();
            }
            default:
                return null;
        }
    }

    private static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }
}
