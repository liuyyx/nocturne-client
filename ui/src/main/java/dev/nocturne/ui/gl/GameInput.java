package dev.nocturne.ui.gl;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.Reflect;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.mapping.ClassType;

import java.lang.reflect.Method;
import java.util.function.IntSupplier;

/**
 * 走**游戏自己的输入状态**的指针输入源：位置与按钮都取自映射后的 {@code MouseHandler}，
 * 键走 {@code InputConstants.isKeyDown}，滚轮委托给其它输入源。
 *
 * <p><b>为什么不能靠 SDL 查询</b>：26.3 真机实测（同一进程、SDL 实例确实已初始化——
 * {@code SDL_GetKeyboardState} 返回有效缓冲）：
 * <ul>
 *   <li>{@code SDL_GetMouseState} 恒返回 0,0、掩码 0（它只在"有鼠标焦点的窗口"上给坐标，没焦点时
 *       静默给 0，不报错）；</li>
 *   <li>{@code SDL_GetGlobalMouseState} 同样返回 0,0、掩码 0；</li>
 *   <li>而游戏自己收到的事件坐标是真实的（{@code MouseHandler.xpos()=484,279}）。</li>
 * </ul>
 * 结论：鼠标坐标只能取游戏的事件态。表现就是"界面里点不中任何东西，日志却一句线索都没有"。
 *
 * <p><b>按钮</b>取 {@code MouseHandler.activeButton}（按下时无条件记录，抬起清空），
 * 因为它不受"当前有没有 screen/overlay"影响：{@code isLeftPressed} 只在没有 screen、没有 overlay
 * 时才更新（见 26.3 {@code onButton} 字节码），主菜单/聊天/背包打开时恒为 false。
 * {@code activeButton} 读不到时退回那三个 {@code isXxxPressed}。
 *
 * <p><b>单位</b>：{@code xpos/ypos} 是窗口坐标，界面用游戏 GUI 缩放后的逻辑坐标，
 * 因此按 {@code 界面逻辑尺寸 / 窗口尺寸（Window.getWidth/getHeight）} 换算。
 *
 * <p>版本差异全部交给映射层：Forge/NeoForge 上这些成员是 SRG 名。
 */
public final class GameInput implements InputSource {

    /** 后端短名，出现在 overlay diag 日志里。 */
    private static final String NAME = "game";

    /** {@code activeButton.button()} 的取值 → {@link #mouseDown(int)} 的按钮索引：左 / 右 / 中。 */
    private static final int BUTTON_LEFT = 1;
    private static final int BUTTON_MIDDLE = 2;
    private static final int BUTTON_RIGHT = 3;

    /** {@code activeButton} 读不到时的退路：与按钮索引同序（左 / 右 / 中）。 */
    private static final String[] PRESSED_METHODS = {"isLeftPressed", "isRightPressed", "isMiddlePressed"};

    private final GameBridge bridge;
    /** 键与滚轮的兜底输入源（SDL 的键盘状态缓冲可用，见类注释）。 */
    private final InputSource fallback;
    private final IntSupplier logicalWidth;
    private final IntSupplier logicalHeight;
    /** {@code InputConstants.isKeyDown(int)}；解析不到时为 {@code null}（退回 fallback 的键）。 */
    private final Method isKeyDown;
    /** {@code MouseButtonInfo.button()} 按运行期类缓存（{@code Optional.empty()} = 该类解析不到）。 */
    private final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<Method>>
            buttonMethods = new java.util.concurrent.ConcurrentHashMap<>();

    /** 是否已就「窗口尺寸未知、坐标退化 1:1」警告过（只打一次）。 */
    private boolean loggedScaleFallback;
    /** 是否已就「鼠标状态读取失败」报过一次。 */
    private boolean loggedReadFailure;

    private GameInput(GameBridge bridge, InputSource fallback, IntSupplier logicalWidth,
                      IntSupplier logicalHeight, Method isKeyDown) {
        this.bridge = bridge;
        this.fallback = fallback;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.isKeyDown = isKeyDown;
    }

