package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.IntSupplier;

/**
 * {@link InputSource} 的反射实现，同时覆盖 LWJGL2（Minecraft ≤ 1.12）与 GLFW（1.13+）两代输入栈。
 *
 * <p>全部通过反射访问：本类编译时链接不到 LWJGL，且不同版本的 LWJGL 属于不同类加载器。
 * 两个后端的句柄在构造时一次性解析，运行期只做调用，避免在每帧路径上重复查找。
 *
 * <p>GLFW 侧有两个必须特殊处理的点：
 * <ul>
 *   <li>窗口句柄（{@code glfwGetCurrentContext}）是<b>线程局部</b>的，只有渲染线程问得到。
 *       因此窗口句柄与滚轮回调都延迟到首次 {@link #mouseX()} 调用时才初始化——那时必然已经在帧循环里。</li>
 *   <li>GLFW 没有查询滚轮的 API，只能通过 {@code glfwSetScrollCallback} 注册回调。回调接口用
 *       {@link Proxy} 动态实现，以免在编译期依赖 LWJGL 的接口类型。</li>
 * </ul>
 */
public final class ReflectiveInput implements InputSource {

    /** GLFW 的按键/鼠标按下状态值。 */
    private static final int GLFW_PRESS = 1;
    /** GLFW 输入模式：指针模式。 */
    private static final int GLFW_CURSOR = 0x00033001;
    /** GLFW 指针模式：正常（可见、可移动）。 */
    private static final int GLFW_CURSOR_NORMAL = 0x00034001;
    /** GLFW 指针模式：禁用（被游戏捕获，用于转视角）。 */
    private static final int GLFW_CURSOR_DISABLED = 0x00034002;

    /** 绘制区域宽度提供者；用于把窗口坐标缩放到 GUI 坐标。 */
    private final IntSupplier surfaceWidth;
    /** 绘制区域高度提供者。 */
    private final IntSupplier surfaceHeight;

    // ---- LWJGL2（Minecraft ≤ 1.12）句柄，不可用时为 null ----
    private final Method mouseGetX;
    private final Method mouseGetY;
    private final Method mouseIsButtonDown;
    private final Method mouseGetDWheel;
    private final Method mouseSetGrabbed;
    private final Method keyboardIsKeyDown;
    private final Method displayGetWidth;
    private final Method displayGetHeight;

    // ---- GLFW（1.13+）句柄，不可用时为 null ----
    private final Method glfwGetCursorPos;
    private final Method glfwGetMouseButton;
    private final Method glfwGetKey;
    private final Method glfwGetCurrentContext;
    private final Method glfwGetWindowSize;
    private final Method glfwSetInputMode;
    private final Method glfwSetScrollCallback;
    /** GLFW 滚轮回调接口类型，供 {@link Proxy} 实现；缺失时为 null。 */
    private final Class<?> scrollCallbackType;

    /** 累积的滚轮增量，由 GLFW 回调线程写入、帧线程取出后清零。 */
    private final double[] pendingScroll = new double[1];
    /** 复用的光标坐标暂存，避免逐帧分配。 */
    private final double[] cursorOut = new double[2];
    /** y 坐标的独立暂存：GLFW 的 {@code glfwGetCursorPos} 要求 x、y 各一个数组参数。 */
    private final double[] cursorOutY = new double[2];
    /** 复用的窗口尺寸暂存。 */
    private final int[] windowSizeOut = new int[2];
    /** 窗口高度的独立暂存：GLFW 的 {@code glfwGetWindowSize} 同样要求两个数组参数。 */
    private final int[] windowSizeOutY = new int[2];

    /** 实际生效的后端名称。 */
    private final String backend;

    /** GLFW 窗口句柄；0 表示尚未取得。 */
    private long glfwWindow;
    /** 是否已尝试过获取 GLFW 窗口句柄（只尝试一次，失败即永久放弃）。 */
    private boolean glfwWindowResolved;

