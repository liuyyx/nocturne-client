package dev.noturne.agent;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.game.Reflect;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.mapping.IdentityMapping;
import dev.noturne.client.mapping.Mapping;
import dev.noturne.client.mapping.ObfuscatedMapping;
import dev.noturne.client.runtime.FrameListener;
import dev.noturne.client.runtime.NoturneRuntime;
import dev.noturne.ui.gl.GlApi;
import dev.noturne.ui.gl.GuiOverlay;
import dev.noturne.ui.gl.InputSource;
import dev.noturne.ui.gl.MinecraftTextRenderer;
import dev.noturne.ui.gl.ModernGlApi;
import dev.noturne.ui.gl.ModernRenderer;
import dev.noturne.ui.gl.ReflectiveInput;
import dev.noturne.ui.gl.TextRenderer;
import dev.noturne.ui.gl.UiBackend;

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
 * 失败就下一帧继续试，直到成功或超过尝试上限。安装成功后自己从监听器列表里退出。
 */
public final class OverlayBootstrap implements FrameListener {

    /**
     * 安装尝试上限。
     *
     * <p>按 60fps 估算约 10 秒；超过这个窗口还没等到 GL，说明当前环境根本不会有渲染循环
     * （例如服务端或纯命令行），再试下去只是白耗每帧开销。
     */
    private static final int MAX_ATTEMPTS = 600;

    /** 游戏类加载器，用于解析 LWJGL 与 Minecraft 的类。 */
    private final ClassLoader gameLoader;
    /** 插桩句柄；模组路径下为 {@code null}。 */
    private final Instrumentation instrumentation;
    /** GUI 开关按键键码。 */
    private final int toggleKey;

    /** 已尝试安装的次数。 */
    private int attempts;
    /** 是否已安装成功；成功后本监听器退场。 */
    private boolean installed;

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
     * @param toggleKey     GUI 开关按键键码
     */
    public static void install(ClassLoader gameLoader, Instrumentation instrumentation, int toggleKey) {
        NoturneRuntime.addListener(new OverlayBootstrap(gameLoader, instrumentation, toggleKey));
    }

    @Override
    public void onFrame() {
        if (installed || attempts >= MAX_ATTEMPTS) {
            return;
        }
        attempts++;
        tryInstall();
    }

    /** 尝试安装一次；任何一步不满足就静默返回，等下一帧。 */
    private void tryInstall() {
        Class<?> gl11 = Reflect.load("org.lwjgl.opengl.GL11", gameLoader);
        if (gl11 == null) {
            return;
        }
        GlApi gl = GlApi.bind(gl11);
        if (gl == null) {
            return;
        }
        Mapping mapping = selectMapping();
        GameBridge bridge = new GameBridge(instrumentation, mapping);
        TextRenderer font = MinecraftTextRenderer.bind(bridge);
        NoturneClient.get().setGameBridge(bridge);

        UiBackend backend = selectBackend(gl, gl11, font);
        InputSource input = ReflectiveInput.create(gl11.getClassLoader(), backend::width, backend::height);
        GuiOverlay overlay = new GuiOverlay(NoturneClient.get().modules(), backend, input, toggleKey);
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
     * 选择映射表：能直接加载到规范类名即为未混淆发行（26.1+），否则回退到 1.8.9 映射表。
     *
     * <p>与 agent 路径的区别在于没有 {@link Instrumentation} 可查已加载类，只能用类加载器探测；
     * 探测失败时退化为恒等映射，最坏结果是模块找不到目标方法，而不是崩溃。
     */
    private Mapping selectMapping() {
        String canonical = ClassType.MINECRAFT.canonicalName();
        if (Reflect.load(canonical, gameLoader) != null) {
            return new IdentityMapping();
        }
        // Fabric 运行时用的是 intermediary 名（如 net.minecraft.class_310），探测规范名必然失败；
        // 打出来才能区分「真的老版本」与「只是类名被重映射」。
        System.out.println("[noturne] canonical Minecraft class not visible: " + canonical
                + "; falling back to 1.8.9 mappings");
        try {
            return ObfuscatedMapping.load("/mappings-1.8.9.json");
        } catch (Throwable t) {
            return new IdentityMapping();
        }
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
        ModernGlApi modern = ModernGlApi.bind(gl11.getClassLoader());
        if (modern != null) {
            return new ModernRenderer(modern, font);
        }
        // 1.13+ 只有核心 profile：回退到固定管线等于画不出来，必须留下痕迹。
        System.out.println("[noturne] core profile bind failed; falling back to fixed pipeline"
                + " (this will render nothing on 1.13+)");
        return new dev.noturne.ui.gl.GlRenderer(fixed, font);
    }

    /** 统一的日志输出，带 {@code [noturne]} 前缀便于在游戏日志中检索。 */
    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
