package dev.noturne.client.module;

import dev.noturne.client.hud.HudSink;
import dev.noturne.client.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 所有功能模块的基类。
 *
 * <p>{@code enabled} 是“已启用（armed）”标志：它由 GUI 切换、并能跨重启保留。模块是否
 * 真正运行由 {@link ModuleRegistry} 决定——注册表只在游戏处于“动作有意义”的状态时才
 * 驱动模块执行，并在状态转换时对称地挂起/恢复副作用（见 {@link #onSuspend()}）。
 */
public abstract class Module {

    /** 已启用标志；由 GUI 线程写入、游戏线程读取，故声明为 volatile。 */
    private volatile boolean enabled;
    /** 本模块拥有的全部设置项，按注册顺序保存。 */
    private final List<Value<?>> values = new ArrayList<Value<?>>();

    /**
     * 注册一个设置项并原样返回，便于子类在字段初始化表达式里直接写
     * {@code add(new XValue(...))}，省去额外的字段赋值语句。
     */
    protected final <V extends Value<?>> V add(V value) {
        values.add(value);
        return value;
    }

    /** 本模块拥有的设置项，按注册顺序返回只读视图。 */
    public final List<Value<?>> values() {
        return Collections.unmodifiableList(values);
    }

    /** 模块名；在注册表内必须唯一。 */
    public abstract String name();

    /** 模块所属的功能分类，用于 GUI 分组。 */
    public abstract Category category();

    /** 返回当前是否已启用。 */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置启用状态；仅在状态实际变化时触发 {@link #onEnable()}/{@link #onDisable()}。
     *
     * <p>顺序契约：<b>先执行生命周期钩子，钩子正常返回后才提交 {@code enabled}</b>。因此钩子抛出
     * 异常时 {@code enabled} 保持原值、异常向上传播，再次调用本方法会重试安装；不会出现
     * 「标志已置位但副作用没装上，且因值未变化被永久短路」的不可自愈状态。
     *
     * <p>线程安全：本方法与 {@link #toggle()} 共用同一把对象锁，判重-写入与读-改-写都不可分割；
     * {@code enabled} 同时为 volatile，供 {@link #isEnabled()} 无锁读取。
     *
     * @param value 目标状态
     */
    public final synchronized void setEnabled(boolean value) {
        // 值未变化时不回调生命周期钩子，避免重复启用导致的副作用叠加与重复注册。
        if (this.enabled == value) {
            return;
        }
        if (value) {
            onEnable();
        } else {
            onDisable();
        }
        this.enabled = value;
    }

    /** 在启用/禁用之间切换；读-改-写在 {@link #setEnabled(boolean)} 的对象锁内完成。 */
    public final synchronized void toggle() {
        setEnabled(!enabled);
    }

    /** 模块被启用时调用。 */
    protected void onEnable() {
    }

    /** 模块被禁用时调用；必须撤销 {@link #onEnable()} 所做的一切副作用。 */
    protected void onDisable() {
        // 默认空实现：子类按需覆写，但必须与 onEnable 成对撤销副作用。
    }

    /**
     * 注册表激活闸门关闭（进入主菜单 / 死亡界面）时调用。
     *
     * <p>只对闸门关闭那一刻 {@code enabled} 为 true 且 {@link #runsWhileInactive()} 为 false 的模块
     * 调用；默认实现委托 {@link #onDisable()}，即撤销副作用但<b>不</b>改变 {@code enabled}
     * 标志——玩家重新进入世界时注册表会对称调用 {@link #onResume()} 重新装上副作用。
     * 子类只有需要与 {@code onDisable} 不同的挂起语义时才覆写本方法，并必须与
     * {@link #onResume()} 成对。
     */
    protected void onSuspend() {
        onDisable();
    }

    /** 激活闸门重新打开时调用，与 {@link #onSuspend()} 成对；默认委托 {@link #onEnable()}。 */
    protected void onResume() {
        onEnable();
    }

    /** 在模块已启用且注册表允许运行时，每个游戏 tick 调用一次。 */
    public void onTick() {
    }

    /**
     * 本模块是否豁免注册表的激活闸门；默认 {@code false}。
     *
     * <p>豁免后：闸门关闭期间注册表仍会驱动本模块的 {@link #onTick()}，也不会对其执行
     * {@link #onSuspend()}。只有触发条件本身就落在“闸门关闭”这种状态里的模块才应覆写为
     * {@code true}（例如自动重生模块——它的触发条件就是玩家已死亡）。此类模块的
     * {@code onTick} 必须自行完成全部前置判空，因为注册表不再替它把关。
     *
     * @return true 表示不受激活闸门约束
     */
    public boolean runsWhileInactive() {
        return false;
    }

    /**
     * HUD 行汇被安装、替换或清除时由客户端转发。
     *
     * <p>默认空实现——只有需要向 HUD 发布文本的模块（{@link HudModule}）才需要覆写。覆写者必须
     * 把自身的行从 {@code previous} 迁移到 {@code current}：否则“汇尚未安装时启用”会导致模块已
     * 显示为开启却永远不出现在 HUD 上，而“汇被替换”会让旧汇里的行变成无法移除的孤儿。
     *
     * @param previous 之前的汇；从未安装过时为 {@code null}
     * @param current  新的汇；被清除时为 {@code null}
     */
    public void onHudSinkChanged(HudSink previous, HudSink current) {
    }

    /** 调试用文本表示。 */
    @Override
    public String toString() {
        return name() + "[" + category() + (enabled ? ",on" : ",off") + "]";
    }
}
