package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.render.CameraState;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.runtime.FrameListener;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.client.runtime.PhysicalKeys;
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
public final class GuiOverlay implements FrameListener, dev.nocturne.client.hud.HudSink,
        dev.nocturne.client.render.RenderCapabilities {

    /** 右 Shift 的规范键码（AWT 数值区之外的哨兵，与 {@code KeyMap.VK_RIGHT_SHIFT} 一致）。 */
    public static final int KEY_RIGHT_SHIFT = 0x1F01;
    /** Esc 的键码（AWT VK_ESCAPE）；用于派发键盘事件使 Esc 关闭可达。 */
    private static final int KEY_ESCAPE = 27;
    /** 兼容开关键：AWT VK_INSERT。第三方客户端常占用右 Shift，留一个不冲突的入口。 */
    private static final int KEY_INSERT = 155;

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
    /** 原生 Screen 关闭后的按压交接：松开前不得把它派发给自绘 GUI。 */
    private boolean nativeMouseHandoff;
    /** 上一帧指针 x（GUI 坐标），用于计算拖拽增量。 */
    private double lastX;
    /** 上一帧指针 y（GUI 坐标）。 */
    private double lastY;
    /** 是否已打印过「GUI 已打开」日志，保证只打印一次。 */
    private boolean loggedFirstDraw;

    /** 世界覆盖层绘制面：把当前后端包成 client 侧接口（模块只认它）。 */
    private final BackendOverlayDraw overlayDraw;
    private final FrameEffects frameEffects = new FrameEffects();
    /** 已注册 HUD 文本行；LinkedHashMap 保证模块注册顺序就是屏幕顺序。 */
    private final java.util.Map<String, java.util.function.Supplier<String>> hudLines =
            new java.util.LinkedHashMap<String, java.util.function.Supplier<String>>();
    private int configuredScale;
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
    /** 键位绑定时轮询的完整规范键表。 */
    private final int[] bindKeys = KeyMap.bindableKeys();
    /** 完整键表上一帧是否按下，下标与 {@link #bindKeys} 对齐。 */
    private final boolean[] bindWasDown = new boolean[bindKeys.length];
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
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.setHudSink(this);
            client.setRenderCapabilities(this);
            installWorld3DBackend(client);
        }
    }

    /**
     * 按当前绘制栈装配世界空间 3D 后端。
     *
     * <p>只有固定管线（{@link GlRenderer}，1.8.9–1.12.2）具备立即模式 3D；其余栈显式降级并携带
     * 具体原因——绝不让 {@code capability()} 在没有后端时谎报可用。
     */
    private void installWorld3DBackend(NocturneClient client) {
        dev.nocturne.client.render3d.World3DRenderer world3d = client.world3d();
        if (world3d == null) {
            return;
        }
        if (renderer instanceof GlRenderer) {
            world3d.setBackend(new GlWorld3DBackend(((GlRenderer) renderer).gl()));
            return;
        }
        if (renderer instanceof ModernRenderer) {
            world3d.markUnavailable(renderer.backendName(),
                    "GL core profile has no fixed-function immediate mode "
                            + "(glBegin/glVertex3f/glFrustum); Phase 1 only implements the"
                            + " 1.8.9 GL11 backend");
            return;
        }
        world3d.markUnavailable(renderer.backendName(),
                "GUI draw stack '" + renderer.backendName()
                        + "' has no 3D world render pass wired in Phase 1");
    }

    @Override
    public void depthTest(boolean enabled) {
        if (renderer instanceof GlRenderer) {
            if (enabled) ((GlRenderer) renderer).gl().enable(0x0B71);
            else ((GlRenderer) renderer).gl().disable(0x0B71);
        } else if (renderer instanceof ModernRenderer) {
            if (enabled) ((ModernRenderer) renderer).gl().enableCap(0x0B71);
            else ((ModernRenderer) renderer).gl().disableCap(0x0B71);
        }
    }

    @Override
    public Boolean depthTestEnabled() {
        if (renderer instanceof GlRenderer) {
            GlApi gl = ((GlRenderer) renderer).gl();
            return gl.hasIsEnabled()
                    ? Boolean.valueOf(gl.isCapEnabled(0x0B71)) : null;
        }
        if (renderer instanceof ModernRenderer) {
            ModernGlApi gl = ((ModernRenderer) renderer).gl();
            return gl.hasIsEnabled()
                    ? Boolean.valueOf(gl.capabilityEnabled(0x0B71)) : null;
        }
        return null;
    }

    @Override
    public void texture2d(boolean enabled) {
        if (renderer instanceof GlRenderer) {
            if (enabled) ((GlRenderer) renderer).gl().enable(0x0DE1);
            else ((GlRenderer) renderer).gl().disable(0x0DE1);
        }
    }

    @Override
    public void color(int argb) {
        if (renderer instanceof GlRenderer) {
            ((GlRenderer) renderer).gl().color(((argb >>> 16) & 255) / 255.0f,
                    ((argb >>> 8) & 255) / 255.0f, (argb & 255) / 255.0f,
                    ((argb >>> 24) & 255) / 255.0f);
        }
    }

    @Override
    public void polygonOffset(boolean enabled) {
        if (renderer instanceof GlRenderer) {
            GlApi gl = ((GlRenderer) renderer).gl();
            if (enabled) {
                gl.enable(32823);
                gl.polygonOffset(1.0f, -2500000.0f);
            } else {
                gl.polygonOffset(1.0f, 2500000.0f);
                gl.disable(32823);
            }
        }
    }

    @Override
    public int[] frameSize() {
        if (renderer instanceof GlRenderer) {
            return ((GlRenderer) renderer).gl().viewport();
        }
        if (renderer instanceof ModernRenderer) {
            return ((ModernRenderer) renderer).physicalSize();
        }
        return null;
    }

    @Override
    public boolean readPixels(int width, int height, int[] out) {
        if (renderer instanceof GlRenderer) {
            return ((GlRenderer) renderer).gl().readPixels(width, height, out);
        }
        if (renderer instanceof ModernRenderer) {
            return ((ModernRenderer) renderer).gl().readPixels(width, height, out);
        }
        return false;
    }

    /** 注册或替换一行实时 HUD 文本。 */
    @Override
    public void add(String id, java.util.function.Supplier<String> text) {
        hudLines.put(id, text);
    }

    /** 移除一行 HUD 文本。 */
    @Override
    public void remove(String id) {
        hudLines.remove(id);
    }

    /** @return 指定 HUD 行是否已注册。 */
    @Override
    public boolean has(String id) {
        return hudLines.containsKey(id);
    }

    /** @return 被叠加的 GUI，供外部（如验收脚本）直接操作 */
    public OverlayGui gui() {
        return gui;
    }

    /** @return 实际生效的输入后端名称，用于日志诊断；按键「没反应」时先看这里是不是 {@code "none"} */
    public String keyBackend() {
        return input.describe();
    }

    /** @return 配置键或 INSERT 任一按下；INSERT 用于规避第三方客户端占用右 Shift。 */
    private boolean togglePressed() {
        return keyPressed(toggleKey) || keyPressed(KEY_INSERT);
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
        // 搜索框/绑定行有焦点时，= 与 - 是打字内容；此时只记边沿，不改缩放。
        boolean usable = open && !gui.blocksScaleKeys();
        boolean up = usable && upRaw;
        boolean down = usable && downRaw;
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
    /** 上一次打印批次统计的时间（仅调试开关下使用）。 */
    private long batchLoggedAt;

    private boolean wasOpenForPointer;
    /** 输入诊断计数（-Dnocturne.ui.debug）。 */
    private int debugInput;
    /** 帧计数（探针用）。 */
    private int debugFrames;
    /** 上一次打印的按下状态（只在变化时打印）。 */
    private boolean debugLastDown;

    /**
     * 读取一次「刚按下」的按键：事件驱动优先，轮询兜底。
     *
     * <p>1.21.1 真机实测 {@code glfwGetKey} 在合成按键下恒为 0（游戏自己的回调却正常收到），
     * 所以以 {@code InputBlock} 记录的按键事件为准；没有事件时再问轮询，兼容旧路径。
     *
     * @param vk AWT VK 码
     * @return 本帧是否按下
     */
    private boolean keyPressed(int vk) {
        int glfw = dev.nocturne.client.input.KeyMap.glfw(vk);
        if (glfw >= 0 && consumeKey(glfw)) {
            return true;
        }
        return input.keyDown(vk);
    }

    /** bootstrap 层 {@code InputBlock.consumePressed} 的方法句柄。 */
    private static java.lang.reflect.Method consumePressedMethod;
    /** 句柄解析失败只报一次。 */
    private static boolean consumePressedFailed;

    /** 指针需要继续重试抓回（关闭界面那次 grabMouse 被短路吃掉时会置位）。 */
    private boolean pointerRestorePending;
    /** 关闭后希望达到的指针抓取状态（重试时使用，避免读到一个过期的捕获值）。 */
    private boolean pointerRestoreTarget;
    /** 关闭/panic 后抓取重试的已用帧数；上限 {@link #POINTER_RESTORE_FRAMES}。 */
    private int pointerRestoreAttempts;
    /** 输入状态是否已由 panic 恢复过（幂等：restoreInputState 可能被多次调用）。 */
    private boolean inputRestored;
    /** 关闭/panic 后最多重试的帧数：游戏在被别的钩子打断时可能晚一两帧才接受抓取。 */
    private static final int POINTER_RESTORE_FRAMES = 20;
    /** panic 还原动作内的有限重试次数（不依赖帧回调，kill 之后也有效）。 */
    private static final int POINTER_RESTORE_ATTEMPTS = 5;
    /** 还原动作内两次重试之间的间隔：给游戏状态机留出被下一帧刷新的机会。 */
    private static final long POINTER_RESTORE_RETRY_MILLIS = 10L;

    /** bootstrap 层 {@code KeySink.setBlocked} 的方法句柄。 */
    private static java.lang.reflect.Method setBlockedMethod;
    /** bootstrap 层 {@code KeySink.shouldBlock} 的方法句柄。 */
    private static java.lang.reflect.Method shouldBlockMethod;
    /** 桥解析失败只报一次。 */
    private static boolean blockedBridgeFailed;

    /**
     * 写输入占用状态。
     *
     * <p>必须走 bootstrap 里的 {@code KeySink}：织入的短路读的是那一份。直接静态调用
     * {@code InputBlock.setBlocked} 在类身份分裂时会把状态写进另一份静态字段（26.x Fabric 风险），
     * 游戏侧照旧响应输入。
     *
     * @param value {@code true} = 我们的界面占用输入
     */
    private static void setBlocked(boolean value) {
        if (!blockedBridgeFailed) {
            try {
                java.lang.reflect.Method method = setBlockedMethod;
                if (method == null) {
                    Class<?> type = Class.forName("dev.nocturne.bootstrap.KeySink", true, null);
                    method = type.getMethod("setBlocked", boolean.class);
                    setBlockedMethod = method;
                }
                method.invoke(null, Boolean.valueOf(value));
                return;
            } catch (Throwable error) {
                blockedBridgeFailed = true;
                System.out.println("[nocturne] input block bridge failed: " + error);
            }
        }
        InputBlock.setBlocked(value);
    }

    /** @return 游戏侧输入入口是否应短路（与织入代码读同一份 bootstrap 状态）。 */
    private static boolean shouldBlock() {
        if (!blockedBridgeFailed) {
            try {
                java.lang.reflect.Method method = shouldBlockMethod;
                if (method == null) {
                    Class<?> type = Class.forName("dev.nocturne.bootstrap.KeySink", true, null);
                    method = type.getMethod("shouldBlock");
                    shouldBlockMethod = method;
                }
                Object value = method.invoke(null);
                return Boolean.TRUE.equals(value);
            } catch (Throwable error) {
                blockedBridgeFailed = true;
                System.out.println("[nocturne] input block bridge failed: " + error);
            }
        }
        return InputBlock.shouldBlock();
    }

    /**
     * panic 收尾：关闭自绘界面并还原输入状态。
     *
     * <p>{@code panic()} 会清空帧分发器，之后 {@link #onFrame()} 不再执行；若此刻界面正开着，
     * 指针仍处于释放状态（现代版本）或 {@code inGameHasFocus} 仍为 false（1.8.9，由
     * {@code ForeignScreenGuard} 负责还原）。这里显式把指针抓回原来的状态，带幂等标志。
     */
    public void restoreInputState() {
        if (inputRestored) {
            return;
        }
        inputRestored = true;
        try {
            gui.setOpen(false);
            setBlocked(false);
            if (wasOpenForPointer) {
                // panic 可能发生在界面仍开着的时候：此时关闭分支还没算过目标值，
                // 必须在这里按同一规则重算（pointerRestoreTarget 的默认 false 会直接把指针放开）。
                boolean wantGrab = pointerGrabbedBeforeGui
                        || (inWorld() && !hasNativeScreen());
                pointerRestoreTarget = wantGrab;
                // panic 已先把分发器 kill 掉：不能再把重试托付给 onFrame（它不会执行）。
                // 还原动作自己有限重试——抓取有时会被游戏状态机晚一两帧接受。
                pointerRestorePending = !restorePointerWithRetry(wantGrab);
                pointerRestoreAttempts = 0;
            } else {
                pointerRestorePending = false;
            }
            wasOpenForPointer = false;
        } catch (Throwable failure) {
            // panic 路径不能因为恢复失败而中断，但不能像以前那样完全静默：
            // 真机“panic 后鼠标不能动”时这条日志是唯一的线索。
            System.out.println("[nocturne] overlay input restore failed: " + failure);
        }
    }

    /**
     * 在还原动作内有限重试抓取/释放指针。
     *
     * <p>只重试“抓回”失败（释放是指令式下发，没有可验证的失败状态）。每次尝试后重新读取
     * 真实状态；全部失败时不写任何假值，交由返回的 false 保留 pending 标记。
     *
     * @param grab true=抓回、false=释放
     * @return 目标状态是否已达成（释放总是返回 true）
     */
    private boolean restorePointerWithRetry(boolean grab) {
        for (int attempt = 0; attempt < POINTER_RESTORE_ATTEMPTS; attempt++) {
            input.setPointerGrabbed(grab);
            if (!grab || input.isPointerGrabbed()) {
                return true;
            }
            if (attempt + 1 < POINTER_RESTORE_ATTEMPTS) {
                try {
                    Thread.sleep(POINTER_RESTORE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        System.out.println("[nocturne] overlay pointer restore still rejected after "
                + POINTER_RESTORE_ATTEMPTS + " attempts");
        return false;
    }

    /**
     * 从 <b>bootstrap</b> 取 {@code InputBlock.consumePressed}。
     *
     * <p>{@code InputBlock} 被追加到 bootstrap 搜索路径（游戏类加载器看得到），而本类由 agent
     * 加载器加载——直接静态调用读到的是 agent jar 里那份，静态字段与游戏写入的不是同一份
     * （1.21.1 实测：recordKey 打印 340，这边恒为 false）。与 FrameDispatcher 同一套处理。
     *
     * @param glfwKey GLFW 键码
     * @return 本帧是否消费到该键
     */
    private static boolean consumeKey(int glfwKey) {
        if (consumePressedFailed) {
            return InputBlock.consumePressed(glfwKey, 250_000_000L);
        }
        try {
            java.lang.reflect.Method method = consumePressedMethod;
            if (method == null) {
                Class<?> type = Class.forName("dev.nocturne.bootstrap.KeySink", true, null);
                if (System.getProperty("nocturne.ui.debug") != null) {
                    System.out.println("[nocturne] InputBlock bridge cls="
                            + System.identityHashCode(type) + " loader=" + type.getClassLoader());
                }
                method = type.getMethod("consumePressed", int.class, long.class);
                consumePressedMethod = method;
            }
            return Boolean.TRUE.equals(method.invoke(null, glfwKey, 250_000_000L));
        } catch (Throwable error) {
            consumePressedFailed = true;
            if (System.getProperty("nocturne.ui.debug") != null) {
                System.out.println("[nocturne] InputBlock bridge failed: " + error);
            }
            // attach 形态有全局唯一的 bootstrap KeySink；mod 形态没有 Instrumentation，
            // 无法安装它，但 Mixin 与 GuiOverlay 都在 Knot 中，本地 InputBlock 恰好是同一份。
            return InputBlock.consumePressed(glfwKey, 250_000_000L);
        }
    }
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
            escapeWasDown = keyPressed(KEY_ESCAPE);
            // 开关键也要取样：否则「用外部方式打开时开关键正按着」会在下一帧被当成新按下而立刻关掉界面。
            toggleWasDown = togglePressed();
            java.util.Arrays.fill(charWasDown, false);
        }
    }

    @Override
    public void onFrame() {
        // 指针位置只取一次：开关判定、悬停更新与事件派发必须基于同一份坐标，
        // 否则一帧内指针移动会造成命中测试与事件坐标不一致。
        double mx = input.mouseX();
        double my = input.mouseY();
        // 物理键快照与原生界面无关：聊天/背包打开时 HUD 也要显示真实按键状态。
        updatePhysicalKeys();
        // 原版侧边栏的抑制开关由「Better Scoreboard 是否启用 / NoRender 的 Scoreboard 是否勾选」
        // 决定；放在所有早退之前，模块关掉后也能把显示槽恢复回来。
        syncVanillaScoreboard();

        // 原生 Screen 优先：它出现时不能再短路 MouseHandler/KeyboardHandler，否则暂停菜单
        // 连“回到游戏”都点不了，同时关闭自绘 GUI，避免“回到游戏”被面板压住。
        // 但绘制不能一起让步：HUD 行（计分板/坐标/按键）、世界覆盖层（NameTags/ESP）
        // 与 Better Chat 的背景都必须在聊天/背包/暂停界面期间继续画。
        boolean nativeScreen = hasNativeScreen();
        if (nativeScreen) {
            setBlocked(false);
            nativeMouseHandoff = false;
            if (gui.isOpen()) {
                gui.setOpen(false);
            }
            input.scrollDelta(); // 丢弃原生界面期间的滚轮，返回后不得补发。
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            toggleWasDown = togglePressed();
            escapeWasDown = keyPressed(KEY_ESCAPE);
            lastX = mx;
            lastY = my;
            java.util.Arrays.fill(charWasDown, false);
            drawFrame();
            return;
        }
        setBlocked(gui.isOpen());
        if (lastBlockedLogged != gui.isOpen()) {
            lastBlockedLogged = gui.isOpen();
            System.out.println("[nocturne] input block: guiOpen=" + gui.isOpen()
                    + " blocked=" + shouldBlock());
        }
        // 事件驱动优先：1.21.1 的 glfwGetKey 在合成按键下恒为 0（游戏回调却正常）。
        boolean toggleDown = togglePressed();
        if (toggleDown && !toggleWasDown) {
            gui.toggle();
            if (gui.isOpen()) {
                // 重置基准状态，避免上一帧的按下/位置被误判成本帧的新事件
                lastX = mx;
                lastY = my;
                leftWasDown = input.mouseDown(BUTTON_LEFT);
                rightWasDown = input.mouseDown(BUTTON_RIGHT);
                escapeWasDown = keyPressed(KEY_ESCAPE);
                java.util.Arrays.fill(charWasDown, false);
            }
        }
        if (System.getProperty("nocturne.ui.debug") != null && (debugFrames % 30) == 0) {
            System.out.println("[nocturne] toggle probe: down=" + toggleDown
                    + " was=" + toggleWasDown + " open=" + gui.isOpen()
                    + " key=" + toggleKey);
        }
        toggleWasDown = toggleDown;
        // 开关键可能在本帧关掉界面：立即同步 blocked，否则关闭分支恢复指针时 grabMouse
        // 仍会被短路吃掉（随后 wasOpenForPointer=false 不再重试，指针永久不抓回）。
        setBlocked(gui.isOpen());
        if (lastBlockedLogged != gui.isOpen()) {
            lastBlockedLogged = gui.isOpen();
            System.out.println("[nocturne] input block: guiOpen=" + gui.isOpen()
                    + " blocked=" + shouldBlock());
        }

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
            // 先解除输入短路再抓回指针：grabMouse 自身也被织入短路，blocked 仍为 true 时
            // 这次恢复会被直接 return，之后 wasOpenForPointer=false 就再也不会重试。
            setBlocked(false);
            boolean wantGrab = pointerGrabbedBeforeGui;
            if (!wantGrab && inWorld() && !hasNativeScreen()) {
                // 打开界面时游戏恰好没抓着鼠标（例如刚关掉原生 Screen 的那一帧），
                // 直接按捕获值恢复会让关掉自绘 GUI 后永远转不了视角：游戏认为自己在游戏中
                //（1.8.9 inGameHasFocus=true），不会再用 setIngameFocus 重新抓取。
                // 只要在世界里且没有原生界面，就应当抓回指针。
                wantGrab = true;
            }
            pointerRestoreTarget = wantGrab;
            pointerRestoreAttempts = 0;
            input.setPointerGrabbed(wantGrab);
            pointerRestorePending = wantGrab && !input.isPointerGrabbed();
        } else if (pointerRestorePending && !hasNativeScreen()) {
            // 上一次恢复没生效（游戏可能又改了抓取状态）：逐帧重试，最多 20 帧后放弃。
            input.setPointerGrabbed(pointerRestoreTarget);
            pointerRestorePending = pointerRestoreTarget && !input.isPointerGrabbed();
            if (pointerRestorePending && ++pointerRestoreAttempts >= POINTER_RESTORE_FRAMES) {
                pointerRestorePending = false;
                System.out.println("[nocturne] WARNING: pointer grab did not take effect after "
                        + POINTER_RESTORE_FRAMES + " frames; giving up (target="
                        + pointerRestoreTarget + ")");
            }
        }
        wasOpenForPointer = open;

        // 滚轮增量每帧取出并清零：界面关闭期间也必须消费，否则打开瞬间会把
        // 关闭期间累积的增量一次性滚动出来。
        if (System.getProperty("nocturne.ui.debug") != null && open) {
            debugFrames++;
        }
        if (System.getProperty("nocturne.ui.debug") != null && open && (debugFrames % 30) == 0) {
            System.out.println("[nocturne] key probe: esc=" + input.keyDown(KEY_ESCAPE)
                    + " toggle=" + input.keyDown(toggleKey)
                    + " grab=" + input.isPointerGrabbed()
                    + " backend=" + input.describe());
        }
        if (System.getProperty("nocturne.ui.debug") != null && open) {
            boolean down = input.mouseDown(BUTTON_LEFT);
            if (down != debugLastDown || debugInput < 6) {
                debugInput++;
                debugLastDown = down;
                System.out.println("[nocturne] input state: mx="
                        + Math.round(input.mouseX() * 100) / 100.0
                        + " my=" + Math.round(input.mouseY() * 100) / 100.0
                        + " left=" + down + " grab=" + input.isPointerGrabbed());
            }
        }
        double scroll = input.scrollDelta();

        if (!gui.isOpen()) {
            nativeMouseHandoff = false;
            // 关闭状态保持输入基准同步，避免重开首帧把陈旧按下态误判为新事件
            leftWasDown = input.mouseDown(BUTTON_LEFT);
            rightWasDown = input.mouseDown(BUTTON_RIGHT);
            escapeWasDown = keyPressed(KEY_ESCAPE);
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
            if (!scrollDispatchLogged) {
                scrollDispatchLogged = true;
                System.out.println("[nocturne] gui wheel dispatch: " + scroll);
            }
            gui.mouseScrolled(mx, my, scroll);
        }

        // 键盘：Esc 关闭可达；其余按键与字符交给界面自己处理（修饰位按 AWT 约定）。
        int modifiers = currentModifiers();
        boolean escapeDown = keyPressed(KEY_ESCAPE);
        if (escapeDown && !escapeWasDown) {
            gui.keyPressed(KEY_ESCAPE, modifiers);
        }
        escapeWasDown = escapeDown;
        dispatchKeys(modifiers);
        if (!gui.isOpen()) {
            nativeMouseHandoff = false;
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
        // 原生按钮的按下/释放可能跨越 Screen 关闭边界。整段交接都只同步基准：
        // 不把返回游戏的那次按压补成自绘 GUI 的点击，也不发送无对应按下的释放。
        if (nativeMouseHandoff) {
            nativeMouseHandoff = left || right;
            lastX = mx;
            lastY = my;
            leftWasDown = left;
            rightWasDown = right;
            drawFrame();
            return;
        }
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
        if (gui.isCapturingKey()) {
            // 绑定键位时不能只走“可打印键 + 固定特殊键”：F1–F12、Insert、小键盘等
            // 都不会出现在那两张表里。这里按完整规范键表派发，且只派发按下沿。
            for (int i = 0; i < bindKeys.length; i++) {
                boolean down = input.keyDown(bindKeys[i]);
                boolean fresh = down && !bindWasDown[i];
                bindWasDown[i] = down;
                if (fresh) {
                    gui.keyPressed(bindKeys[i], modifiers);
                }
            }
            // 捕获期间不能合成字符；同时同步普通键的基准，避免退出捕获后把仍按住的键
            // 误判成新按下（例如 Esc 关闭界面）。
            for (int i = 0; i < SPECIAL_KEYS.length; i++) {
                specialWasDown[i] = input.keyDown(SPECIAL_KEYS[i]);
            }
            int[] printable = KeyChars.keys();
            for (int i = 0; i < printable.length; i++) {
                charWasDown[i] = input.keyDown(printable[i]);
            }
            return;
        }
        java.util.Arrays.fill(bindWasDown, false);
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

    /** 原生 Screen 探针只打一次：读不到字段会让 GUI 永不关闭，输入被永久短路。 */
    private boolean nativeScreenProbeLogged;

    /** 滚轮派发诊断只打一次：区分「增量没进来」和「进来了但界面没接住」。 */
    private boolean scrollDispatchLogged;

    /** 输入占用状态变化时打一次：确认 setBlocked 真的写到了 bootstrap 那份。 */
    private boolean lastBlockedLogged;
    /** @return 当前启用中的世界覆盖层模块数（诊断用） */
    private int worldOverlayCount() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return 0;
        }
        int count = 0;
        for (dev.nocturne.client.module.Module module : client.modules().all()) {
            if (module instanceof WorldOverlay && module.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    /** 上一帧世界覆盖层绘制耗时（纳秒），配合 debug 统计打印。 */
    private long lastOverlayNanos;

    /** @return 当前是否在世界里（主菜单/加载界面不抢指针）。 */
    private boolean inWorld() {
        try {
            NocturneClient client = NocturneClient.get();
            dev.nocturne.client.game.GameBridge bridge = client == null ? null : client.gameBridge();
            return bridge != null && bridge.inWorld();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return 游戏当前是否显示原生 Screen；桥不可用时按没有处理，不阻断自绘 GUI。 */
    private boolean hasNativeScreen() {
        try {
            NocturneClient client = NocturneClient.get();
            dev.nocturne.client.game.GameBridge bridge = client == null ? null : client.gameBridge();
            Object minecraft = bridge == null ? null : bridge.minecraft();
            if (minecraft == null) {
                return false;
            }
            Object current = bridge.readField(minecraft,
                    dev.nocturne.client.mapping.ClassType.MINECRAFT, "currentScreen");
            Object legacy = bridge.readField(minecraft,
                    dev.nocturne.client.mapping.ClassType.MINECRAFT, "screen");
            // 26.x 的 Minecraft 没有 currentScreen/screen（映射 absent）：屏幕在 Gui.screen()。
            // 先按方法取（与 GuiDrawHook 的探针同一套），再按形状兜底。
            Object viaGui = readGuiScreen(bridge, minecraft);
            if (viaGui == null && current == null && legacy == null) {
                viaGui = readScreenByShape(minecraft);
            }
            if (!nativeScreenProbeLogged) {
                nativeScreenProbeLogged = true;
                System.out.println("[nocturne] native screen probe: currentScreen=" + current
                        + " screen=" + legacy + " gui.screen=" + viaGui);
            }
            return current != null || legacy != null || viaGui != null;
        } catch (Throwable t) {
            if (!nativeScreenProbeLogged) {
                nativeScreenProbeLogged = true;
                System.out.println("[nocturne] native screen probe failed: " + t);
            }
            return false;
        }
    }
    /** 形状兜底缓存的 Screen 字段（26.3 的 currentScreen/screen 映射 absent）。 */
    private java.lang.reflect.Field nativeScreenField;
    /** 是否已尝试过形状解析。 */
    private boolean nativeScreenFieldResolved;

    /**
     * 按形状找当前 Screen：非 static、类型名以 {@code Screen} 结尾的字段。
     *
     * @param minecraft 游戏主类实例
     * @return Screen 实例；找不到时为 {@code null}
     */
    private Object readScreenByShape(Object minecraft) {
        try {
            java.lang.reflect.Field field = nativeScreenField;
            if (field == null && !nativeScreenFieldResolved) {
                nativeScreenFieldResolved = true;
                for (java.lang.reflect.Field candidate : minecraft.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                            || !candidate.getType().getSimpleName().endsWith("Screen")) {
                        continue;
                    }
                    candidate.setAccessible(true);
                    nativeScreenField = candidate;
                    field = candidate;
                    break;
                }
            }
            return field == null ? null : field.get(minecraft);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 26.3 的屏幕挂在 {@code Gui.screen()} 上；22.x 的 {@code Minecraft.gui} 是 {@code Gui}。
     *
     * @param bridge    游戏桥
     * @param minecraft 游戏主类实例
     * @return Screen 实例；不可用时为 {@code null}
     */
    private Object readGuiScreen(dev.nocturne.client.game.GameBridge bridge, Object minecraft) {
        try {
            dev.nocturne.client.mapping.Mapping mapping = bridge.mapping();
            if (mapping == null || mapping.isMemberAbsent(
                    dev.nocturne.client.mapping.ClassType.HUD, "screen")) {
                // 该版本 Gui 没有 screen()（1.8.9–1.21.x）：不要每帧调 callMapped，
                // 否则 GameBridge 每次都会打一条「no overload for screen ()」把日志刷爆。
                return null;
            }
            Object gui = bridge.readField(minecraft,
                    dev.nocturne.client.mapping.ClassType.MINECRAFT, "gui");
            if (gui == null) {
                return null;
            }
            Object screen = bridge.callMapped(gui,
                    dev.nocturne.client.mapping.ClassType.HUD, "screen");
            return screen;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把本帧的物理移动键写入快照，供 Keystrokes 等 HUD 模块读取。 */
    private void updatePhysicalKeys() {
        PhysicalKeys.update(
                input.keyDown(87),   // W
                input.keyDown(65),   // A
                input.keyDown(83),   // S
                input.keyDown(68),   // D
                input.keyDown(32));  // Space
    }

    /**
     * 每帧同步原版侧边栏的抑制状态。
     *
     * <p>判定放在客户端（模块开关 + NoRender 开关），这里只执行：避免 UI 层直接依赖某个模块类，
     * 同时保证模块关闭后的第一帧就把显示槽恢复。
     */
    private void syncVanillaScoreboard() {
        NocturneClient client = NocturneClient.get();
        if (client == null || client.gameBridge() == null) {
            return;
        }
        try {
            client.gameBridge().setVanillaScoreboardHidden(client.hideVanillaScoreboard());
        } catch (Throwable ignored) {
            // 抑制失败只意味着原版侧边栏还在；不能让它打断整帧输入/绘制。
        }
    }

    private void drawFrame() {
        // TEMP-DIAG（定位界面不显示后删除）：界面打开时每秒打一次真实下发次数。
        long diagNow = System.currentTimeMillis();
        boolean guiOpen = gui.isOpen();
        // 世界覆盖层（ESP/Tracers/NameTags/StorageESP…）与界面无关，必须单独计入：
        // 界面关着时本方法此前直接 return，于是 beginFrame() 根本不执行、覆盖层一帧都不画——
        // 世界里什么都看不到，日志里也没有半句线索。
        if (!guiOpen && !hasWorldOverlays() && hudLines.isEmpty()) {
            return;
        }
        Integer wantedScale = Integer.getInteger("nocturne.ui.scale", 0);
        if (wantedScale != null && wantedScale != configuredScale) {
            renderer.setScaleOverride(wantedScale);
            configuredScale = wantedScale;
        }
        try {
            // beginFrame 也可能抛（GL 句柄缺失/驱动异常）：放进 try 才能保证 finally 的
            // endFrame 一定执行，把已压入的矩阵栈按实际层数回滚。
            renderer.beginFrame();
            if (renderer.width() <= 0 || renderer.height() <= 0) {
                // 尺寸尚未解析时不能创建/恢复一个 0×0 裁剪区；那会把游戏自身整帧裁黑。
                return;
            }
            // 视口尺寸只有在 beginFrame 之后才是本帧的真值（GlRenderer 在那一步才从 GL 读回视口）。
            // 这里再同步一次：输入处理用上一帧尺寸可以接受，但绘制尺寸必须是当帧的——否则固定
            // 管线后端会按 0 尺寸布局，整个界面等于空框。
            gui.setViewport(renderer.width(), renderer.height());
            // 世界覆盖物画在最底下：GUI 菜单要盖住它们，否则 ESP 的框会糊在面板上。
            long overlayStart = System.nanoTime();
            frameEffects.render(overlayDraw, NocturneClient.get());
            drawWorldOverlays();
            lastOverlayNanos = System.nanoTime() - overlayStart;
            drawHudLines();
            if (guiOpen) {
                gui.render(renderer);
            }
        } finally {
            renderer.endFrame();
            if (System.getProperty("nocturne.ui.debug") != null) {
                long now = System.currentTimeMillis();
                if (now - batchLoggedAt >= 1000L) {
                    batchLoggedAt = now;
                    if (renderer instanceof GlRenderer) {
                        int[] counts = ((GlRenderer) renderer).takeBatchCounts();
                        int[] uploads = ((GlRenderer) renderer).takeUploadCounts();
                        System.out.println("[nocturne] draw batches/s=" + counts[0]
                                + " vertices/s=" + counts[1]
                                + " tex batches/s=" + counts[2]
                                + " text/s=" + MinecraftTextRenderer.takeDrawCalls()
                                + " upload full/s=" + uploads[0] + " sub/s=" + uploads[1]);
                    } else if (renderer instanceof ModernRenderer) {
                        // 世界覆盖层耗时：玩家报「开渲染框后人物飞速移动」时，这一行直接区分
                        // 「覆盖层自己画得太慢」与「游戏 tick 在追赶」。
                        System.out.println("[nocturne] world overlay cost=" + (lastOverlayNanos / 1000L)
                                + "us modules=" + worldOverlayCount());
                        // gl-core 没有顶点批处理层：这里的小批次是「同色几何合并后」的真实下发次数，
                        // 与图元数的比值直接反映合批收益。
                        int[] counts = ((ModernRenderer) renderer).takeDrawCounts();
                        System.out.println("[nocturne] gl-core draw/s=" + counts[0]
                                + " vertices/s=" + counts[1]
                                + " text/s=" + MinecraftTextRenderer.takeDrawCalls());
                    }
                }
            }
        }
    }


    /** 在屏幕左上角按注册顺序绘制 HUD 行；单行异常不影响其它行或界面。 */
    private void drawHudLines() {
        if (hudLines.isEmpty()) {
            return;
        }
        dev.nocturne.client.module.modules.render.hud.TextGuiSettingsModule textGui =
                dev.nocturne.client.module.modules.render.hud.TextGuiSettingsModule.current();
        boolean styled = textGui != null && textGui.isEnabled();
        // 所有 HUD 行共用一个字号：旧实现只让 Text GUI 自己那一行读 Scale，其余写死 10f。
        // 26.x/1.20+ 的逻辑宽度约 427（1.8.9 是 854），同一个 10f 会占两倍屏宽，
        // 因此基础字号按逻辑宽度归一，再乘玩家的 Scale。
        float size = hudTextSize(styled ? textGui.scaleFactor() : 1f);
        float height = overlayDraw.textHeight(size);
        int color = styled ? textGui.color() : 0xFFFFFFFF;
        boolean background = !styled || textGui.background();
        boolean shadow = styled && textGui.shadow();
        float y = 4f;
        for (java.util.Map.Entry<String, java.util.function.Supplier<String>> entry
                : new java.util.ArrayList<java.util.Map.Entry<String, java.util.function.Supplier<String>>>(
                        hudLines.entrySet())) {
            String text;
            try {
                text = entry.getValue().get();
            } catch (Throwable t) {
                continue;
            }
            if (text == null || text.isEmpty()) {
                continue;
            }
            for (String line : text.split("\\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                float width = overlayDraw.textWidth(line, size);
                if (background) {
                    overlayDraw.roundedRect(3f, y - 1f, width + 6f, height + 3f, 2f, 0x90000000);
                }
                if (shadow) {
                    overlayDraw.text(line, 7f, y + 2f, size, 0x80000000);
                }
                overlayDraw.text(line, 6f, y + 1f, size, color);
                y += height + 5f;
            }
        }
    }

    /**
     * HUD 基础字号：按后端逻辑宽度归一到 854 宽的设计基准，再乘玩家 Scale。
     *
     * @param userScale Text GUI 的 Scale（&le;0 按 1 处理）
     * @return 不小于 3 的字号
     */
    private float hudTextSize(float userScale) {
        float width = renderer.width();
        float base = 10f;
        if (width > 0f && width < 854f) {
            base = 10f * width / 854f;
        }
        float scale = userScale > 0f ? userScale : 1f;
        return Math.max(3f, base * scale);
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
        long beforeDrawCalls = overlayDraw.drawCalls();
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
        // 模块被调用不代表有绘制：空世界、实体都在视口外时不该报告“已画出”。
        long calls = overlayDraw.drawCalls() - beforeDrawCalls;
        if (calls > 0 && !worldOverlayDrawLogged) {
            worldOverlayDrawLogged = true;
            logWorldOverlay("drawing (" + renderer.width() + "x" + renderer.height() + "): "
                    + calls + " draw call(s) from " + drawn + " module(s), enabled=["
                    + enabledOverlayNames(client) + "]");
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
