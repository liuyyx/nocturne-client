package dev.noturne.ui.hud;

import dev.noturne.ui.render.Renderer;

/**
 * 单个屏幕读数元素（如 FPS、坐标、按键状态）。
 *
 * <p>元素以锚点（屏幕像素坐标）定位并自行绘制；绘制顺序与启用状态由 {@link HudManager} 统一管理，
 * 因此这里只暴露状态与位置，不关心层级。
 */
public abstract class HudElement {

    /** 元素唯一标识，用于 {@link HudManager#byId(String)} 查找；构造后不可变 */
    private final String id;
    /** 是否绘制该元素；默认启用，由 {@link #setEnabled(boolean)} 或模块开关驱动 */
    private boolean enabled = true;
    /** 绘制锚点 x，屏幕像素；由 {@link #setPosition(float, float)} 写入 */
    protected float x;
    /** 绘制锚点 y，屏幕像素；由 {@link #setPosition(float, float)} 写入 */
    protected float y;

    /**
     * 构造元素。
     *
     * @param id 元素标识，全局唯一，注册后不应再改变
     */
    protected HudElement(String id) {
        this.id = id;
    }

    /** @return 构造时传入的元素标识 */
    public final String id() {
        return id;
    }

    /** @return true 表示元素已启用，{@link HudManager} 会绘制它 */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置启用状态。
     *
     * @param value true 允许绘制
     */
    public final void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** @return 当前 x 坐标（屏幕像素） */
    public final float x() {
        return x;
    }

    /** @return 当前 y 坐标（屏幕像素） */
    public final float y() {
        return y;
    }

    /**
     * 设置元素位置。
     *
     * @param x 屏幕像素 x 坐标
     * @param y 屏幕像素 y 坐标
     */
    public final void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /**
     * 绘制本元素。
     *
     * @param renderer 渲染后端；调用方保证元素处于启用状态且坐标已设置
     */
    public abstract void render(Renderer renderer);
}