    /**
     * 探测并创建输入源。
     *
     * @param bridge        映射桥（用于读取 {@code Minecraft.mouseHandler} 等成员）
     * @param loader        游戏类加载器（解析 {@code InputConstants} 与 {@code MouseButtonInfo}）
     * @param fallback      键与滚轮的兜底输入源，可为 {@code null}
     * @param logicalWidth  界面逻辑宽度提供者
     * @param logicalHeight 界面逻辑高度提供者
     * @return 可用的输入源；该版本没有 {@code MouseHandler}（1.13 及更早）时返回 {@code null}，
     *         调用方应继续用 {@code fallback}
     */
    public static InputSource create(GameBridge bridge, ClassLoader loader, InputSource fallback,
                                    IntSupplier logicalWidth, IntSupplier logicalHeight) {
        if (bridge == null || bridge.minecraft() == null) {
            return null;
        }
        // 只在 SDL 世代接管：GLFW/LWJGL2 的轮询本来可用，而且不受"开着 screen 时游戏不更新按下标志"
        // 的限制；用本类替换它们只会把能用的场景换坏（点得中变点不中）。
        if (fallback == null || !"sdl".equals(fallback.describe())) {
            return null;
        }
        Object handler = bridge.readField(bridge.minecraft(), ClassType.MINECRAFT, "mouseHandler");
        if (handler == null) {
            return null;   // 老版本的鼠标在别处（LWJGL2/GLFW 那条路由 fallback 负责，实测可用）
        }
        // InputConstants 只在**游戏类加载器**里可见：用传进来的加载器（可能是系统加载器）按类名
        // 加载会静默拿到 null，键就永远读不到。鼠标处理器的类加载器一定是游戏那一个。
        ClassLoader gameLoader = handler.getClass().getClassLoader();
        Class<?> inputConstants = Reflect.loadWithoutInit(
                "com.mojang.blaze3d.platform.InputConstants",
                gameLoader == null ? loader : gameLoader);
        Method isKeyDown = inputConstants == null
                ? null
                : Reflect.method(inputConstants, "isKeyDown", int.class);
        // MouseButtonInfo 的类只在游戏加载器里可见（按类名加载拿不到），运行期从实例取类再解析
        // button()——见 GameInput#buttonOf 的缓存。
        return new GameInput(bridge, fallback, logicalWidth, logicalHeight, isKeyDown);
    }

    @Override
    public String describe() {
        return NAME;
    }

    @Override
    public double mouseX() {
        Object value = bridge.callMapped(handler(), ClassType.MOUSE_HANDLER, "xpos");
        return value instanceof Number
                ? ((Number) value).doubleValue() * scale(true)
                : 0d;
    }

    @Override
    public double mouseY() {
        Object value = bridge.callMapped(handler(), ClassType.MOUSE_HANDLER, "ypos");
        return value instanceof Number
                ? ((Number) value).doubleValue() * scale(false)
                : 0d;
    }

    @Override
    public boolean mouseDown(int button) {
        if (button < 0 || button >= PRESSED_METHODS.length) {
            return false;
        }
        Object handler = handler();
        Object active = bridge.readField(handler, ClassType.MOUSE_HANDLER, "activeButton");
        Method buttonOf = active == null ? null : buttonOf(active.getClass());
        if (buttonOf != null) {
            // 有按钮按下：按 button() 的值判定是哪一个（1=左 2=中 3=右）。
            Object value = Reflect.call(buttonOf, active);
            if (value instanceof Number) {
                int pressed = ((Number) value).intValue();
                return pressed == pressedIndex(button);
            }
        }
        // 没按下（activeButton 为 null）或按钮面解析不到：退回游戏那三个按下标志。
        Object value = bridge.callMapped(handler, ClassType.MOUSE_HANDLER, PRESSED_METHODS[button]);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (active == null && !loggedReadFailure) {
            loggedReadFailure = true;
            // 读取路径整体失效时会一直"点不中"，静默是最难查的：留一行痕迹。
            System.out.println("[nocturne] WARNING: game mouse button state unreadable;"
                    + " clicks will not register");
        }
        return false;
    }

    @Override
    public double scrollDelta() {
        // SDL 的滚轮是事件驱动的，没有轮询接口；兜底源同样如此（那边恒为 0）。
        return fallback == null ? 0d : fallback.scrollDelta();
    }

    @Override
    public boolean keyDown(int key) {
        // 键盘状态缓冲本身可用（SDL_GetKeyboardState 返回有效缓冲），走它比自建事件队列省事，
        // 也与游戏读的是同一份状态。
        if (isKeyDown != null) {
            int scancode = KeyMap.sdl(key);
            if (scancode >= 0) {
                Object down = Reflect.call(isKeyDown, null, Integer.valueOf(scancode));
                if (down instanceof Boolean) {
                    return ((Boolean) down).booleanValue();
                }
            }
        }
        return fallback != null && fallback.keyDown(key);
    }