    private ReflectiveInput(ClassLoader loader, IntSupplier surfaceWidth, IntSupplier surfaceHeight) {
        this.surfaceWidth = surfaceWidth;
        this.surfaceHeight = surfaceHeight;

        Class<?> mouse = Reflect.load("org.lwjgl.input.Mouse", loader);
        Class<?> keyboard = Reflect.load("org.lwjgl.input.Keyboard", loader);
        Class<?> display = Reflect.load("org.lwjgl.opengl.Display", loader);
        if (mouse != null && keyboard != null && display != null) {
            this.mouseGetX = Reflect.method(mouse, "getX");
            this.mouseGetY = Reflect.method(mouse, "getY");
            this.mouseIsButtonDown = Reflect.method(mouse, "isButtonDown", int.class);
            this.mouseGetDWheel = Reflect.method(mouse, "getDWheel");
            this.mouseSetGrabbed = Reflect.method(mouse, "setGrabbed", boolean.class);
            this.keyboardIsKeyDown = Reflect.method(keyboard, "isKeyDown", int.class);
            this.displayGetWidth = Reflect.method(display, "getWidth");
            this.displayGetHeight = Reflect.method(display, "getHeight");
            this.glfwGetCursorPos = null;
            this.glfwGetMouseButton = null;
            this.glfwGetKey = null;
            this.glfwGetCurrentContext = null;
            this.glfwGetWindowSize = null;
            this.glfwSetInputMode = null;
            this.glfwSetScrollCallback = null;
            this.scrollCallbackType = null;
            this.backend = "lwjgl2";
            return;
        }

        Class<?> glfw = Reflect.load("org.lwjgl.glfw.GLFW", loader);
        if (glfw != null) {
            this.glfwGetCursorPos = Reflect.method(glfw, "glfwGetCursorPos",
                    long.class, double[].class, double[].class);
            this.glfwGetMouseButton = Reflect.method(glfw, "glfwGetMouseButton", long.class, int.class);
            this.glfwGetKey = Reflect.method(glfw, "glfwGetKey", long.class, int.class);
            this.glfwGetCurrentContext = Reflect.method(glfw, "glfwGetCurrentContext");
            this.glfwGetWindowSize = Reflect.method(glfw, "glfwGetWindowSize",
                    long.class, int[].class, int[].class);
            this.glfwSetInputMode = Reflect.method(glfw, "glfwSetInputMode", long.class, int.class, int.class);
            this.scrollCallbackType = Reflect.load("org.lwjgl.glfw.GLFWScrollCallbackI", loader);
            this.glfwSetScrollCallback = scrollCallbackType == null
                    ? null
                    : Reflect.method(glfw, "glfwSetScrollCallback", long.class, scrollCallbackType);
            if (glfwGetCursorPos == null) {
                // 类找到了却解析不出方法时，把「类本身长什么样」打出来：加载器、方法总数、
                // 以及逐个探测结果——否则只能看到一句 input=none。
                System.out.println("[noturne] glfw present but unresolved; loader=" + glfw.getClassLoader()
                        + " declared=" + glfw.getDeclaredMethods().length
                        + " cursorPos=" + (glfwGetCursorPos != null)
                        + " mouseButton=" + (glfwGetMouseButton != null)
                        + " key=" + (glfwGetKey != null)
                        + " context=" + (glfwGetCurrentContext != null)
                        + " windowSize=" + (glfwGetWindowSize != null));
                // 直接查一次并打印异常：区分「没有这个方法」与「解析过程本身出错」。
                try {
                    java.lang.reflect.Method direct =
                            glfw.getDeclaredMethod("glfwGetCursorPos", long.class, double[].class);
                    System.out.println("[noturne] direct getDeclaredMethod ok: " + direct);
                } catch (Throwable t) {
                    System.out.println("[noturne] direct getDeclaredMethod failed: " + t);
                }
                for (java.lang.reflect.Method m : glfw.getDeclaredMethods()) {
                    if (m.getName().contains("CursorPos")) {
                        System.out.println("[noturne]   candidate: " + m);
                    }
                }
            }
        } else {
            this.glfwGetCursorPos = null;
            this.glfwGetMouseButton = null;
            this.glfwGetKey = null;
            this.glfwGetCurrentContext = null;
            this.glfwGetWindowSize = null;
            this.glfwSetInputMode = null;
            this.glfwSetScrollCallback = null;
            this.scrollCallbackType = null;
        }

        this.mouseGetX = null;
        this.mouseGetY = null;
        this.mouseIsButtonDown = null;
        this.mouseGetDWheel = null;
        this.mouseSetGrabbed = null;
        this.keyboardIsKeyDown = null;
        this.displayGetWidth = null;
        this.displayGetHeight = null;
        this.backend = glfwGetCursorPos != null ? "glfw" : "none";
    }

