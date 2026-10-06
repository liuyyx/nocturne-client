package dev.nocturne.ui.gl;

import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.game.Reflect;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.IntSupplier;

/**
 * SDL3 输入源（LWJGL3 的 {@code org.lwjgl.sdl}）。
 *
 * <p>为什么需要它：Minecraft 26.x 把输入栈从 GLFW 换成了 SDL3，而 {@link ReflectiveInput} 只认
 * LWJGL2 与 GLFW——26.3 上实测 {@code input=none}，开关键根本没有输入源，界面永远打不开。
 *
 * <p>用轮询而不是事件回调：与其它输入源保持一致（每帧查一次状态），SDL 的全局查询
 * {@code SDL_GetMouseState} / {@code SDL_GetKeyboardState} 正好支持这种用法，也省掉了注册回调、
 * 处理事件队列与线程模型的差异。
 *
 * <p><b>已知缺口</b>：SDL 的滚轮是事件驱动的，没有轮询接口，因此 {@link #scrollDelta()} 恒为 0
 * ——界面能打开、能点击，但列表暂时滚不动。补齐它要在 {@code SDL_AddEventWatch} 上挂监听、把增量
 * 累加进原子值（与 GLFW 路径同样的做法），属于独立一步。
 *
 * <p>所有 GL/系统调用都走反射：本模块编译期不依赖任何一代 LWJGL，且目标 JVM 里存在哪一代由游戏决定。
 */
public final class SdlInput implements InputSource {

    /** SDL 的鼠标按钮位掩码：左键。 */
    private static final int SDL_BUTTON_LMASK = 1;
    /** 中键。 */
    private static final int SDL_BUTTON_MMASK = 1 << 1;
    /** 右键。 */
    private static final int SDL_BUTTON_RMASK = 1 << 2;

    private final IntSupplier surfaceWidth;
    private final IntSupplier surfaceHeight;

    /** {@code SDL_GetMouseState(float* x, float* y)}：返回按钮位掩码，坐标写进两个缓冲。 */
    private final Method getMouseState;
    /** {@code SDL_GetKeyboardState()}：返回按 SDL_Scancode 索引的按键状态缓冲。 */
    private final Method getKeyboardState;
    /** {@code SDL_GetMouseFocus()}：当前有鼠标焦点的窗口句柄。 */
    private final Method getMouseFocus;
    /** {@code SDL_GetWindowSize(long, int* w, int* h)}：用于把窗口坐标换算到绘制坐标。 */
    private final Method getWindowSize;
    /** {@code SDL_SetWindowRelativeMouseMode(long, boolean)}：指针捕获开关。 */
    private final Method setRelativeMouseMode;
    /** {@code SDL_GetWindowRelativeMouseMode(long)}：读取当前捕获状态；可能不存在。 */
    private final Method getRelativeMouseMode;

    /** 坐标输出缓冲：SDL 要求原生直连缓冲，复用避免每帧分配。 */
    private final FloatBuffer mouseX;
    private final FloatBuffer mouseY;
    /** 窗口尺寸输出缓冲。 */
    private final IntBuffer windowWidth;
    private final IntBuffer windowHeight;

    /** 最近一次成功取得的窗口句柄；0 表示尚未取得。 */
    private long window;
    /** 最近一次设置的指针捕获状态（SDL 侧读取不可用时用它兜底）。 */
    private boolean pointerGrabbed;

