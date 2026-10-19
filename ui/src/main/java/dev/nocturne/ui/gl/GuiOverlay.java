package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.render.CameraState;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.runtime.FrameListener;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.ui.fatality.FaClickGui;

import java.awt.event.KeyEvent;

/**
 * 把 ClickGUI 与世界覆盖层叠加到游戏画面上，并承担它们的全部输入。
 *
 * <p>由被补丁的帧交换点每帧调用一次（见 {@code NocturneRuntime}）。这里不做事件 hook，而是每帧
 * 轮询一次指针与按键状态，再自己合成为点击 / 拖拽 / 释放 / 滚轮 / 键盘事件：轮询只需一个查询接口，
 * 而三代输入栈（LWJGL2 / GLFW / SDL）的差异全部被 {@link InputSource} 吸收，代价是每帧几次反射调用。
 * 字符输入同理由 {@link KeyChars} 从轮询状态合成（见该类的说明）。
 *
 * <p>三层关系已经收敛成两层：**世界覆盖层**（ESP 等，与界面无关，独立绘制）与**界面**（
 * {@link FaClickGui}，打开时接输入）。
 *
 * <p>三个容易踩空的地方，都在这里集中处理：
 * <ul>
 *   <li><b>指针捕获</b>：游戏中指针被游戏锁住用于转视角，此时坐标恒定、GUI 无法操作。打开界面时
 *       必须解除捕获，关闭时恢复。</li>
 *   <li><b>基准状态同步</b>：打开界面的那一刻要把「上一帧的按键/坐标」重置为当前值，否则上一帧
 *       的按下状态会在本帧被误判为一次新的点击或拖拽。</li>
 *   <li><b>绘制时机</b>：必须在帧交换点之内完成绘制，否则画面会被游戏后续的绘制覆盖。</li>
 * </ul>
 */
public final class GuiOverlay implements FrameListener {

    /** 右 Shift 的键码，统一采用 AWT VK 码；输入后端负责翻译到 LWJGL2 / GLFW / SDL。 */
    public static final int KEY_RIGHT_SHIFT = 54;
    /** Esc 的键码（AWT VK_ESCAPE）；用于派发键盘事件使 Esc 关闭可达。 */
    private static final int KEY_ESCAPE = 27;

    /** 放大界面的键：主键盘 {@code =}（键帽上印的是 {@code +}）。 */
    private static final int KEY_SCALE_UP = 61;
    /** 放大界面的键：数字键盘 {@code +}。 */
    private static final int KEY_SCALE_UP_KEYPAD = 107;
    /** 缩小界面的键：主键盘 {@code -}。 */
    private static final int KEY_SCALE_DOWN = 45;
    /** 缩小界面的键：数字键盘 {@code -}。 */
    private static final int KEY_SCALE_DOWN_KEYPAD = 109;
    /** 手动缩放的下限：1 = 逐像素，再小界面元素会糊成一团。 */
    private static final int SCALE_MIN = 1;
    /** 手动缩放的上限；与 {@code GlRenderer} 的自动上限对齐，超过只会让 1px 描边与圆角显粗。 */
    private static final int SCALE_MAX = 8;

    /** 左键编号。 */
    private static final int BUTTON_LEFT = 0;
    /** 右键编号。 */
    private static final int BUTTON_RIGHT = 1;

    /** AWT 修饰位：Shift。 */
    private static final int MOD_SHIFT = 1;
    /** AWT 修饰位：Control。 */
    private static final int MOD_CTRL = 2;
    /** AWT 修饰位：Alt。 */
    private static final int MOD_ALT = 8;

    /** 点击式 GUI。 */
    private final OverlayGui gui;
    /** 绘制后端。 */
    private final UiBackend renderer;
    /** 输入来源。 */
    private final InputSource input;
    /** 开关 GUI 的键码。 */
    private final int toggleKey;

    /** 上一帧开关按键是否按下，用于取「按下沿」。 */
    private boolean toggleWasDown;
    /** 上一帧「放大」键是否按下，用于取按下沿（按住不重复触发）。 */
    private boolean scaleUpWasDown;
    /** 上一帧「缩小」键是否按下，用于取按下沿。 */
    private boolean scaleDownWasDown;
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

