package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;
import dev.noturne.client.input.KeyMap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * {@link InputSource} 的反射实现，同时覆盖 LWJGL2（Minecraft ≤ 1.12）与 GLFW（1.13+）两代输入栈。
 *
 * <p>全部通过反射访问：本类编译时链接不到 LWJGL，且不同版本的 LWJGL 属于不同类加载器。
 * 两个后端的句柄在构造时一次性解析，运行期只做调用，避免在每帧路径上重复查找。
 *
 * <p>键码契约：对外一律使用 <b>AWT VK 码</b>（右 Shift 固定为 54），由 {@link KeyMap} 在调用时
 * 翻译到具体后端——两代键码彼此无关（右 Shift 是 54 与 344），直接透传必然失效。
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
    /** GLFW 窗口属性：是否获得输入焦点。 */
    private static final int GLFW_FOCUSED = 0x00020001;

    /** LWJGL2 在 Windows 上按 {@code WM_MOUSEWHEEL} 的 {@code WHEEL_DELTA} 上报滚轮，一格为 120。 */
    private static final double LWJGL2_WHEEL_DIVISOR =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? 120d : 1d;

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
    private final Method glfwGetWindowAttrib;
    private final Method glfwSetScrollCallback;
    /** GLFW 滚轮回调接口类型，供 {@link Proxy} 实现；缺失时为 null。 */
    private final Class<?> scrollCallbackType;
    /** {@code GLFWScrollCallbackI.invoke(long, double, double)} 句柄，用于转发给被顶掉的旧回调。 */
    private final Method scrollCallbackInvoke;

    /** 累积的滚轮增量（按 double 位模式存于 {@link AtomicLong}），回调线程写入、帧线程取出后清零。 */
    private final AtomicLong pendingScrollBits = new AtomicLong(Double.doubleToRawLongBits(0d));
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
    /** 我们注册进去的滚轮回调强引用，避免被 GC。 */
    private Object installedScrollCallback;
    /** 被 {@code glfwSetScrollCallback} 顶掉的旧回调；保留引用并在代理里继续转发。 */
    private Object previousScrollCallback;
    /** 是否已成功下发过一次指针捕获状态（幂等判定用）。 */
    private boolean pointerGrabApplied;
    /** 最近一次下发的指针捕获状态。 */
    private boolean pointerGrabLast;
    /** LWJGL2 的 {@code Mouse.isGrabbed()}；不可用时为 {@code null}。 */
    private Method mouseIsGrabbed;
    /** GLFW 的 {@code glfwGetInputMode(long, int)}；不可用时为 {@code null}。 */
    private Method glfwGetInputMode;
    /** 上一帧窗口是否获得焦点；用于在焦点重新获得时重发光标模式（失焦会被 GLFW 重置）。 */
    private boolean lastFocused;

    private ReflectiveInput(ClassLoader loader, IntSupplier surfaceWidth, IntSupplier surfaceHeight) {
        this.surfaceWidth = surfaceWidth;
        this.surfaceHeight = surfaceHeight;

        // 探测用 loadWithoutInit（M-73）：对未知类执行 <clinit> 若抛错，JVM 会把该类
        // 永久标记为 erroneous，等于注入动作本身把游戏搞崩。这里只是「在不在」，不是「初始化」。
        Class<?> mouse = Reflect.loadWithoutInit("org.lwjgl.input.Mouse", loader);
        Class<?> keyboard = Reflect.loadWithoutInit("org.lwjgl.input.Keyboard", loader);
        Class<?> display = Reflect.loadWithoutInit("org.lwjgl.opengl.Display", loader);
        Method mGetX = mouse == null ? null : Reflect.method(mouse, "getX");
        Method mGetY = mouse == null ? null : Reflect.method(mouse, "getY");
        Method mIsButtonDown = mouse == null ? null : Reflect.method(mouse, "isButtonDown", int.class);
        Method mGetDWheel = mouse == null ? null : Reflect.method(mouse, "getDWheel");
        Method mSetGrabbed = mouse == null ? null : Reflect.method(mouse, "setGrabbed", boolean.class);
        Method mIsGrabbed = mouse == null ? null : Reflect.method(mouse, "isGrabbed");
        Method kIsKeyDown = keyboard == null ? null : Reflect.method(keyboard, "isKeyDown", int.class);
        Method dGetWidth = display == null ? null : Reflect.method(display, "getWidth");
        Method dGetHeight = display == null ? null : Reflect.method(display, "getHeight");
        if (mGetX != null && mGetY != null && mIsButtonDown != null && kIsKeyDown != null
                && dGetWidth != null && dGetHeight != null) {
            this.mouseGetX = mGetX;
            this.mouseGetY = mGetY;
            this.mouseIsButtonDown = mIsButtonDown;
            this.mouseGetDWheel = mGetDWheel;
            this.mouseSetGrabbed = mSetGrabbed;
            this.keyboardIsKeyDown = kIsKeyDown;
            this.displayGetWidth = dGetWidth;
            this.displayGetHeight = dGetHeight;
            this.glfwGetCursorPos = null;
            this.glfwGetMouseButton = null;
            this.glfwGetKey = null;
            this.glfwGetCurrentContext = null;
            this.glfwGetWindowSize = null;
            this.glfwSetInputMode = null;
            this.glfwGetWindowAttrib = null;
            this.glfwSetScrollCallback = null;
            this.scrollCallbackType = null;
            this.scrollCallbackInvoke = null;
            this.mouseIsGrabbed = mIsGrabbed;
            this.backend = "lwjgl2";
            return;
        }

        Class<?> glfw = Reflect.loadWithoutInit("org.lwjgl.glfw.GLFW", loader);
        Method gCursorPos = glfw == null ? null
                : Reflect.method(glfw, "glfwGetCursorPos", long.class, double[].class, double[].class);
        Method gMouseButton = glfw == null ? null
                : Reflect.method(glfw, "glfwGetMouseButton", long.class, int.class);
        Method gKey = glfw == null ? null : Reflect.method(glfw, "glfwGetKey", long.class, int.class);
        Method gContext = glfw == null ? null : Reflect.method(glfw, "glfwGetCurrentContext");
        Method gWindowSize = glfw == null ? null
                : Reflect.method(glfw, "glfwGetWindowSize", long.class, int[].class, int[].class);
        Method gSetInputMode = glfw == null ? null
                : Reflect.method(glfw, "glfwSetInputMode", long.class, int.class, int.class);
        Method gGetWindowAttrib = glfw == null ? null
                : Reflect.method(glfw, "glfwGetWindowAttrib", long.class, int.class);
        // 接口探测也用 loadWithoutInit：它可能没有被加载过，加载了也不会 <clinit>
        Class<?> callback = glfw == null ? null
                : Reflect.loadWithoutInit("org.lwjgl.glfw.GLFWScrollCallbackI", loader);
        Method callbackInvoke = callback == null ? null
                : Reflect.method(callback, "invoke", long.class, double.class, double.class);
        Method gSetScroll = callback == null ? null
                : Reflect.method(glfw, "glfwSetScrollCallback", long.class, callback);

        if (glfw != null && gCursorPos == null) {
            // 类找到了却解析不出方法时，把「类本身长什么样」打出来：加载器、方法总数、
            // 以及逐个探测结果——否则只能看到一句 input=none。
            System.out.println("[noturne] glfw present but unresolved; loader=" + glfw.getClassLoader()
                    + " declared=" + glfw.getDeclaredMethods().length
                    + " cursorPos=" + (gCursorPos != null)
                    + " mouseButton=" + (gMouseButton != null)
                    + " key=" + (gKey != null)
                    + " context=" + (gContext != null)
                    + " windowSize=" + (gWindowSize != null));
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

        this.mouseGetX = null;
        this.mouseGetY = null;
        this.mouseIsButtonDown = null;
        this.mouseGetDWheel = null;
        this.mouseSetGrabbed = null;
        this.keyboardIsKeyDown = null;
        this.displayGetWidth = null;
        this.displayGetHeight = null;
        this.glfwGetCursorPos = gCursorPos;
        this.glfwGetMouseButton = gMouseButton;
        this.glfwGetKey = gKey;
        this.glfwGetCurrentContext = gContext;
        this.glfwGetWindowSize = gWindowSize;
        this.glfwSetInputMode = gSetInputMode;
        this.glfwGetWindowAttrib = gGetWindowAttrib;
        this.glfwSetScrollCallback = gSetScroll;
        this.scrollCallbackType = callback;
        this.scrollCallbackInvoke = callbackInvoke;

        // 后端可用性必须校验必需方法句柄，而不是「类存在」：部分解析失败时若仍报 glfw，
        // 每帧调用都会静默返回 0/false，describe() 却在日志里谎报可用。
        boolean glfwUsable = gCursorPos != null && gMouseButton != null && gKey != null
                && gContext != null && gSetInputMode != null;
        this.backend = glfwUsable ? "glfw" : "none";
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
                    + (Reflect.loadWithoutInit("org.lwjgl.input.Mouse", loader) != null)
                    + " glfw=" + (Reflect.loadWithoutInit("org.lwjgl.glfw.GLFW", loader) != null)
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
            if (!(wheel instanceof Number)) {
                return 0d;
            }
            // LWJGL2 的 getDWheel 返回本次轮询的增量并自行清零，无需再取。
            // Windows 上一格为 120、macOS 为 1；统一归一化成「每格 ±1」，与 GLFW 同量纲。
            return ((Number) wheel).doubleValue() / LWJGL2_WHEEL_DIVISOR;
        }
        // 原子取出并清零：回调线程随时可能累加，读后清零若不是原子的会丢失增量。
        long bits = pendingScrollBits.getAndSet(Double.doubleToRawLongBits(0d));
        return Double.longBitsToDouble(bits);
    }

    @Override
    public boolean keyDown(int key) {
        // 对外键码是 AWT VK，先翻译到本后端的键码；未映射返回 -1。
        if ("lwjgl2".equals(backend)) {
            int code = KeyMap.lwjgl2(key);
            if (code < 0) {
                return false;
            }
            Object down = Reflect.call(keyboardIsKeyDown, null, code);
            return Boolean.TRUE.equals(down);
        }
        if (!ensureGlfwWindow()) {
            return false;
        }
        int code = KeyMap.glfw(key);
        if (code < 0) {
            return false;
        }
        Object state = Reflect.call(glfwGetKey, null, glfwWindow, code);
        return state instanceof Number && ((Number) state).intValue() == GLFW_PRESS;
    }

    @Override
    public void setPointerGrabbed(boolean grabbed) {
        // 每帧由叠加层以期望值校正。GLFW 在窗口失焦时会把光标模式重置为 NORMAL 且
        // 重新获得焦点后不自动恢复；因此在轮询路径上检测「焦点重新获得」这一跳变，
        // 并在那一刻强制重发期望模式。只在跳变时重发（而非每帧按实际模式纠偏）是为了
        // 不跟游戏自身的指针管理打架——原生界面（背包/暂停）会主动把模式设为 NORMAL。
        boolean focusRegained = pollFocusRegained();
        if (!focusRegained && pointerGrabApplied && pointerGrabLast == grabbed) {
            return;
        }
        applyPointerGrab(grabbed);
    }

    /** @return 本帧是否检测到窗口由失焦转为获得焦点（仅 GLFW 有该语义） */
    private boolean pollFocusRegained() {
        if (!"glfw".equals(backend) || glfwGetWindowAttrib == null || !ensureGlfwWindow()) {
            return false;
        }
        Object focused = Reflect.call(glfwGetWindowAttrib, null, glfwWindow, GLFW_FOCUSED);
        boolean nowFocused = focused instanceof Number && ((Number) focused).intValue() != 0;
        boolean regained = nowFocused && !lastFocused;
        lastFocused = nowFocused;
        return regained;
    }

    @Override
    public boolean isPointerGrabbed() {
        if ("lwjgl2".equals(backend)) {
            Object grabbed = Reflect.call(mouseIsGrabbed, null);
            return grabbed instanceof Boolean && (Boolean) grabbed;
        }
        if ("glfw".equals(backend)) {
            if (glfwGetInputMode == null || !ensureGlfwWindow()) {
                return pointerGrabApplied && pointerGrabLast;
            }
            Object mode = Reflect.call(glfwGetInputMode, null, glfwWindow, GLFW_CURSOR);
            return mode instanceof Number && ((Number) mode).intValue() == GLFW_CURSOR_DISABLED;
        }
        // 无捕获控制能力的后端：只能回报我们最近下发过的值。
        return pointerGrabApplied && pointerGrabLast;
    }

    /** 把捕获状态下发到后端；成功后才记录，窗口未就绪时留待下一帧重试。 */
    private void applyPointerGrab(boolean grabbed) {
        if ("lwjgl2".equals(backend)) {
            Reflect.call(mouseSetGrabbed, null, grabbed);
        } else if ("glfw".equals(backend)) {
            if (!ensureGlfwWindow()) {
                return;
            }
            Reflect.call(glfwSetInputMode, null, glfwWindow, GLFW_CURSOR,
                    grabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        } else {
            return;
        }
        pointerGrabApplied = true;
        pointerGrabLast = grabbed;
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

    /** 用动态代理实现 {@code GLFWScrollCallbackI}，把滚轮增量累积到 {@link #pendingScrollBits}。 */
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
                                accumulateScroll(((Number) args[2]).doubleValue());
                                // GLFW 回调是单槽位语义：我们顶掉了旧回调。继续转发给旧回调，
                                // 否则已注册滚轮的模组（缩放、滚动列表）会永久失效。
                                if (previousScrollCallback != null) {
                                    Reflect.call(scrollCallbackInvoke, previousScrollCallback,
                                            args[0], args[1], args[2]);
                                }
                            }
                            return null;
                        }
                    });
            Object previous = Reflect.call(glfwSetScrollCallback, null, glfwWindow, callback);
            installedScrollCallback = callback;
            if (previous != null && scrollCallbackType.isInstance(previous)) {
                // 保留被顶掉的旧回调引用并（在代理里）转发，而不是让它成为孤儿
                previousScrollCallback = previous;
            }
        } catch (Throwable t) {
            // 注册失败只是没有滚轮，不影响键鼠操作；但不能静默——滚轮失效必须能从日志发现。
            System.out.println("[noturne] scroll callback registration failed;"
                    + " wheel scrolling disabled: " + t);
        }
    }

    /** 原子累加滚轮增量（回调线程与帧线程并发访问）。 */
    private void accumulateScroll(double delta) {
        long prev;
        long next;
        do {
            prev = pendingScrollBits.get();
            next = Double.doubleToRawLongBits(Double.longBitsToDouble(prev) + delta);
        } while (!pendingScrollBits.compareAndSet(prev, next));
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
        public boolean isPointerGrabbed() {
            // 无后端可查，永远当作「未被游戏捕获」。
            return false;
        }

        @Override
        public String describe() {
            return "none";
        }
    }
}
