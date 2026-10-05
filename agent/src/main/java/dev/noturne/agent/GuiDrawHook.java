package dev.noturne.agent;

import dev.noturne.client.runtime.NoturneRuntime;

import java.lang.instrument.Instrumentation;

/**
 * GUI 绘制钩子：由 {@link dev.noturne.agent.transform.CallbackHookTransformer} 注入到
 * {@code Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} 的开头。
 *
 * <p>为什么需要它（而不是继续用帧钩子）：Minecraft 26.x 起改用 SDL 管理 GL 上下文，而 LWJGL 的
 * GL 绑定在其中不可用——**在帧回调里调用任何 GL 函数（哪怕只是 glGetIntegerv 读视口）都会让 LWJGL
 * 直接 {@code FATAL ERROR} 终止整个 JVM**，且是 native abort，Java 侧捕获不到。实测就是那样崩的：
 * {@code SDL_GL_SwapWindow → 帧钩子 → SkijaBackend.beginFrame → glGetIntegerv → JVM abort}。
 *
 * <p>而 GUI 绘制期的 GL 上下文是**正常可用**的（游戏自己正在用它画界面），因此：
 * <ul>
 *   <li>叠加层的**安装**放在这里首次触发时发起（GL 安全）；</li>
 *   <li>每帧的**输入轮询与绘制**也由这里驱动；</li>
 *   <li>帧钩子在 SDL 栈下**不注册**，避免任何一次误入 GL 的调用把游戏弄崩。</li>
 * </ul>
 *
 * <p>契约：本方法由注入的字节码调用，**绝不抛出异常**——否则会把游戏崩在绘制路径上。
 */
public final class GuiDrawHook {

    /** 游戏类加载器（来自 {@code NoturneAgent} 的解析结果）。 */
    private static volatile ClassLoader gameLoader;
    /** 插桩句柄。 */
    private static volatile Instrumentation instrumentation;
    /** GUI 开关键（AWT VK 码）。 */
    private static volatile int toggleKey;
    /** 是否已发起过叠加层安装。 */
    private static volatile boolean armed;

    /** 是否已进入过 GUI 绘制——只有那里的 GL 上下文有效，叠加层安装必须等到那时。 */
    private static volatile boolean safeGlContext;

    private GuiDrawHook() {
    }

    /** @return 是否已进入过 GUI 绘制（GL 上下文有效的时机） */
    static boolean isSafeGlContext() {
        return safeGlContext;
    }

    /** 是否 SDL 渲染栈（Minecraft 26.x）。首次 GUI 绘制时用已加载的类判定。 */
    private static volatile boolean sdlStack;
    /** 是否已做过 SDL 判定（只判一次）。 */
    private static volatile boolean sdlDetected;

    /** @return 是否 SDL 渲染栈 */
    static boolean isSdlStack() {
        return sdlStack;
    }

    /**
     * 记录安装所需的环境；由 {@code NoturneAgent} 在注册转换器时调用。
     *
     * @param loader          游戏类加载器
     * @param inst            插桩句柄
     * @param key             GUI 开关键（AWT VK 码）
     */
    static void arm(ClassLoader loader, Instrumentation inst, int key) {
        gameLoader = loader;
        instrumentation = inst;
        toggleKey = key;
    }

    /**
     * 每次 GUI 绘制时被调用一次（方法开头）。
     *
     * <p>首次调用发起叠加层安装——此刻 GL 上下文安全；之后每帧驱动帧回调（输入轮询 + 绘制）。
     *
     * @param graphics 绘制上下文（{@code GuiGraphicsExtractor}）；本钩子不需要它，只用于确认
     *                 目标方法的首个引用形参已按约定传入
     */
    public static void onDraw(Object graphics) {
        // 能走到这里就说明 GL 上下文有效：叠加层的安装与绘制都只允许在此之后发生
        // （见 isSafeGlContext 与 OverlayBootstrap 的安全门）。
        safeGlContext = true;
        if (!sdlDetected) {
            sdlDetected = true;
            // 用 Instrumentation 的已加载类判定：SDLVideo 此刻必然已加载（游戏正在用它渲染），
            // 而按名字从游戏加载器查并不可靠（隔离加载器不一定愿意把 LWJGL 暴露给 by-name 查询）。
            Instrumentation inst = instrumentation;
            if (inst != null) {
                for (Class<?> type : inst.getAllLoadedClasses()) {
                    if ("org.lwjgl.sdl.SDLVideo".equals(type.getName())) {
                        sdlStack = true;
                        break;
                    }
                }
            } else {
                // 没有插桩句柄就无法可靠判定：按 SDL 处理（保守——不安装总比崩游戏好）。
                sdlStack = true;
            }
        }
        try {
            if (!armed) {
                armed = true;
                System.out.println("[noturne] gui draw hook: arming overlay (GL context is valid here)");
                OverlayBootstrap.install(gameLoader, instrumentation, toggleKey);
            }
            NoturneRuntime.onFrame();
        } catch (Throwable t) {
            // 绝不把异常带回游戏的绘制路径。
            System.out.println("[noturne] gui draw hook failed: " + t);
        }
    }
}