    /** 世界覆盖层绘制面：把当前后端包成 client 侧接口（模块只认它）。 */
    private final OverlayDraw overlayDraw;
    /** 相机状态读取器；每帧刷新它持有的投影。 */
    private final CameraState camera;
    /** 世界覆盖层异常是否已记录过（限流，避免每帧刷屏）。 */
    private boolean worldOverlayErrorLogged;
    /** 游戏内相机读不到时是否已记过（只记一次）。 */
    private boolean worldOverlaySkipLogged;
    /** 首次真的画出覆盖层时是否已记过（只记一次）。 */
    private boolean worldOverlayDrawLogged;

    /** 可打印键上一帧是否按下，下标与 {@link KeyChars#keys()} 对齐。 */
    private final boolean[] charWasDown;
    /**
     * 不可打印但界面必须收到的键（删除/提交/方向）。
     *
     * <p>这些键既不在 {@link KeyChars#keys()} 里、也不会合成字符，早先因此从来没被派发：
     * 搜索框/数值输入/HEX 输入能打字、能粘贴，但**退格删不掉、回车提交不了**。
     */
    private static final int[] SPECIAL_KEYS = {
            8,   // VK_BACK_SPACE
            10,  // VK_ENTER
            127, // VK_DELETE
            9,   // VK_TAB
            37, 38, 39, 40, // 方向键
    };
    /** 特殊键上一帧是否按下，下标与 {@link #SPECIAL_KEYS} 对齐。 */
    private final boolean[] specialWasDown = new boolean[SPECIAL_KEYS.length];
    /** 打印过一次的字符合成诊断是否已输出。 */
    private boolean loggedFirstChar;

    /**
     * @param registry  模块注册表，GUI 据此列出各分类下的模块
     * @param renderer  绘制后端
     * @param input     输入来源
     * @param toggleKey 开关 GUI 的键码
     */
    public GuiOverlay(ModuleRegistry registry, UiBackend renderer, InputSource input, int toggleKey) {
        this.gui = new FaClickGui(registry);
        this.charWasDown = new boolean[KeyChars.keys().length];
        this.renderer = renderer;
        this.input = input;
        this.toggleKey = toggleKey;
        this.overlayDraw = new BackendOverlayDraw(renderer);
        this.camera = new CameraState(new WorldProjection());
    }

