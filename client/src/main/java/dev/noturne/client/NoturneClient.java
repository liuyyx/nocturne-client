package dev.noturne.client;

import dev.noturne.client.event.EventBus;
import dev.noturne.client.hud.HudSink;
import dev.noturne.client.module.ModuleRegistry;

import java.lang.instrument.Instrumentation;

/**
 * 客户端单例：持有事件总线与模块注册表，是 agent / mod-loader 各入口点移交控制权的唯一位置。
 *
 * <p>{@link #boot} 具备幂等性——多个入口点可能先后触发（例如 mod loader 初始化器与 attach），
 * 只有第一个会真正安装客户端。
 */
public final class NoturneClient {

    /** 全局唯一实例；首次 {@link #boot} 成功后写入，此后只读。 */
    private static volatile NoturneClient instance;

    /** 事件总线，供各模块与钩子发布/订阅事件。 */
    private final EventBus eventBus = new EventBus();
    /** 模块注册表，保存本客户端注册的全部模块。 */
    private final ModuleRegistry modules = new ModuleRegistry();
    /** 由 agent 入口传入的 JVM 插桩句柄，用于字节码级钩子。 */
    private final Instrumentation instrumentation;
    /** HUD 行输出汇，由 UI 模块注入；未安装前为 {@code null}。 */
    private volatile HudSink hudSink;
    /** 指向运行中游戏的反射桥；非游戏 JVM 中为 {@code null}。 */
    private volatile dev.noturne.client.game.GameBridge gameBridge;

    private NoturneClient(Instrumentation instrumentation) {
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
     */
    public void setHudSink(HudSink sink) {
        this.hudSink = sink;
    }

    /** 指向运行中游戏的反射桥；非游戏 JVM 中为 {@code null}。 */
    public dev.noturne.client.game.GameBridge gameBridge() {
        return gameBridge;
    }

    /**
     * 安装指向运行中游戏的反射桥。
     *
     * <p>由游戏侧入口在确认游戏已加载后调用；等待游戏出现期间可保持为 {@code null}。
     */
    public void setGameBridge(dev.noturne.client.game.GameBridge bridge) {
        this.gameBridge = bridge;
    }

    /**
     * 取得（必要时创建并安装）客户端单例。
     *
     * <p>可安全并发调用：多个入口点同时触发时，只有第一个调用会真正构造并安装，
     * 其余调用直接返回同一实例。传入的 {@code instrumentation} 仅在首次创建时生效。
     *
     * @param instrumentation agent 提供的插桩句柄；无插桩场景（如测试）可为 {@code null}
     * @return 当前 JVM 中已安装的客户端单例，非 {@code null}
     */
    public static NoturneClient boot(Instrumentation instrumentation) {
        NoturneClient local = instance;
        // 无锁快路径：实例已存在时避免加锁；volatile 保证可见性。
        if (local == null) {
            synchronized (NoturneClient.class) {
                local = instance;
                // 双重检查：可能在等待锁期间已被其他线程安装，此时复用而不重复安装。
                if (local == null) {
                    local = new NoturneClient(instrumentation);
                    local.install();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 客户端是否已在本 JVM 中安装。 */
    public static boolean isRunning() {
        return instance != null;
    }

    /** 已安装的客户端；{@link #boot} 之前为 {@code null}。 */
    public static NoturneClient get() {
        return instance;
    }

    /** 执行实际安装：注册内置模块并打日志。仅在持锁的 {@link #boot} 内调用，保证只执行一次。 */
    private void install() {
        registerModules();
        log("client installed (modules=" + modules.size() + ")");
        // 后续阶段：映射初始化、渲染/输入钩子、HUD。
    }

    /** 客户端开箱即带的模块。 */
    private void registerModules() {
        modules.register(new dev.noturne.client.module.modules.WatermarkModule());
        modules.register(new dev.noturne.client.module.modules.FullBrightModule());
        modules.register(new dev.noturne.client.module.modules.SprintModule());
        modules.register(new dev.noturne.client.module.modules.AutoRespawnModule());
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
     */
    public void shutdown() {
        for (dev.noturne.client.module.Module module : modules.all()) {
            module.setEnabled(false);
        }
        log("client shut down");
    }

    /** 以 {@code [noturne]} 前缀输出日志，保持客户端日志易于过滤。 */
    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
