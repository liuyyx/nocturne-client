package dev.noturne.agent.transform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从自身资源加载 ASM 与帧钩子转换器的隔离类加载器。
 *
 * <p>目标 JVM（原版 / Forge 1.8.9 等）的类路径上没有 ASM，而 dist 产物又刻意不在 jar 根展开
 * {@code org/objectweb/asm/**}（避免与 Fabric/Forge 自带的 ASM 冲突）。因此 ASM 以原文件形式内嵌为
 * 资源 {@code dev/noturne/agent/asm.jar}，运行时在内存里解出并加载——不落任何临时文件（K4）。
 *
 * <p>父加载器可见性上做 child-first 的有两组名前缀：
 * <ul>
 *   <li>{@code org.objectweb.asm.}：优先用内嵌 ASM，绝不落到目标 JVM 可能存在的旧版本上；</li>
 *   <li>{@code dev.noturne.agent.transform.FrameHookTransformer}（含匿名内部类）：让转换器与它引用的
 *       ASM 处在同一个加载器里，这样转换器的 ASM 依赖才解析得到内嵌副本。</li>
 * </ul>
 * 其余类（{@code java.lang.instrument.*} 等）一律委派给父加载器。
 *
 * <p>加载失败（资源缺失、jar 损坏、ASM 版本不兼容）时 {@link #create()} 返回 {@code null} 并打日志，
 * 调用方据此跳过帧钩子安装，其余功能（客户端引导、叠加层）不受影响。
 */
public final class EmbeddedAsmLoader extends ClassLoader {
    /** 与 MemoryClassLoader 同源：声明「按类名并行可加载」，否则所有类加载串行在 loader 监视器上。 */
    static {
        ClassLoader.registerAsParallelCapable();
    }


    /** 内嵌 ASM jar 的资源路径，由 dist 打包任务固定写入（K3）。 */
    private static final String ASM_JAR_RESOURCE = "dev/noturne/agent/asm.jar";

    /** 需要 child-first 的 ASM 包前缀。 */
    private static final String ASM_PACKAGE_PREFIX = "org.objectweb.asm.";

    /** 需要 child-first 的转换器包前缀（覆盖 FrameHookTransformer、CallbackHookTransformer 及匿名内部类）。 */
    private static final String TRANSFORMER_PREFIX = "dev.noturne.agent.transform.";

    /** 转换器类名与钩子目标，与 {@link FrameHookTransformer} 的契约一致。 */
    private static final String TRANSFORMER_CLASS = "dev.noturne.agent.transform.FrameHookTransformer";
    /** 钩子所在类内部名（斜杠形式）。 */
    private static final String HOOK_OWNER = "dev/noturne/client/runtime/NoturneRuntime";
    /** 钩子方法名。 */
    private static final String HOOK_METHOD = "onFrame";

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
            asm = readAsmJar(parent);
        } catch (Throwable t) {
            log("embedded ASM resource unusable (" + ASM_JAR_RESOURCE + "): " + t
                    + "; frame hook disabled");
            return null;
        }
        if (asm.isEmpty()) {
            log("embedded ASM resource missing or empty: " + ASM_JAR_RESOURCE
                    + "; frame hook disabled");
            return null;
        }
        return new EmbeddedAsmLoader(parent, asm);
    }

    /**
     * 创建一个已归一化类名的帧钩子转换器。
     *
     * @param targetInternalName 目标类内部名（点号或斜杠形式均可，构造器内会归一化）
     * @param method 目标方法名
     * @param descriptor 目标方法描述符
     * @return 转换器实例；子加载器或类加载失败时返回 {@code null}（已打日志）
     */
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
     * 从内嵌 {@code asm.jar} 资源读出全部 {@code org/objectweb/asm/} 类。
     *
     * @return 类名（点号）→ 字节码；资源缺失时返回空表
     */
    private static Map<String, byte[]> readAsmJar(ClassLoader parent) throws IOException {
        Map<String, byte[]> classes = new HashMap<String, byte[]>();
        InputStream raw = parent.getResourceAsStream(ASM_JAR_RESOURCE);
        if (raw == null) {
            return classes;
        }
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entry.isDirectory()
                        || !entryName.startsWith("org/objectweb/asm/")
                        || !entryName.endsWith(".class")) {
                    continue;
                }
                String className = entryName.substring(0, entryName.length() - ".class".length())
                        .replace('/', '.');
                classes.put(className, readAll(zip));
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

    /** 统一的日志输出，带 {@code [noturne]} 前缀便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
