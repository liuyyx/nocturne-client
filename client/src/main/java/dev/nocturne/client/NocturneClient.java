package dev.nocturne.client;

import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.hud.HudSink;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;

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
    /** 指向运行中游戏的反射桥；非游戏 JVM 中为 {@code null}。 */
    private volatile dev.nocturne.client.game.GameBridge gameBridge;

    private NocturneClient(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /** 由 UI 模块提供的 HUD 行汇；未安装前为 {@code null}。 */
    public HudSink hudSink() {
        return hudSink;
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

    /** 指向运行中游戏的反射桥；非游戏 JVM 中为 {@code null}。 */
    public dev.nocturne.client.game.GameBridge gameBridge() {
        return gameBridge;
    }

    /**
     * 安装指向运行中游戏的反射桥。
     *
     * <p>由游戏侧入口在确认游戏已加载后调用；等待游戏出现期间可保持为 {@code null}。
     */
    public void setGameBridge(dev.nocturne.client.game.GameBridge bridge) {
        this.gameBridge = bridge;
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

    /** 执行实际安装：注册内置模块并打日志。仅在持锁的 {@link #boot} 内调用，保证只执行一次。 */
    private void install() {
        registerModules();
        log("client installed (modules=" + modules.size() + ")");
        // 后续阶段：映射初始化、渲染/输入钩子、HUD。
    }

    /** 客户端开箱即带的模块。 */
    private void registerModules() {
        modules.register(new dev.nocturne.client.module.modules.WatermarkModule());
        modules.register(new dev.nocturne.client.module.modules.FullBrightModule());
        modules.register(new dev.nocturne.client.module.modules.SprintModule());
        modules.register(new dev.nocturne.client.module.modules.AutoRespawnModule());
        modules.register(new dev.nocturne.client.module.modules.StorageEspModule());
        modules.register(new dev.nocturne.client.module.modules.EspModule());
        modules.register(new dev.nocturne.client.module.modules.TracersModule());
        modules.register(new dev.nocturne.client.module.modules.NameTagsModule());
        modules.register(new dev.nocturne.client.module.modules.ItemEspModule());
        modules.register(new dev.nocturne.client.module.modules.TrajectoriesModule());
        modules.register(new dev.nocturne.client.module.modules.ChamsModule());
        modules.register(new dev.nocturne.client.module.modules.XrayModule());
        modules.register(new dev.nocturne.client.module.modules.SearchModule());
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
        for (Module module : modules.all()) {
            try {
                module.setEnabled(false);
            } catch (Throwable t) {
                log("module '" + module.name() + "' failed to disable on shutdown: " + t);
            }
        }
        log("client shut down");
    }

    /** 以 {@code [nocturne]} 前缀输出日志，保持客户端日志易于过滤。 */
    private static void log(String message) {
        System.out.println("[nocturne] " + message);
    }
}