    /** @return 被叠加的 GUI，供外部（如验收脚本）直接操作 */
    public OverlayGui gui() {
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

    /**
     * 用 {@code =} / {@code -}（含数字键盘）手动调整界面大小。
     *
     * <p>只在界面打开时响应：游戏里 {@code -} 常被模组占用（缩小视野之类），不吃它的按下沿就不会
     * 误触发。关闭期间照样记录按下状态，否则"关着按住、打开瞬间连跳几档"。
     *
     * <p>缩放由后端从下一帧起生效（见 {@link UiBackend#setScaleOverride}）：本帧的组件树已经按旧
     * 尺寸排好，半途改会让绘制与命中测试错位。
     *
     * @param open 界面当前是否打开
     */
    private void handleScaleKeys(boolean open) {
        // 沿检测必须按**原始按下状态**记录：若界面关着时把状态写成 false，
        // 「关着按住缩放键、再打开界面」会在第一帧被误判成新按下，界面一开就跳一档。
        boolean upRaw = input.keyDown(KEY_SCALE_UP) || input.keyDown(KEY_SCALE_UP_KEYPAD);
        boolean downRaw = input.keyDown(KEY_SCALE_DOWN) || input.keyDown(KEY_SCALE_DOWN_KEYPAD);
        boolean up = open && upRaw;
        boolean down = open && downRaw;
        if (up && !scaleUpWasDown) {
            stepScale(1);
        }
        if (down && !scaleDownWasDown) {
            stepScale(-1);
        }
        scaleUpWasDown = upRaw;
        scaleDownWasDown = downRaw;
    }

    /**
     * 按 {@code delta} 调一档缩放，并在日志里留痕。
     *
     * <p>日志是必要的：缩放只改逻辑尺寸，界面本身没有别的反馈，而"按了没反应"到底是键没送到、
     * 还是后端不支持缩放，只能靠这行区分。
     *
     * @param delta {@code +1} 放大、{@code -1} 缩小
     */
    private void stepScale(int delta) {
        int current = renderer.scale();
        int next = Math.max(SCALE_MIN, Math.min(SCALE_MAX, current + delta));
        if (next == current) {
            return;
        }
        renderer.setScaleOverride(next);
        System.out.println("[nocturne] ui scale " + current + " -> " + next
                + " (press = / - while the GUI is open)");
    }

    /** 是否已打印过首帧输入诊断，保证只打印一次。 */
    private boolean loggedFirstInput;
    /** 是否已打印过首次点击诊断，保证只打印一次。 */
    private boolean loggedFirstClick;
    /** 是否已打印过「开关键被按下」诊断，保证只打印一次。 */
    private boolean loggedToggleSeen;
    /** 是否已打印过左右 Shift 探针，保证只打印一次。 */
    private boolean loggedShiftProbe;
    /** 上一帧是否有界面处于打开状态；用于识别指针捕获的交接沿。 */
    private boolean wasOpenForPointer;
    /** 打开界面之前游戏的指针捕获状态；关闭时原样恢复。 */
    private boolean pointerGrabbedBeforeGui;

    /** @return 我们的界面当前是否打开（供需要避让/顶替外部界面的组件判断） */
    public boolean isOpen() {
        return gui.isOpen();
    }

    /**
     * 不经按键直接置开/关。
     *
     * <p>给验收用（agent 选项 {@code openGui=true}）：1.8.9 的 LWJGL2 走 DirectInput，
     * 合成按键事件到不了游戏，自动化没法"按一下开关键"。
     *
     * @param open 目标状态
     */
    public void setOpen(boolean open) {
        gui.setOpen(open);
        if (open) {
            // 与按键打开同一套基准重置：否则上一帧的按下/位置会被当成新事件（见 onFrame）。
            lastX = input.mouseX();
            lastY = input.mouseY();
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            escapeWasDown = input.keyDown(KEY_ESCAPE);
            // 开关键也要取样：否则「用外部方式打开时开关键正按着」会在下一帧被当成新按下而立刻关掉界面。
            toggleWasDown = input.keyDown(toggleKey);
            java.util.Arrays.fill(charWasDown, false);
        }
    }

    @Override
    public void onFrame() {
        // 指针位置只取一次：开关判定、悬停更新与事件派发必须基于同一份坐标，
        // 否则一帧内指针移动会造成命中测试与事件坐标不一致。
        double mx = input.mouseX();
        double my = input.mouseY();

        // 我们的界面开着时短路游戏自己的输入入口：否则同一次点击/按键会同时打在后面的原生界面上
        //（点模块顺带按到「回到游戏」、Esc 顺带打开暂停菜单）。状态放在 bootstrap 层，游戏类看得到。
        InputBlock.setBlocked(gui.isOpen());
        boolean toggleDown = input.keyDown(toggleKey);
        if (toggleDown && !toggleWasDown) {
            gui.toggle();
            if (gui.isOpen()) {
                // 重置基准状态，避免上一帧的按下/位置被误判成本帧的新事件
                lastX = mx;
                lastY = my;
                leftWasDown = input.mouseDown(BUTTON_LEFT);
                rightWasDown = input.mouseDown(BUTTON_RIGHT);
                escapeWasDown = input.keyDown(KEY_ESCAPE);
                java.util.Arrays.fill(charWasDown, false);
            }
        }
        toggleWasDown = toggleDown;

        // 指针捕获交接（不是每帧强制）：
        //   打开界面      → 记下游戏原本的捕获状态，然后把指针交还给界面；
        //   界面打开期间  → 每帧保持释放（游戏中游戏会自行持续捕获，必须重申）；
        //   关闭的那一帧  → 恢复打开前的状态。
        // 界面关闭期间完全不碰这个状态：主菜单/聊天/原生界面本来就需要可见光标，
        // 无条件捕获会把光标锁死——真机上「鼠标被锁」就是这里来的。
        boolean open = gui.isOpen();
        // 用 = / - 手动调界面大小：与开关、指针捕获一样每帧轮询（按键只在这一层读）。
        handleScaleKeys(open);
        if (open) {
            if (!wasOpenForPointer) {
                pointerGrabbedBeforeGui = input.isPointerGrabbed();
            }
            input.setPointerGrabbed(false);
        } else if (wasOpenForPointer) {
            input.setPointerGrabbed(pointerGrabbedBeforeGui);
        }
        wasOpenForPointer = open;

        // 滚轮增量每帧取出并清零：界面关闭期间也必须消费，否则打开瞬间会把
        // 关闭期间累积的增量一次性滚动出来。
        double scroll = input.scrollDelta();

        if (!gui.isOpen()) {
            // 关闭状态保持输入基准同步，避免重开首帧把陈旧按下态误判为新事件
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            escapeWasDown = input.keyDown(KEY_ESCAPE);
            lastX = mx;
            lastY = my;
            java.util.Arrays.fill(charWasDown, false);
            // 界面关闭不等于不绘制：世界覆盖层与界面无关，必须继续走绘制
            drawFrame();
            return;
        }

        // 视口与悬停必须先同步：滚轮、点击、拖动的夹取都依赖它。放在滚轮之前，是因为滚轮会走
        // 平移夹取——视口还未知（0）时那套夹取会把整列推出屏幕。
        gui.setViewport(renderer.width(), renderer.height());
        gui.update(System.currentTimeMillis(), mx, my);

        // 滚轮先于 Esc 派发：Esc 会在本帧关闭界面，若先处理 Esc，这一帧取出的滚轮增量就再也
        // 没有接收方，被静默丢弃——玩家看到的是「界面明明还开着，滚轮这一下没反应」。
        if (scroll != 0d) {
            gui.mouseScrolled(mx, my, scroll);
        }

        // 键盘：Esc 关闭可达；其余按键与字符交给界面自己处理（修饰位按 AWT 约定）。
        int modifiers = currentModifiers();
        boolean escapeDown = input.keyDown(KEY_ESCAPE);
        if (escapeDown && !escapeWasDown) {
            gui.keyPressed(KEY_ESCAPE, modifiers);
        }
        escapeWasDown = escapeDown;
        dispatchKeys(modifiers);
        if (!gui.isOpen()) {
            // Esc 在本帧关闭了界面：同步输入基准后只画覆盖层。
            // 指针不在这一帧硬性恢复：下一帧的交接沿会把它还原成打开前的状态。
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            lastX = mx;
            lastY = my;
            drawFrame();
            return;
        }

        // Esc 可能改了界面状态（例如关掉最上面那层浮层）：后续鼠标派发必须用当前界面状态。
        gui.setViewport(renderer.width(), renderer.height());
        gui.update(System.currentTimeMillis(), mx, my);

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            if (renderer.ready()) {
                System.out.println("[nocturne] overlay active; input=" + input.describe()
                        + "; backend=" + renderer.backendName()
                        + "; screen=" + gui.getClass().getSimpleName());
            } else {
                // 后端未就绪时叠加层照样会绘制，但可能全帧不可见；明确警告，
                // 避免与「输入没解析出来」的现象混为一谈。
                System.out.println("[nocturne] WARNING: overlay opened but renderer not ready;"
                        + " input=" + input.describe() + "; backend=" + renderer.backendName());
            }
        }

        boolean left = input.mouseDown(BUTTON_LEFT);
        boolean right = input.mouseDown(BUTTON_RIGHT);
        if (!loggedFirstClick && (left || right)) {
            loggedFirstClick = true;
            // 「点了没反应」必须能定位到这一层的哪一半：指针坐标（由原始坐标换算）
            // 与按钮状态都在这里；grabbed 说明指针是否仍被游戏抓着（抓着时系统光标被锁死，
            // 点击落不到界面上）。
            System.out.println("[nocturne] overlay first click: mouse=" + Math.round(mx) + ","
                    + Math.round(my) + " left=" + left + " right=" + right
                    + " grabbed=" + input.isPointerGrabbed()
                    + " surface=" + renderer.width() + "x" + renderer.height());
        }
        double dx = mx - lastX;
        double dy = my - lastY;
        if (left && !leftWasDown) {
            gui.mouseClicked(mx, my, BUTTON_LEFT);
        } else if (left && (dx != 0d || dy != 0d)) {
            gui.mouseDragged(mx, my, BUTTON_LEFT, dx, dy);
        }
        if (!left && leftWasDown) {
            gui.mouseReleased(mx, my, BUTTON_LEFT);
        }

        // 右键只用于「打开浮层 / 直接输入」，不参与拖拽
        if (right && !rightWasDown) {
            gui.mouseClicked(mx, my, BUTTON_RIGHT);
        }
        if (!right && rightWasDown) {
            gui.mouseReleased(mx, my, BUTTON_RIGHT);
        }

        lastX = mx;
        lastY = my;
        leftWasDown = left;
        rightWasDown = right;

        drawFrame();
    }

