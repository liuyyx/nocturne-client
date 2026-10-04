package dev.noturne.agent.mod;

import dev.noturne.agent.OverlayBootstrap;
import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.Reflect;
import dev.noturne.client.runtime.NoturneRuntime;
import dev.noturne.ui.gl.DrawContextBackend;
import dev.noturne.ui.gl.GuiOverlay;
import dev.noturne.ui.gl.InputSource;
import dev.noturne.ui.gl.ReflectiveInput;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 模组路径下的逐帧驱动与叠加层安装。
 *
 * <p>agent 路径靠 ASM 在帧交换点插桩拿到「每帧一次」的回调；模组路径没有
 * {@link java.lang.instrument.Instrumentation}，装不了钩子，只能改挂加载器提供的渲染事件。
 * Fabric 这边用 {@code HudRenderCallback}——它在每帧的 HUD 阶段触发。
 *
 * <p>绘制后端分两档，按运行环境自动选择：
 * <ul>
 *   <li><b>MC 1.21.9+</b>：渲染改成了自己的管线（RenderPipeline / GpuBuffer），直接发 GL 调用
 *       会被管线状态与提交顺序覆盖，画面上什么都不剩。因此优先用 {@link DrawContextBackend}，
 *       让 MC 自己提交绘制——{@code HudRenderCallback} 的第一个参数就是 DrawContext。</li>
 *   <li><b>更老的版本</b>：回退到 {@link OverlayBootstrap} 的 GL 后端。</li>
 * </ul>
 *
 * <p>全程反射：加载器构件在编译期不可见（{@code modStubs} 只桩了注解与接口），
 * 且 Fabric 与 Forge 的事件 API 毫无共同点。注册失败一律静默降级——模组路径下没有 GUI
 * 也要能正常进游戏。
 */
public final class ModFrameDriver {

    /** 已绑定的 MC DrawContext 后端；为 {@code null} 表示尚未绑定成功。 */
    private static DrawContextBackend drawContextBackend;
    /** 是否已装好叠加层（无论走哪条后端路径）。 */
    private static boolean overlayInstalled;
    /** 是否已回退到 GL 后端。 */
    private static boolean glFallback;

    /** 工具类，禁止实例化。 */
    private ModFrameDriver() {
    }

    /**
     * 在当前加载器上挂载逐帧驱动；找不到可用事件时静默返回。
     *
     * @param loader 模组类加载器（通常传入口类自己的 {@code getClassLoader()}）
     */
    public static void install(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        installFabric(loader);
    }

    /**
     * 注册 Fabric 的 {@code HudRenderCallback}。
     *
     * <p>回调接口是函数式接口，用 {@link Proxy} 动态实现即可，无需在编译期依赖 Fabric API。
     * 事件对象取 {@code HudRenderCallback.EVENT} 静态字段，注册方法名为 {@code register}。
     */
    private static void installFabric(ClassLoader loader) {
        Class<?> callback = Reflect.load(
                "net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback", loader);
        if (callback == null) {
            // 没装 Fabric API（或版本改了包名）：模组仍可加载，只是没有叠加层。
            System.out.println("[noturne] fabric HudRenderCallback not found; overlay stays hidden");
            return;
        }
        Object event = Reflect.staticField(callback, "EVENT");
        if (event == null) {
            System.out.println("[noturne] fabric hud EVENT field missing; overlay stays hidden");
            return;
        }
        // register 声明在 Event 接口上，实现类通常不重新声明它；Reflect.method 走的是
        // getDeclaredMethod（只查本类），因此必须在接口类型上解析，否则永远找不到。
        Class<?> eventType = Reflect.load("net.fabricmc.fabric.api.event.Event", loader);
        Method register = eventType != null
                ? Reflect.method(eventType, "register", Object.class)
                : Reflect.method(event.getClass(), "register", Object.class);
        if (register == null) {
            System.out.println("[noturne] fabric Event.register() not found; overlay stays hidden");
            return;
        }
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                // 函数式接口只有一个方法，任何一次调用都等价于「该渲染这一帧了」；
                // 第一个参数是 DrawContext（1.21.9+ 的唯一可靠绘制入口）。
                Object drawContext = args != null && args.length > 0 ? args[0] : null;
                onHudFrame(drawContext);
                return null;
            }
        };
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, handler);
        Reflect.call(register, event, listener);
        System.out.println("[noturne] fabric frame driver registered (HudRenderCallback)");
    }

    /**
     * 每帧回调：先把 DrawContext 绑成后端，绑定成功后用它安装叠加层。
     *
     * @param drawContext 本帧的 DrawContext；Fabric 之外的回调可能没有该参数，此时为 {@code null}
     */
    private static void onHudFrame(Object drawContext) {
        if (!overlayInstalled) {
            DrawContextBackend backend = drawContext == null ? null : DrawContextBackend.bind(drawContext);
            if (backend != null) {
                drawContextBackend = backend;
                InputSource input = ReflectiveInput.create(
                        ModFrameDriver.class.getClassLoader(), backend::width, backend::height);
                GuiOverlay overlay = new GuiOverlay(
                        NoturneClient.get().modules(), backend, input, GuiOverlay.KEY_RIGHT_SHIFT);
                NoturneRuntime.addListener(overlay);
                overlayInstalled = true;
                System.out.println("[noturne] overlay attached via MC DrawContext; backend="
                        + backend.backendName() + "; input=" + overlay.keyBackend()
                        + "; size=" + backend.width() + "x" + backend.height());
            } else if (!glFallback) {
                // 没有 DrawContext（老版本）→ 回退到 GL 后端；OverlayBootstrap 会在 GL 就绪后安装。
                glFallback = true;
                OverlayBootstrap.install(ModFrameDriver.class.getClassLoader(), null,
                        GuiOverlay.KEY_RIGHT_SHIFT);
                System.out.println("[noturne] no DrawContext; falling back to GL overlay bootstrap");
            }
            return;
        }
        if (drawContextBackend != null) {
            // 尺寸可能随窗口变化，每帧同步一次。
            drawContextBackend.update(drawContext);
        }
        NoturneRuntime.onFrame();
    }
}
