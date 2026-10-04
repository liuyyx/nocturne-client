package dev.noturne.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持有全部已注册的 {@link Module}，并驱动它们的 tick 与激活生命周期。
 *
 * <p>激活闸门：仅当 {@code active} 为 true 时模块才会运行。调用方在“进入/离开带有存活
 * 玩家的世界”这两个转换点翻转该标志，使模块在主菜单和重生界面上停用，避免在此处产生
 * 明显的异常行为。
 *
 * <p>闸门转换的生命周期契约：{@link #setActive(boolean)} 不只是翻标志位——
 * <ul>
 *   <li>关闭时，对每个已启用且不豁免闸门的模块调用 {@link Module#onSuspend()}（默认即
 *       {@link Module#onDisable()}），从而<b>撤销副作用</b>（如 FullBright 写回的 gamma）
 *       但保留 {@code enabled} 标志；</li>
 *   <li>打开时，对上次被挂起的模块对称调用 {@link Module#onResume()} 重新装上副作用。</li>
 * </ul>
 * 豁免闸门的模块见 {@link Module#runsWhileInactive()}：它们既不挂起也照常 tick，
 * 因为其触发条件本身就只存在于闸门关闭的时段（例如自动重生）。
 *
 * <p>线程约束：注册表可被 GUI 线程与游戏线程并发访问，内部 map 与挂起集合都由对象锁保护；
 * {@link #tick()} 由游戏线程按固定节拍调用。生命周期钩子一律在<b>不</b>持有注册表锁的情况下
 * 调用（避免把外部代码锁进注册表锁），因此闸门转换应由游戏线程串行发起。
 */
public final class ModuleRegistry {

    /** 单个模块异常日志的限流窗口（毫秒）：同一模块在此窗口内最多打印一条异常日志。 */
    private static final long ERROR_LOG_INTERVAL_MS = 5000L;

    /** 按注册顺序保存的模块表，键为模块名；所有读写都在对象锁内进行。 */
    private final Map<String, Module> modules = new LinkedHashMap<String, Module>();
    /** 闸门关闭时被挂起的模块，待闸门打开时唤醒；受对象锁保护。 */
    private final List<Module> suspended = new ArrayList<Module>();
    /** 各模块上次打印异常的时间戳；值为单元素数组充当可变槽位，避免为每次 tick 分配对象。 */
    private final Map<Module, long[]> errorLogTimes = new ConcurrentHashMap<Module, long[]>();

    /**
     * 激活闸门；游戏线程读取、转换点写入，故为 volatile。
     *
     * <p>它与 {@link #active} 的 synchronized 访问互不排斥：volatile 保证无锁读取也能看到
     * 最新值，锁只用于保护 {@link #modules}。
     */
    private volatile boolean active;
    /** 内容版本号；每次成功注册递增，受对象锁保护 */
    private long revision;

    /**
     * 注册一个模块。
     *
     * @param module 待注册模块
     * @throws IllegalArgumentException 已存在同名模块时抛出
     */
    public synchronized void register(Module module) {
        String name = module.name();
        // 先判存在再放入，既保证同名唯一，也让迭代顺序等于注册顺序。
        if (modules.containsKey(name)) {
            throw new IllegalArgumentException("duplicate module name: " + name);
        }
        modules.put(name, module);
        revision++;
    }

    /**
     * 按名称查找模块；不存在时返回 {@code null}。
     *
     * <p>供配置持久化按名恢复启用状态，故查不到时必须返回 {@code null} 而非抛异常。
     */
    public synchronized Module get(String name) {
        return modules.get(name);
    }

    /** 返回全部模块的只读快照（按注册顺序）。 */
    public synchronized List<Module> all() {
        // 返回拷贝而非视图：否则调用方持有视图期间 GUI 注册新模块会抛 ConcurrentModificationException。
        return Collections.unmodifiableList(new ArrayList<Module>(modules.values()));
    }

    /**
     * 返回指定分类下的全部模块，保持注册顺序。
     *
     * <p>契约：GUI 渲染分组时应以本方法的返回值判断该分类是否有内容，返回空列表的分类
     * 必须跳过（不建列、不占位）；不得只按 {@link Category#values()} 无条件建列。
     */
    public synchronized List<Module> byCategory(Category category) {
        List<Module> out = new ArrayList<Module>();
        // 枚举身份比较（==）即可，Category 是枚举单例。
        for (Module module : modules.values()) {
            if (module.category() == category) {
                out.add(module);
            }
        }
        return out;
    }

    /** 已注册模块的数量。 */
    public synchronized int size() {
        return modules.size();
    }

    /**
     * 注册表内容版本号：每次成功 {@link #register(Module)} 递增，只增不减。
     *
     * <p>契约：本注册表只增不删（{@link Module} 没有对应的注销入口）。任何按分类或全量缓存
     * 结果的消费者（如 ClickGUI 的面板列表）应记录本值，发现变化后重新调用 {@link #all()} /
     * {@link #byCategory(Category)} 刷新，否则运行期新注册的模块不会出现在缓存里（幽灵模块）。
     *
     * @return 当前版本号；每次注册后必然大于之前读到的值
     */
    public synchronized long revision() {
        return revision;
    }

    /**
     * 在“允许已启用模块运行”的状态之间切换，并按契约挂起/唤醒已启用模块的副作用。
     *
     * <p>由客户端在进入/离开带存活玩家的世界时调用；不改变任何模块的 {@code enabled}，
     * 因此重新激活后此前已启用的模块会自动恢复运行并重新装上副作用。
     */
    public void setActive(boolean value) {
        List<Module> toSuspend = null;
        List<Module> toResume = null;
        synchronized (this) {
            // 与 Module.setEnabled 一致：仅在状态真正变化时继续，避免转换点重复触发造成语义歧义。
            if (this.active == value) {
                return;
            }
            this.active = value;
            if (value) {
                toResume = new ArrayList<Module>(suspended);
                suspended.clear();
            } else {
                toSuspend = new ArrayList<Module>();
                for (Module module : modules.values()) {
                    if (module.isEnabled() && !module.runsWhileInactive()) {
                        toSuspend.add(module);
                    }
                }
                suspended.clear();
                suspended.addAll(toSuspend);
            }
        }
        // 钩子在锁外调用：模块的挂起/恢复可能触达 UI（HUD 行增删），不应把外部代码锁进注册表锁
        if (toSuspend != null) {
            for (Module module : toSuspend) {
                try {
                    module.onSuspend();
                } catch (Throwable error) {
                    logModuleFailure("suspend", module, error);
                }
            }
        } else {
            for (Module module : toResume) {
                if (!module.isEnabled()) {
                    // 挂起期间被 GUI 禁用的模块不再唤醒
                    continue;
                }
                try {
                    module.onResume();
                } catch (Throwable error) {
                    logModuleFailure("resume", module, error);
                }
            }
        }
    }

    /** 当前是否允许已启用模块运行；无锁读取，可从任意线程调用。 */
    public boolean isActive() {
        return active;
    }

    /**
     * 驱动每个已启用模块的 {@link Module#onTick()}；未激活时只驱动豁免闸门的模块。
     *
     * <p>仅由游戏线程按固定节拍调用（推荐每 50ms 一次），故不加锁遍历——{@link #all()} 已在锁内
     * 取到快照，遍历期间的注册/注销不会影响本次分发。
     *
     * <p>逐模块异常隔离：单个模块抛出的任何 {@link Throwable} 只被记入限流日志，不会中止本 tick
     * 内其余模块。无需 {@code try}/{@code catch} 包裹本方法。
     */
    public void tick() {
        // 未激活（主菜单/死亡界面）时短路普通模块，避免它们对无效对象调用游戏 API
        boolean gateOpen = active;
        // all() 是快照：onTick 期间模块仍可能被 GUI 增删，快照可避免迭代中修改集合。
        for (Module module : all()) {
            if (!module.isEnabled()) {
                continue;
            }
            if (!gateOpen && !module.runsWhileInactive()) {
                continue;
            }
            try {
                module.onTick();
            } catch (Throwable error) {
                logModuleFailure("tick", module, error);
            }
        }
    }

    /**
     * 记录模块生命周期/驱动阶段的异常，按模块限流（同一模块每 {@value #ERROR_LOG_INTERVAL_MS}ms
     * 最多一条），避免每 tick 刷屏但同时保证故障可检索。
     */
    private void logModuleFailure(String phase, Module module, Throwable error) {
        long now = System.currentTimeMillis();
        long[] slot = errorLogTimes.get(module);
        if (slot == null) {
            slot = new long[]{0L};
            long[] existing = errorLogTimes.putIfAbsent(module, slot);
            if (existing != null) {
                slot = existing;
            }
        }
        synchronized (slot) {
            if (now - slot[0] < ERROR_LOG_INTERVAL_MS) {
                return;
            }
            slot[0] = now;
        }
        System.out.println("[noturne] module '" + module.name() + "' failed during " + phase + ": " + error);
        error.printStackTrace(System.out);
    }
}
