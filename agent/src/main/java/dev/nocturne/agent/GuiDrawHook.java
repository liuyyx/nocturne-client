package dev.nocturne.agent;

import dev.nocturne.client.runtime.NocturneRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GUI 绘制钩子：由 {@link dev.nocturne.agent.transform.CallbackHookTransformer} 注入到**两个**绘制
 * 入口的**末尾**（首个 {@code RETURN} 之前）：
 * <ul>
 *   <li>{@code Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)}——游戏内（世界里的 HUD）；</li>
 *   <li>{@code Screen.extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float)}
 *       ——任意界面（主菜单、暂停菜单、容器界面…）。它是 {@code final}，所以子类覆盖
 *       {@code extractRenderState} 也照样走它。</li>
 * </ul>
 *
 * <p><b>为什么两个都要</b>：{@code Hud} 那条只在游戏内渲染 HUD 时触发（主菜单根本不调它，实测：
 * 注入后 {@code gui draw hook} 一行都没有）；只织 {@code Hud} 的话，主菜单里界面永远画不出来。
 * 而 {@code Screen} 那条只在有界面时触发，游戏内没有界面时又不走。两条合起来才覆盖全部时机。
 *
 * <p><b>为什么不会一帧画两次</b>：游戏内同时存在 HUD 与界面时，{@code Gui.extractRenderState}
 * 先提取 HUD、后提取界面（界面在上层），所以有界面时 {@link #onHudDraw} 直接让位给
 * {@link #onScreenDraw}——否则我们画在 HUD 层的内容会被界面盖住。
 *
 * <p>为什么需要它（而不是继续用帧钩子）：Minecraft 26.x 起改用 SDL 管理 GL 上下文，而 LWJGL 的
 * GL 绑定在其中不可用——**在帧回调里调用任何 GL 函数（哪怕只是 glGetIntegerv 读视口）都会让 LWJGL
 * 直接 {@code FATAL ERROR} 终止整个 JVM**，且是 native abort，Java 侧捕获不到。实测就是那样崩的：
 * {@code SDL_GL_SwapWindow → 帧钩子 → SkijaBackend.beginFrame → glGetIntegerv → JVM abort}。
 *
 * <p><b>本类不持有任何静态状态</b>：它由游戏的隔离类加载器加载（注入的字节码在游戏方法里），而叠加层
 * 后端由 agent 的加载器持有——同一个类在两处各有一份，静态字段互不可见。因此绘制上下文与帧回调一律
 * 走 {@link NocturneRuntime}，它内部强制从 **bootstrap 层**取分发器（见 {@code FrameDispatcher}）。
 * 实测教训：早先直接调 {@code OverlayBootstrap.setDrawContext}，钩子写进的是空副本，界面"已打开"
 * 却一个像素都不画。
 *
 * <p>契约：本类的入口由注入的字节码调用，**绝不抛出异常**——否则会把游戏崩在绘制路径上。
 */
public final class GuiDrawHook {

    /** 判定"当前是否有界面"用的句柄；解析一次后复用（每帧都要问，不能每帧解析）。 */
    private static volatile Method minecraftGetInstance;
    private static volatile Field minecraftGuiField;
    private static volatile Method guiScreenMethod;
    /** 是否已尝试过解析上述句柄（失败也只试一次，避免每帧刷日志）。 */
    private static volatile boolean screenProbeResolved;

    private GuiDrawHook() {
    }

    /**
     * HUD 绘制入口（游戏内每帧一次）。
     *
     * <p>当前有界面时**不驱动**：{@code Gui.extractRenderState} 里 HUD 先提取、界面后提取，界面在上层，
     * 在 HUD 这一层画出来的东西会被界面盖住。让位给 {@link #onScreenDraw}。
     *
     * @param graphics 本帧的 {@code GuiGraphicsExtractor}
     */
    public static void onHudDraw(Object graphics) {
        if (hasScreen()) {
            return;
        }
        drive(graphics);
    }

    /**
     * 界面绘制入口（任意界面每帧一次）。
     *
     * @param graphics 本帧的 {@code GuiGraphicsExtractor}
     */
    public static void onScreenDraw(Object graphics) {
        drive(graphics);
    }

    /**
     * 驱动一帧：把本帧绘制上下文交给叠加层后端，再跑帧回调（输入轮询 + 绘制）。
     *
     * @param graphics 本帧的绘制上下文
     */
    private static void drive(Object graphics) {
        try {
            NocturneRuntime.setDrawContext(graphics);
            try {
                NocturneRuntime.onFrame();
            } finally {
                // 绘制上下文只在这次调用期间有效：用完立刻释放，避免后端持有已失效的对象。
                NocturneRuntime.setDrawContext(null);
            }
        } catch (Throwable t) {
            // 绝不把异常带回游戏的绘制路径。
            System.out.println("[nocturne] gui draw hook failed: " + t);
        }
    }

    /**
     * 当前是否挂着界面（{@code Minecraft.gui.screen() != null}）。
     *
     * <p>探测不到时返回 {@code false}：那意味着 HUD 钩子每帧都驱动——多画一次不会出错，
     * 而不画会让人以为叠加层没装。
     *
     * @return 有界面返回 {@code true}
     */
    private static boolean hasScreen() {
        if (!screenProbeResolved) {
            resolveScreenProbe();
        }
        Method getInstance = minecraftGetInstance;
        Field guiField = minecraftGuiField;
        Method screenMethod = guiScreenMethod;
        if (getInstance == null || guiField == null || screenMethod == null) {
            return false;
        }
        try {
            Object minecraft = getInstance.invoke(null);
            if (minecraft == null) {
                return false;
            }
            Object gui = guiField.get(minecraft);
            return gui != null && screenMethod.invoke(gui) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解析"当前是否有界面"所需的句柄。
     *
     * <p>用**本类的加载器**（游戏的隔离加载器）按名加载：注入的字节码在游戏方法里，本类就是被它加载
     * 的，所以这个加载器一定看得到游戏类。绝不使用 agent 侧传进来的加载器——那是另一个加载器，
     * 两个副本的静态字段互不可见（这正是本类不持有配置的原因）。
     *
     * <p>{@code Class.forName(name, false, loader)} 不触发目标类的静态初始化（K2 / M-88）。
     */
    private static void resolveScreenProbe() {
        screenProbeResolved = true;
        try {
            ClassLoader loader = GuiDrawHook.class.getClassLoader();
            Class<?> minecraftType = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> guiType = Class.forName("net.minecraft.client.gui.Gui", false, loader);
            minecraftGetInstance = minecraftType.getMethod("getInstance");
            minecraftGuiField = minecraftType.getField("gui");
            guiScreenMethod = guiType.getMethod("screen");
        } catch (Throwable t) {
            System.out.println("[nocturne] gui draw hook: screen probe unavailable (" + t + ");"
                    + " the HUD hook will drive every frame");
        }
    }
}
