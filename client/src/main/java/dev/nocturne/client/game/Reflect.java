package dev.nocturne.client.game;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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

    /**
     * 通过指定类加载器加载并<b>初始化</b>类；失败返回 {@code null}。
     *
     * <p><b>风险</b>：本方法会触发目标类的 {@code <clinit>}。若只想探测“类是否存在 / 是否已加载”，
     * 必须改用 {@link #loadWithoutInit(String, ClassLoader)}——对尚未初始化的类执行 {@code <clinit>}
     * 可能执行任意静态代码，一旦抛异常，JVM 会把该类永久标记为 erroneous，等于注入动作本身把游戏
     * 搞崩（M-73）。
     */
    public static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, true, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 通过指定类加载器加载类，但<b>不初始化</b>；失败返回 {@code null}。
     *
     * <p>用于类存在性 / 已加载状态的探测（跨任务契约 K2）：{@code Class.forName(name, false, loader)}
     * 不会执行 {@code <clinit>}，因此不会因静态初始化异常而把目标类标记为 erroneous。
     *
     * @param className 全限定类名
     * @param loader    类加载器
     * @return 类对象；未找到或加载失败返回 {@code null}
     */
    public static Class<?> loadWithoutInit(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 精确形参列表的 declared 方法（已尽力置为可访问）；未找到返回 {@code null}。
     *
     * @return 找到的方法；不存在返回 {@code null}；{@code setAccessible} 失败但方法本身公开时，
     *         仍返回该句柄（见 {@link #accessible(Method)}）
     */
    public static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            return accessible(owner.getDeclaredMethod(name, parameterTypes));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把方法置为可访问。
     *
     * <p>{@code setAccessible} 失败时，只要方法与其声明类都是 {@code public}，仍然返还句柄——
     * Java 9+ 对未 {@code open} 的包会抛 {@code InaccessibleObjectException}，此时 public 方法依旧
     * 可以直接 {@code invoke}，没有理由把可用的公开路径一并丢掉（M-79）。
     *
     * @param method 目标方法
     * @return 可直接使用的方法句柄；彻底不可用时返回 {@code null}
     */
    static Method accessible(Method method) {
        try {
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                return method;
            }
            return null;
        }
    }

    /**
     * 把字段置为可访问；语义与 {@link #accessible(Method)} 相同：{@code setAccessible} 失败但字段与
     * 声明类均 public 时仍返回句柄。
     *
     * @param field 目标字段
     * @return 可直接使用的字段句柄；彻底不可用时返回 {@code null}
     */
    static Field accessible(Field field) {
        try {
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isPublic(field.getDeclaringClass().getModifiers())) {
                return field;
            }
            return null;
        }
    }

    /** TEMP-DIAG：已经报告过的失败方法，避免每帧刷屏。 */
    private static final java.util.Set<String> REPORTED_FAILURES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 调用方法句柄；句柄为 {@code null} 或调用失败时返回 {@code null}。 */
    public static Object call(Method method, Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            reportFailure(method, t, args);
            return null;
        }
    }

    /**
     * TEMP-DIAG：在 {@code -Dnocturne.ui.debug} 打开时，把"静默失败"的调用打到日志。
     *
     * <p>反射调用失败原本被刻意吞掉（版本差异属于正常结果）；但 GL 调用失败会导致
     * "无报错、无图形"，必须先看得见。
     */
    private static void reportFailure(Method method, Throwable t, Object[] args) {
        if (System.getProperty("nocturne.ui.debug") == null) {
            return;
        }
        String key = method.getDeclaringClass().getName() + "." + method.getName();
        if (!REPORTED_FAILURES.add(key)) {
            return;
        }
        StringBuilder b = new StringBuilder("[nocturne] REFLECT FAIL ").append(key).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        b.append(") -> ").append(t);
        System.err.println(b);
    }

    /** 读取静态字段值；字段缺失或不可访问时返回 {@code null}。 */
    public static Object staticField(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field field = accessible(owner.getDeclaredField(name));
            return field == null ? null : field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
