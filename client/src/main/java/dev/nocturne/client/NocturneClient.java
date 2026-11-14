package dev.nocturne.client;

import dev.nocturne.client.config.ModuleConfigStore;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.hud.HudSink;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.runtime.NocturneRuntime;

import java.lang.instrument.Instrumentation;

/**
 * 客户端单例：持有事件总线与模块注册表，是 agent / mod-loader 各入口点移交控制权的唯一位置。
 *
 * <p>{@link #boot} 具备幂等性——多个入口点可能先后触发（例如 mod loader 初始化器与 attach），
 * 只有第一个会真正安装客户端。
 */
public final class NocturneClient {

    /** 全局唯一实例；首次 {@link #boot} 成功后写入，此后只读。 */
    private static volatile NocturneClient instance;

    /** 事件总线，供各模块与钩子发布/订阅事件。 */
    private final EventBus eventBus = new EventBus();
    /** 模块注册表，保存本客户端注册的全部模块。 */
    private final ModuleRegistry modules = new ModuleRegistry();
    /** 由 agent 入口传入的 JVM 插桩句柄，用于字节码级钩子；可被后到的 agent 补写（volatile）。 */
    private volatile Instrumentation instrumentation;
    /** HUD 行输出汇，由 UI 模块注入；未安装前为 {@code null}。 */
    private volatile HudSink hudSink;
    /** 复合 HUD 元素输出汇；与文本行汇并行，互不争用 id。 */
    private volatile dev.nocturne.client.hud.HudElementSink hudElementSink;
    /** 实体渲染期间的后端状态操作；由 GuiOverlay 安装。 */
    private volatile dev.nocturne.client.render.RenderCapabilities renderCapabilities;
    /** 指向运行中游戏的反射桥；非游戏 JVM 中为 {@code null}。 */
    private volatile dev.nocturne.client.game.GameBridge gameBridge;
    /** 模块共享的语义动作面；游戏桥安装后绑定。 */
    private volatile dev.nocturne.client.action.ActionApi actions;
    /** 全局旋转仲裁器；所有模块的朝向请求都经过它。 */
    private final dev.nocturne.client.control.RotationController rotations =
            new dev.nocturne.client.control.RotationController();
    /** 全局目标选择器；无状态，仅读当前世界。 */
    private final dev.nocturne.client.control.TargetManager targets =
            new dev.nocturne.client.control.TargetManager();
    /** 双向包闸门；构造早于事件安装，保证首个包钩子即可安全访问。 */
    private final dev.nocturne.client.control.PacketController packets =
            new dev.nocturne.client.control.PacketController(this);
    /** 系统加载器上的游戏事件汇；供跨加载器 sink 桥接使用。 */
    private volatile dev.nocturne.client.event.GameplayEvents gameplayEvents;
    /** 世界空间 3D 渲染器；{@link #install()} 时创建，未 boot 完成时为 {@code null}。 */
    private volatile dev.nocturne.client.render3d.World3DRenderer world3d;
    /** 模块开关/热键/设置项的持久化；{@link #install()} 中创建。 */
    private volatile ModuleConfigStore moduleConfig;
    /** 是否已自销毁（panic）：置位后不再接受任何驱动，见 {@link #panic()}。 */
    private volatile boolean panicked;

