package dev.noturne.agent;

import dev.noturne.agent.transform.FrameHookTransformer;
import dev.noturne.client.NoturneClient;

import java.lang.instrument.Instrumentation;

/**
 * Java 代理（agent）的入口点。
 *
 * <p>本类通过 MANIFEST 的 {@code Premain-Class}/{@code Agent-Class} 被 JVM 调用，负责拉起整个
 * Noturne 客户端并安装渲染帧钩子；它是 agent 模块与运行时之间唯一的引导入口。
 *
 * <p>{@code premain} 在 jar 以 {@code -javaagent} 方式随 JVM 启动（或由 attach 加载）时执行；
 * {@code agentmain} 在 jar 被附加到已在运行的 JVM 时执行。两者都把实际工作转交到独立线程上调用
 * {@link NoturneClient}：若在此直接做初始化，会阻塞我们正试图插桩的那个 JVM 的类加载。
 */
public final class NoturneAgent {

    /**
     * GUI 开关按键。
     *
     * <p>由 agent 参数 {@code guiKey=<键码>} 覆盖（注入器把用户录制的按键写进这里），缺省为右 Shift。
     * 声明为 volatile：写入发生在 {@code premain}/{@code agentmain} 的调用线程，读取发生在渲染线程。
     */
    private static volatile int guiToggleKey = dev.noturne.ui.gl.GuiOverlay.KEY_RIGHT_SHIFT;

