package dev.noturne.ui.gl;

import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.client.runtime.FrameListener;
import dev.noturne.ui.clickgui.ClickGui;

/**
 * 把点击式 GUI 叠加到游戏画面上，并承担它的全部输入。
 *
 * <p>由被补丁的帧交换点每帧调用一次（见 {@code NoturneRuntime}）。这里不做事件 hook，而是每帧
 * 轮询一次指针与按键状态，再自己合成为点击 / 拖拽 / 释放 / 滚轮事件：轮询只需一个查询接口，
 * 而两代 LWJGL 的回调模型差异全部被 {@link InputSource} 吸收，代价是每帧几次反射调用。
 *
 * <p>三个容易踩空的地方，都在这里集中处理：
 * <ul>
 *   <li><b>指针捕获</b>：游戏中指针被游戏锁住用于转视角，此时坐标恒定、GUI 无法操作。打开 GUI 时
 *       必须解除捕获，关闭时恢复。</li>
 *   <li><b>基准状态同步</b>：打开 GUI 的那一刻要把「上一帧的按键/坐标」重置为当前值，否则上一帧
 *       的按下状态会在本帧被误判为一次新的点击或拖拽。</li>
 *   <li><b>绘制时机</b>：必须在帧交换点之内完成绘制，否则画面会被游戏后续的绘制覆盖。</li>
 * </ul>
 */
public final class GuiOverlay implements FrameListener {

    /** 右 Shift 的键码，统一采用 AWT VK 码；输入后端负责翻译到 LWJGL2 / GLFW。 */
    public static final int KEY_RIGHT_SHIFT = 54;
    /** Esc 的键码（AWT VK_ESCAPE）；用于派发键盘事件使 Esc 关闭可达。 */
    private static final int KEY_ESCAPE = 27;

    /** 左键编号。 */
    private static final int BUTTON_LEFT = 0;
    /** 右键编号。 */
    private static final int BUTTON_RIGHT = 1;

    /** 被叠加的点击式 GUI。 */
    private final ClickGui gui;
    /** 绘制后端。 */
    private final UiBackend renderer;
    /** 输入来源。 */
    private final InputSource input;
    /** 开关 GUI 的键码。 */
    private final int toggleKey;

    /** 上一帧开关按键是否按下，用于取「按下沿」。 */
    private boolean toggleWasDown;
    /** 上一帧 Esc 是否按下，用于取「按下沿」。 */
    private boolean escapeWasDown;
    /** 上一帧左键是否按下。 */
    private boolean leftWasDown;
    /** 上一帧右键是否按下。 */
    private boolean rightWasDown;
    /** 上一帧指针 x（GUI 坐标），用于计算拖拽增量。 */
    private double lastX;
    /** 上一帧指针 y（GUI 坐标）。 */
    private double lastY;
    /** 是否已打印过「GUI 已打开」日志，保证只打印一次。 */
    private boolean loggedFirstDraw;

    /**
     * @param registry  模块注册表，GUI 据此列出各分类下的模块
     * @param renderer  绘制后端
     * @param input     输入来源
     * @param toggleKey 开关 GUI 的键码
     */
    public GuiOverlay(ModuleRegistry registry, UiBackend renderer, InputSource input, int toggleKey) {
        this.gui = new ClickGui(registry);
        this.renderer = renderer;
        this.input = input;
        this.toggleKey = toggleKey;
    }

    /** @return 被叠加的 GUI，供外部（如设置界面）直接操作 */
    public ClickGui gui() {
        return gui;
    }

    /** @return 实际生效的输入后端名称，用于日志诊断；按键「没反应」时先看这里是不是 {@code "none"} */
    public String keyBackend() {
        return input.describe();
    }

    /** @return 当前使用的开关按键键码 */
    public int toggleKey() {
        return toggleKey;
    }

    /** 是否已打印过首帧输入诊断，保证只打印一次。 */
    private boolean loggedFirstInput;
    /** 上一帧 GUI 是否处于打开状态；用于识别指针捕获的交接沿。 */
    private boolean wasOpenForPointer;
    /** 打开 GUI 之前游戏的指针捕获状态；关闭时原样恢复。 */
    private boolean pointerGrabbedBeforeGui;