    /** @return 当前修饰位（AWT 约定：SHIFT=1、CTRL=2、ALT=8） */
    private int currentModifiers() {
        int modifiers = 0;
        if (input.keyDown(KeyMap.VK_LEFT_SHIFT) || input.keyDown(KeyMap.VK_RIGHT_SHIFT)) {
            modifiers |= MOD_SHIFT;
        }
        if (input.keyDown(KeyMap.VK_LEFT_CONTROL) || input.keyDown(KeyMap.VK_RIGHT_CONTROL)) {
            modifiers |= MOD_CTRL;
        }
        if (input.keyDown(KeyMap.VK_LEFT_ALT) || input.keyDown(KeyMap.VK_RIGHT_ALT)) {
            modifiers |= MOD_ALT;
        }
        return modifiers;
    }

    /**
     * 把「本帧新按下的可打印键」合成字符派发给界面。
     *
     * <p>轮询模型下没有字符回调（见 {@link KeyChars}），只能自己合成：按下沿 + Shift 决定字符，
     * 一帧内按多次也只产生一个字符——人手敲键盘不会比帧率更快。
     *
     * @param modifiers 本帧修饰位
     */
    private void dispatchKeys(int modifiers) {
        // 先派发不可打印键：退格/回车/方向键走 keyPressed，不参与字符合成。
        for (int i = 0; i < SPECIAL_KEYS.length; i++) {
            boolean down = input.keyDown(SPECIAL_KEYS[i]);
            boolean fresh = down && !specialWasDown[i];
            specialWasDown[i] = down;
            if (fresh) {
                gui.keyPressed(SPECIAL_KEYS[i], modifiers);
            }
        }
        int[] keys = KeyChars.keys();
        boolean shift = (modifiers & MOD_SHIFT) != 0;
        boolean control = (modifiers & MOD_CTRL) != 0;
        for (int i = 0; i < keys.length; i++) {
            boolean down = input.keyDown(keys[i]);
            boolean fresh = down && !charWasDown[i];
            charWasDown[i] = down;
            if (!fresh) {
                continue;
            }
            if (control) {
                // 组合键（Ctrl+F / Ctrl+V / Ctrl+C）由 keyPressed 处理，不产生字符。
                gui.keyPressed(keys[i], modifiers);
                continue;
            }
            char character = KeyChars.charFor(keys[i], shift);
            if (character == 0) {
                // 可打印键但本表没有映射：仍然把键码派下去，界面可以自己处理（例如 Tab）。
                gui.keyPressed(keys[i], modifiers);
                continue;
            }
            if (!loggedFirstChar) {
                loggedFirstChar = true;
                System.out.println("[nocturne] char input synthesised from polling: '"
                        + character + "' (vk=" + keys[i] + ")");
            }
            gui.charTyped(character);
        }
    }

