package dev.nocturne.agent;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.runtime.FrameListener;
import dev.nocturne.client.runtime.NocturneRuntime;
import dev.nocturne.ui.gl.GlApi;
import dev.nocturne.ui.gl.GuiOverlay;
import dev.nocturne.ui.gl.InputSource;
import dev.nocturne.ui.gl.MinecraftTextRenderer;
import dev.nocturne.ui.gl.ModernGlApi;
import dev.nocturne.ui.gl.ModernRenderer;
import dev.nocturne.ui.gl.ReflectiveInput;
import dev.nocturne.ui.gl.SkijaBackend;
import dev.nocturne.ui.gl.TextRenderer;
import dev.nocturne.ui.gl.UiBackend;
import dev.nocturne.ui.skija.SkijaHudSink;

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

    /**
     * 等"游戏主类实例可用"的尝试上限（按 60fps 约 2 秒）。
     *
     * <p>GL 可用**早于** Minecraft 实例构造（Forge/launchwrapper 下尤其明显），而字体绑定必须
     * 拿到实例才能做。窗口内仍拿不到实例就按"无字"安装，绝不因为字体问题让界面装不上。
     */
    private static final int GAME_WAIT_ATTEMPTS = 120;

    /** GL11 全限定名；每帧用它去已加载类里重新解析游戏类加载器。 */
    private static final String GL11_CLASS = "org.lwjgl.opengl.GL11";

    /** 游戏类加载器；仅在无 {@link Instrumentation} 的模组路径下作为兜底使用。 */
    private final ClassLoader gameLoader;
    /** 插桩句柄；模组路径下为 {@code null}。 */
    private final Instrumentation instrumentation;
    /** GUI 开关按键键码（AWT VK 码）。 */
    private final int toggleKey;

    /** 装好后是否直接打开界面（验收用；见 {@code NocturneAgent} 的 {@code openGui=true}）。 */
    private final boolean openGuiOnInstall;

    /** 已尝试安装的次数；仅覆盖「前置就绪但安装失败」的帧。 */
    private int attempts;
    /** 是否已安装成功；成功后本监听器退场。 */
    private boolean installed;
    /** 是否已记录过放弃日志，保证只打印一次。 */
    private boolean abandoned;
    /** 是否已就 SDL 栈延迟安装打过一次说明日志。 */
    private boolean sdlStackDetected;

    private OverlayBootstrap(ClassLoader gameLoader, Instrumentation instrumentation, int toggleKey,
                             boolean openGuiOnInstall) {
        this.gameLoader = gameLoader;
        this.instrumentation = instrumentation;
        this.toggleKey = toggleKey;
        this.openGuiOnInstall = openGuiOnInstall;
    }

    /**
     * 注册安装器；真正的安装会推迟到第一帧。
     *
     * @param gameLoader    游戏类加载器（agent 路径取 GL 类的加载器，模组路径取入口类的加载器）
     * @param instrumentation 插桩句柄，模组路径传 {@code null}
     * @param toggleKey     GUI 开关按键键码（AWT VK 码）
     */
    public static void install(ClassLoader gameLoader, Instrumentation instrumentation, int toggleKey,
                               boolean openGuiOnInstall) {
        // 文件诊断（diag2）已删除：生产环境写 nocturne-diag.txt 是残留 IO；
        // 安装状态由 tryInstall 成功后的 overlay diag 日志行覆盖。
        NocturneRuntime.addListener(new OverlayBootstrap(gameLoader, instrumentation, toggleKey,
                openGuiOnInstall));
    }

    @Override
    public void onFrame() {
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
        // 不直接解引用 NocturneClient.get()：客户端若尚未 boot 完成，取不到就下一帧再试，
        // 且不消耗重试次数（L-53）。
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        // SDL 栈（26.x）保护：那里 LWJGL 的 GL 绑定不可用——实测帧回调（缓冲交换点）、GUI 绘制路径
        // 与游戏自己的呈现入口三处调用 GL 都会让 LWJGL FATAL ERROR 终止 JVM（native abort，捕获不到）。
        // 判定必须用 Instrumentation 的**已加载类现查**：启动早期游戏类还没加载，按名字探会漏判，
        // 而漏判的代价正是"注册了帧钩子 → 帧回调里做 GL → 崩游戏"（实测如此）。
        if (instrumentation != null) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if ("org.lwjgl.sdl.SDLVideo".equals(loaded.getName())) {
                    if (!sdlStackDetected) {
                        sdlStackDetected = true;
                        log("SDL GL stack: overlay disabled (LWJGL GL bindings unusable);"
                                + " the game is left untouched");
                    }
                    return;
                }
            }
        }
        attempts++;

        // 注：SDL 栈（Minecraft 26.x）下帧钩子**不再注册**（见 NocturneAgent.installFrameHook），
        // 帧回调因此只由 GUI 绘制钩子驱动——那里 GL 上下文安全（见 GuiDrawHook）。所以这里不必再
        // 拦截安装：它只会在安全时机发生。

        ClassLoader loader = gl11.getClassLoader() != null ? gl11.getClassLoader() : gameLoader;
        GlApi gl = GlApi.bind(gl11);
        if (gl == null) {
            return;
        }
        Mapping mapping = currentMapping();
        GameBridge bridge = new GameBridge(instrumentation, mapping);
        // 等游戏主类实例就绪再绑定字体：GL 可用**早于** Minecraft 实例构造（Forge/launchwrapper 下
        // 尤其明显），此时 bridge.minecraft() 还是 null，字体必然绑不上——界面照样能装出来，
        // 但一个字都不显示。等就绪的窗口内直接返回，下一帧再试（与等 GL 同一套策略）；
        // 窗口耗尽仍不可用就按"无字"安装，绝不因为字体问题让整个界面装不上。
        boolean gameReady = bridge.minecraft() != null;
        if (!gameReady && attempts <= GAME_WAIT_ATTEMPTS) {
            return;
        }
        TextRenderer font = MinecraftTextRenderer.bind(bridge);
        // bind 不再因"字体晚到"返回 null（改为绘制时晚绑定），所以这里只需报告是否已就绪。
        if (!gameReady) {
            log("game instance still unreachable after " + attempts
                    + " attempts; resolution diagnostics: " + bridge.describeResolution());
        }
        // 字体状态必须可见：字体没就绪时界面能开但**一个字都不显示**，而日志里此前没有任何线索。
        // 最常见的根因是映射表与目标版本/重映射环境不匹配（例如 Forge 用 SRG 名）。
        // 这里只报"是否已就绪"；真正的绑定结果由 MinecraftTextRenderer 打（含 font/gl/widthHandle）。
        System.out.println("[nocturne] text renderer: "
                + (font == null ? "bridge unavailable" : font.getClass().getSimpleName()
                        + " (font may bind late; see text renderer bound/pending lines)")
                + "; mapping=" + mapping.describe());
        client.setGameBridge(bridge);

        UiBackend backend = selectBackend(gl, gl11, font, bridge);
        InputSource input = ReflectiveInput.create(loader, backend::width, backend::height);
        // 诊断（diag1）：把输入源的真实类与它的类来源打出来。"input=none" 这类现象必须能区分
        // 「探测失败」与「运行时加载到了别处的旧类」——否则只在日志里猜，无法定位。
        System.out.println("[nocturne] overlay diag: input=" + input.getClass().getName()
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
        NocturneRuntime.addListener(overlay);
        if (openGuiOnInstall) {
            overlay.setOpen(true);
            log("GUI opened on install (openGui=true)");
        }
        // 我们界面打开时，顶掉已知外部客户端（FPSMaster 等）用 MC screen 机制弹出的界面：
        // 它们是真正的 currentScreen，会盖住并吃掉输入，看起来像我们没生效。
        NocturneRuntime.addListener(new ForeignScreenGuard(bridge, overlay::isOpen));

        installed = true;
        // 安装完成即退场：之后每帧的开销全部留给真正的叠加层。
        NocturneRuntime.removeListener(this);
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
        NocturneRuntime.removeListener(this);
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
        return NocturneAgent.currentMapping();
    }

    /**
     * 挑选绘制后端：能绑定核心 profile 入口的就是 1.13+，否则用固定管线。
     *
     * @param fixed 固定管线绑定
     * @param gl11  游戏实际加载的 GL11 类，用于取得正确的类加载器
     * @param font  文本渲染器，可为 null
     * @param bridge 游戏桥（ModernRenderer 取 Window 尺寸用）
     * @return 绘制后端，永不为 null
     */
    private static UiBackend selectBackend(GlApi fixed, Class<?> gl11, TextRenderer font,
                                          GameBridge bridge) {
        // LWJGL2（≤1.12）直接走固定管线，不 probe Skija：Skia 包 fb0 直写会盖黑游戏
        // （wrap/make、samples、flush/submit、colorspace 已逐项排除，机制层面不兼容），
        // 正确修法是纹理中转（另开任务）。固定管线在 1.8.9 已验证游戏 + overlay 全正常。
        if (!isModernRenderStack(gl11)) {
            System.out.println("[nocturne] backend: fixed pipeline (LWJGL2; skija disabled:"
                    + " direct fb0 writes black out the game)");
            return new dev.nocturne.ui.gl.GlRenderer(fixed, font);
        }
        // 1.13+（core profile）暂时直接走 ModernRenderer，不 probe Skija：Skia 包外部帧缓冲
        // 直写会盖黑游戏（1.8.9 与 1.16.5 真机均已实锤；make/wrap、samples、flush/submit、
        // colorspace、reset 逐项排除无解），正确修法是纹理中转（另开任务）。ModernRenderer 若
        // 绑定失败再按原逻辑回落（1.13+ 上固定管线画不出，会告警）。
        ModernGlApi modern = ModernGlApi.bind(gl11.getClassLoader());
        if (modern != null) {
            return new ModernRenderer(modern, font, bridge);
        }
        // 1.12 及更早（LWJGL2）本该走固定管线，回退是预期行为；只有 1.13+（LWJGL3）本应具备核心
        // profile 却绑定失败时才告警——那时固定管线确实什么都画不出来。上面 ModernGlApi 的
        // 「core profile bind miss」诊断行保留，便于定位缺了哪一项。
        if (isModernRenderStack(gl11)) {
            System.out.println("[nocturne] core profile bind failed on a core-profile-only stack"
                    + " (1.13+); falling back to fixed pipeline, which will render nothing");
        } else {
            System.out.println("[nocturne] no core profile in this GL stack (1.12 and earlier,"
                    + " LWJGL2); using the fixed-pipeline renderer as expected");
        }
        return new dev.nocturne.ui.gl.GlRenderer(fixed, font);
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
     * nocturne-init 线程上与游戏主线程抢跑，甚至可能把类永久标记为 erroneous（M-88）。
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

    /** 统一的日志输出，带 {@code [nocturne]} 前缀便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[nocturne] " + message);
    }
}
