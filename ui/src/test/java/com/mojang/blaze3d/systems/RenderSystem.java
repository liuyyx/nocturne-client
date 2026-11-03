package com.mojang.blaze3d.systems;

/**
 * 测试替身：1.16–1.18 没有 {@code GuiComponent.enableScissor}，裁剪回退到这里的静态方法。
 *
 * <p>真实实现直接转发 {@code GL20.glScissor}：参数是**物理像素**、y 从帧缓冲底部算。
 * 替身把调用原样记下来，供测试核对坐标换算（逻辑像素 → 物理像素 + 垂直翻转）。
 */
public final class RenderSystem {

    /** 最近一次 enableScissor 的实参；未调用为 {@code null}。 */
    public static int[] lastScissor;

    /** enableScissor 调用次数。 */
    public static int enables;

    /** disableScissor 调用次数。 */
    public static int disables;

    private RenderSystem() {
    }

    /** 记录一次裁剪；约定参数顺序与真实方法一致：{@code (x, y, width, height)}。 */
    public static void enableScissor(int x, int y, int width, int height) {
        lastScissor = new int[]{x, y, width, height};
        enables++;
    }

    /** 记录一次裁剪关闭。 */
    public static void disableScissor() {
        disables++;
    }

    /** 清空记录。 */
    public static void reset() {
        lastScissor = null;
        enables = 0;
        disables = 0;
    }
}
