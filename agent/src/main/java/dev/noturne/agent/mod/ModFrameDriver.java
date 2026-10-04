package dev.noturne.agent.mod;

import dev.noturne.agent.OverlayBootstrap;
import dev.noturne.client.game.Reflect;
import dev.noturne.client.runtime.NoturneRuntime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 模组路径下的逐帧驱动。
 *
 * <p>agent 路径靠 ASM 在帧交换点插桩拿到「每帧一次」的回调；模组路径没有
 * {@link java.lang.instrument.Instrumentation}，装不了钩子，只能改挂加载器提供的渲染事件。
 * Fabric 这边用 {@code HudRenderCallback}——它在每帧的 HUD 阶段触发，正是我们需要的位置。
 *
 * <p>全程反射：加载器构件在编译期不可见（{@code modStubs} 只桩了注解与接口），
 * 且 Fabric 与 Forge 的事件 API 毫无共同点。注册失败一律静默返回——模组路径下没有 GUI
 * 也要能正常进游戏，绝不能因此让加载失败。
 */
public final class ModFrameDriver {

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
        // 模组路径同样要装叠加层：GL 此刻还没加载，OverlayBootstrap 会在渲染跑起来后自行完成。
        // 开关按键没有注入器可传参，固定用右 Shift。
        OverlayBootstrap.install(loader, null, dev.noturne.ui.gl.GuiOverlay.KEY_RIGHT_SHIFT);
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
            return;
        }
        Object event = Reflect.staticField(callback, "EVENT");
        if (event == null) {
            return;
        }
        Method register = Reflect.method(event.getClass(), "register", Object.class);
        if (register == null) {
            return;
        }
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                // 函数式接口只有一个方法，任何一次调用都等价于「该渲染这一帧了」。
                NoturneRuntime.onFrame();
                return null;
            }
        };
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, handler);
        Reflect.call(register, event, listener);
    }
}