    /**
     * 绘制本帧：世界覆盖层在最底，界面盖在其上。
     *
     * <p>begin/end 必须成对且**每帧只有一次**：绘制中途抛异常时若不执行 endFrame，
     * GL 状态与原生表面都会留在中间态。
     */
    /** TEMP-DIAG：每帧画红矩形并回读中心像素（不需要界面打开）。 */

    /** TEMP-DIAG：probe 模式的日志限流。 */

    private void drawFrame() {
        // TEMP-DIAG（定位界面不显示后删除）：界面打开时每秒打一次真实下发次数。
        long diagNow = System.currentTimeMillis();
        boolean guiOpen = gui.isOpen();
        // 世界覆盖层（ESP/Tracers/NameTags/StorageESP…）与界面无关，必须单独计入：
        // 界面关着时本方法此前直接 return，于是 beginFrame() 根本不执行、覆盖层一帧都不画——
        // 世界里什么都看不到，日志里也没有半句线索。
        if (!guiOpen && !hasWorldOverlays()) {
            return;
        }
        renderer.beginFrame();
        try {
            // 视口尺寸只有在 beginFrame 之后才是本帧的真值（GlRenderer 在那一步才从 GL 读回视口）。
            // 这里再同步一次：输入处理用上一帧尺寸可以接受，但绘制尺寸必须是当帧的——否则固定
            // 管线后端会按 0 尺寸布局，整个界面等于空框。
            gui.setViewport(renderer.width(), renderer.height());
            // 世界覆盖物画在最底下：GUI 菜单要盖住它们，否则 ESP 的框会糊在面板上。
            drawWorldOverlays();
            if (guiOpen) {
                gui.render(renderer);
            }
        } finally {
            renderer.endFrame();
        }
    }


