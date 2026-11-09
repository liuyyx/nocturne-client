package dev.nocturne.agent.transform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import dev.nocturne.core.load.PayloadLoader;
import dev.nocturne.core.pack.PayloadKey;

/**
 * 从自身资源加载 ASM 与帧钩子转换器的隔离类加载器。
 *
 * <p>目标 JVM（原版 / Forge 1.8.9 等）的类路径上没有 ASM，而 dist 产物又刻意不在 jar 根展开
 * {@code org/objectweb/asm/**}（避免与 Fabric/Forge 自带的 ASM 冲突）。因此 ASM 以
 * <b>加密载荷</b>形式内嵌为资源 {@code dev/nocturne/agent/asm.pack}（{@code PayloadPack} 格式：
 * deflate + AES-256-GCM），运行时在内存里解密并加载——不落任何临时文件（K4）。
 *
 * <p>载荷密钥来自 {@link PayloadKey#asmPayload()}：种子写在代码里，因此它只提高零成本静态扫描的
 * 门槛，不构成对定向逆向的防护（详见该类 javadoc 的威胁模型）。
 *
 * <p>父加载器可见性上做 child-first 的有两组名前缀：
 * <ul>
 *   <li>{@code org.objectweb.asm.}：优先用内嵌 ASM，绝不落到目标 JVM 可能存在的旧版本上；</li>
 *   <li>{@code dev.nocturne.agent.transform.FrameHookTransformer}（含匿名内部类）：让转换器与它引用的
 *       ASM 处在同一个加载器里，这样转换器的 ASM 依赖才解析得到内嵌副本。</li>
 * </ul>
 * 其余类（{@code java.lang.instrument.*} 等）一律委派给父加载器。
 *
 * <p>加载失败（资源缺失、载荷损坏、密钥不符、ASM 版本不兼容）时 {@link #create()} 返回
 * {@code null} 并打日志，调用方据此跳过帧钩子安装，其余功能（客户端引导、叠加层）不受影响。
 */
public final class EmbeddedAsmLoader extends ClassLoader {
    /** 与 MemoryClassLoader 同源：声明「按类名并行可加载」，否则所有类加载串行在 loader 监视器上。 */
    static {
        ClassLoader.registerAsParallelCapable();
    }


    /** 内嵌 ASM 加密载荷的资源路径，由 dist 打包任务固定写入（K3）。 */
    private static final String ASM_PACK_RESOURCE = "dev/nocturne/agent/asm.pack";

    /** 需要 child-first 的 ASM 包前缀。 */
    private static final String ASM_PACKAGE_PREFIX = "org.objectweb.asm.";

    /** 需要 child-first 的转换器包前缀（覆盖 FrameHookTransformer、CallbackHookTransformer 及匿名内部类）。 */
    private static final String TRANSFORMER_PREFIX = "dev.nocturne.agent.transform.";

    /** 转换器类名与钩子目标，与 {@link FrameHookTransformer} 的契约一致。 */
    private static final String TRANSFORMER_CLASS = "dev.nocturne.agent.transform.FrameHookTransformer";
    /** 在目标方法开头插入「占用输入时直接返回」的转换器实现类。 */
    private static final String INPUT_BLOCK_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.InputBlockTransformer";
    /** 首参回调转换器类名（与 {@link CallbackHookTransformer} 的契约一致）。 */
    private static final String CALLBACK_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.CallbackHookTransformer";
    /** 通用事件通知/取消转换器。 */
    private static final String GATE_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.GateHookTransformer";
    /** 支持包替换与取消的转换器。 */
    private static final String PACKET_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.PacketHookTransformer";
    /** 实际位移实参改写转换器。 */
    private static final String MOVE_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.MoveHookTransformer";
    /** 滚轮形参转换器：取指定的 double 形参（GLFW 世代 {@code MouseHandler#onScroll}）。 */
    private static final String SCROLL_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.ScrollHookTransformer";
    /** 世界空间 3D 钩子：在 void 方法的每个 RETURN 前传首个 float 形参。 */
    private static final String WORLD_RENDER_TRANSFORMER_CLASS =
            "dev.nocturne.agent.transform.WorldRenderHookTransformer";
    /** 钩子所在类内部名（斜杠形式）。 */
    // 必须指向 bootstrap 层的中立类：Forge 的模块化 classloader 看不到 agent jar，
    // 直接调 NocturneRuntime.onFrame 会 NoClassDefFoundError（1.20.1 Forge 实测）。
    private static final String HOOK_OWNER = "dev/nocturne/client/runtime/FrameDispatcher";
    /** 钩子方法名。 */
    private static final String HOOK_METHOD = "dispatch";