    private NoturneAgent() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 启动期代理入口（{@code -javaagent} 或 attach 加载 jar 时由 JVM 调用）。
     *
     * @param agentArgs 代理参数字符串（{@code -javaagent:...=args} 中 {@code =} 之后的部分），可能为 null
     * @param instrumentation JVM 注入的插桩句柄，用于类转换与重转换
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start("premain", agentArgs, instrumentation);
    }

    /**
     * 运行期附加代理入口（jar 被 attach 到已启动的 JVM 时由 JVM 调用）。
     *
     * @param agentArgs 代理参数字符串，可能为 null
     * @param instrumentation JVM 注入的插桩句柄
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start("agentmain", agentArgs, instrumentation);
    }

    /**
     * 两个入口共用的启动流程：打印加载来源，并在守护线程 {@code noturne-init} 上执行
     * 客户端引导、安装帧钩子与叠加层。
     *
     * <p>之所以另起线程而非在调用线程上直接执行：{@code premain}/{@code agentmain} 由 JVM 在类
     * 加载/附加的关键路径上回调，在此阻塞会拖死待插桩 JVM 的类加载；守护线程则不会阻止 JVM 退出。
     *
     * @param via 触发来源标识（{@code "premain"} 或 {@code "agentmain"}），仅用于日志
     * @param agentArgs 代理参数，用于日志
     * @param instrumentation 插桩句柄，透传给各安装步骤
     */
    private static void start(String via, final String agentArgs, final Instrumentation instrumentation) {
        log("agent loaded via " + via
                + (agentArgs == null || agentArgs.isEmpty() ? "" : " args=[" + agentArgs + "]"));
        guiToggleKey = parseToggleKey(agentArgs);

        Thread init = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NoturneClient.boot(instrumentation);
                    installFrameHook(instrumentation);
                    installOverlay(instrumentation);
                } catch (Throwable t) {
                    log("client initialisation failed: " + t);
                    t.printStackTrace();
                }
            }
        }, "noturne-init");
        init.setDaemon(true);
        init.start();
    }

    /**
     * 给渲染帧交换点打补丁，使 {@code NoturneRuntime.onFrame()} 每帧执行一次。
     *
     * <p>受支持版本范围内存在两条渲染路径：LWJGL2 的 {@code Display.update()}（Minecraft ≤ 1.12）
     * 与 LWJGL3 的 {@code GLFW.glfwSwapBuffers(long)}（1.13+）。哪个类已被实际加载，就决定补丁打在
     * 哪一个上。选择交换点是因为在此处绘制的画面才能存活到屏幕上——若在帧开始处绘制会被后续覆盖。
     */
    private static void installFrameHook(Instrumentation instrumentation) {
        if (instrumentation == null) {
            log("no Instrumentation: frame hook skipped");
            return;
        }
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        String targetClass;
        FrameHookTransformer transformer;

        if (dev.noturne.client.game.Reflect.load("org.lwjgl.opengl.Display", loader) != null) {
            targetClass = "org.lwjgl.opengl.Display";
            transformer = new FrameHookTransformer(targetClass, "update", "()V",
                    "dev/noturne/client/runtime/NoturneRuntime", "onFrame");
        } else if (dev.noturne.client.game.Reflect.load("org.lwjgl.glfw.GLFW", loader) != null) {
            targetClass = "org.lwjgl.glfw.GLFW";
            transformer = new FrameHookTransformer(targetClass, "glfwSwapBuffers", "(J)V",
                    "dev/noturne/client/runtime/NoturneRuntime", "onFrame");
        } else {
            log("no LWJGL render entry point found; frame hook skipped");
            return;
        }

        try {
            // canRetransform=true：目标类此刻可能已被加载，注册后需立即重转换才能补上钩子。
            instrumentation.addTransformer(transformer, true);
        } catch (Throwable t) {
            log("could not register transformer: " + t);
            return;
        }
        // 两类情况：目标类已加载 → 显式 retransform 立刻打补丁；尚未加载 → 保持注册，
        // 待其首次加载时由 transformer 自动处理。
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (targetClass.equals(loaded.getName())) {
                try {
                    instrumentation.retransformClasses(loaded);
                    log("frame hook installed on " + targetClass);
                } catch (Throwable t) {
                    log("retransform failed: " + t);
                }
                return;
            }
        }
        log(targetClass + " not loaded yet; frame hook will apply when it loads");
    }

    /**
     * 绑定 GL 能力，并把点击式 GUI 叠加层挂到帧钩子上。
     *
     * <p>任一步骤失败（GL 未加载、无可用入口、后端不可用）都只记录日志并放弃叠加层，
     * 绝不向上抛出——绝不能因为叠加层问题影响游戏本身的运行。
     */
    private static void installOverlay(Instrumentation instrumentation) {
        try {
            // 用游戏实际加载的类加载器：Fabric/Forge 下游戏跑在隔离类加载器中，
            // 系统类加载器看不到 LWJGL。
            Class<?> gl11 = findLoadedClass(instrumentation, "org.lwjgl.opengl.GL11");
            ClassLoader loader = gl11 != null
                    ? gl11.getClassLoader()
                    : ClassLoader.getSystemClassLoader();
            // 交给 OverlayBootstrap：它在 GL 真正可用后才安装，模组路径复用同一套逻辑。
            OverlayBootstrap.install(loader, instrumentation, guiToggleKey);
            dev.noturne.client.runtime.NoturneRuntime.trace(true);
            log("overlay bootstrap registered; toggle key=" + guiToggleKey);
        } catch (Throwable t) {
            log("overlay attach failed: " + t);
        }
    }

    /**
     * 为当前运行环境挑选绘制后端。
     *
     * <p>能成功绑定核心配置的入口（core profile，即 VAO 等 1.13+ 才有的一组函数），正是「游戏为
     * 1.13+」的判据：在 1.8.9 上这些函数根本不存在，此时固定管线渲染器是唯一可选后端。
     *
     * @param fixed 已绑定核心类/兼容入口的 GL API（固定管线）
     * @param gl11 游戏实际加载的 GL11 类，用于取得正确的类加载器
     * @param font 文本渲染器，可为 null（表示无可用游戏字体）
     * @return 绘制后端，永不为 null
     */
    private static dev.noturne.ui.gl.UiBackend selectBackend(
            dev.noturne.ui.gl.GlApi fixed, Class<?> gl11, dev.noturne.ui.gl.TextRenderer font) {
        dev.noturne.ui.gl.ModernGlApi modern =
                dev.noturne.ui.gl.ModernGlApi.bind(gl11.getClassLoader());
        if (modern != null) {
            return new dev.noturne.ui.gl.ModernRenderer(modern, font);
        }
        return new dev.noturne.ui.gl.GlRenderer(fixed, font);
    }

    /**
     * 在已加载类中查找指定名字的类。
     *
     * <p>必须经由游戏自己的类加载器定位 GL：Fabric/Forge 下游戏跑在隔离的类加载器中，
     * 系统类加载器看不到 LWJGL 的类文件。
     *
     * @param instrumentation 插桩句柄，为 null 时直接返回 null
     * @param className 类名（如 {@code org.lwjgl.opengl.GL11}）
     * @return 找到的 {@link Class}，未加载或句柄缺失时返回 null
     */
    private static Class<?> findLoadedClass(Instrumentation instrumentation, String className) {
        if (instrumentation == null) {
            return null;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) {
                return loaded;
            }
        }
        return null;
    }

    /**
     * 为当前运行的构建挑选映射表；无法判定时退化为恒等映射。
     *
     * @param instrumentation 插桩句柄，为 null 时直接走恒等映射
     * @return Minecraft 类名/字段名映射，永不为 null
     */
    private static dev.noturne.client.mapping.Mapping selectMapping(Instrumentation instrumentation) {
        // 26.1+ 版本为未混淆发行：规范化类名原样加载，因此映射即为恒等映射；
        // 只有更老的版本才需要 1.8.9 映射表。
        if (isLoaded(instrumentation, dev.noturne.client.mapping.ClassType.MINECRAFT.canonicalName())) {
            return new dev.noturne.client.mapping.IdentityMapping();
        }
        try {
            return dev.noturne.client.mapping.ObfuscatedMapping.load("/mappings-1.8.9.json");
        } catch (Throwable t) {
            return new dev.noturne.client.mapping.IdentityMapping();
        }
    }

    /**
     * 判断指定类此刻是否已加载。
     *
     * <p>这是探测运行版本的廉价手段：先启动的游戏必然已经加载过自己的主类。
     *
     * @param instrumentation 插桩句柄，为 null 时视为未加载
     * @param className 类名
     * @return 已加载返回 true
     */
    private static boolean isLoaded(Instrumentation instrumentation, String className) {
        if (instrumentation == null) {
            return false;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 agent 参数中解析 GUI 开关按键。
     *
     * <p>参数由注入器以逗号分隔的 {@code key=value} 形式传入（JDK attach 的 options 约定）。
     * 任何畸形输入都退回默认的右 Shift：一个拼错的参数不该让整个 GUI 无法唤出。
     *
     * @param agentArgs 代理参数字符串，可为 null
     * @return 解析出的键码，或默认的 {@link dev.noturne.ui.gl.GuiOverlay#KEY_RIGHT_SHIFT}
     */
    private static int parseToggleKey(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return dev.noturne.ui.gl.GuiOverlay.KEY_RIGHT_SHIFT;
        }
        for (String part : agentArgs.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("guiKey=")) {
                try {
                    return Integer.parseInt(trimmed.substring("guiKey=".length()).trim());
                } catch (NumberFormatException malformed) {
                    return dev.noturne.ui.gl.GuiOverlay.KEY_RIGHT_SHIFT;
                }
            }
        }
        return dev.noturne.ui.gl.GuiOverlay.KEY_RIGHT_SHIFT;
    }

    /** 统一的日志输出，统一加 {@code [noturne]} 前缀，便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
