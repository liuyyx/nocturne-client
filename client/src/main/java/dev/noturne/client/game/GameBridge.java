package dev.noturne.client.game;

import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.mapping.Mapping;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通往运行中游戏的反射桥接层，完全由 {@link Mapping} 驱动。
 *
 * <p>这里的一切名字都来自映射表，因此同一份代码既能跑在混淆过的 1.8.9 客户端上，也能跑在未混淆的
 * 26.x 版本上。跨类加载器不做缓存：类尚未找到时 {@link #resolve()} 会重新执行查找，
 * 以适应类加载器被替换（如重载、attach 后重新引导）的情况。
 */
public final class GameBridge {

    /** Agent 提供的仪表接口，可为 {@code null}（此时退回到线程上下文类加载器查找）。 */
    private final Instrumentation instrumentation;
    private final Mapping mapping;

    /** 已解析出的主游戏类；未解析成功时为 {@code null}，须在游戏主线程之外访问。 */
    private Class<?> minecraftClass;
    /** 主游戏类的单例访问器（静态、无参）；未解析成功时为 {@code null}。 */
    private Method getInstanceMethod;

    /**
     * 构造反射桥。
     *
     * @param instrumentation agent 注入的 {@link Instrumentation}，可为 {@code null}
     * @param mapping         提供混淆名/描述符的映射表，不可为 {@code null}
     */
    public GameBridge(Instrumentation instrumentation, Mapping mapping) {
        this.instrumentation = instrumentation;
        this.mapping = mapping;
    }

    /** @return 本桥持有的映射表（与构造参数相同） */
    public Mapping mapping() {
        return mapping;
    }

    /**
     * 主游戏类的候选运行时名称，按优先级从高到低排列。
     *
     * <p>映射得到的（混淆）名优先；规范名作为兜底保留，因为 Forge/SRG 环境以及未混淆版本
     * 暴露的是可读名。
     */
    public String[] minecraftClassCandidates() {
        String mapped = mapping.className(ClassType.MINECRAFT);
        String canonical = ClassType.MINECRAFT.canonicalName();
        return mapped.equals(canonical) ? new String[]{canonical} : new String[]{mapped, canonical};
    }

    /** @return 游戏类与单例访问器是否都已定位成功；两者齐备才算解析完成 */
    public boolean isResolved() {
        return minecraftClass != null && getInstanceMethod != null;
    }

    /**
     * 定位游戏类及其单例访问器，可安全地重复调用。
     *
     * <p>幂等：一旦 {@link #isResolved()} 为 true 就直接返回，不重复反射查找。
     *
     * @return 定位成功返回 true；类尚未加载或访问器不存在时返回 false，不抛异常
     */
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

    /** @return 已解析的游戏主类引用；未解析时为 {@code null}，不应缓存该结果 */
    public Class<?> minecraftClass() {
        return minecraftClass;
    }

    /** @return 游戏单例；类尚未可达或调用失败时为 {@code null} */
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

    /** @return 本地玩家；尚未进入世界时为 {@code null} */
    public Object player() {
        Object minecraft = minecraft();
        return minecraft == null ? null : readField(minecraft, ClassType.MINECRAFT, "player");
    }

    /** @return 当前是否处于世界中（本地玩家已可用） */
    public boolean inWorld() {
        return player() != null;
    }

    /**
     * 调用一个映射方法，按记录的 JNI 描述符挑选重载。
     *
     * <p>描述符正是用来区分同名混淆方法的手段。
     *
     * @param target          接收调用的实例，可为 {@code null}（直接返回 {@code null}）
     * @param owner           声明该方法的类
     * @param canonicalMethod 未混淆的规范方法名
     * @param args            实参；反射调用会按目标方法的形参自动装箱
     * @return 方法返回值；方法未找到或调用失败时为 {@code null}
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

    /**
     * 在类及其父类链上按名称与形参列表查找方法。
     *
     * @return 找到的可访问 {@link Method}；未找到返回 {@code null}
     */
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
                // 继续向父类上溯，父类也可能声明了该方法
            }
        }
        return null;
    }

    /**
     * 写入一个映射字段。
     *
     * @return 写入真正生效返回 true；字段缺失、不可访问或类型不匹配返回 false
     */
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

    /** 读取一个映射字段，沿类继承链向上查找声明处。 */
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

    /** 依次尝试候选名称，返回第一个已加载的类；全部未加载返回 {@code null}。 */
    private Class<?> findLoadedClass(String[] names) {
        for (String name : names) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

    /**
     * 按名称查找已加载的类。
     *
     * <p>优先扫描 {@code instrumentation} 的已加载类表——它能看见游戏的自定义类加载器加载的类；
     * 没有仪表时退回线程上下文类加载器。
     */
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

    /** 沿类继承链向上查找声明的字段；未找到返回 {@code null}。 */
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
