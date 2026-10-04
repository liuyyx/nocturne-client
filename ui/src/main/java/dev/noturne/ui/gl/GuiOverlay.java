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

    /** 右 Shift 的键码；LWJGL2 与 GLFW 在功能键区取值一致。 */
    public static final int KEY_RIGHT_SHIFT = 344;

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

    @Override
    public void onFrame() {
        // 指针位置只取一次：开关判定、悬停更新与事件派发必须基于同一份坐标，
        // 否则一帧内指针移动会造成命中测试与事件坐标不一致。
        double mx = input.mouseX();
        double my = input.mouseY();

        boolean toggleDown = input.keyDown(toggleKey);
        if (toggleDown && !toggleWasDown) {
            gui.toggle();
            if (gui.isOpen()) {
                // 打开：把指针从游戏手里要回来，否则坐标恒定在屏幕中心、什么都点不到
                input.setPointerGrabbed(false);
                // 重置基准状态，避免上一帧的按下/位置被误判成本帧的新事件
                lastX = mx;
                lastY = my;
                leftWasDown = input.mouseDown(BUTTON_LEFT);
                rightWasDown = input.mouseDown(BUTTON_RIGHT);
            } else {
                // 关闭：交还指针，否则游戏无法转视角
                input.setPointerGrabbed(true);
            }
        }
        toggleWasDown = toggleDown;

        if (!gui.isOpen()) {
            return;
        }
        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            System.out.println("[noturne] click GUI opened; input=" + input.describe()
                    + "; backend=" + renderer.backendName());
        }

        // 先同步绘制区域高度，滚动范围才能正确夹取（后端未渲染过时返回 0，此时不做限制）
        gui.setViewport(renderer.height());
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

        double scroll = input.scrollDelta();
        if (scroll != 0d) {
            gui.mouseScrolled(mx, my, scroll);
        }

        lastX = mx;
        lastY = my;
        leftWasDown = left;
        rightWasDown = right;

        renderer.beginFrame();
        gui.render(renderer);
        renderer.endFrame();
    }
}