    /**
     * 创建输入源。
     *
     * @param loader         游戏类加载器，用于解析 LWJGL 类
     * @param surfaceWidth   绘制区域宽度提供者（通常为 {@code renderer::width}）
     * @param surfaceHeight  绘制区域高度提供者
     * @return 可用的输入源；两代输入栈都找不到时返回一个恒为「无输入」的退化实现，绝不返回 null
     */
    public static InputSource create(ClassLoader loader, IntSupplier surfaceWidth, IntSupplier surfaceHeight) {
        ReflectiveInput input = new ReflectiveInput(loader, surfaceWidth, surfaceHeight);
        if ("none".equals(input.backend)) {
            // 两代输入栈都没解析出来时，把探测结果打出来——否则日志里只有一句 input=none，无从下手。
            System.out.println("[noturne] no input backend; lwjgl2-mouse="
                    + (Reflect.load("org.lwjgl.input.Mouse", loader) != null)
                    + " glfw=" + (Reflect.load("org.lwjgl.glfw.GLFW", loader) != null)
                    + " loader=" + loader);
        }
        return "none".equals(input.backend) ? new NoInput() : input;
    }

    @Override
    public String describe() {
        return backend;
    }

    @Override
    public double mouseX() {
        if ("lwjgl2".equals(backend)) {
            Object x = Reflect.call(mouseGetX, null);
            return x instanceof Number ? ((Number) x).doubleValue() * scaleX() : 0d;
        }
        if (!ensureGlfwWindow()) {
            return 0d;
        }
        // GLFW 要求 x、y 各一个数组参数，两个都要传。
        Reflect.call(glfwGetCursorPos, null, glfwWindow, cursorOut, cursorOutY);
        return cursorOut[0] * scaleX();
    }

    @Override
    public double mouseY() {
        if ("lwjgl2".equals(backend)) {
            Object y = Reflect.call(mouseGetY, null);
            if (!(y instanceof Number)) {
                return 0d;
            }
            // LWJGL2 的窗口坐标原点在左下角，GUI 原点在左上角 → 纵向翻转
            double flipped = windowHeight() - ((Number) y).doubleValue();
            return flipped * scaleY();
        }
        if (!ensureGlfwWindow()) {
            return 0d;
        }
        Reflect.call(glfwGetCursorPos, null, glfwWindow, cursorOut, cursorOutY);
        return cursorOutY[0] * scaleY();
    }

    @Override
    public boolean mouseDown(int button) {
        if ("lwjgl2".equals(backend)) {
            Object down = Reflect.call(mouseIsButtonDown, null, button);
            return Boolean.TRUE.equals(down);
        }
        if (!ensureGlfwWindow()) {
            return false;
        }
        Object state = Reflect.call(glfwGetMouseButton, null, glfwWindow, button);
        return state instanceof Number && ((Number) state).intValue() == GLFW_PRESS;
    }

    @Override
    public double scrollDelta() {
        if ("lwjgl2".equals(backend)) {
            Object wheel = Reflect.call(mouseGetDWheel, null);
            // LWJGL2 的 getDWheel 返回本次轮询的增量并自行清零，无需再取。
            return wheel instanceof Number ? ((Number) wheel).doubleValue() : 0d;
        }
        double pending = pendingScroll[0];
        pendingScroll[0] = 0d;
        return pending;
    }

    @Override
    public boolean keyDown(int key) {
        if ("lwjgl2".equals(backend)) {
            Object down = Reflect.call(keyboardIsKeyDown, null, key);
            return Boolean.TRUE.equals(down);
        }
        if (!ensureGlfwWindow()) {
            return false;
        }
        Object state = Reflect.call(glfwGetKey, null, glfwWindow, key);
        return state instanceof Number && ((Number) state).intValue() == GLFW_PRESS;
    }

