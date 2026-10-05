package dev.noturne.agent;

import dev.noturne.agent.transform.EmbeddedAsmLoader;
import dev.noturne.client.NoturneClient;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 右 Shift 的 AWT 虚拟键码（VK_RIGHT_SHIFT）。
     *
     * <p>注入器写给 agent 的 {@code guiKey} 统一是 AWT VK 码，右 Shift 即 54（跨任务契约 K1）；
     * 运行时到 LWJGL2/GLFW 键码的翻译由 {@code dev.noturne.client.input.KeyMap} 完成。
     */
    public static final int VK_RIGHT_SHIFT = 54;

    /**
     * 是否已经启动过一次。
     *
     * <p>{@code premain} 与 {@code agentmain} 可能在同一个 JVM 里先后触发（{@code -javaagent}
     * 启动 + 之后再次 attach），重复执行会注册两套转换器，使 {@code onFrame()} 每帧被驱动两次、
     * 开关按键互相抵消。这里用 CAS 保证只装一次（M-89）。
     */
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /**
     * GUI 开关按键。
     *
     * <p>由 agent 参数 {@code guiKey=<键码>} 覆盖（注入器把用户录制的按键写进这里），缺省为右 Shift。
     * 声明为 volatile：写入发生在 {@code premain}/{@code agentmain} 的调用线程，读取发生在渲染线程。
     */
    private static volatile int guiToggleKey = VK_RIGHT_SHIFT;

    /**
     * 目标 Minecraft 版本族（如 {@code 1.8.9}），由注入器的 {@code mcVersion=} 选项传入。
     *
     * <p>运行时据此选择映射表 {@code /mappings-<版本族>.json}，**不做版本探测**。选项格式与注入侧
     * {@code dev.noturne.core.attach.AgentOptions} 的组装保持一致（agent 看不到 core 模块，
     * 只能本地解析这两个键）。
     */
    private static volatile String mcVersion = "unknown";

    private NoturneAgent() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 启动期代理入口（仅由 {@code -javaagent} 启动参数触发；attach 走 {@link #agentmain}）。
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
        // 参数解析必须在幂等 CAS **之前**：否则后触发的入口（例如先用 CLI --pid= 注入、再用 GUI
        // 带正确 mcVersion/guiKey 注入同一进程）携带的选项会被 early-return 丢弃。M-89 的幂等
        // 保护的是「安装一次」，不是「配置一次」；配置允许后到者补齐缺失项。
        final int newGuiKey = parseToggleKey(agentArgs);
        final String newMcVersion = parseVersion(agentArgs);
        log("agent loaded via " + via
                + (agentArgs == null || agentArgs.isEmpty() ? "" : " args=[" + agentArgs + "]"));
        if (!STARTED.compareAndSet(false, true)) {
            // 已装过：仅当旧值为默认且新入口带来了可用新值时才升级（覆盖默认键 / unknown 版本）。
            boolean upgraded = false;
            if (guiToggleKey == VK_RIGHT_SHIFT && newGuiKey != VK_RIGHT_SHIFT) {
                guiToggleKey = newGuiKey;
                upgraded = true;
            }
            if ("unknown".equals(mcVersion) && !"unknown".equals(newMcVersion)) {
                mcVersion = newMcVersion;
                selectMapping(instrumentation);   // 用新版本重选映射（selectMapping 内部已记住）
                upgraded = true;
            }
            if (upgraded) {
                log("agent options upgraded by " + via + ": guiKey=" + guiToggleKey
                        + ", mcVersion=" + mcVersion);
            } else {
                log("agent already started; ignoring duplicate entry via " + via);
            }
            return;
        }
        guiToggleKey = newGuiKey;
        mcVersion = newMcVersion;
        log("agent options: guiKey=" + guiToggleKey + ", mcVersion=" + mcVersion);

        // 在注册任何转换器之前打开首帧存活日志：钩子一旦被重新转换激活，游戏主线程可能立刻
        // 执行到补丁点。若此时 TRACE 仍为 false，那唯一一次「frame hook is live」就被白白吞掉
        // （M-82）。放在这里可把该竞态窗口压到零。
        // 帧钩子注入的调用由**游戏的类加载器**解析，它看不到 agent jar：不先把最小类集推到
        // bootstrap 层，钩子一触发就是 NoClassDefFoundError 崩游戏（26.3 实测如此）。
        installBootstrapBridge(instrumentation);
        // Fabric 的隔离加载器会**拦截**来自未"暴露" jar 的类加载请求（直接抛 NoClassDefFoundError，
        // 而不是委托父加载器），因此还要把 agent jar 交给它——否则帧钩子一触发就崩游戏。
        exposeAgentJarToGameLoader(instrumentation);

        dev.noturne.client.runtime.NoturneRuntime.trace(true);

        Thread init = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 映射表先选再 boot：下游（boot 内部、叠加层、游戏桥、模块）不应拿到
                    // 兜底的恒等映射再被永久缓存（M-84 注释原意）。
                    selectMapping(instrumentation);
                    NoturneClient.boot(instrumentation);
                    installFrameHook(instrumentation);
                    installOverlay(instrumentation);
                } catch (Throwable t) {
                    // 允许后续重复入口重试安装：只有真正完成才算 STARTED。
                    STARTED.set(false);
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
     * <p>受支持版本范围内存在三条渲染路径，这里**三条都无条件注册**：转换器对未命中的类是无副作用的
     * no-op，注册成本近乎为零；而用系统类加载器去「探测 LWJGL 是否已被加载」在 Fabric/Forge 的隔离
     * 类加载器下必然失败，会导致钩子被完全跳过（H-01）。三条候选：
     * <ul>
     *   <li>LWJGL2 {@code Display.update()V}（Minecraft ≤ 1.12）；</li>
     *   <li>LWJGL3 GLFW {@code GLFW.glfwSwapBuffers(J)V}（1.13 – 26.2）；</li>
     *   <li>LWJGL 3.4 SDL 绑定 {@code SDLVideo.SDL_GL_SwapWindow(J)Z}（26.3：其 libraries 里没有
     *       {@code lwjgl-glfw}，只有 {@code org.lwjgl:lwjgl-sdl}，因此 GLFW 永不加载）。</li>
     * </ul>
     */
    /**
     * 把 agent jar 暴露给**游戏的隔离类加载器**。
     *
     * <p>问题：Fabric 的 {@code KnotClassLoader} 对「它认得、但没被暴露给它」的 jar 会直接抛
     * {@code NoClassDefFoundError: ... as it hasn't been exposed to the game}，而不是按
     * ClassLoader 契约委托父加载器。因此只把类推到 bootstrap 层还不够：帧钩子注入的调用
     * （{@code SDLVideo.SDL_GL_SwapWindow} 里的 {@code NoturneRuntime.onFrame()}）由游戏的
     * 类加载器解析，一触发就崩（26.3 实测）。
     *
     * <p>做法：反射调用 Knot 自己提供的 {@code addUrlFwd(URL)}——它把该 URL 加进"转发"列表，
     * Knot 遇到其中的类时交给父加载器（即上面装了最小类集的 bootstrap 层），于是插桩引用与
     * agent 自身解析到的是**同一份**类，静态状态（监听器列表）才是同一个。
     *
     * <p>非 Fabric 环境（原版 / Forge）没有这个方法，直接跳过：那些环境不需要这一步。
     */
    private static void exposeAgentJarToGameLoader(Instrumentation instrumentation) {
        try {
            java.security.CodeSource source =
                    NoturneAgent.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return;
            }
            java.net.URL self = source.getLocation();
            // 游戏类加载器：Fabric/Forge 下是隔离加载器，**不在 system 类加载器的视野里**——
            // 按名字从 system loader 查必然失败（第一版就是这么静默退出的）。这里从已加载类里找。
            ClassLoader gameLoader = null;
            if (instrumentation != null) {
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    String name = type.getName();
                    if ("net.minecraft.client.Minecraft".equals(name)
                            || "net.minecraft.client.main.Main".equals(name)) {
                        gameLoader = type.getClassLoader();
                        if (gameLoader != null) {
                            break;
                        }
                    }
                }
            }
            if (gameLoader == null) {
                log("game loader not found; skipping agent jar exposure");
                return;
            }
            for (java.lang.reflect.Method method : gameLoader.getClass().getMethods()) {
                if ("addUrlFwd".equals(method.getName())
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == java.net.URL.class) {
                    // 声明它的类不是 public（实测会抛 IllegalAccessException），必须显式放开。
                    method.setAccessible(true);
                    method.invoke(gameLoader, self);
                    log("agent jar exposed to game loader: " + self);
                    return;
                }
            }
            log("game loader has no addUrlFwd; assuming a non-isolated launcher");
        } catch (Throwable t) {
            log("exposeAgentJarToGameLoader failed: " + t);
        }
    }

    /**
     * 让**插桩注入的调用**能被游戏的类加载器解析。
     *
     * <p>帧钩子把 {@code NoturneRuntime.onFrame()} 注入到 LWJGL 的缓冲交换函数里，而那条调用指令
     * 由**定义该函数的类加载器**解析——在 Fabric/Forge 下是隔离的 KnotClassLoader，它看不到 agent
     * jar。实测后果（26.3）：每次交换缓冲都抛 {@code NoClassDefFoundError}，游戏直接崩在启动阶段。
     *
     * <p>做法：把「无第三方依赖的最小类集」导出成临时 jar 并追加到 **bootstrap** 搜索路径。
     * 选 bootstrap 而不是 system：它是所有加载器的父层，因此插桩引用与 agent 自身解析到的是
     * **同一份**类，静态状态（监听器列表）才是同一个——否则帧回调会分发到另一个空的列表上。
     *
     * <p>只导出这两个类：它们不引用 Skija/ASM/gson 等第三方库，放进 bootstrap 不会与游戏的依赖
     * 产生版本冲突（若把整个 agent jar 推上去，游戏自己的 gson 等会被我们的版本顶掉）。
     */
    private static void installBootstrapBridge(Instrumentation instrumentation) {
        if (instrumentation == null) {
            return;
        }
        String[] entries = {
                "dev/noturne/client/runtime/FrameDispatcher.class",
                "dev/noturne/client/runtime/FrameListener.class",
        };
        java.nio.file.Path jar = null;
        try {
            jar = java.nio.file.Files.createTempFile("noturne-bridge", ".jar");
            jar.toFile().deleteOnExit();
            try (java.util.zip.ZipOutputStream out =
                         new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
                for (String entry : entries) {
                    try (java.io.InputStream in =
                                 NoturneAgent.class.getClassLoader().getResourceAsStream(entry)) {
                        if (in == null) {
                            log("bootstrap bridge: entry missing " + entry);
                            continue;
                        }
                        out.putNextEntry(new java.util.zip.ZipEntry(entry));
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        out.closeEntry();
                    }
                }
            }
            instrumentation.appendToBootstrapClassLoaderSearch(
                    new java.util.jar.JarFile(jar.toFile()));
            log("bootstrap bridge installed: " + entries.length + " classes from " + jar.getFileName());
        } catch (Throwable t) {
            log("bootstrap bridge failed: " + t);
        }
    }

    /**
     * @return 游戏类加载器（从已加载的游戏类取）；取不到返回 {@code null}
     */
    private static ClassLoader gameClassLoader(Instrumentation instrumentation) {
        if (instrumentation == null) {
            return null;
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            if ("net.minecraft.client.Minecraft".equals(name)
                    || "net.minecraft.client.gui.Hud".equals(name)) {
                return type.getClassLoader();
            }
        }
        return null;
    }

    /**
     * @return 是否 SDL 渲染栈（Minecraft 26.x 起）
     *
     * <p>SDL 栈下**不能**用帧钩子：LWJGL 的 GL 绑定在 SDL 管理的上下文里不可用，帧回调里任何一次
     * GL 调用（哪怕只读视口）都会让 LWJGL {@code FATAL ERROR} 终止 JVM，且捕获不到。所以这些版本
     * 改为把驱动点放在 GUI 绘制钩子上（那时 GL 上下文有效）。
     */
    private static boolean isSdlStack(Instrumentation instrumentation, ClassLoader gameLoader) {
        if (gameLoader != null && loadNoInit("org.lwjgl.sdl.SDLVideo", gameLoader) != null) {
            return true;
        }
        if (instrumentation != null) {
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if ("org.lwjgl.sdl.SDLVideo".equals(type.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void installFrameHook(Instrumentation instrumentation) {
        if (instrumentation == null) {
            log("no Instrumentation: frame hook skipped");
            return;
        }
        // GUI 绘制钩子先注册：SDL 栈下它是唯一的驱动点（见 isSdlStack 的说明）。
        installGuiDrawHook(instrumentation);
        if (isSdlStack(instrumentation, gameClassLoader(instrumentation))) {
            log("SDL render stack detected: frame hook skipped (a GL call from the frame callback"
                    + " aborts the JVM); the overlay is driven by the GUI draw hook instead");
            return;
        }
        EmbeddedAsmLoader asmLoader = EmbeddedAsmLoader.create();
        if (asmLoader == null) {
            // 内嵌 ASM 缺失/损坏：不打钩子，但客户端引导与叠加层照常，其余功能不受影响。
            log("embedded ASM unavailable; frame hook skipped (overlay listener will idle)");
            return;
        }
        // 内部名一律用斜杠形式（跨任务契约 K2）；转换器构造器也做点号→斜杠归一化兜底。
        log("frame hook candidates: org/lwjgl/opengl/Display.update()V,"
                + " org/lwjgl/glfw/GLFW.glfwSwapBuffers(J)V,"
                + " org/lwjgl/sdl/SDLVideo.SDL_GL_SwapWindow(J)Z");
        registerFrameHook(instrumentation, asmLoader, "org/lwjgl/opengl/Display", "update", "()V");
        registerFrameHook(instrumentation, asmLoader, "org/lwjgl/glfw/GLFW", "glfwSwapBuffers", "(J)V");
        registerFrameHook(instrumentation, asmLoader, "org/lwjgl/sdl/SDLVideo", "SDL_GL_SwapWindow", "(J)Z");
    }

    /**
     * 注册一个帧钩子转换器，并在目标类已加载时立即重转换。
     *
     * @param instrumentation 插桩句柄
     * @param asmLoader 内嵌 ASM 子加载器，用于创建转换器
     * @param targetInternalName 目标类内部名（斜杠形式）
     * @param method 目标方法名
     * @param descriptor 目标方法描述符
     */
    /** GUI 绘制钩子的目标类（点号形式）。签名取自 26.3 实际字节码：{@code javap net.minecraft.client.gui.Hud}。 */
    private static final String GUI_DRAW_CLASS = "net.minecraft.client.gui.Hud";
    /** 目标方法名。 */
    private static final String GUI_DRAW_METHOD = "extractRenderState";
    /** 目标方法描述符：首个引用形参即绘制上下文。 */
    private static final String GUI_DRAW_DESCRIPTOR =
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";
    /** 钩子类内部名（斜杠形式）。 */
    private static final String GUI_HOOK_OWNER = "dev/noturne/agent/GuiDrawHook";
    /** 钩子方法名（描述符固定 {@code (Ljava/lang/Object;)V}）。 */
    private static final String GUI_HOOK_METHOD = "onDraw";

    /**
     * 注册 GUI 绘制钩子：把 {@code Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} 的首个
     * 引用形参交给 {@link GuiDrawHook#onDraw(Object)}。
     *
     * <p>为什么是这里：Minecraft 26.x 用 SDL 管理 GL 上下文，帧回调里做任何 GL 调用都会让 LWJGL
     * 直接终止 JVM（native abort，捕获不到）。而 GUI 绘制期的 GL 上下文是有效的——游戏自己正在画界面。
     * 因此 SDL 栈上把"输入轮询 + 绘制"整体挪到这个钩子里，由它每帧驱动 {@code NoturneRuntime.onFrame()}。
     *
     * <p>钩子描述符必须是 {@code (Ljava/lang/Object;)V}：转换器只负责把首个引用形参原样传出来，
     * 不关心它具体是什么类型（把具体类型写进描述符会让钩子在类加载顺序变化时失配）。
     */
    private static void installGuiDrawHook(Instrumentation instrumentation) {
        EmbeddedAsmLoader asmLoader = EmbeddedAsmLoader.create();
        if (asmLoader == null) {
            log("embedded ASM unavailable; gui draw hook skipped");
            return;
        }
        ClassFileTransformer transformer = asmLoader.createCallbackTransformer(
                GUI_DRAW_CLASS, GUI_DRAW_METHOD, GUI_DRAW_DESCRIPTOR, GUI_HOOK_OWNER, GUI_HOOK_METHOD);
        if (transformer == null) {
            return;
        }
        try {
            instrumentation.addTransformer(transformer, true);
        } catch (Throwable t) {
            log("could not register gui draw hook: " + t);
            return;
        }
        // 目标类可能已被加载：显式重转换一次，否则要等下一次加载（那时可能永远不重载）。
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (GUI_DRAW_CLASS.equals(loaded.getName())) {
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable t) {
                    log("retransform " + GUI_DRAW_CLASS + " failed: " + t);
                }
                break;
            }
        }
        log("gui draw hook registered on " + GUI_DRAW_CLASS + "." + GUI_DRAW_METHOD);
    }

    private static void registerFrameHook(Instrumentation instrumentation, EmbeddedAsmLoader asmLoader,
                                          String targetInternalName, String method, String descriptor) {
        ClassFileTransformer transformer =
                asmLoader.createTransformer(targetInternalName, method, descriptor);
        if (transformer == null) {
            return;
        }
        try {
            // canRetransform=true：目标类此刻可能已被加载，注册后需立即重转换才能补上钩子。
            instrumentation.addTransformer(transformer, true);
        } catch (Throwable t) {
            log("could not register transformer for " + targetInternalName + ": " + t);
            return;
        }
        // 两类情况：目标类已加载 → 显式 retransform 立刻打补丁；尚未加载 → 保持注册，
        // 待其首次加载时由 transformer 自动处理。
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (targetInternalName.replace('/', '.').equals(loaded.getName())) {
                try {
                    instrumentation.retransformClasses(loaded);
                    // 只注册成功、不代表钩子已经跑起来：真正「live」由 NoturneRuntime 首帧日志确认。
                    log("frame hook registered on " + targetInternalName + "; awaiting first frame");
                } catch (Throwable t) {
                    log("retransform failed for " + targetInternalName + ": " + t);
                }
                return;
            }
        }
        log(targetInternalName + " not loaded yet; frame hook will apply when it loads");
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
            // trace(true) 已在 start() 里、注册转换器之前打开（见 M-82），此处不再重复。
            // GUI 绘制钩子需要 loader/instrumentation 才能发起安装：SDL 栈下只有它触发的时机
            // GL 上下文有效，因此安装由它首次调用时发起（见 GuiDrawHook）。
            GuiDrawHook.arm(loader, instrumentation, guiToggleKey);
            OverlayBootstrap.install(loader, instrumentation, guiToggleKey);
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
        logFallback(gl11);
        return new dev.noturne.ui.gl.GlRenderer(fixed, font);
    }

    /**
     * 在「核心 profile 绑定失败、退回固定管线」时给出与版本相符的日志。
     *
     * <p>1.12 及更早（LWJGL2）本该走固定管线，回退是预期行为，不该用告警语气把人带偏；只有
     * 1.13+（LWJGL3：GLFW 或 SDL）本应具备核心 profile 却绑定失败时，才保留告警——那时固定管线
     * 确实什么都画不出来。
     */
    private static void logFallback(Class<?> gl11) {
        if (isModernRenderStack(gl11)) {
            log("core profile bind failed on a core-profile-only stack (1.13+);"
                    + " falling back to fixed pipeline, which will render nothing");
        } else {
            log("no core profile in this GL stack (1.12 and earlier, LWJGL2);"
                    + " using the fixed-pipeline renderer as expected");
        }
    }

    /**
     * 当前渲染栈是否为 1.13+ 的 LWJGL3（GLFW 或 26.3 的 SDL）。
     *
     * <p>1.13+ 只有核心 profile，固定管线已被移除，因此这是「本应具备核心 profile」的判据；
     * LWJGL2（≤1.12）只有固定管线，回退属预期。
     */
    private static boolean isModernRenderStack(Class<?> gl11) {
        ClassLoader loader = gl11.getClassLoader();
        return loadNoInit("org.lwjgl.glfw.GLFW", loader) != null
                || loadNoInit("org.lwjgl.sdl.SDLVideo", loader) != null;
    }

    /** 以不初始化方式探测类存在（K2）；失败返回 {@code null}。 */
    private static Class<?> loadNoInit(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
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
     * 为当前运行的构建挑选映射表，并记为本次会话唯一的映射（{@link #currentMapping()}）。
     *
     * <p>版本由注入器随选项传入（{@code mcVersion=<版本族>}），运行时**不做版本探测**：
     * 表名就是 {@code /mappings-<版本族>.json}。未混淆的版本（26.1+）其规范名即运行期名，
     * 表中也这么写并附带 {@code absent} 标记；版本未知或表缺失时退化为恒等映射，但会打印一次说明。
     *
     * <p>单一来源：叠加层等下游**不得**再自己猜版本——那会让 1.16.5~1.21.x 这类混淆版本误用
     * 1.8.9 的表（名字全错，界面一片空白）。
     *
     * @param instrumentation 插桩句柄，为 null 时直接走恒等映射
     * @return Minecraft 类名/字段名映射，永不为 null
     */
    private static dev.noturne.client.mapping.Mapping selectMapping(Instrumentation instrumentation) {
        String version = mcVersion;
        if (version == null || version.isEmpty() || "unknown".equals(version)) {
            // 注入器没给出可用版本：只能退化为恒等映射，并留日志说明后果。
            log("mcVersion unknown; using identity mapping (obfuscated builds need an explicit mcVersion)");
            return remember(new dev.noturne.client.mapping.IdentityMapping());
        }
        // 表优先：未混淆版本（26.x）的表里成员名等于规范名，但**携带 absent 标记**——
        // 某个成员在该版本不存在时必须能明确告知模块，而不是等到反射抛异常。
        try {
            dev.noturne.client.mapping.Mapping mapping =
                    dev.noturne.client.mapping.ObfuscatedMapping.load("/mappings-" + version + ".json");
            log("mapping table loaded: mappings-" + version + ".json (" + mapping.describe() + ")");
            return remember(mapping);
        } catch (Throwable missing) {
            // 只有「该版本确实没有表」时才用恒等映射；这必须是可诊断的，不能静默。
            log("no mapping table for mcVersion=" + version + " (" + missing.getMessage()
                    + "); using identity mapping");
            return remember(new dev.noturne.client.mapping.IdentityMapping());
        }
    }

    /** 本次会话选定的映射表；由 {@link #selectMapping} 写入，下游只读复用。 */
    private static volatile dev.noturne.client.mapping.Mapping selectedMapping;

    /** 记录本次会话唯一的映射表并返回它。 */
    private static dev.noturne.client.mapping.Mapping remember(
            dev.noturne.client.mapping.Mapping mapping) {
        selectedMapping = mapping;
        return mapping;
    }

    /**
     * 本次会话选定的映射表；尚未选择时返回恒等映射（并打一次日志说明）。
     *
     * <p>供叠加层等下游复用，避免它们各自猜版本。
     *
     * @return 映射表，永不为 null
     */
    static dev.noturne.client.mapping.Mapping currentMapping() {
        dev.noturne.client.mapping.Mapping mapping = selectedMapping;
        if (mapping == null) {
            log("mapping requested before selection; using identity mapping");
            return new dev.noturne.client.mapping.IdentityMapping();
        }
        return mapping;
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
     * 从 agent 参数中解析目标 Minecraft 版本族（{@code mcVersion=1.8.9}）。
     *
     * <p>缺失或畸形时返回 {@code "unknown"}：此时映射选择会退化为恒等映射并打日志，
     * 而不是猜一个版本——猜错会让模块静默失败。
     *
     * @param agentArgs 代理参数字符串，可为 null
     * @return 版本族字符串，未知时为 {@code "unknown"}
     */
    private static String parseVersion(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return "unknown";
        }
        for (String part : agentArgs.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith("mcVersion=")) {
                continue;
            }
            String raw = trimmed.substring("mcVersion=".length()).trim();
            if (raw.isEmpty()) {
                continue;
            }
            // 只允许版本号字符，避免把别的选项内容当成版本拼进资源名。
            if (!raw.matches("[0-9][0-9.]*")) {
                log("mcVersion looks malformed: " + raw + "; treating it as unknown");
                return "unknown";
            }
            return raw;
        }
        return "unknown";
    }

    /**
     * 从 agent 参数中解析 GUI 开关按键。
     *
     * <p>参数由注入器以逗号分隔的 {@code key=value} 形式传入（JDK attach 的 options 约定），
     * 其中 {@code guiKey=<VK>} 是 AWT 虚拟键码（跨任务契约 K1）。只接受 {@code 0..0xFFFF} 这个
     * AWT VK 合法范围：越界值会被后续的键码翻译悄悄丢成「永不按下」，不如在这里就退回默认键并
     * 留下日志。任何畸形输入（非数字、缺值、越界）一律回退右 Shift——一个拼错的参数不该让整个
     * GUI 无法唤出（L-52）。
     *
     * @param agentArgs 代理参数字符串，可为 null
     * @return 解析出的 AWT VK 码，或默认的 {@link #VK_RIGHT_SHIFT}
     */
    private static int parseToggleKey(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return VK_RIGHT_SHIFT;
        }
        for (String part : agentArgs.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith("guiKey=")) {
                continue;
            }
            String raw = trimmed.substring("guiKey=".length()).trim();
            try {
                int code = Integer.parseInt(raw);
                if (code < 0 || code > 0xFFFF) {
                    log("guiKey out of AWT VK range (0..65535): " + raw
                            + "; falling back to right shift (" + VK_RIGHT_SHIFT + ")");
                    return VK_RIGHT_SHIFT;
                }
                return code;
            } catch (NumberFormatException malformed) {
                log("guiKey is not a number: " + raw
                        + "; falling back to right shift (" + VK_RIGHT_SHIFT + ")");
                return VK_RIGHT_SHIFT;
            }
        }
        return VK_RIGHT_SHIFT;
    }

    /** 统一的日志输出，统一加 {@code [noturne]} 前缀，便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
