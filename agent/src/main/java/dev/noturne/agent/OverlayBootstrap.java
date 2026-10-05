package dev.noturne.agent;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.Mapping;
import dev.noturne.client.runtime.FrameListener;
import dev.noturne.client.runtime.NoturneRuntime;
import dev.noturne.ui.gl.GlApi;
import dev.noturne.ui.gl.GuiOverlay;
import dev.noturne.ui.gl.InputSource;
import dev.noturne.ui.gl.MinecraftTextRenderer;
import dev.noturne.ui.gl.ModernGlApi;
import dev.noturne.ui.gl.ModernRenderer;
import dev.noturne.ui.gl.ReflectiveInput;
import dev.noturne.ui.gl.SkijaBackend;
import dev.noturne.ui.gl.TextRenderer;
import dev.noturne.ui.gl.UiBackend;
import dev.noturne.ui.skija.SkijaHudSink;

import java.lang.instrument.Instrumentation;

/**
 * 叠加层安装器：把「GL 就绪之后再装 GUI 叠加层」这件事做成可重试的。
 *
 * <p>两条路径的时机完全不同，因此不能各写一套：
 * <ul>
 *   <li><b>agent 路径</b>：注入完成时游戏早已在渲染，GL 类都在，可以立刻安装。</li>
 *   <li><b>模组路径</b>：初始化回调发生在游戏启动早期，那时 LWJGL 还没被加载，
 *       {@code org.lwjgl.opengl.GL11} 根本找不到——直接装必然失败。</li>
 * </ul>
 *
 * <p>统一做法：先注册一个每帧监听器，等第一帧真正到来（说明渲染循环已经跑起来）再尝试安装；
 * 失败就下一帧继续试，直到成功或尝试上限。安装成功后自己从监听器列表里退出。
 *
 * <p>游戏类加载器**每帧重新解析**，绝不做一次性快照：{@code premain} 路径下 GL11 一定还没加载，
 * 此时若把系统类加载器记下来，之后 GL11 真正加载了也永远找不到它（H-02 / M-87）。
 */
public final class OverlayBootstrap implements FrameListener {

    /**
     * 安装尝试上限。
     *
     * <p>按 60fps 估算约 10 秒；超过这个窗口还没装成功，说明当前环境无法安装叠加层，再试下去只是
     * 白耗每帧开销。注意：这一计数只在「GL 与客户端都已就绪、但绑定/安装失败」时累加，GL 尚未
     * 加载的帧不计数，因此不会出现「还没看到 GL 就把重试次数用完」的情况。
     */
    private static final int MAX_ATTEMPTS = 600;

    /** GL11 全限定名；每帧用它去已加载类里重新解析游戏类加载器。 */
    private static final String GL11_CLASS = "org.lwjgl.opengl.GL11";

    /** 游戏类加载器；仅在无 {@link Instrumentation} 的模组路径下作为兜底使用。 */
    private final ClassLoader gameLoader;
    /** 插桩句柄；模组路径下为 {@code null}。 */
    private final Instrumentation instrumentation;
    /** GUI 开关按键键码（AWT VK 码）。 */
    private final int toggleKey;

    /** 已尝试安装的次数；仅覆盖「前置就绪但安装失败」的帧。 */
    private int attempts;
    /** 是否已安装成功；成功后本监听器退场。 */
    private boolean installed;
    /** 是否已记录过放弃日志，保证只打印一次。 */
    private boolean abandoned;
    /** 是否已就 SDL 栈延迟安装打过一次说明日志。 */
    private boolean sdlStackDetected;

    private OverlayBootstrap(ClassLoader gameLoader, Instrumentation instrumentation, int toggleKey) {
        this.gameLoader = gameLoader;
        this.instrumentation = instrumentation;
        this.toggleKey = toggleKey;
    }

