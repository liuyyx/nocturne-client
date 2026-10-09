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
import dev.nocturne.ui.clickgui.ClickGui;
import dev.nocturne.ui.skija.SetsunaClickGui;
import dev.nocturne.ui.skija.SetsunaHud;
import dev.nocturne.ui.skija.SetsunaHudEditor;
import dev.nocturne.ui.skija.SkijaHudSink;

import io.github.humbleui.skija.Canvas;

/**
 * 把 HUD、点击式 GUI 与 HUD 编辑器叠加到游戏画面上，并承担它们的全部输入。
 *
 * <p>由被补丁的帧交换点每帧调用一次（见 {@code NocturneRuntime}）。这里不做事件 hook，而是每帧
 * 轮询一次指针与按键状态，再自己合成为点击 / 拖拽 / 释放 / 滚轮事件：轮询只需一个查询接口，
 * 而两代 LWJGL 的回调模型差异全部被 {@link InputSource} 吸收，代价是每帧几次反射调用。
 *
 * <p>三层的关系：**HUD 常显**；GUI 与 HUD 编辑器互斥（同一时刻只有一个在前台接输入），
 * 从 GUI 标题栏的「编辑 HUD」进入编辑器、「完成」或 Esc 回到 GUI。三层共用同一次 Skija 帧——
 * 原生表面每帧只能建立与提交一次，各画一帧会把先提交的内容丢掉。
 *
 * <p>界面实现由后端能力决定（见 {@link OverlayGui}）：Skija 可用时用 Canvas 直绘的
 * {@link SetsunaClickGui} / {@link SetsunaHud} / {@link SetsunaHudEditor}（能画玻璃层/阴影/图标字体，
 * 视觉与上游一致），否则用面向 {@code Renderer} 抽象的 {@link ClickGui}（四列布局，任何后端都能画；
 * 此时没有 HUD，也没有编辑器）。
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

    /** 右 Shift 的键码，统一采用 AWT VK 码；输入后端负责翻译到 LWJGL2 / GLFW。 */
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

    /** 帧率指数平滑系数：越小越稳（HUD 上的读数不该逐帧跳）。 */
    private static final float FPS_SMOOTHING = 0.15f;
    /** 单帧间隔超过这个秒数就不参与平滑：卡顿/暂停（例如窗口失焦）会把均值拖到无意义的低位。 */
    private static final float FPS_MAX_DELTA_SECONDS = 1f;

    /** 点击式 GUI。 */
    private final OverlayGui gui;
    /** HUD 编辑器；仅 Skija 后端可用时为非空。 */
    private final SetsunaHudEditor editor;
    /** HUD；仅 Skija 后端可用时为非空。 */
    private final SetsunaHud hud;
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
    /** 上一次帧回调的时间戳（纳秒）；0 表示尚未收到第一帧。 */
    private long lastFrameNanos;
    /** 平滑后的帧率；0 表示还没算出来。 */
    private float fps;

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

    /**
     * @param registry  模块注册表，GUI 据此列出各分类下的模块
     * @param renderer  绘制后端
     * @param input     输入来源
     * @param toggleKey 开关 GUI 的键码
     */
    public GuiOverlay(ModuleRegistry registry, UiBackend renderer, InputSource input, int toggleKey) {
        this(registry, renderer, input, toggleKey, null);
    }

    /**
     * @param sink 模块文本行的汇；非空且为 Skija 后端时 HUD 会显示这些行
     */
    public GuiOverlay(ModuleRegistry registry, UiBackend renderer, InputSource input, int toggleKey,
                      SkijaHudSink sink) {
        // 选择界面实现：Skija 后端能提供画布，就用 Canvas 直绘的 Setsuna 界面；否则用抽象绘制面
        // 上实现的四列界面。判定放在构造期而不是每帧，是为了让「用哪套界面」在日志里可见。
        boolean skija = renderer instanceof SkijaBackend;
        if (skija) {
            final SetsunaClickGui clickGui = new SetsunaClickGui(registry);
            final SetsunaHud setsunaHud = new SetsunaHud(registry, sink, null);
            final SetsunaHudEditor hudEditor = new SetsunaHudEditor(setsunaHud);
            // 两个界面互斥：进入编辑器要先把 GUI 关掉，返回时再打开——否则两者会同时接输入，
            // 点一下编辑器既拖了方块又改了模块开关。
            clickGui.setOnEditHud(new Runnable() {
                @Override
                public void run() {
                    clickGui.setOpen(false);
                    hudEditor.setOpen(true);
                }
            });
            hudEditor.setOnClose(new Runnable() {
                @Override
                public void run() {
                    clickGui.setOpen(true);
                }
            });
            this.gui = clickGui;
            this.hud = setsunaHud;
            this.editor = hudEditor;
        } else {
            this.gui = new ClickGui(registry);
            this.hud = null;
            this.editor = null;
        }
        this.renderer = renderer;
        this.input = input;
        this.toggleKey = toggleKey;
        this.overlayDraw = new BackendOverlayDraw(renderer);
        this.camera = new CameraState(new WorldProjection());
    }

    /** @return 被叠加的 GUI，供外部（如设置界面）直接操作 */
    public OverlayGui gui() {
        return gui;
    }

    /** @return HUD 实现；非 Skija 后端时为 {@code null} */
    public SetsunaHud hud() {
        return hud;
    }

    /** @return HUD 编辑器；非 Skija 后端时为 {@code null} */
    public SetsunaHudEditor editor() {
        return editor;
    }

    /** @return 实际生效的输入后端名称，用于日志诊断；按键「没反应」时先看这里是不是 {@code "none"} */
    public String keyBackend() {
        return input.describe();
    }

    /** @return 当前使用的开关按键键码 */
    public int toggleKey() {
        return toggleKey;
    }

    /** @return 当前在前台接输入的界面：编辑器优先，否则是 GUI */
    private OverlayGui active() {
        return editor != null && editor.isOpen() ? editor : gui;
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
        boolean up = open && (input.keyDown(KEY_SCALE_UP) || input.keyDown(KEY_SCALE_UP_KEYPAD));
        boolean down = open && (input.keyDown(KEY_SCALE_DOWN) || input.keyDown(KEY_SCALE_DOWN_KEYPAD));
        if (up && !scaleUpWasDown) {
            stepScale(1);
        }
        if (down && !scaleDownWasDown) {
            stepScale(-1);
        }
        scaleUpWasDown = up;
        scaleDownWasDown = down;
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
        return active().isOpen();
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
        active().setOpen(open);
        if (open) {
            // 与按键打开同一套基准重置：否则上一帧的按下/位置会被当成新事件（见 onFrame）。
            lastX = input.mouseX();
            lastY = input.mouseY();
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            escapeWasDown = input.keyDown(KEY_ESCAPE);
        }
    }

    @Override
    public void onFrame() {
        // 首帧文件诊断（diag3）已删除：生产环境每会话 APPEND 写 nocturne-diag.txt
        // 是残留 IO；首帧状态由下面的 overlay first frame 日志行覆盖。
        // 指针位置只取一次：开关判定、悬停更新与事件派发必须基于同一份坐标，
        // 否则一帧内指针移动会造成命中测试与事件坐标不一致。
        double mx = input.mouseX();
        double my = input.mouseY();
        updateFps(System.nanoTime());

        // 我们的界面开着时短路游戏自己的输入入口：否则同一次点击/按键会同时打在后面的原生界面上
        //（点模块顺带按到「回到游戏」、Esc 顺带打开暂停菜单）。状态放在 bootstrap 层，游戏类看得到。
        InputBlock.setBlocked(active().isOpen());
        boolean toggleDown = input.keyDown(toggleKey);
        if (!loggedShiftProbe && (input.keyDown(KeyMap.VK_LEFT_SHIFT)
                || input.keyDown(KeyMap.VK_RIGHT_SHIFT))) {
            loggedShiftProbe = true;
            // 一次性诊断：左右 Shift 在输入层是"看得见"还是"看不见"，决定"开关键没反应"往哪查。
            System.out.println("[nocturne] shift probe: left=" + input.keyDown(KeyMap.VK_LEFT_SHIFT)
                    + " right=" + input.keyDown(KeyMap.VK_RIGHT_SHIFT)
                    + " toggleKey=" + toggleKey + " backend=" + input.describe());
        }
        if (toggleDown && !loggedToggleSeen) {
            loggedToggleSeen = true;
            // 一次性诊断：开关键"按了没反应"要先分清是"键没到输入层"还是"开关逻辑没跑"。
            System.out.println("[nocturne] toggle key seen down: vk=" + toggleKey
                    + " backend=" + input.describe());
        }
        if (!loggedFirstInput) {
            loggedFirstInput = true;
            // 首次进入叠加层时把输入侧的真实状态打出来：按键「没反应」时，
            // 这行能直接区分「输入源没解析出来」与「键没被按下」。
            System.out.println("[nocturne] overlay first frame: input=" + input.describe()
                    + " mouse=" + Math.round(mx) + "," + Math.round(my)
                    + " toggleKey=" + toggleKey + " down=" + toggleDown);
        }
        if (toggleDown && !toggleWasDown) {
            if (editor != null && editor.isOpen()) {
                // 编辑器里按开关键 = 返回 GUI（与点「完成」同一语义）
                editor.setOpen(false);
                gui.setOpen(true);
            } else {
                gui.toggle();
            }
            if (active().isOpen()) {
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
        //   打开界面      → 记下游戏原本的捕获状态，然后把指针交还给界面；
        //   界面打开期间  → 每帧保持释放（游戏中游戏会自行持续捕获，必须重申）；
        //   关闭的那一帧  → 恢复打开前的状态。
        // 界面关闭期间完全不碰这个状态：主菜单/聊天/原生界面本来就需要可见光标，
        // 无条件捕获会把光标锁死——真机上「鼠标被锁」就是这里来的。
        boolean open = active().isOpen();
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

        if (!active().isOpen()) {
            // 关闭状态保持输入基准同步，避免重开首帧把陈旧按下态误判为新事件
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            escapeWasDown = input.keyDown(KEY_ESCAPE);
            lastX = mx;
            lastY = my;
            // 界面关闭不等于不绘制：HUD 是常显层，这里必须继续走绘制
            drawFrame();
            return;
        }

        // 视口与悬停必须先同步：滚轮、点击、拖动的夹取都依赖它。放在滚轮之前，是因为滚轮会走
        // 平移夹取——视口还未知（0）时那套夹取会把整列推出屏幕。
        OverlayGui current = active();
        current.setViewport(renderer.width(), renderer.height());
        if (editor != null) {
            editor.setFps(fps);
        }
        current.update(System.currentTimeMillis(), mx, my);

        // 滚轮先于 Esc 派发：Esc 会在本帧关闭界面，若先处理 Esc，这一帧取出的滚轮增量就再也
        // 没有接收方，被静默丢弃——玩家看到的是「界面明明还开着，滚轮这一下没反应」。
        if (scroll != 0d) {
            current.mouseScrolled(mx, my, scroll);
        }

        // 键盘：目前只需让 Esc 可达（关闭当前界面）。键码为 AWT VK，后端已翻译。
        boolean escapeDown = input.keyDown(KEY_ESCAPE);
        if (escapeDown && !escapeWasDown) {
            active().keyPressed(KEY_ESCAPE, 0);
        }
        escapeWasDown = escapeDown;
        if (!active().isOpen()) {
            // Esc 在本帧关闭了界面：同步输入基准后只画 HUD。
            // 指针不在这一帧硬性恢复：下一帧的交接沿会把它还原成打开前的状态
            // （在主菜单里打开界面再按 Esc，光标必须保持可见）。
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            lastX = mx;
            lastY = my;
            drawFrame();
            return;
        }
        // Esc 可能把编辑器切回 GUI（D16）：后续鼠标派发必须用切后的界面，
        // 否则本帧沿会派给已关闭的编辑器而丢掉。视口/悬停给新界面补一次同步。
        current = active();
        current.setViewport(renderer.width(), renderer.height());
        current.update(System.currentTimeMillis(), mx, my);

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            if (renderer.ready()) {
                System.out.println("[nocturne] overlay active; input=" + input.describe()
                        + "; backend=" + renderer.backendName()
                        + "; screen=" + gui.getClass().getSimpleName()
                        + "; hud=" + (hud == null ? "none" : "setsuna"));
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
            // 「点了没反应」必须能定位到这一层的哪一半：指针坐标（由 SDL 原始坐标换算）
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
            current.mouseClicked(mx, my, BUTTON_LEFT);
        } else if (left && (dx != 0d || dy != 0d)) {
            current.mouseDragged(mx, my, BUTTON_LEFT, dx, dy);
        }
        if (!left && leftWasDown) {
            current.mouseReleased(mx, my, BUTTON_LEFT);
        }

        // 右键只用于「恢复默认值 / 打开模块设置」，不参与拖拽
        if (right && !rightWasDown) {
            current.mouseClicked(mx, my, BUTTON_RIGHT);
        }
        if (!right && rightWasDown) {
            current.mouseReleased(mx, my, BUTTON_RIGHT);
        }

        lastX = mx;
        lastY = my;
        leftWasDown = left;
        rightWasDown = right;

        drawFrame();
    }

    /**
     * 绘制本帧：HUD 常显，前台界面（GUI 或 HUD 编辑器）叠加其上。
     *
     * <p>编辑器打开时由它自己画 HUD 预览，所以这里不再重复画 HUD——重复画会让卡片叠出更实的心色，
     * 而编辑器看到的应当就是最终效果。
     *
     * <p>begin/end 必须成对且**每帧只有一次**：Skija 的原生表面每帧只能建立与提交一次，
     * 多层各走一遍会把先绘制的内容丢掉；渲染中途抛异常时若不执行 endFrame，GL 状态与
     * 原生表面都会留在中间态。
     */
    private void drawFrame() {
        boolean editorOpen = editor != null && editor.isOpen();
        boolean guiOpen = gui.isOpen() && !editorOpen;
        // 世界覆盖层（ESP/Tracers/NameTags/StorageESP…）与 GUI/HUD 无关，必须单独计入：
        // 26.x 没有 Skija 画布（hud == null），关掉 GUI 后本方法此前直接 return，于是 beginFrame()
        // 根本不执行、覆盖层一帧都不画——世界里什么都看不到，日志里也没有半句线索。
        if (hud == null && !guiOpen && !editorOpen && !hasWorldOverlays()) {
            return;
        }
        renderer.beginFrame();
        try {
            // 视口尺寸只有在 beginFrame 之后才是本帧的真值（GlRenderer 在那一步才从 GL 读回视口）。
            // 这里再同步一次：输入处理用上一帧尺寸可以接受，但绘制尺寸必须是当帧的——否则固定
            // 管线后端会按 0 尺寸布局，整个界面等于空框。
            active().setViewport(renderer.width(), renderer.height());
            // 画布必须在 beginFrame 之后取：Skija 后端在这一步才建立/复用原生表面
            Canvas canvas = renderer instanceof SkijaBackend
                    ? ((SkijaBackend) renderer).canvas()
                    : null;
            int width = renderer.width();
            int height = renderer.height();
            if (editorOpen) {
                // 编辑器只在前端为 Skija 时存在（见构造），此时 canvas 必然可用。
                editor.render(renderer, canvas);
            } else {
                // 世界覆盖物画在最底下：GUI 菜单要盖住它们，否则 ESP 的框会糊在面板上。
                drawWorldOverlays();
                if (hud != null && canvas != null) {
                    hud.render(canvas, width, height, fps);
                }
                if (guiOpen) {
                    // 不能按 canvas 是否为空决定画不画界面：gl-fixed 后端下 canvas 恒为 null，而
                    // ClickGui 只用抽象绘制面（忽略 canvas）——原写法会让整个 ClickGUI 永不渲染。
                    gui.render(renderer, canvas);
                }
            }
        } finally {
            renderer.endFrame();
        }
    }

    /**
     * 是否存在启用中的世界覆盖层模块。
     *
     * <p>用来决定「这一帧还有没有必要开绘制面」：没有它，{@code hud == null && !guiOpen} 时整帧
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
     * 之后、{@code endFrame()} 之前，与 HUD / ClickGUI 同一帧、同一后端。
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

    /**
     * 更新平滑帧率。
     *
     * <p>用指数平滑而不是逐帧瞬时值：HUD 上的数字逐帧跳动既难读、也掩盖不了真实的趋势。
     * 间隔超过 {@link #FPS_MAX_DELTA_SECONDS} 的帧（暂停、断点、窗口最小化）不参与计算，
     * 否则恢复后的头几帧读数会被拖到无意义的低位。
     */
    private void updateFps(long nowNanos) {
        if (lastFrameNanos != 0L) {
            float delta = (nowNanos - lastFrameNanos) / 1_000_000_000f;
            if (delta > 0f && delta < FPS_MAX_DELTA_SECONDS) {
                float instant = 1f / delta;
                fps = fps <= 0f ? instant : fps + (instant - fps) * FPS_SMOOTHING;
            }
        }
        lastFrameNanos = nowNanos;
    }
}
