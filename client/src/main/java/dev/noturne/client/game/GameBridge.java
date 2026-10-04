package dev.noturne.client.game;

import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.mapping.Mapping;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通往运行中游戏的反射桥接层，完全由 {@link Mapping} 驱动。
 *
 * <p>这里的一切名字都来自映射表，因此同一份代码既能跑在混淆过的 1.8.9 客户端上，也能跑在未混淆的
 * 26.x 版本上。类尚未找到时 {@link #resolve()} 会重新执行查找，以适应类加载器被替换
 * （如重载、attach 后重新引导）的情况；成功解析出的句柄才会进入缓存。
 *
 * <p>已解析出的类/访问器以及字段/方法句柄都会缓存：字段与方法查找在继承链上逐级试探会产生大量
 * 异常分配，若每个 tick 重来一次会持续制造垃圾（M-71/M-72）。缓存只记录成功的查找结果，
 * 失败一律不缓存，因此类晚一点加载仍然能被后续调用重新解析。
 */
public final class GameBridge {

    /**
     * 未解析成功时的重试间隔（纳秒）。{@code instrumentation} 路径的类查找要遍历全部已加载类，
     * 若类尚未加载就每帧全表扫描一次，代价随类数线性增长（M-71）；用固定间隔把重试频率压下来。
     */
    private static final long RESOLVE_RETRY_INTERVAL_NANOS = 250_000_000L;

    /** Agent 提供的仪表接口，可为 {@code null}（此时退回到线程上下文类加载器查找）。 */
    private final Instrumentation instrumentation;
    private final Mapping mapping;

    /** 已解析出的主游戏类；未解析成功时为 {@code null}，须在游戏主线程之外访问。 */
    private volatile Class<?> minecraftClass;
    /** 主游戏类的单例访问器（静态、无参）；未解析成功时为 {@code null}。 */
    private volatile Method getInstanceMethod;
    /** 上次尝试解析失败的时间戳（纳秒）；0 表示尚未尝试。用于限流全表扫描。 */
    private volatile long lastResolveAttemptNanos;

    /** 已成功定位的字段句柄，键为（宿主类，字段名）。 */
    private final ConcurrentHashMap<FieldKey, Field> fieldCache = new ConcurrentHashMap<FieldKey, Field>();
    /** 已成功定位的方法句柄，键为（宿主类，方法名，形参签名）。 */
    private final ConcurrentHashMap<MethodKey, Method> methodCache = new ConcurrentHashMap<MethodKey, Method>();
    /** 日志限流计数器，避免热路径上的失败信息刷屏。 */
    private final AtomicLong logCount = new AtomicLong();

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
     * <p>幂等：一旦 {@link #isResolved()} 为 true 就直接返回，不重复反射查找。查找失败会按
     * {@link #RESOLVE_RETRY_INTERVAL_NANOS} 限流，避免未解析状态下每个 tick 全表扫描已加载类。
     * 只有当类已加载、且确实存在一个静态、非 void、返回该游戏类的 {@code getInstance} 访问器时才
     * 提交结果；否则保持未解析，下次继续尝试（M-70）。
     *
     * @return 定位成功返回 true；类尚未加载或访问器不存在时返回 false，不抛异常
     */
    public boolean resolve() {
        if (isResolved()) {
            return true;
        }
        long now = System.nanoTime();
        long last = lastResolveAttemptNanos;
        if (last != 0L && now - last < RESOLVE_RETRY_INTERVAL_NANOS) {
            return false;
        }
        lastResolveAttemptNanos = now;

        Class<?> found = findLoadedClass(minecraftClassCandidates());
        if (found == null) {
            return false;
        }
        String descriptor = "()L" + found.getName().replace('.', '/') + ";";
        String methodName = mapping.methodName(ClassType.MINECRAFT, "getInstance", descriptor);
        Method accessor = findStaticAccessor(found, methodName, found);
        if (accessor == null) {
            return false;
        }
        this.minecraftClass = found;
        this.getInstanceMethod = accessor;
        return true;
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
        Method accessor = getInstanceMethod;
        try {
            return accessor.invoke(null);
        } catch (Throwable t) {
            // 访问器失效（类被替换/重载）时不留下恒为 true 的“已解析”状态，否则桥会永久失效；
            // 清除后下一次调用会重新解析并重试（M-70）。
            invalidate();
            logThrottled("getInstance() invoke failed; cleared resolution for retry", t);
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
     * <p>描述符正是用来区分同名混淆方法的手段；{@link Mapping#hasMethodDescriptor} 显式区分
     * 「表中无记录」（恒等映射、或表缺条目——此时按实参运行时类型推导形参）与「确有描述符」
     * （含零参的 {@code ()...}），不再把 {@code null} 当成空参数组（H-35）。恒等映射下会按实参
     * 在类/父类/接口上匹配唯一重载；匹配不到或存在歧义时记录限流日志并返回 {@code null}（M-69）。
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
        Class<?> type = target.getClass();
        boolean hasDescriptor = mapping.hasMethodDescriptor(owner, canonicalMethod);
        String descriptor = hasDescriptor ? mapping.methodDescriptor(owner, canonicalMethod) : null;
        String methodName = mapping.methodName(owner, canonicalMethod, descriptor == null ? "" : descriptor);
        Class<?>[] parameters = hasDescriptor
                ? descriptorParameters(type, methodName, descriptor)
                : inferParameters(type, methodName, args);
        if (parameters == null) {
            return null;
        }
        Method method = findMethodCached(type, methodName, parameters);
        return Reflect.call(method, target, args);
    }

    /** 解析表中记录的描述符；结构非法时记录限流日志并返回 {@code null}。 */
    private Class<?>[] descriptorParameters(Class<?> type, String methodName, String descriptor) {
        Class<?>[] parameters = JniTypes.parameterTypes(descriptor, type.getClassLoader());
        if (parameters == null) {
            logThrottled("unparsable descriptor for " + methodName + ": " + descriptor, null);
        }
        return parameters;
    }

    /**
     * 在恒等映射（无描述符）下按实参类型推导形参列表：只接受参数个数匹配且每个参数与实参兼容的
     * 唯一重载；若仍有多个候选，再尝试精确类型匹配，仍不唯一则记为歧义并返回 {@code null}。
     */
    private Class<?>[] inferParameters(Class<?> type, String methodName, Object[] args) {
        int expected = args == null ? 0 : args.length;
        List<Method> candidates = new ArrayList<Method>();
        for (Method method : collectMethods(type)) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != expected) {
                continue;
            }
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!isCompatible(parameters[i], args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                candidates.add(method);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0).getParameterTypes();
        }
        if (candidates.size() > 1) {
            List<Method> exact = new ArrayList<Method>();
            for (Method method : candidates) {
                if (exactMatch(method.getParameterTypes(), args)) {
                    exact.add(method);
                }
            }
            if (exact.size() == 1) {
                return exact.get(0).getParameterTypes();
            }
            logThrottled("ambiguous overload for " + methodName + " " + describeArgs(args), null);
            return null;
        }
        logThrottled("no overload for " + methodName + " " + describeArgs(args), null);
        return null;
    }

    /**
     * 在类及其父类链、并在各级实现的接口上查找方法。
     *
     * <p>接口上的方法（含默认方法）也必须可达：{@code getSuperclass()} 对接口返回 {@code null}，
     * 只沿父类上溯会漏掉接口声明（M-78）。
     *
     * @return 找到的可访问 {@link Method}；未找到返回 {@code null}
     */
    private Method findMethod(Class<?> type, String name, Class<?>[] parameters) {
        for (Method method : collectMethods(type)) {
            if (method.getName().equals(name) && sameParameters(method.getParameterTypes(), parameters)) {
                Method accessible = Reflect.accessible(method);
                if (accessible != null) {
                    return accessible;
                }
            }
        }
        return null;
    }

    /** 命中缓存则直接返回；未命中时查找，仅缓存成功结果以便失败可重试。 */
    private Method findMethodCached(Class<?> type, String name, Class<?>[] parameters) {
        MethodKey key = new MethodKey(type, name, signature(parameters));
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        Method found = findMethod(type, name, parameters);
        if (found != null) {
            methodCache.putIfAbsent(key, found);
        }
        return found;
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
        Field field = findFieldCached(target.getClass(), name);
        if (field == null) {
            return false;
        }
        try {
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
        Field field = findFieldCached(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 命中缓存则直接返回；未命中时查找，仅缓存成功结果以便失败可重试。 */
    private Field findFieldCached(Class<?> type, String name) {
        FieldKey key = new FieldKey(type, name);
        Field cached = fieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        Field found = findField(type, name);
        if (found == null) {
            return null;
        }
        Field accessible = Reflect.accessible(found);
        if (accessible == null) {
            return null;
        }
        fieldCache.putIfAbsent(key, accessible);
        return accessible;
    }

    /** 清除已解析状态，使下一次 {@link #resolve()} 重新查找。 */
    private void invalidate() {
        minecraftClass = null;
        getInstanceMethod = null;
        lastResolveAttemptNanos = 0L;
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

    /** 在类/父类/接口上查找静态、无参、返回类型可赋给 {@code expectedReturn} 的访问器。 */
    private Method findStaticAccessor(Class<?> type, String name, Class<?> expectedReturn) {
        Method fallback = null;
        for (Method method : collectMethods(type)) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 0) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) {
                continue;
            }
            if (!expectedReturn.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Method accessible = Reflect.accessible(method);
            if (accessible == null) {
                continue;
            }
            if (method.getReturnType() == expectedReturn) {
                return accessible;
            }
            if (fallback == null) {
                fallback = accessible;
            }
        }
        return fallback;
    }

    /** 沿类继承链向上查找声明的字段；未找到返回 {@code null}。 */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类上溯
            }
        }
        return null;
    }

    /**
     * 收集类、其父类链以及各级所实现接口上声明的全部方法，按“本类 → 父类 → 接口”的顺序去重。
     *
     * <p>接口必须单独遍历：只走 {@code getSuperclass()} 会漏掉接口上的默认方法（M-78）。
     */
    private static List<Method> collectMethods(Class<?> type) {
        List<Method> out = new ArrayList<Method>();
        collectMethods(type, out, new HashSet<String>());
        return out;
    }

    /** 递归收集的辅助实现；{@code seen} 以“名字+形参签名”去重，避免重复扫描同一签名。 */
    private static void collectMethods(Class<?> type, List<Method> out, Set<String> seen) {
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (seen.add(method.getName() + signature(method.getParameterTypes()))) {
                out.add(method);
            }
        }
        collectMethods(type.getSuperclass(), out, seen);
        for (Class<?> iface : type.getInterfaces()) {
            collectMethods(iface, out, seen);
        }
    }

    /** 形参列表是否逐项相等（用于按描述符挑选重载）。 */
    private static boolean sameParameters(Class<?>[] declared, Class<?>[] expected) {
        if (declared.length != expected.length) {
            return false;
        }
        for (int i = 0; i < declared.length; i++) {
            if (declared[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** 实参能否赋给形参：引用类型走 {@code isInstance}，原始类型只接受对应包装类。 */
    private static boolean isCompatible(Class<?> parameter, Object argument) {
        if (argument == null) {
            return !parameter.isPrimitive();
        }
        if (parameter.isInstance(argument)) {
            return true;
        }
        return parameter.isPrimitive() && boxed(parameter).isInstance(argument);
    }

    /** 实参类型与形参类型是否逐一完全一致（用于在多个候选中挑精确匹配）。 */
    private static boolean exactMatch(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            Object argument = args[i];
            if (argument == null) {
                if (parameter.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (parameter.isPrimitive()) {
                parameter = boxed(parameter);
            }
            if (parameter != argument.getClass()) {
                return false;
            }
        }
        return true;
    }

    /** 原始类型到包装类型的映射；非原始类型原样返回。 */
    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    /** 形参签名（用于缓存键与去重），如 {@code Ljava/lang/String;II}。 */
    private static String signature(Class<?>[] parameters) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> parameter : parameters) {
            sb.append(parameter.getName()).append(',');
        }
        return sb.toString();
    }

    /** 实参的人类可读描述，用于歧义/未命中的限流日志。 */
    private static String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getName());
        }
        return sb.append(')').toString();
    }

    /** 限流日志：前若干次与之后每 {@code 600} 次打印一条，避免热路径刷屏。 */
    private void logThrottled(String message, Throwable cause) {
        long count = logCount.incrementAndGet();
        if (count <= 5 || count % 600 == 0) {
            System.out.println("[noturne] GameBridge: " + message + (cause == null ? "" : " (" + cause + ")"));
        }
    }

    /** 字段句柄缓存键：宿主类 + 字段名。 */
    private static final class FieldKey {
        private final Class<?> owner;
        private final String name;

        FieldKey(Class<?> owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldKey)) {
                return false;
            }
            FieldKey key = (FieldKey) other;
            return owner == key.owner && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(owner) * 31 + name.hashCode();
        }
    }

    /** 方法句柄缓存键：宿主类 + 方法名 + 形参签名。 */
    private static final class MethodKey {
        private final Class<?> owner;
        private final String name;
        private final String signature;

        MethodKey(Class<?> owner, String name, String signature) {
            this.owner = owner;
            this.name = name;
            this.signature = signature;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MethodKey)) {
                return false;
            }
            MethodKey key = (MethodKey) other;
            return owner == key.owner && name.equals(key.name) && signature.equals(key.signature);
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(owner) * 31 + name.hashCode()) * 31 + signature.hashCode();
        }
    }
}