    @Override
    public void setPointerGrabbed(boolean grabbed) {
        // 用游戏自己的抓取 API：只改 SDL 的相对模式会被游戏每帧按自己的模型改回来。
        Object handler = handler();
        Object current = bridge.callMapped(handler, ClassType.MOUSE_HANDLER, "isMouseGrabbed");
        if (current instanceof Boolean && ((Boolean) current).booleanValue() == grabbed) {
            return;
        }
        bridge.callMapped(handler, ClassType.MOUSE_HANDLER,
                grabbed ? "grabMouse" : "releaseMouse");
    }

    @Override
    public boolean isPointerGrabbed() {
        Object value = bridge.callMapped(handler(), ClassType.MOUSE_HANDLER, "isMouseGrabbed");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    /**
     * {@link #mouseDown(int)} 的按钮索引 → {@code MouseButtonInfo.button()} 的取值。
     *
     * <p>1=左 2=中 3=右（见 26.3 {@code onButton} 的分支顺序），而界面侧的索引是 0=左 1=右 2=中。
     * 两套顺序不一致，搞反了就是"左键点出右键菜单"，所以单独钉住。
     *
     * @param index 界面侧按钮索引（0=左 1=右 2=中）
     * @return 游戏侧取值；索引越界时返回 -1（与任何合法取值都不等）
     */
    static int pressedIndex(int index) {
        if (index == 0) {
            return BUTTON_LEFT;
        }
        if (index == 1) {
            return BUTTON_RIGHT;
        }
        if (index == 2) {
            return BUTTON_MIDDLE;
        }
        return -1;
    }

    /**
     * 窗口坐标 → 界面逻辑坐标。
     *
     * @param relative 窗口坐标
     * @param logical  界面逻辑尺寸（该轴）
     * @param window   窗口尺寸（该轴）
     * @return 逻辑坐标；任一尺寸未知（{@code <= 0}）时原样返回
     */
    static double scaled(double relative, int logical, int window) {
        if (logical <= 0 || window <= 0) {
            return relative;
        }
        return relative * logical / window;
    }

    /**
     * 取 {@code MouseButtonInfo.button()}。
     *
     * <p>按**运行期类**缓存：这个类只在游戏加载器里可见（按类名加载拿不到），实例拿得到、类就
     * 拿得到。名字用规范名（未混淆构建）；重映射环境下解析不到，退回「按下标志」那条路。
     *
     * @param type {@code MouseButtonInfo} 的运行期类
     * @return 方法句柄；解析不到时返回 {@code null}
     */
    private Method buttonOf(Class<?> type) {
        return buttonMethods.computeIfAbsent(type,
                key -> java.util.Optional.ofNullable(Reflect.method(key, "button"))).orElse(null);
    }

    /** 鼠标处理器实例；取不到时返回 {@code null}（调用方按 0/false 处理）。 */
    private Object handler() {
        Object minecraft = bridge.minecraft();
        return minecraft == null
                ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "mouseHandler");
    }

    /**
     * 窗口坐标 → 界面逻辑坐标的比例。
     *
     * @param width {@code true} 用宽度这一轴，{@code false} 用高度这一轴
     * @return 比例；任一侧未知时退化 1:1 并提示一次
     */
    private double scale(boolean width) {
        IntSupplier supplier = width ? logicalWidth : logicalHeight;
        int logical = supplier == null ? 0 : supplier.getAsInt();
        int window = windowSize(width);
        if (window <= 0) {
            warnScaleFallback();
        }
        // 逻辑尺寸还没测出来（界面尚未画过一帧）时按 1:1 处理，不刷屏告警。
        return scaled(1d, logical, window);
    }

    /**
     * 读游戏窗口的宽或高（{@code Window.getWidth()/getHeight()}）。
     *
     * @param width {@code true} 取宽，{@code false} 取高
     * @return 像素值；读不到时返回 0
     */
    private int windowSize(boolean width) {
        Object minecraft = bridge.minecraft();
        if (minecraft == null) {
            return 0;
        }
        Object window = bridge.callMapped(minecraft, ClassType.MINECRAFT, "getWindow");
        if (window == null) {
            return 0;
        }
        Object value = bridge.callMapped(window, ClassType.WINDOW, width ? "getWidth" : "getHeight");
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /** 坐标换算退化时的提示（只打一次）：错位的点击比"点了没反应"更难查。 */
    private void warnScaleFallback() {
        if (loggedScaleFallback) {
            return;
        }
        loggedScaleFallback = true;
        System.out.println("[nocturne] WARNING: game window size unavailable; pointer coordinates"
                + " fall back to 1:1 and will miss on a scaled window (logical="
                + (logicalWidth == null ? -1 : logicalWidth.getAsInt()) + "x"
                + (logicalHeight == null ? -1 : logicalHeight.getAsInt()) + ")");
    }
}