    @Override
    public void onFrame() {
        // 指针位置只取一次：开关判定、悬停更新与事件派发必须基于同一份坐标，
        // 否则一帧内指针移动会造成命中测试与事件坐标不一致。
        double mx = input.mouseX();
        double my = input.mouseY();

        boolean toggleDown = input.keyDown(toggleKey);
        if (!loggedFirstInput) {
            loggedFirstInput = true;
            // 首次进入叠加层时把输入侧的真实状态打出来：按键「没反应」时，
            // 这行能直接区分「输入源没解析出来」与「键没被按下」。
            System.out.println("[noturne] overlay first frame: input=" + input.describe()
                    + " mouse=" + Math.round(mx) + "," + Math.round(my)
                    + " toggleKey=" + toggleKey + " down=" + toggleDown);
        }
        if (toggleDown && !toggleWasDown) {
            gui.toggle();
            if (gui.isOpen()) {
                // 重置基准状态，避免上一帧的按下/位置被误判成本帧的新事件
                lastX = mx;
                lastY = my;
                leftWasDown = input.mouseDown(BUTTON_LEFT);
                rightWasDown = input.mouseDown(BUTTON_RIGHT);
                escapeWasDown = input.keyDown(KEY_ESCAPE);
            }
        }
        toggleWasDown = toggleDown;

        // 指针捕获交接（不是每帧强制）：
        //   打开 GUI     → 记下游戏原本的捕获状态，然后把指针交还给 GUI；
        //   打开期间     → 每帧保持释放（游戏中游戏会自行持续捕获，必须重申）；
        //   关闭的那一帧 → 恢复打开前的状态。
        // GUI 关闭期间完全不碰这个状态：主菜单/聊天/原生界面本来就需要可见光标，
        // 无条件捕获会把光标锁死——真机上「鼠标被锁」就是这里来的。
        boolean open = gui.isOpen();
        if (open) {
            if (!wasOpenForPointer) {
                // 打开沿：记下原状态，供关闭时恢复
                pointerGrabbedBeforeGui = input.isPointerGrabbed();
            }
            input.setPointerGrabbed(false);
        } else if (wasOpenForPointer) {
            // 关闭沿：恢复打开前的捕获状态
            input.setPointerGrabbed(pointerGrabbedBeforeGui);
        }
        wasOpenForPointer = open;

        // 滚轮增量每帧取出并清零：GUI 关闭期间也必须消费，否则打开瞬间会把
        // 关闭期间累积的增量一次性滚动出来。
        double scroll = input.scrollDelta();

        if (!gui.isOpen()) {
            // 关闭状态保持输入基准同步，避免重开首帧把陈旧按下态误判为新事件
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            escapeWasDown = input.keyDown(KEY_ESCAPE);
            lastX = mx;
            lastY = my;
            return;
        }

        // 键盘：目前只需让 Esc 可达（关闭 GUI）。键码为 AWT VK，后端已翻译。
        boolean escapeDown = input.keyDown(KEY_ESCAPE);
        if (escapeDown && !escapeWasDown) {
            gui.keyPressed(KEY_ESCAPE, 0);
        }
        escapeWasDown = escapeDown;
        if (!gui.isOpen()) {
            // Esc 在本帧关闭了 GUI：同步输入基准，本帧不再绘制。
            // 指针不在这一帧硬性恢复：下一帧的交接沿会把它还原成打开前的状态
            // （在主菜单里打开 GUI 再按 Esc，光标必须保持可见）。
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            lastX = mx;
            lastY = my;
            return;
        }

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            if (renderer.ready()) {
                System.out.println("[noturne] click GUI opened; input=" + input.describe()
                        + "; backend=" + renderer.backendName());
            } else {
                // 后端未就绪时叠加层照样会绘制，但可能全帧不可见；明确警告，
                // 避免与「输入没解析出来」的现象混为一谈。
                System.out.println("[noturne] WARNING: click GUI opened but renderer not ready;"
                        + " input=" + input.describe() + "; backend=" + renderer.backendName());
            }
        }

        // 先同步绘制区域尺寸，滚动范围与拖动/平移夹取才能正确计算
        gui.setViewport(renderer.width(), renderer.height());
        gui.update(System.currentTimeMillis(), mx, my);

        boolean left = input.mouseDown(BUTTON_LEFT);
        boolean right = input.mouseDown(BUTTON_RIGHT);
        double dx = mx - lastX;
        double dy = my - lastY;

        // 左键：按下沿 → 点击；按住且有位移 → 拖拽；松开沿 → 释放
        if (left && !leftWasDown) {
            gui.mouseClicked(mx, my, BUTTON_LEFT);
        } else if (left && (dx != 0d || dy != 0d)) {
            gui.mouseDragged(mx, my, BUTTON_LEFT, dx, dy);
        }
        if (!left && leftWasDown) {
            gui.mouseReleased(mx, my, BUTTON_LEFT);
        }

        // 右键只用于「打开模块设置」，不参与拖拽
        if (right && !rightWasDown) {
            gui.mouseClicked(mx, my, BUTTON_RIGHT);
        }
        if (!right && rightWasDown) {
            gui.mouseReleased(mx, my, BUTTON_RIGHT);
        }

        if (scroll != 0d) {
            gui.mouseScrolled(mx, my, scroll);
        }

        lastX = mx;
        lastY = my;
        leftWasDown = left;
        rightWasDown = right;

        // begin/end 必须成对：渲染中途抛异常时若不执行 endFrame，
        // beginFrame 压入的投影/模型视图矩阵栈永远不会弹出（每帧泄漏 2 层，约 16 帧后栈溢出），
        // 游戏的 3D 画面将永久错乱且不可自愈。
        renderer.beginFrame();
        try {
            gui.render(renderer);
        } finally {
            renderer.endFrame();
        }
    }
}