    private NocturneClient(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /** 由 UI 模块提供的 HUD 行汇；未安装前为 {@code null}。 */
    public HudSink hudSink() {
        return hudSink;
    }

    /** 复合 HUD 元素汇；UI 尚未安装时为 {@code null}。 */
    public dev.nocturne.client.hud.HudElementSink hudElementSink() {
        return hudElementSink;
    }

    public dev.nocturne.client.render.RenderCapabilities renderCapabilities() {
        return renderCapabilities;
    }

    public void setRenderCapabilities(dev.nocturne.client.render.RenderCapabilities capabilities) {
        this.renderCapabilities = capabilities;
    }

    /**
     * 安装（或清除，传入 {@code null}）HUD 行汇。
     *
     * <p>通常由 UI 模块在自身初始化时调用；模块可在无汇的情况下安全启用。
     *
     * <p>汇被安装或替换时，客户端会把该变化转发给所有已注册模块
     * （{@link Module#onHudSinkChanged(HudSink, HudSink)}），使此前因汇缺失而无法登记的 HUD 行
     * 补登记、已挂载的行从旧汇迁移到新汇，避免行丢失或旧汇残留孤儿行。
     *
     * @param sink 新汇；{@code null} 表示清除
     */
    public void setHudSink(HudSink sink) {
        HudSink previous;
        synchronized (this) {
            previous = this.hudSink;
            this.hudSink = sink;
        }
        if (previous == sink) {
            // 同一汇重复安装无需迁移
            return;
        }
        for (Module module : modules.all()) {
            try {
                module.onHudSinkChanged(previous, sink);
            } catch (Throwable t) {
                // 单个模块迁移失败不得阻止其余模块，也不能静默丢弃（迁移失败意味着 HUD 行会丢）
                log("hud sink migration failed for module '" + module.name() + "': " + t);
            }
        }
    }

    /** 安装或替换复合 HUD 元素汇，并把变化逐模块隔离转发。 */
    public void setHudElementSink(dev.nocturne.client.hud.HudElementSink sink) {
        dev.nocturne.client.hud.HudElementSink previous;
        synchronized (this) {
            previous = this.hudElementSink;
            this.hudElementSink = sink;
        }
        if (previous == sink) {
            return;
        }
        for (Module module : modules.all()) {
            try {
                module.onHudElementSinkChanged(previous, sink);
            } catch (Throwable t) {
                log("hud element sink migration failed for module '" + module.name() + "': " + t);
            }
        }
    }

    /** 指向运行中游戏的反射桥；非游戏 JVM 中为 {@code null}。 */
    public dev.nocturne.client.game.GameBridge gameBridge() {
        return gameBridge;
    }

    /** 模块共享的语义动作面；游戏桥安装前为 {@code null}。 */
    public dev.nocturne.client.action.ActionApi actions() {
        return actions;
    }

    /** 全局旋转仲裁器。 */
    public dev.nocturne.client.control.RotationController rotations() {
        return rotations;
    }

    /**
     * 是否要抑制原版侧边栏（保证默认只显示一套计分板）。
     *
     * <p>来源是 Better Scoreboard 模块；模块层正在重写，当前没有任何内置模块，
     * 因此正常情况下返回 {@code false}，保留原版侧边栏。
     *
     * @return {@code true} 表示原版侧边栏应隐藏
     */
    public boolean hideVanillaScoreboard() {
        Module better = modules.get("Better Scoreboard");
        return better != null && better.isEnabled();
    }

    /** 全局目标选择器。 */
    public dev.nocturne.client.control.TargetManager targets() {
        return targets;
    }

    /** 包捕获、原序释放与时序敏感直发入口。 */
    public dev.nocturne.client.control.PacketController packets() {
        return packets;
    }

    /** 系统加载器上的游戏事件汇；未 boot 完成时为 {@code null}。 */
    public dev.nocturne.client.event.GameplayEvents gameplayEvents() {
        return gameplayEvents;
    }

    /** 模块配置存储；未 boot 完成时为 {@code null}。 */
    public ModuleConfigStore moduleConfig() {
        return moduleConfig;
    }

    /**
     * 通知配置层「模块开关/设置项/热键发生了成功变更」。
     *
     * <p>由 {@link Module#setEnabled(boolean)} 与 {@code Value#set} 调用；存储内部去抖后落盘，
     * 因此 GUI 连续拖动滑块也只写一次文件。
     */
    public void markConfigDirty() {
        ModuleConfigStore store = moduleConfig;
        if (store != null) {
            store.requestSave();
        }
    }

    /**
     * 世界空间 3D 渲染器；未 boot 完成时为 {@code null}。
     *
     * <p>ui 在装配绘制栈时通过 {@code setBackend} / {@code markUnavailable} 写入后端能力，
     * 模块与诊断只读 {@code capability()}。
     */
    public dev.nocturne.client.render3d.World3DRenderer world3d() {
        return world3d;
    }

    /**
     * 安装指向运行中游戏的反射桥。
     *
     * <p>由游戏侧入口在确认游戏已加载后调用；等待游戏出现期间可保持为 {@code null}。
     */
    public void setGameBridge(dev.nocturne.client.game.GameBridge bridge) {
        this.gameBridge = bridge;
        if (bridge == null) {
            actions = null;
            return;
        }
        try {
            actions = dev.nocturne.client.action.ActionBinder.create(bridge).api(bridge);
        } catch (Throwable t) {
            actions = null;
            log("action contract binding failed: " + t);
        }
    }

    /**
     * 取得（必要时创建并安装）客户端单例。
     *
     * <p>可安全并发调用：多个入口点同时触发时，只有第一个调用会真正构造并安装，
     * 其余调用直接返回同一实例。
     *
     * <p>插桩句柄语义：首次创建时使用的 {@code instrumentation} 会写入实例；若实例已由模组加载器
     * 以 {@code null} 创建、之后 agent 才 attach 并以非 {@code null} 调用本方法，句柄会被补写进
     * 现有实例（首个非 {@code null} 者胜出），从而不会永久丢失插桩能力。
     *
     * @param instrumentation agent 提供的插桩句柄；无插桩场景（如测试）可为 {@code null}
     * @return 当前 JVM 中已安装的客户端单例，非 {@code null}
     */
    public static NocturneClient boot(Instrumentation instrumentation) {
        NocturneClient local = instance;
        // 无锁快路径：实例已存在时避免加锁；volatile 保证可见性。
        if (local == null) {
            synchronized (NocturneClient.class) {
                local = instance;
                // 双重检查：可能在等待锁期间已被其他线程安装，此时复用而不重复安装。
                if (local == null) {
                    local = new NocturneClient(instrumentation);
                    // 先发布单例再 install：注册模块时调用的构造器/钩子通过 NocturneClient.get()
                    // 立刻能拿到实例，而不是整个 install 期间都为 null
                    instance = local;
                    try {
                        local.install();
                    } catch (RuntimeException | Error failure) {
                        // 安装失败时撤回发布，避免后续 boot() 复用一个半初始化的实例
                        instance = null;
                        throw failure;
                    }
                }
            }
        }
        // 后到的 agent 句柄补写（模组先启动、随后 attach 的路径）；已持有句柄时为无操作
        local.adoptInstrumentation(instrumentation);
        return local;
    }

    /** 客户端是否已在本 JVM 中安装。 */
    public static boolean isRunning() {
        return instance != null;
    }

    /** 已安装的客户端；{@link #boot} 之前为 {@code null}。 */
    public static NocturneClient get() {
        return instance;
    }

    /**
     * 把后到的插桩句柄补写进已存在的实例；已持有句柄或 {@code candidate} 为 {@code null} 时为无操作。
     *
     * <p>句柄一旦写入不再被覆盖（首个非 {@code null} 者胜出），避免后到的旁观者把 agent 句柄换掉。
     */
    private synchronized void adoptInstrumentation(Instrumentation candidate) {
        if (candidate != null && this.instrumentation == null) {
            this.instrumentation = candidate;
            log("late instrumentation adopted");
        }
    }

    /** 注册已完成移植的内置模块；必须在配置读取前执行。 */
    private void registerModules() {
        modules.register(new dev.nocturne.client.module.modules.combat.KillAuraModule());
        modules.register(new dev.nocturne.client.module.modules.render.Esp2DModule());
        modules.register(new dev.nocturne.client.module.modules.render.NameTagsModule());
        modules.register(new dev.nocturne.client.module.modules.render.HealthModule());
        modules.register(new dev.nocturne.client.module.modules.render.JumpCircleModule());
        modules.register(new dev.nocturne.client.module.modules.render.HatModule());
        modules.register(new dev.nocturne.client.module.modules.render.BlockHighlightModule());
        modules.register(new dev.nocturne.client.module.modules.render.TntTimerModule());
        modules.register(new dev.nocturne.client.module.modules.render.LogoutSpotsModule());
        modules.register(new dev.nocturne.client.module.modules.render.BlockEspModule());
        modules.register(new dev.nocturne.client.module.modules.render.HoleEspModule());
        modules.register(new dev.nocturne.client.module.modules.render.XrayModule());
        modules.register(new dev.nocturne.client.module.modules.render.NoGhostBlocksModule());
        modules.register(new dev.nocturne.client.module.modules.render.TrajectoriesModule());
    }

    /** 执行实际安装：装载配置并挂接运行时回调。仅在持锁的 {@link #boot} 内调用，保证只执行一次。 */
    private void install() {
        registerModules();
        // 只注册已移植模块；框架（注册表、配置、键位、tick）保留。
        // 先加载用户配置，再处理调试开关；顺序反过来会把用户显式关闭的模块重新打开。
        moduleConfig = new ModuleConfigStore(modules);
        moduleConfig.load();
        dev.nocturne.client.module.ModuleKeybinds.install(this);
        gameplayEvents = dev.nocturne.client.event.GameplayEvents.install(this);
        installScrollEvents();
        world3d = new dev.nocturne.client.render3d.World3DRenderer(this);
        rotations.install(this);
        dev.nocturne.client.runtime.NocturneRuntime.addListener(
                new dev.nocturne.client.runtime.ModuleTicker(this));
        log("client installed (modules=" + modules.size() + ")");
        // 后续阶段：映射初始化、渲染/输入钩子、HUD。
    }

    /** 只把 JDK 回调交给 bootstrap 唯一的汇；由客户端自己的加载器构造事件。 */
    private void installScrollEvents() {
        try {
            Class<?> sink = Class.forName("dev.nocturne.bootstrap.KeySink", true, null);
            sink.getMethod("setScrollListener", java.util.function.DoubleConsumer.class).invoke(null,
                    (java.util.function.DoubleConsumer) delta -> {
                        if (!panicked) eventBus.post(new dev.nocturne.client.event.ScrollEvent(delta));
                    });
        } catch (Throwable failure) {
            log("scroll event listener unavailable: " + failure);
        }
    }

    /** 全局事件总线。 */
    public EventBus events() {
        return eventBus;
    }

    /** 全局模块注册表。 */
    public ModuleRegistry modules() {
        return modules;
    }

    /** 本客户端持有的插桩句柄；可能为 {@code null}（无 agent 场景）。 */
    public Instrumentation instrumentation() {
        return instrumentation;
    }

    /**
     * 关闭客户端：禁用所有已注册模块，使其释放资源并撤销 HUD 等副作用。
     *
     * <p>单例引用不会被清除，{@link #isRunning()} 在调用后仍为 {@code true}。
     *
     * <p>线程安全：可能与 GUI 线程点击模块开关并发执行。{@link Module#setEnabled(boolean)} 自身在
     * 模块对象锁上串行化（先跑钩子、成功后才提交标志），因此不会产生重复的生命周期回调。
     * 逐模块隔离异常：单个模块的 {@code onDisable} 失败不会让其余模块停留在已启用状态。
     */
    public synchronized void shutdown() {
        // 必须在禁用任何模块之前落盘：panic 的语义是「看起来没注入过」，如果把随后产生的
        // 全关状态写进配置，下次启动会把玩家此前开着的模块全部当成主动关闭。
        ModuleConfigStore store = moduleConfig;
        if (store != null) {
            store.flushAndClose();
        }
        for (Module module : modules.all()) {
            try {
                module.setEnabled(false);
            } catch (Throwable t) {
                log("module '" + module.name() + "' failed to disable on shutdown: " + t);
            }
        }
        log("client shut down");
    }

    /**
     * 自销毁（panic）：把客户端从"正在运行"变成"看起来没注入过"。
     *
     * <p>做三件事，顺序有讲究：
     * <ol>
     *   <li>禁用全部模块（{@link #shutdown()}）——每个模块释放自己的副作用；</li>
     *   <li>关掉模块驱动闸门（{@code ModuleRegistry.setActive(false)}）——tick 不再进模块；</li>
     *   <li>清空 bootstrap 层分发器——GUI 不再绘制、开关键不再响应、输入不再轮询。</li>
     * </ol>
     *
     * <p><b>不可逆</b>：agent 无法从目标 JVM 里卸载自己，所以"恢复"只能靠重启游戏。这是有意的——
     * 一个还能被同一条按键唤回来的"自销毁"没有意义。
     *
     * <p>线程安全：可能从 GUI 线程（点模块开关）或模块 tick 线程调用，故加锁并置一次性标志。
     */
    public synchronized void panic() {
        if (panicked) {
            return;
        }
        panicked = true;
        // 1) 先关闸门：分发器与**全部注入钩子/绘制入口**立即变成放行空操作。
        //    必须排在还原动作之前——否则还原执行期间，其它监听器/绘制钩子还在并发跑，
        //    会把刚恢复好的输入状态再写坏（真机：panic 后视角/光标永久失效的另一条竞态）。
        //    拆除动作与 Render 线程的帧回调曾经并发（1.16.5 WER dump：拆 GL sink 时
        //    Render 线程仍在帧钩子里做 GL，疑似 use-after-free）。闸门是 volatile，跨加载器共享。
        NocturneRuntime.kill();
        // 2) 再把输入还原投递到**游戏主线程**并限时等待。
        //    FrameDispatcher.dispatch 即使在 KILLED 下也会先执行投递的帧任务，所以还原不会丢；
        //    分发循环本身在 KILLED 时立即 break，后续监听器不再执行。
        //    MouseHandler.grabMouse / Mouse.setGrabbed 只能在持有游戏上下文的线程上调用；
        //    本方法可能从 GUI 线程或模块线程进来。
        NocturneRuntime.restoreInputOnGameThread(1200L);
        // 钩子汇是 bootstrap 里的另一份状态（游戏线程读的是它），必须同样置位。
        dev.nocturne.client.bootstrap.GameplayHook.kill();
        packets.onPanic();
        if (gameplayEvents != null) {
            gameplayEvents.discardPendingReceive();
        }
        // 3) 状态层同步收尾：模块停用（GL 还原留在持有上下文的线程上）、驱动闸门关闭、输入放开。
        //    这些必须在 panic() 返回前完成——调用方（GUI / 模块 tick）依赖同步结论，
        //    而此刻钩子已经不会再有并发进入。
        shutdown();
        modules.setActive(false);
        // 输入占用必须显式停：blocked 由叠加层每帧刷新，清空分发器后没人再刷新它——若那一刻界面
        // 正开着，游戏侧输入入口就被永久短路（点不动、按不动、滚不动）。
        dev.nocturne.client.runtime.InputBlock.disable();
        // 4) 拆分发器（帧监听器、绘制上下文汇、transformer 解绑）放到专用线程：
        //    它由渲染线程驱动，绝不能在渲染线程上边拆边被调用。解绑动作登记在分发器的
        //    shutdown actions 里，clear() 保证「先解绑 transformer、再清状态」。
        Thread teardown = new Thread(new Runnable() {
            @Override
            public void run() {
                NocturneRuntime.shutdown();
            }
        }, "nocturne-teardown");
        teardown.setDaemon(true);
        teardown.start();
        log("panic: client disabled — no GUI, no modules, no per-frame work;"
                + " restart the game to bring it back");
    }

    /** @return 是否已自销毁（诊断与日志用） */
    public boolean isPanicked() {
        return panicked;
    }

    /** 以 {@code [nocturne]} 前缀输出日志，保持客户端日志易于过滤。 */
    private static void log(String message) {
        System.out.println("[nocturne] " + message);
    }
}