    /** ASM 类名（点号）→ 字节码，构造时一次性从内嵌 jar 读出。 */
    private final Map<String, byte[]> asmClasses;
    /** 转换器类名（点号）→ 字节码，按需从父加载器资源读取并缓存。 */
    private final Map<String, byte[]> transformerClasses = new HashMap<String, byte[]>();
    /** 父加载器，用于委派非 child-first 的类。 */
    private final ClassLoader parent;

    private EmbeddedAsmLoader(ClassLoader parent, Map<String, byte[]> asmClasses) {
        super(parent);
        this.parent = parent;
        this.asmClasses = asmClasses;
    }

    /**
     * 创建一个可用的内嵌 ASM 加载器。
     *
     * @return 加载器；内嵌资源缺失或损坏时返回 {@code null}（已打日志），调用方应跳过帧钩子
     */
    public static EmbeddedAsmLoader create() {
        ClassLoader parent = EmbeddedAsmLoader.class.getClassLoader();
        Map<String, byte[]> asm;
        try {
            asm = readAsmPayload(parent);
        } catch (Throwable t) {
            log("embedded ASM payload unusable (" + ASM_PACK_RESOURCE + "): " + t
                    + "; frame hook disabled");
            return null;
        }
        if (asm.isEmpty()) {
            log("embedded ASM payload missing or empty: " + ASM_PACK_RESOURCE
                    + "; frame hook disabled");
            return null;
        }
        return new EmbeddedAsmLoader(parent, asm);
    }

