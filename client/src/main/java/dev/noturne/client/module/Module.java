package dev.noturne.client.module;

import dev.noturne.client.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 所有功能模块的基类。
 *
 * <p>{@code enabled} 是“已启用（armed）”标志：它由 GUI 切换、并能跨重启保留。模块是否
 * 真正运行由 {@link ModuleRegistry} 决定——注册表只在游戏处于“动作有意义”的状态时才
 * 驱动模块执行。
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
     * @param value 目标状态
     */
    public final void setEnabled(boolean value) {
        // 值未变化时不回调生命周期钩子，避免重复启用导致的副作用叠加与重复注册。
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        if (value) {
            onEnable();
        } else {
            onDisable();
        }
    }

    /** 在启用/禁用之间切换。 */
    public final void toggle() {
        setEnabled(!enabled);
    }

    /** 模块被启用时调用。 */
    protected void onEnable() {
    }

    /** 模块被禁用时调用；必须撤销 {@link #onEnable()} 所做的一切副作用。 */
    protected void onDisable() {
        // 默认空实现：子类按需覆写，但必须与 onEnable 成对撤销副作用。
    }

    /** 在模块已启用且注册表处于激活状态时，每个游戏 tick 调用一次。 */
    public void onTick() {
    }

    /** 调试用文本表示。 */
    @Override
    public String toString() {
        return name() + "[" + category() + (enabled ? ",on" : ",off") + "]";
    }
}