    private SdlInput(IntSupplier surfaceWidth, IntSupplier surfaceHeight,
                     Method getMouseState, Method getKeyboardState, Method getMouseFocus,
                     Method getWindowSize, Method setRelativeMouseMode, Method getRelativeMouseMode) {
        this.surfaceWidth = surfaceWidth;
        this.surfaceHeight = surfaceHeight;
        this.getMouseState = getMouseState;
        this.getKeyboardState = getKeyboardState;
        this.getMouseFocus = getMouseFocus;
        this.getWindowSize = getWindowSize;
        this.setRelativeMouseMode = setRelativeMouseMode;
        this.getRelativeMouseMode = getRelativeMouseMode;
        this.mouseX = ByteBuffer.allocateDirect(Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mouseY = ByteBuffer.allocateDirect(Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.windowWidth = ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        this.windowHeight = ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    /**
     * 探测并创建 SDL 输入源。
     *
     * @param loader        游戏类加载器
     * @param surfaceWidth  绘制区宽度提供者
     * @param surfaceHeight 绘制区高度提供者
     * @return 可用的输入源；目标 JVM 里没有 SDL 绑定时返回 {@code null}
     */
    public static InputSource create(ClassLoader loader, IntSupplier surfaceWidth,
                                     IntSupplier surfaceHeight) {
        Class<?> mouse = Reflect.loadWithoutInit("org.lwjgl.sdl.SDLMouse", loader);
        Class<?> keyboard = Reflect.loadWithoutInit("org.lwjgl.sdl.SDLKeyboard", loader);
        Class<?> video = Reflect.loadWithoutInit("org.lwjgl.sdl.SDLVideo", loader);
        if (mouse == null || keyboard == null) {
            return null;
        }
        Method getMouseState = Reflect.method(mouse, "SDL_GetMouseState", FloatBuffer.class,
                FloatBuffer.class);
        // 无参重载返回内部的按键状态缓冲；带 (IntBuffer) 的重载只是额外回报键数，用不上。
        Method getKeyboardState = Reflect.method(keyboard, "SDL_GetKeyboardState");
        Method getMouseFocus = Reflect.method(mouse, "SDL_GetMouseFocus");
        Method getWindowSize = video == null ? null
                : Reflect.method(video, "SDL_GetWindowSize", long.class, IntBuffer.class,
                IntBuffer.class);
        Method setRelative = video == null ? null
                : Reflect.method(video, "SDL_SetWindowRelativeMouseMode", long.class, boolean.class);
        Method getRelative = video == null ? null
                : Reflect.method(video, "SDL_GetWindowRelativeMouseMode", long.class);
        if (getMouseState == null || getKeyboardState == null) {
            return null;
        }
        return new SdlInput(surfaceWidth, surfaceHeight, getMouseState, getKeyboardState,
                getMouseFocus, getWindowSize, setRelative, getRelative);
    }

    @Override
    public String describe() {
        return "sdl";
    }

    @Override
    public double mouseX() {
        readMouseState();
        return mouseX.get(0) * scaleX();
    }

    @Override
    public double mouseY() {
        readMouseState();
        // SDL 的窗口坐标原点已在左上角，与 GUI 坐标系一致，无需像 LWJGL2 那样翻转。
        return mouseY.get(0) * scaleY();
    }

    @Override
    public boolean mouseDown(int button) {
        int mask = readMouseState();
        if (button == 0) {
            return (mask & SDL_BUTTON_LMASK) != 0;
        }
        if (button == 1) {
            return (mask & SDL_BUTTON_RMASK) != 0;
        }
        if (button == 2) {
            return (mask & SDL_BUTTON_MMASK) != 0;
        }
        return false;
    }

    /**
     * SDL 没有滚轮轮询接口（滚轮是事件驱动的），因此这里恒为 0。
     *
     * <p>界面因此能打开、能点击，但列表滚动暂不可用——见类注释里的补齐方案。
     */
    @Override
    public double scrollDelta() {
        return 0d;
    }

    @Override
    public boolean keyDown(int key) {
        int scancode = KeyMap.sdl(key);
        if (scancode < 0) {
            return false;
        }
        Object state = Reflect.call(getKeyboardState, null);
        if (!(state instanceof ByteBuffer)) {
            return false;
        }
        ByteBuffer keys = (ByteBuffer) state;
        return scancode < keys.capacity() && keys.get(scancode) != 0;
    }

    @Override
    public void setPointerGrabbed(boolean grabbed) {
        if (setRelativeMouseMode == null || !ensureWindow()) {
            return;
        }
        if (pointerGrabbed == grabbed) {
            return;
        }
        Reflect.call(setRelativeMouseMode, null, Long.valueOf(window), Boolean.valueOf(grabbed));
        pointerGrabbed = grabbed;
    }

    @Override
    public boolean isPointerGrabbed() {
        if (getRelativeMouseMode != null && ensureWindow()) {
            Object value = Reflect.call(getRelativeMouseMode, null, Long.valueOf(window));
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
        }
        return pointerGrabbed;
    }

    /** 读一次鼠标状态，返回按钮位掩码；失败时返回 0 并把坐标清零。 */
    private int readMouseState() {
        mouseX.clear();
        mouseY.clear();
        Object mask = Reflect.call(getMouseState, null, mouseX, mouseY);
        return mask instanceof Number ? ((Number) mask).intValue() : 0;
    }

    /** 取得窗口句柄；SDL 的窗口相关函数都需要它。 */
    private boolean ensureWindow() {
        if (window != 0L) {
            return true;
        }
        if (getMouseFocus == null) {
            return false;
        }
        Object focus = Reflect.call(getMouseFocus, null);
        if (focus instanceof Number && ((Number) focus).longValue() != 0L) {
            window = ((Number) focus).longValue();
            return true;
        }
        return false;
    }

    /** 绘制区宽度 / 窗口宽度：高 DPI 或缩放窗口下两者不等，坐标必须按这个比例换算。 */
    private double scaleX() {
        int surface = surfaceWidth == null ? 0 : surfaceWidth.getAsInt();
        int client = windowWidth();
        return client <= 0 ? 1d : (double) surface / client;
    }

    private double scaleY() {
        int surface = surfaceHeight == null ? 0 : surfaceHeight.getAsInt();
        int client = windowHeight();
        return client <= 0 ? 1d : (double) surface / client;
    }

    private int windowWidth() {
        return readWindowSize() ? windowWidth.get(0) : 0;
    }

    private int windowHeight() {
        return readWindowSize() ? windowHeight.get(0) : 0;
    }

    /** 读窗口尺寸；没有尺寸查询函数或尚未取得窗口句柄时返回 false。 */
    private boolean readWindowSize() {
        if (getWindowSize == null || !ensureWindow()) {
            return false;
        }
        windowWidth.clear();
        windowHeight.clear();
        Reflect.call(getWindowSize, null, Long.valueOf(window), windowWidth, windowHeight);
        return windowWidth.get(0) > 0 && windowHeight.get(0) > 0;
    }
}
