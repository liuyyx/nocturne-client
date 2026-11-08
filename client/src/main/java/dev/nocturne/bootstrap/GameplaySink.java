package dev.nocturne.bootstrap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏事件汇的<b>唯一状态持有者</b>：只存在于 bootstrap 搜索路径（由 agent 的 bridge jar 提供）。
 *
 * <p>为什么必须单独一个类：{@code GameplayHook} 会被不同的类加载器各加载一份（bootstrap 一份、
 * 26.3 Fabric 的 {@code KnotClassLoader} 又从暴露的 agent jar 定义一份），静态字段跟着分裂。
 * 真机表现为 {@code gameplay hook first tick event; sink=false}——织入的字节码调用的是没有安装
 * sink 的那一份，所有布尔门控退化成默认值（输入取消也随之失效）。
 *
 * <p>本类与 {@link KeySink} 同一套做法：交付 jar 里不含它（dist 排除 {@code dev/nocturne/bootstrap/**}），
 * 只以资源形式提供给 bootstrap bridge，因此所有加载器解析到的都是同一份。
 *
 * <p>接口身份也有陷阱：不同副本的 {@code GameplayHook.Sink} 是不同的 Class 对象。持有的是
 * {@code Object}，调用方用 {@link #adapt(Class)} 把它适配成本副本的接口——直接实现本副本接口时
 * 原样返回（零开销），否则用代理按方法名转发。
 *
 * <p>只依赖 JDK；不得 import 任何项目类。
 */
public final class GameplaySink {

    /** 真实的汇对象（通常是客户端加载器里的 {@code GameplayEvents}）。 */
    private static volatile Object target;
    /** 是否已直接织入 {@code sendPosition} 的 motion 钩子（跨副本共享）。 */
    private static volatile boolean motionDirect;
    /** 界面探测用的候选类名/成员名（由 agent 写入，绘制钩子只读）。 */
    private static volatile String[] probeMinecraft = new String[0];
    private static volatile String[] probeScreen = new String[0];
    private static volatile String[] probeGetInstance = new String[0];
    private static volatile String[] probeScreenFields = new String[0];

    /** 代理转发用的方法缓存：方法名 + 形参个数 → 目标方法。 */
    private static final ConcurrentHashMap<String, Method> METHODS =
            new ConcurrentHashMap<String, Method>();
    /**
     * 自销毁闸门：置位后所有注入钩子入口直接放行。
     *
     * <p>放在本类（bootstrap 唯一副本）而不是 {@code GameplayHook}：后者每个加载器一份，
     * 渲染线程读到的可能是另一份，闸门会失效。
     */
    private static volatile boolean killed;
    /**
     * 钩子能力表：id → 不可用原因（空串表示"已接线"）。
     *
     * <p>由 agent 在注册钩子时写入（映射面是否存在、转换器是否挂上），模块读取后显式降级，
     * 而不是"开关显示 ON 但什么都不做"。
     */
    private static final ConcurrentHashMap<String, String> HOOK_STATE =
            new ConcurrentHashMap<String, String>();

    private GameplaySink() {
    }

    /**
     * 安装（或替换）汇对象。
     *
     * @param replacement 汇实例；{@code null} 表示清除
     */
    public static void install(Object replacement) {
        target = replacement;
        // 目标换了，旧方法句柄可能属于另一个类，全部作废。
        METHODS.clear();
    }

    /** @return 当前汇对象；未安装时为 {@code null} */
    public static Object target() {
        return target;
    }

    /** 触发自销毁闸门；幂等。只置标志，不拆任何资源。 */
    public static void kill() {
        killed = true;
    }

    /**
     * 仅供测试：清空闸门与能力表，避免一个用例的自销毁状态污染同一 JVM 里的其它用例。
     * 生产路径不调用。
     */
    public static void resetForTests() {
        killed = false;
        HOOK_STATE.clear();
        target = null;
        motionDirect = false;
        METHODS.clear();
    }

    /** @return 是否已进入自销毁状态 */
    public static boolean isKilled() {
        return killed;
    }

    /** 登记一条"钩子已接线"。 */
    public static void markWired(String id) {
        if (id != null) {
            HOOK_STATE.put(id, "");
        }
    }

    /**
     * 登记一条"钩子在当前版本不可用"。
     *
     * @param id     能力 id
     * @param reason 原因；为空时按 {@code unsupported} 处理
     */
    public static void markUnavailable(String id, String reason) {
        if (id != null) {
            HOOK_STATE.put(id, reason == null || reason.isEmpty() ? "unsupported" : reason);
        }
    }

    /** @return 该能力是否已真实接线；未登记视为未知（false） */
    public static boolean isWired(String id) {
        String state = id == null ? null : HOOK_STATE.get(id);
        return state != null && state.isEmpty();
    }

    /** @return 该能力是否已被明确标记为不可用 */
    public static boolean isUnavailable(String id) {
        String state = id == null ? null : HOOK_STATE.get(id);
        return state != null && !state.isEmpty();
    }

    /** @return 不可用原因；已接线或未登记时为 {@code null} */
    public static String unavailableReason(String id) {
        String state = id == null ? null : HOOK_STATE.get(id);
        return state == null || state.isEmpty() ? null : state;
    }

    /**
     * 写入界面探测候选。
     *
     * <p>为什么放在本类：绘制钩子会被复制进 bootstrap 桥 jar，那一层看不到 agent 类与映射实现；
     * 候选只能以纯字符串 push 进来。放在 bootstrap 唯一副本里，任何加载器的钩子副本都读得到。
     */
    public static void configureProbe(String[] minecraft, String[] screen,
                                      String[] getInstance, String[] screenField) {
        probeMinecraft = copy(probeMinecraft, minecraft);
        probeScreen = copy(probeScreen, screen);
        probeGetInstance = copy(probeGetInstance, getInstance);
        probeScreenFields = copy(probeScreenFields, screenField);
    }

    public static String[] probeMinecraft() {
        return probeMinecraft;
    }

    public static String[] probeScreen() {
        return probeScreen;
    }

    public static String[] probeGetInstance() {
        return probeGetInstance;
    }

    public static String[] probeScreenFields() {
        return probeScreenFields;
    }

    /** @return 是否已由 agent 写入过完整候选；false 时绘制钩子不做界面探测 */
    public static boolean probeConfigured() {
        return probeMinecraft.length > 0 && probeScreen.length > 0
                && probeGetInstance.length > 0 && probeScreenFields.length > 0;
    }

    private static String[] copy(String[] current, String[] source) {
        if (source == null || source.length == 0) {
            return current;
        }
        String[] out = new String[source.length];
        int at = 0;
        for (String value : source) {
            if (value != null && !value.isEmpty()) {
                out[at++] = value;
            }
        }
        if (at == 0) {
            return current;
        }
        String[] trimmed = new String[at];
        System.arraycopy(out, 0, trimmed, 0, at);
        return trimmed;
    }

    public static void markMotionDirect() {
        motionDirect = true;
    }

    public static boolean isMotionDirect() {
        return motionDirect;
    }

    /**
     * 把共享汇适配成调用方副本的 {@code Sink} 接口。
     *
     * @param sinkType 调用方副本的 Sink 接口
     * @return 可直接强转为该接口的实例；无汇或适配失败时返回 {@code null}
     */
    public static Object adapt(Class<?> sinkType) {
        Object shared = target;
        if (shared == null || sinkType == null) {
            return null;
        }
        if (sinkType.isInstance(shared)) {
            return shared;
        }
        // 代理必须定义在能看到该接口的加载器里；bootstrap 接口（loader 为 null）没有可定义的命名空间，
        // 这种组合只会在同一份副本内出现，正常路径已经在上面的 isInstance 命中。
        ClassLoader loader = sinkType.getClassLoader();
        if (loader == null) {
            return null;
        }
        try {
            return Proxy.newProxyInstance(loader, new Class<?>[]{sinkType},
                    new Forwarder(shared));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把一个接口副本的方法按名字转发给共享汇对象。 */
    private static final class Forwarder implements InvocationHandler {

        private final Object delegate;

        Forwarder(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "NocturneGameplaySinkProxy";
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(proxy == (args == null ? null : args[0]));
                }
            }
            Method target = resolve(method);
            try {
                return target.invoke(delegate, args);
            } catch (InvocationTargetException wrapped) {
                // 把业务异常原样抛出，别让 Proxy 再包一层 UndeclaredThrowableException：
                // 否则钩子只看到 "UndeclaredThrowableException: null"，真因永远看不到。
                throw wrapped.getCause();
            }
        }

        private Method resolve(Method method) throws NoSuchMethodException {
            String key = method.getName() + "/" + method.getParameterCount();
            Method cached = METHODS.get(key);
            if (cached != null && cached.getDeclaringClass().isInstance(delegate)) {
                return cached;
            }
            Method found = delegate.getClass().getMethod(method.getName(),
                    method.getParameterTypes());
            METHODS.put(key, found);
            return found;
        }
    }
}
