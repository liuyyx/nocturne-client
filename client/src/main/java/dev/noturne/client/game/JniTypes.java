package dev.noturne.client.game;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 JNI 描述符解析器。
 *
 * <p>混淆会把大量方法压到相同的短名上（{@code a}、{@code b}……），因此反射选择某一条的唯一可靠手段
 * 就是按参数类型来定位。映射表保存的是 JNI 描述符，本类负责把它转成
 * {@code getDeclaredMethod} 所需的 {@code Class[]}。
 */
public final class JniTypes {

    /** 工具类，禁止实例化。 */
    private JniTypes() {
    }

    /**
     * 解析方法描述符的参数列表。
     *
     * @param descriptor 形如 {@code (ILjava/lang/String;)V} 的 JNI 描述符
     * @param loader     解析引用类型时使用的类加载器，通常是目标类的加载器
     * @return 参数类数组；描述符格式错误或引用的类无法解析时返回 {@code null}
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
            return null; // 参数列表缺少右括号，未正常结束
        }
        return parameters.toArray(new Class<?>[0]);
    }

    /** 解析方法描述符的返回类型；无返回类型时为 {@code void.class}，格式错误时为 {@code null}。 */
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

    /**
     * 解析单个类型描述符。
     *
     * <p>游标通过 {@code int[1]} 出参回写——这是为了在递归解析数组维度时向上层返回下一个待解析位置。
     *
     * @return 解析出的类型；未知类型字符或缺少分号等非法输入返回 {@code null}
     */
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
                // 数组类型由元素类型构造零长数组取得其 Class，避免手写数组类名
                Class<?> component = parseType(descriptor, index + 1, loader, cursor);
                return component == null ? null : Array.newInstance(component, 0).getClass();
            }
            default:
                return null;
        }
    }

    /** 按名称加载引用类型（不初始化），失败时返回 {@code null}。 */
    private static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }
}
