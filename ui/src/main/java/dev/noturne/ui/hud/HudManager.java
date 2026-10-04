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
     * 注册一个 HUD 元素，追加到列表末尾。
     *
     * @param element 待注册元素；重复添加同一实例会被保存多份
     */
    public void add(HudElement element) {
        elements.add(element);
    }

    /**
     * 移除首个与给定实例相等的元素。
     *
     * @param element 待移除元素；未注册时无副作用
     */
    public void remove(HudElement element) {
        elements.remove(element);
    }

    /**
     * 返回当前已注册元素的只读视图。
     *
     * @return 不可修改的列表视图；调用方不能借此增删元素，顺序即注册顺序
     */
    public List<HudElement> elements() {
        return Collections.unmodifiableList(elements);
    }

    /**
     * 按 id 查找元素。
     *
     * @param id 目标标识，须与 {@link HudElement#id()} 相等
     * @return 首个匹配的元素；无匹配时为 null（GUI 层直接判空，无需 Optional 包装）
     */
    public HudElement byId(String id) {
        for (HudElement element : elements) {
            if (element.id().equals(id)) {
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
        for (HudElement element : elements) {
            if (element.isEnabled()) {
                element.render(renderer);
            }
        }
    }
}