    /**
     * 注册安装器；真正的安装会推迟到第一帧。
     *
     * @param gameLoader    游戏类加载器（agent 路径取 GL 类的加载器，模组路径取入口类的加载器）
     * @param instrumentation 插桩句柄，模组路径传 {@code null}
     * @param toggleKey     GUI 开关按键键码（AWT VK 码）
     */
    public static void install(ClassLoader gameLoader, Instrumentation instrumentation, int toggleKey) {
        // 诊断（diag2）：stdout 在目标 JVM 里可能被日志框架吞掉（只看得到部分行），文件不会。
        try {
            java.nio.file.Files.write(
                    java.nio.file.Paths.get(System.getProperty("user.dir", "."), "noturne-diag.txt"),
                    ("diag2 install: loader=" + gameLoader + " self=" + OverlayBootstrap.class
                            + " from=" + codeSourceOf(OverlayBootstrap.class) + "\n").getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // 诊断失败绝不影响安装
        }
        NoturneRuntime.addListener(new OverlayBootstrap(gameLoader, instrumentation, toggleKey));
    }

    @Override
    public void onFrame() {
        // 诊断（diag2）：用 attempts==0 作为条件——它在「真正尝试安装」时才自增，因此
        // GL 未就绪的帧也仍是 0，能保证进入 onFrame 就一定会记录一次。
        if (attempts == 0) {
            try {
                java.nio.file.Files.write(
                        java.nio.file.Paths.get(System.getProperty("user.dir", "."), "noturne-diag.txt"),
                        ("diag2 onFrame: installed=" + installed + " attempts=" + attempts + "\n")
                                .getBytes("UTF-8"),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // 诊断失败不影响安装
            }
        }
        if (installed) {
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            giveUp();
            return;
        }
        tryInstall();
    }

    /** 尝试安装一次；任何一步不满足就返回等下一帧。 */
    private void tryInstall() {
        Class<?> gl11 = resolveGl11();
        if (gl11 == null) {
            // GL 还没加载（游戏启动早期）：不计数，下一帧继续等。
            return;
        }
        // 不直接解引用 NoturneClient.get()：客户端若尚未 boot 完成，取不到就下一帧再试，
        // 且不消耗重试次数（L-53）。
        NoturneClient client = NoturneClient.get();
        if (client == null) {
            return;
        }
        // 只在 GUI 绘制路径（GL 上下文有效）里安装：SDL 栈下在帧回调里做 GL 会让 LWJGL 直接终止
        // JVM（native abort，捕获不到）。放在计数之前，保证"等待安全时机"不消耗重试次数。
        if (!GuiDrawHook.isSafeGlContext()) {
            return;
        }
        // 实测：即便是 GUI 绘制路径，26.x 的 SDL 上下文对 LWJGL 的 GL 绑定同样不可用——在这里调用
        // GL 仍会让 JVM abort。因此 SDL 栈下**不做 GL 初始化**（宁可没有界面，也不能崩游戏）；
        // 待接入不依赖 LWJGL 绑定的绘制路径后再启用。
        if (GuiDrawHook.isSdlStack()) {
            if (!sdlStackDetected) {
                sdlStackDetected = true;
                log("SDL GL stack: overlay rendering unavailable via LWJGL bindings;"
                        + " skipping GL initialisation (the game is left untouched)");
            }
            return;
        }
        attempts++;

        // 注：SDL 栈（Minecraft 26.x）下帧钩子**不再注册**（见 NoturneAgent.installFrameHook），
        // 帧回调因此只由 GUI 绘制钩子驱动——那里 GL 上下文安全（见 GuiDrawHook）。所以这里不必再
        // 拦截安装：它只会在安全时机发生。

        ClassLoader loader = gl11.getClassLoader() != null ? gl11.getClassLoader() : gameLoader;
        GlApi gl = GlApi.bind(gl11);
        if (gl == null) {
            return;
        }
        Mapping mapping = currentMapping();
        GameBridge bridge = new GameBridge(instrumentation, mapping);
        TextRenderer font = MinecraftTextRenderer.bind(bridge);
        client.setGameBridge(bridge);

        UiBackend backend = selectBackend(gl, gl11, font);
        InputSource input = ReflectiveInput.create(loader, backend::width, backend::height);
        // 诊断（diag1）：把输入源的真实类与它的类来源打出来。"input=none" 这类现象必须能区分
        // 「探测失败」与「运行时加载到了别处的旧类」——否则只在日志里猜，无法定位。
        System.out.println("[noturne] overlay diag: input=" + input.getClass().getName()
                + " from=" + codeSourceOf(input.getClass())
                + " backendClass=" + backend.getClass().getName()
                + " from=" + codeSourceOf(backend.getClass()) + " mark=diag1");
        // HUD 行汇：模块只面向 HudSink 发布文本行，接住它们的实现必须由 UI 侧提供。此前没有任何
        // 实现，模块的发布全部落到空处——HUD 上永远看不到模块文本，而模块自身毫无察觉。
        SkijaHudSink hudSink = backend instanceof SkijaBackend ? new SkijaHudSink() : null;
        if (hudSink != null) {
            client.setHudSink(hudSink);
        }
        GuiOverlay overlay = new GuiOverlay(client.modules(), backend, input, toggleKey, hudSink);
        NoturneRuntime.addListener(overlay);

        installed = true;
        // 安装完成即退场：之后每帧的开销全部留给真正的叠加层。
        NoturneRuntime.removeListener(this);
        log("GUI overlay attached; backend=" + backend.backendName()
                + "; input=" + overlay.keyBackend()
                + "; toggle key=" + toggleKey
                + "; mapping=" + mapping.describe());
    }

    /**
     * 每帧重新解析游戏侧的 GL11 类。
     *
     * <p>agent 路径优先用 {@link Instrumentation#getAllLoadedClasses()}——它能看到 Fabric/Forge
     * 隔离类加载器里的类，而且不触发目标类的静态初始化（K2 / M-88）。模组路径没有句柄，退回到
     * 构造时传入的游戏类加载器，并用不初始化方式探测。
     */
    private Class<?> resolveGl11() {
        if (instrumentation != null) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (GL11_CLASS.equals(loaded.getName())) {
                    return loaded;
                }
            }
        }
        return loadNoInit(GL11_CLASS, gameLoader);
    }

