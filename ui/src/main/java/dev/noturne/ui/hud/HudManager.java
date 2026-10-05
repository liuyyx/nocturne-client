package dev.noturne.ui.hud;

import dev.noturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HUD 元素注册表：持有全部 {@link HudElement}，维护其注册顺序，并控制整个 HUD 是否绘制。
 *
 * <p>位于渲染管线的最上层：每帧由客户端调用 {@link #render(Renderer)}，按注册顺序把已启用的元素
 * 委托给底层 {@link Renderer} 绘制。
 */
public final class HudManager {

    /** 已注册元素，按添加顺序保存；该顺序同时决定绘制层级（后添加者后绘制）。 */
    private final List<HudElement> elements = new ArrayList<HudElement>();

    /** HUD 总开关；为 false 时 {@link #render(Renderer)} 直接返回，不绘制任何元素。 */
    private boolean visible = true;

    /**
     * 注册一个 HUD 元素；同 id 已注册时替换之（与 HUD 汇「同 id 替换」的契约一致），
     * 否则追加到列表末尾。
     *
     * @param element 待注册元素；为 null 时无副作用
     */
    public void add(HudElement element) {
        if (element == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            if (java.util.Objects.equals(elements.get(i).id(), element.id())) {
                elements.set(i, element);
                return;
            }
        }
        elements.add(element);
    }

    /**
     * 移除首个与给定实例相等的元素。
     *
     * @param element 待移除元素；未注册时无副作用
     */
    public void remove(HudElement element) {
        if (element == null) {
            return;
        }
        // 按 id 移除，与 add 的「同 id 替换」同基准。add 会用新实例顶掉旧实例，而调用方手里
        // 往往还持有旧实例——按身份比会静默 no-op，元素实际仍留在列表里继续绘制。
        Object id = element.id();
        elements.removeIf(e -> java.util.Objects.equals(id, e.id()));
    }

    /**
     * 返回当前已注册元素的只读视图。
     *
     * <p>返回的是快照副本：渲染期间 supplier 回调增删元素（或调用方持有该视图）不会看到
     * 底层列表的并发改动，也不会因此抛 {@code ConcurrentModificationException}。
     *
     * @return 不可修改的列表副本，顺序即注册顺序
     */
    public List<HudElement> elements() {
        return Collections.unmodifiableList(new ArrayList<HudElement>(elements));
    }

    /**
     * 按 id 查找元素。
     *
     * @param id 目标标识，须与 {@link HudElement#id()} 相等
     * @return 首个匹配的元素；无匹配时为 null（GUI 层直接判空，无需 Optional 包装）
     */
    public HudElement byId(String id) {
        for (HudElement element : elements) {
            if (java.util.Objects.equals(element.id(), id)) {
                return element;
            }
        }
        return null;
    }

    /** @return HUD 总开关当前状态，true 表示本帧会绘制元素 */
    public boolean isVisible() {
        return visible;
    }

    /**
     * 设置 HUD 总开关。
     *
     * @param value true 允许绘制，false 时 {@link #render(Renderer)} 整体短路
     */
    public void setVisible(boolean value) {
        this.visible = value;
    }

    /**
     * 按注册顺序绘制所有已启用的元素。
     *
     * <p>后注册者后绘制，因此可借此形成层级关系（提示条应先于常驻读数注册）。
     *
     * @param renderer 本帧的渲染后端；调用方需保证已处于正确的矩阵与混合状态
     */
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        // 迭代快照：元素的 supplier 回调可能在渲染途中 add/remove，
        // 直接遍历底层列表会抛 ConcurrentModificationException 打断整帧 HUD
        HudElement[] snapshot = elements.toArray(new HudElement[0]);
        for (HudElement element : snapshot) {
            if (element.isEnabled()) {
                element.render(renderer);
            }
        }
    }
}
