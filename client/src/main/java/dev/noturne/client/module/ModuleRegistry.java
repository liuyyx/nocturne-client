package dev.noturne.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持有全部已注册的 {@link Module}，并驱动它们的 tick 与激活生命周期。
 *
 * <p>激活闸门：仅当 {@code active} 为 true 时模块才会运行。调用方在“进入/离开带有存活
 * 玩家的世界”这两个转换点翻转该标志，使模块在主菜单和重生界面上停用，避免在此处产生
 * 明显的异常行为。
 *
 * <p>线程约束：注册表可被 GUI 线程与游戏线程并发访问，所有公开方法均以
 * {@code synchronized} 保护内部 map；{@link #tick()} 由游戏线程每 tick 调用一次。
 */
public final class ModuleRegistry {

    /** 按注册顺序保存的模块表，键为模块名；所有读写都在对象锁内进行。 */
    private final Map<String, Module> modules = new LinkedHashMap<String, Module>();
    /**
     * 激活闸门；游戏线程读取、转换点写入，故为 volatile。
     *
     * <p>它与 {@link #active} 的 synchronized 访问互不排斥：volatile 保证无锁读取也能看到
     * 最新值，锁只用于保护 {@link #modules}。
     */
    private volatile boolean active;

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

    /** 返回指定分类下的全部模块，保持注册顺序。 */
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
     * 在“允许已启用模块运行”的状态之间切换。
     *
     * <p>由客户端在进入/离开带存活玩家的世界时调用；此处不改变任何模块的 {@code enabled}，
     * 因此重新激活后此前已启用的模块会自动恢复运行。
     */
    public synchronized void setActive(boolean value) {
        // 与 Module.setEnabled 一致：仅在状态真正变化时继续，避免转换点重复触发造成语义歧义。
        if (this.active == value) {
            return;
        }
        this.active = value;
    }

    /** 当前是否允许已启用模块运行；无锁读取，可从任意线程调用。 */
    public boolean isActive() {
        return active;
    }

    /**
     * 驱动每个已启用模块的 {@link Module#onTick()}；未激活时不做任何事。
     *
     * <p>仅由游戏线程每 tick 调用一次，故不加锁遍历——{@link #all()} 已在锁内取到快照。
     */
    public void tick() {
        // 未激活（主菜单/死亡界面）时短路，避免模块对无效对象调用游戏 API。
        if (!active) {
            return;
        }
        // all() 是快照：onTick 期间模块仍可能被 GUI 增删，快照可避免迭代中修改集合。
        for (Module module : all()) {
            if (module.isEnabled()) {
                module.onTick();
            }
        }
    }
}