    /**
     * 是否存在启用中的世界覆盖层模块。
     *
     * <p>用来决定「这一帧还有没有必要开绘制面」：没有它，{@code !guiOpen} 时整帧
     * 直接跳过，覆盖层永远画不出来（26.x 实测）。
     *
     * @return 任一启用中的模块实现了 {@link WorldOverlay} 时返回 {@code true}
     */
    private static boolean hasWorldOverlays() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return false;
        }
        for (Module module : client.modules().all()) {
            if (module.isEnabled() && module instanceof WorldOverlay) {
                return true;
            }
        }
        return false;
    }

    /**
     * 世界覆盖层：把启用中的、实现了 {@link WorldOverlay} 的模块在当前绘制面与投影上画一遍。
     *
     * <p>为什么在这里而不是事件总线：{@code RenderEvent} 的投递点在帧回调里，那时后端还没
     * {@code beginFrame}（甚至还没挑到绘制上下文），画下去只会落到空处。这里在 {@code beginFrame()}
     * 之后、{@code endFrame()} 之前，与界面同一帧、同一后端。
     *
     * <p>相机读不到（玩家还没进世界、或该版本缺成员）时整帧跳过：拿一份零值相机去投，只会投出
     * 一堆乱线。
     */
    private void drawWorldOverlays() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        if (!camera.update(client.gameBridge(), renderer.width(), renderer.height())) {
            // 这条早退以前是静默的：世界里"什么都没有"时无从判断是相机没就绪、视口是 0，
            // 还是没有模块被启用。一次性报出三者的真值。
            // 主菜单里读不到相机是正常的，不值得记；**游戏内**读不到才是问题。
            if (client.gameBridge() != null && client.gameBridge().inWorld() && !worldOverlaySkipLogged) {
                worldOverlaySkipLogged = true;
                logWorldOverlay("skipped in world: camera/viewport not ready (viewport="
                        + renderer.width() + "x" + renderer.height() + ", enabled=["
                        + enabledOverlayNames(client) + "])");
            }
            return;
        }
        int drawn = 0;
        for (Module module : client.modules().all()) {
            if (!module.isEnabled() || !(module instanceof WorldOverlay)) {
                continue;
            }
            try {
                ((WorldOverlay) module).drawWorldOverlay(overlayDraw, camera.projection());
                drawn++;
            } catch (Throwable t) {
                if (!worldOverlayErrorLogged) {
                    worldOverlayErrorLogged = true;
                    System.out.println("[nocturne] world overlay '" + module.name() + "' failed: " + t);
                }
            }
        }
        // 首次真的画出覆盖层时报一次：这是"覆盖层到底画没画"的唯一正面证据。
        if (drawn > 0 && !worldOverlayDrawLogged) {
            worldOverlayDrawLogged = true;
            logWorldOverlay("drawing (" + renderer.width() + "x" + renderer.height() + "): "
                    + drawn + " module(s), enabled=[" + enabledOverlayNames(client) + "]");
        }
    }

    /**
     * 打一条世界覆盖层诊断。
     *
     * @param detail 具体状态，例如相机是否就绪、启用了哪些模块
     */
    private void logWorldOverlay(String detail) {
        System.out.println("[nocturne] world overlay: " + detail);
    }

    /**
     * 启用中的世界覆盖层模块名（诊断用）。
     *
     * @param client 客户端实例
     * @return 以逗号分隔的模块名；一个都没有时返回空串
     */
    private static String enabledOverlayNames(NocturneClient client) {
        StringBuilder names = new StringBuilder();
        for (Module module : client.modules().all()) {
            if (module.isEnabled() && module instanceof WorldOverlay) {
                if (names.length() > 0) {
                    names.append(',');
                }
                names.append(module.name());
            }
        }
        return names.toString();
    }
}