    /**
     * 创建一个「开头捕获、末尾消费」的双端回调转换器。
     *
     * <p>用于 1.16–1.19 的 {@code Gui.render(PoseStack,float)}：末尾路径上形参已被 StackMapTable
     * 标记为 {@code top}，末尾 {@code aload} 会触发 {@code VerifyError}（1.16.5 实测）。
     *
     * @param targetInternalName 目标类内部名
     * @param method             目标方法名
     * @param descriptor         目标方法描述符
     * @param hookOwner          钩子类内部名
     * @param hookMethod         开头捕获钩子，签名 {@code (Ljava/lang/Object;)V}
     * @param deferredMethod     末尾消费钩子，签名 {@code ()V}
     * @return 转换器；内嵌 ASM 缺失或类加载失败时返回 {@code null}
     */
    public ClassFileTransformer createDeferredCallbackTransformer(String targetInternalName,
                                                                  String method, String descriptor,
                                                                  String hookOwner, String hookMethod,
                                                                  String deferredMethod) {
        try {
            Class<?> transformerClass = loadClass(CALLBACK_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    boolean.class, String.class);
            Object instance = constructor.newInstance(
                    targetInternalName, method, descriptor, hookOwner, hookMethod,
                    Boolean.FALSE, deferredMethod);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create deferred callback hook transformer for " + targetInternalName
                    + "." + method + ": " + t + "; callback hook disabled");
            return null;
        }
    }

    /**
     * 创建一个「首参回调」转换器：把目标方法的**首个引用形参**交给钩子。
     *
     * <p>与 {@link #createTransformer} 的唯一区别是转换器实现类——那一个注入**无参**调用（帧交换点），
     * 这一个注入**带一个对象参数**的调用（绘制上下文就在形参里的那代 API）。两者都用同一套内嵌 ASM
     * 与 child-first 加载策略。
     *
     * @param targetInternalName 目标类内部名（斜杠形式）
     * @param method             目标方法名
     * @param descriptor         目标方法描述符（必须含至少一个引用形参）
     * @param hookOwner          钩子类内部名（斜杠形式）
     * @param hookMethod         钩子方法名（描述符固定为 {@code (Ljava/lang/Object;)V}）
     * @param atMethodEnd        {@code true} = 在方法末尾注入（首个 {@code RETURN} 之前）；
     *                           绘制入口必须末尾注入，否则叠加层会被目标方法随后画的内容盖住
     * @return 转换器；内嵌 ASM 缺失或类加载失败时返回 {@code null}
     */
    public ClassFileTransformer createCallbackTransformer(String targetInternalName, String method,
                                                          String descriptor, String hookOwner,
                                                          String hookMethod, boolean atMethodEnd) {
        try {
            Class<?> transformerClass = loadClass(CALLBACK_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class, boolean.class);
            Object instance = constructor.newInstance(
                    targetInternalName, method, descriptor, hookOwner, hookMethod, atMethodEnd);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create callback hook transformer for " + targetInternalName + "." + method
                    + ": " + t + "; callback hook disabled");
            return null;
        }
    }

    /**
     * 创建一个「占用输入时短路」的转换器：目标 {@code void} 方法开头插入
     * {@code if (hook()) return;}。
     *
     * <p>与上两个的区别：它会**改变控制流**（前两个只插一次调用），用来吃掉游戏自己的输入处理——
     * 我们的界面不是 vanilla {@code Screen}，不这样会点一次触发两处。
     *
     * @param targetInternalName 目标类内部名（斜杠形式）
     * @param method             目标方法名
     * @param descriptor         目标方法描述符（返回类型须为 {@code V}）
     * @param hookOwner          钩子类内部名（斜杠形式）
     * @param hookMethod         钩子方法名（无参、返回 {@code boolean}）
     * @return 转换器；内嵌 ASM 缺失或类加载失败时返回 {@code null}
     */
    public ClassFileTransformer createInputBlockTransformer(String targetInternalName, String method,
                                                            String descriptor, String hookOwner,
                                                            String hookMethod) {
        try {
            Class<?> transformerClass = loadClass(INPUT_BLOCK_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, hookMethod);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create input block transformer for " + targetInternalName + "." + method
                    + ": " + t + "; input block disabled");
            return null;
        }
    }

    /**
     * 创建带选项的输入短路转换器。
     *
     * @param targetInternalName 目标类内部名（斜杠形式）
     * @param method             目标方法名
     * @param descriptor         目标方法描述符
     * @param hookOwner          钩子类内部名（斜杠形式）
     * @param hookMethod         钩子方法名（无参、返回 {@code boolean}）
     * @param forwardWheel       短路前把首个数值形参转发进滚轮累加器
     * @param forceFalseParam    短路时把首个布尔形参置 false 后继续执行
     * @param zeroIntFields      短路返回前清零实例整型字段
     * @return 转换器；内嵌 ASM 缺失或类加载失败时返回 {@code null}
     */
    public ClassFileTransformer createInputBlockTransformer(String targetInternalName, String method,
                                                            String descriptor, String hookOwner,
                                                            String hookMethod, boolean forwardWheel,
                                                            boolean forceFalseParam,
                                                            boolean zeroIntFields) {
        try {
            Class<?> transformerClass = loadClass(INPUT_BLOCK_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    boolean.class, boolean.class, boolean.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, hookMethod, Boolean.valueOf(forwardWheel),
                    Boolean.valueOf(forceFalseParam), Boolean.valueOf(zeroIntFields));
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create input block transformer for " + targetInternalName + "." + method
                    + ": " + t + "; input block disabled");
            return null;
        }
    }

    /**
     * 创建滚轮形参转换器：把目标方法第 {@code argumentIndex} 个 {@code double} 形参交给无返回值钩子。
     *
     * <p>GLFW 世代没有 SDL 那种"事件对象"，滚轮增量只以 {@code MouseHandler#onScroll(JDD)V} 的
     * 形参形式出现一次，因此必须按形参下标取。
     *
     * @param targetInternalName 目标类内部名（斜杠形式）
     * @param method             目标方法名
     * @param descriptor         目标方法描述符（返回类型须为 {@code V}）
     * @param hookOwner          钩子类内部名（斜杠形式）
     * @param hookMethod         钩子方法名，签名 {@code (D)V}
     * @param argumentIndex      要交出去的形参下标（0 起），该形参必须是 double
     * @return 转换器；内嵌 ASM 缺失或类加载失败时返回 {@code null}
     */
    public ClassFileTransformer createScrollHookTransformer(String targetInternalName, String method,
                                                            String descriptor, String hookOwner,
                                                            String hookMethod, int argumentIndex) {
        try {
            Class<?> transformerClass = loadClass(SCROLL_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class, int.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, hookMethod, Integer.valueOf(argumentIndex));
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create scroll hook transformer for " + targetInternalName + "." + method
                    + ": " + t + "; scroll hook disabled");
            return null;
        }
    }

    /** 创建事件通知/取消转换器；参数转发与钩子返回类型由目标描述符派生。 */
    public ClassFileTransformer createGateTransformer(String targetInternalName, String method,
                                                       String descriptor, String hookOwner,
                                                       String hookMethod, String hookReturn,
                                                       boolean atMethodEnd, boolean passThis) {
        try {
            Class<?> transformerClass = loadClass(GATE_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    String.class, boolean.class, boolean.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, hookMethod, hookReturn, atMethodEnd, passThis);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create gate hook transformer for " + targetInternalName + "." + method
                    + ": " + t + "; gameplay hook disabled");
            return null;
        }
    }

    /** 创建头部捕获 this、返回点无参消费的输入 tick 转换器。 */
    public ClassFileTransformer createInputTickTransformer(String targetInternalName,
            String method, String descriptor, String hookOwner, String captureMethod,
            String deferredMethod) {
        try {
            Class<?> type = loadClass("dev.nocturne.agent.transform.InputTickHookTransformer");
            return (ClassFileTransformer) type.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, String.class).newInstance(targetInternalName, method,
                    descriptor, hookOwner, captureMethod, deferredMethod);
        } catch (Throwable t) {
            log("could not create input tick transformer for " + targetInternalName + "." + method
                    + ": " + t + "; input tick hook disabled");
            return null;
        }
    }

    /** 创建头部捕获、返回点消费的 attackPost 转换器。 */
    public ClassFileTransformer createAttackPostTransformer(String targetInternalName,
            String method, String descriptor, String hookOwner) {
        try {
            Class<?> type = loadClass("dev.nocturne.agent.transform.AttackPostHookTransformer");
            return (ClassFileTransformer) type.getConstructor(String.class, String.class, String.class,
                    String.class).newInstance(targetInternalName, method, descriptor, hookOwner);
        } catch (Throwable t) {
            log("could not create attack-post transformer for " + targetInternalName + "." + method
                    + ": " + t + "; attack-post hook disabled");
            return null;
        }
    }

    /** 创建实际位移实参改写器；legacy 覆盖 (DDD) 与 (MoverType,DDD) 两种旧形状。 */
    public ClassFileTransformer createMoveTransformer(String targetInternalName, String method,
                                                       String descriptor, String hookOwner,
                                                       boolean legacy) {
        try {
            Class<?> transformerClass = loadClass(MOVE_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, boolean.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, legacy);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create move hook transformer for " + targetInternalName + "." + method
                    + ": " + t + "; move hook disabled");
            return null;
        }
    }

    /**
     * 创建世界空间 3D 钩子转换器：在目标 {@code void} 方法的每个 RETURN 之前，
     * 把首个 {@code float} 形参传给钩子 {@code (F)V}。
     *
     * @param targetInternalName 目标类内部名
     * @param method             目标方法名
     * @param descriptor         目标方法描述符（返回类型必须为 {@code V}，且含 float 形参）
     * @param hookOwner          钩子类内部名
     * @param hookMethod         钩子方法名（描述符固定 {@code (F)V}）
     * @return 转换器；内嵌 ASM 缺失或类加载失败时返回 {@code null}
     */
    public ClassFileTransformer createWorldRenderTransformer(String targetInternalName,
                                                              String method, String descriptor,
                                                              String hookOwner, String hookMethod) {
        return createWorldRenderTransformer(targetInternalName, method, descriptor, hookOwner,
                null, hookMethod);
    }

    /**
     * 世界空间 3D 钩子转换器：同一个转换器一次插入头部捕获与尾部绘制，避免重复改写。
     *
     * @param headHookMethod 方法头钩子（可为 null）
     * @param tailHookMethod 每个 RETURN 前钩子（可为 null）
     */
    public ClassFileTransformer createWorldRenderTransformer(String targetInternalName,
                                                              String method, String descriptor,
                                                              String hookOwner,
                                                              String headHookMethod,
                                                              String tailHookMethod) {
        try {
            Class<?> transformerClass = loadClass(WORLD_RENDER_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class,
                    String.class, String.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, headHookMethod, tailHookMethod);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create world render hook transformer for " + targetInternalName
                    + "." + method + ": " + t + "; 3D world hook disabled");
            return null;
        }
    }

    /** 创建包入口转换器；钩子返回替换包，null 表示取消。 */
    public ClassFileTransformer createPacketTransformer(String targetInternalName, String method,
                                                         String descriptor, String hookOwner,
                                                         String hookMethod, int packetArgument) {
        try {
            Class<?> transformerClass = loadClass(PACKET_TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class, int.class);
            Object instance = constructor.newInstance(targetInternalName, method, descriptor,
                    hookOwner, hookMethod, packetArgument);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create packet hook transformer for " + targetInternalName + "." + method
                    + ": " + t + "; packet hook disabled");
            return null;
        }
    }

    /** 创建首参 boolean 门控转换器。 */
    public ClassFileTransformer createFirstArgumentGateTransformer(String targetInternalName,
            String method, String descriptor, String hookOwner, String hookMethod) {
        return createFirstArgumentGateTransformer(targetInternalName, method, descriptor, hookOwner,
                hookMethod, 0, 1);
    }

    /** 创建按下标取形参的门控转换器（形参位置随版本变化时用）。 */
    public ClassFileTransformer createFirstArgumentGateTransformer(String targetInternalName,
            String method, String descriptor, String hookOwner, String hookMethod,
            int first, int second) {
        try {
            Class<?> type = loadClass("dev.nocturne.agent.transform.FirstArgumentGateTransformer");
            return (ClassFileTransformer) type.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, int.class, int.class)
                    .newInstance(targetInternalName, method, descriptor, hookOwner, hookMethod,
                            Integer.valueOf(first), Integer.valueOf(second));
        } catch (Throwable t) {
            log("could not create first-argument gate for " + targetInternalName + "." + method + ": " + t);
            return null;
        }
    }

    /** 创建实体渲染首参门控。 */
    public ClassFileTransformer createEntityRenderGateTransformer(String targetInternalName,
            String method, String descriptor, String hookOwner) {
        try {
            Class<?> type = loadClass("dev.nocturne.agent.transform.EntityRenderGateTransformer");
            return (ClassFileTransformer) type.getConstructor(String.class, String.class, String.class,
                    String.class).newInstance(targetInternalName, method, descriptor, hookOwner);
        } catch (Throwable t) {
            log("could not create entity render gate for " + targetInternalName + "." + method
                    + ": " + t + "; entity gate disabled");
            return null;
        }
    }

    /** 创建 float 返回值缩放转换器。 */
    public ClassFileTransformer createFloatReturnTransformer(String targetInternalName,
            String method, String descriptor, String hookOwner, String hookMethod) {
        try {
            Class<?> type = loadClass("dev.nocturne.agent.transform.FloatReturnHookTransformer");
            return (ClassFileTransformer) type.getConstructor(String.class, String.class, String.class,
                    String.class, String.class).newInstance(targetInternalName, method, descriptor,
                    hookOwner, hookMethod);
        } catch (Throwable t) {
            log("could not create float-return hook for " + targetInternalName + "." + method + ": " + t);
            return null;
        }
    }

    public ClassFileTransformer createTransformer(String targetInternalName, String method,
                                                  String descriptor) {
        try {
            Class<?> transformerClass = loadClass(TRANSFORMER_CLASS);
            Constructor<?> constructor = transformerClass.getConstructor(
                    String.class, String.class, String.class, String.class, String.class);
            Object instance = constructor.newInstance(
                    targetInternalName, method, descriptor, HOOK_OWNER, HOOK_METHOD);
            return (ClassFileTransformer) instance;
        } catch (Throwable t) {
            log("could not create frame hook transformer for " + targetInternalName + "." + method
                    + ": " + t + "; frame hook disabled");
            return null;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith(ASM_PACKAGE_PREFIX)) {
                    loaded = defineLocal(name, asmClasses.get(name));
                } else if (name.startsWith(TRANSFORMER_PREFIX)) {
                    loaded = defineLocal(name, readTransformerClass(name));
                }
                if (loaded == null) {
                    // 非 child-first 的类（含 java.*、java.lang.instrument.*）交给父加载器。
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /** 用本加载器定义给定字节码；字节码缺失时返回 {@code null}。 */
    private Class<?> defineLocal(String name, byte[] bytecode) {
        if (bytecode == null) {
            return null;
        }
        return defineClass(name, bytecode, 0, bytecode.length);
    }

    /** 从父加载器读取转换器（及其内部类）的 {@code .class} 资源；失败返回 {@code null}。 */
    private byte[] readTransformerClass(String name) {
        if (transformerClasses.containsKey(name)) {
            return transformerClasses.get(name);
        }
        String resource = name.replace('.', '/') + ".class";
        byte[] bytecode = null;
        try (InputStream in = parent.getResourceAsStream(resource)) {
            if (in != null) {
                bytecode = readAll(in);
            }
        } catch (Throwable t) {
            log("could not read transformer class " + resource + ": " + t);
        }
        transformerClasses.put(name, bytecode);
        return bytecode;
    }

    /**
     * 从内嵌加密载荷资源解出全部 {@code org/objectweb/asm/} 类。
     *
     * <p>解包用的密钥与构建期 {@code PayloadTool asm-pack} 完全一致（{@link PayloadKey}），
     * 因此密钥/格式一旦不同步，这里会抛异常 → 帧钩子被禁用并打日志，不会静默错用别的类。
     *
     * @return 类名（点号）→ 字节码；资源缺失时返回空表
     */
    private static Map<String, byte[]> readAsmPayload(ClassLoader parent) throws IOException {
        Map<String, byte[]> classes = new HashMap<String, byte[]>();
        InputStream raw = parent.getResourceAsStream(ASM_PACK_RESOURCE);
        if (raw == null) {
            return classes;
        }
        Map<String, byte[]> unpacked;
        try {
            unpacked = PayloadLoader.read(raw, PayloadKey.asmPayload());
        } finally {
            raw.close();
        }
        for (Map.Entry<String, byte[]> entry : unpacked.entrySet()) {
            if (entry.getKey().startsWith(ASM_PACKAGE_PREFIX)) {
                classes.put(entry.getKey(), entry.getValue());
            }
        }
        return classes;
    }

    /** 读尽一个流（zip 条目在流结束前不会关闭，因此此处只读到 EOF）。 */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 统一的日志输出，带 {@code [nocturne]} 前缀便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[nocturne] " + message);
    }

    /**
     * 展开反射包装异常，取真正的失败原因。
     *
     * <p>转换器构造器由内嵌 ASM 子加载器反射调用，参数不合法时（例如 1.8.9 的实体门控返回
     * boolean）抛出的异常会被包成 {@code InvocationTargetException}，只打外层等于什么都没说
     * ——真机日志里 12 行 {@code InvocationTargetException; entity gate disabled} 完全看不出
     * 是"返回值形态不匹配"。
     */
    static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 8; depth++) {
            if (current instanceof java.lang.reflect.InvocationTargetException
                    && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            if ((current instanceof ExceptionInInitializerError
                    || current instanceof java.lang.reflect.UndeclaredThrowableException)
                    && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            break;
        }
        return current;
    }
}