    @Override
    public void setPointerGrabbed(boolean grabbed) {
        if ("lwjgl2".equals(backend)) {
            Reflect.call(mouseSetGrabbed, null, grabbed);
            return;
        }
        if (ensureGlfwWindow()) {
            Reflect.call(glfwSetInputMode, null, glfwWindow, GLFW_CURSOR,
                    grabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        }
    }

    /**
     * 取得 GLFW 窗口句柄并在必要时注册滚轮回调；只尝试一次。
     *
     * <p>必须在渲染线程首次调用——{@code glfwGetCurrentContext} 返回的是<b>本线程</b>当前的上下文，
     * 在 agent 初始化线程上问只会得到 0。
     */
    private boolean ensureGlfwWindow() {
        if (glfwWindowResolved) {
            return glfwWindow != 0L;
        }
        glfwWindowResolved = true;
        Object context = Reflect.call(glfwGetCurrentContext, null);
        if (!(context instanceof Number)) {
            return false;
        }
        glfwWindow = ((Number) context).longValue();
        installScrollCallback();
        return glfwWindow != 0L;
    }

    /** 用动态代理实现 {@code GLFWScrollCallbackI}，把滚轮增量累积到 {@link #pendingScroll}。 */
    private void installScrollCallback() {
        if (glfwSetScrollCallback == null || scrollCallbackType == null || glfwWindow == 0L) {
            return;
        }
        try {
            Object callback = Proxy.newProxyInstance(
                    scrollCallbackType.getClassLoader(),
                    new Class<?>[]{scrollCallbackType},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            // 回调签名固定为 (long window, double xoffset, double yoffset)
                            if (args != null && args.length == 3 && args[2] instanceof Number) {
                                pendingScroll[0] += ((Number) args[2]).doubleValue();
                            }
                            return null;
                        }
                    });
            Reflect.call(glfwSetScrollCallback, null, glfwWindow, callback);
        } catch (Throwable ignored) {
            // 回调注册失败只是没有滚轮，不影响键鼠操作，故静默降级。
        }
    }

    /** @return 窗口宽度（像素）；取不到时返回 0 */
    private int windowWidth() {
        if ("lwjgl2".equals(backend)) {
            Object width = Reflect.call(displayGetWidth, null);
            return width instanceof Number ? ((Number) width).intValue() : 0;
        }
        if (!ensureGlfwWindow()) {
            return 0;
        }
        Reflect.call(glfwGetWindowSize, null, glfwWindow, windowSizeOut, windowSizeOutY);
        return windowSizeOut[0];
    }

    /** @return 窗口高度（像素）；取不到时返回 0 */
    private int windowHeight() {
        if ("lwjgl2".equals(backend)) {
            Object height = Reflect.call(displayGetHeight, null);
            return height instanceof Number ? ((Number) height).intValue() : 0;
        }
        if (!ensureGlfwWindow()) {
            return 0;
        }
        Reflect.call(glfwGetWindowSize, null, glfwWindow, windowSizeOut, windowSizeOutY);
        return windowSizeOutY[0];
    }

    /** @return 绘制区域宽度 / 窗口宽度；任一未知时退化为 1（即不做缩放） */
    private double scaleX() {
        int surface = surfaceWidth.getAsInt();
        int window = windowWidth();
        return surface > 0 && window > 0 ? (double) surface / window : 1d;
    }

    /** @return 绘制区域高度 / 窗口高度；任一未知时退化为 1 */
    private double scaleY() {
        int surface = surfaceHeight.getAsInt();
        int window = windowHeight();
        return surface > 0 && window > 0 ? (double) surface / window : 1d;
    }

    /**
     * 两代输入栈都不可用时的退化实现。
     *
     * <p>存在的意义是让 {@link GuiOverlay} 不必到处判空：GUI 依然能绘制（例如只为截图或调试），
     * 只是永远收不到输入。
     */
    private static final class NoInput implements InputSource {

        @Override
        public double mouseX() {
            return 0d;
        }

        @Override
        public double mouseY() {
            return 0d;
        }

        @Override
        public boolean mouseDown(int button) {
            return false;
        }

        @Override
        public double scrollDelta() {
            return 0d;
        }

        @Override
        public boolean keyDown(int key) {
            return false;
        }

        @Override
        public void setPointerGrabbed(boolean grabbed) {
            // 无输入后端可控制，空实现。
        }

        @Override
        public String describe() {
            return "none";
        }
    }
}