    /** 放弃安装：打一次明确日志并把自己移出监听器列表，避免永久空转（M-90）。 */
    private void giveUp() {
        if (abandoned) {
            return;
        }
        abandoned = true;
        log("GUI overlay install abandoned after " + attempts
                + " attempts (GL became available but binding kept failing); removing listener");
        NoturneRuntime.removeListener(this);
    }

    /**
     * 复用 agent 选定的映射表（**不再自己猜版本**）。
     *
     * <p>曾经这里按「规范主类能不能加载」猜版本、猜不到就硬编码回退 1.8.9 的表：在 1.16.5~1.21.x 这些
     * 混淆版本上规范主类同样加载不到，于是叠加层会用**错误的表**（名字全错、界面一片空白）。
     * 版本已由注入器传给 agent，表也只该选一次，这里只做读取。
     *
     * @return 本次会话的映射表，永不为 null
     */
    private static Mapping currentMapping() {
        return NoturneAgent.currentMapping();
    }

    /**
     * 挑选绘制后端：能绑定核心 profile 入口的就是 1.13+，否则用固定管线。
     *
     * @param fixed 固定管线绑定
     * @param gl11  游戏实际加载的 GL11 类，用于取得正确的类加载器
     * @param font  文本渲染器，可为 null
     * @return 绘制后端，永不为 null
     */
    private static UiBackend selectBackend(GlApi fixed, Class<?> gl11, TextRenderer font) {
        // 首选 Skija：它与游戏用哪代 OpenGL、哪个绘制 API 都无关（一份 GUI 代码管所有版本），
        // 字体也自带。安装时探测一次（真正走一帧），失败才回落到按代际的 GL 后端。
        SkijaBackend skija = SkijaBackend.probe(fixed);
        if (skija != null) {
            System.out.println("[noturne] backend: skija (version independent, self-hosted fonts)");
            return skija;
        }
        ModernGlApi modern = ModernGlApi.bind(gl11.getClassLoader());
        if (modern != null) {
            return new ModernRenderer(modern, font);
        }
        // 1.12 及更早（LWJGL2）本该走固定管线，回退是预期行为；只有 1.13+（LWJGL3）本应具备核心
        // profile 却绑定失败时才告警——那时固定管线确实什么都画不出来。上面 ModernGlApi 的
        // 「core profile bind miss」诊断行保留，便于定位缺了哪一项。
        if (isModernRenderStack(gl11)) {
            System.out.println("[noturne] core profile bind failed on a core-profile-only stack"
                    + " (1.13+); falling back to fixed pipeline, which will render nothing");
        } else {
            System.out.println("[noturne] no core profile in this GL stack (1.12 and earlier,"
                    + " LWJGL2); using the fixed-pipeline renderer as expected");
        }
        return new dev.noturne.ui.gl.GlRenderer(fixed, font);
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

    /**
     * 诊断用：取类的来源（jar 路径或目录），取不到时返回 {@code "?"}。
     *
     * <p>定位「运行时到底加载了哪一份类」时，这是唯一直接的办法：类名相同、来源不同（agent jar /
     * 模组加载器 / 别的副本）会表现成完全不同的行为，而日志里只看到类名是看不出来的。
     */
    private static String codeSourceOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null
                    ? "?" : source.getLocation().toString();
        } catch (Throwable t) {
            return "?(" + t + ")";
        }
    }

    /**
     * 以不初始化方式探测类存在（K2）。
     *
     * <p>绝不能用 {@code Class.forName(..., true, ...)}：那会强制执行目标类的静态初始化，在
     * noturne-init 线程上与游戏主线程抢跑，甚至可能把类永久标记为 erroneous（M-88）。
     *
     * @param className 全限定类名（点号）
     * @param loader    类加载器，可为 null（退化为 bootstrap 加载器）
     * @return 类对象；不存在时返回 {@code null}
     */
    private static Class<?> loadNoInit(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 统一的日志输出，带 {@code [noturne]} 前缀便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
