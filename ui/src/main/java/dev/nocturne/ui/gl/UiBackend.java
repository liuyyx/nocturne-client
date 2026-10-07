package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Renderer;

/**
 * 可绘制后端：在 {@link Renderer} 绘制表面之上补上每帧的准备与收尾。
 *
 * <p>目前有三个实现——面向固定管线（Minecraft ≤ 1.12、LWJGL2）的 {@link GlRenderer}、
 * 面向 OpenGL 3.2 核心 profile（1.13+ / 26.x、LWJGL3）的 {@link ModernRenderer}、
 * 以及不分代的 Skija 实现 {@link SkijaBackend}。
 * 将来接入游戏的 Vulkan 设备时，Vulkan 后端可以直接挂在同一接口下，
 * 这也是覆盖层面向本类型而非某个具体渲染器的原因。
 */
public interface UiBackend extends Renderer {

    /** 应用每帧的 GL 状态；在绘制组件树之前调用一次。 */
    void beginFrame();

    /** 释放 {@link #beginFrame()} 设置的状态；绘制完成后调用一次。 */
    void endFrame();

    /** 用于日志与诊断的短名称，如 {@code "gl-fixed"} 或 {@code "gl-core"}。 */
    String backendName();

    /**
     * 当前绘制区域的宽度（像素）。
     *
     * <p>GUI 用它把窗口坐标映射到绘制坐标：两者在高 DPI 或缩放窗口下并不相等。
     * 尚未完成首帧、尺寸未知时返回 0。
     */
    int width();

    /** 当前绘制区域的高度（像素）；未知时返回 0。 */
    int height();

    /**
     * 本后端当前是否真能绘制。
     *
     * <p>叠加层用它把「后端初始化失败」与「输入没反应」区分开，并在失败时给出提示：
     * 只打印「GUI 已打开」会让两种故障看起来完全一样，无法定位。
     *
     * @return 可绘制返回 {@code true}
     */
    default boolean ready() {
        return true;
    }

    /**
     * UI 缩放系数（1 = 逐像素绘制）。
     *
     * <p>界面元素都是按固定像素尺寸写的，在 2K/4K 屏上不缩放就会显得"十分小"。
     * 会自己按屏幕高度放大的后端（目前是固定管线的 {@link GlRenderer}）返回大于 1 的整数，
     * 且 {@link #width()}/{@link #height()} 返回的是**逻辑**尺寸（物理 ÷ 缩放）——
     * 这样依赖 {@code width()} 推导鼠标坐标换算的输入层会自动跟随，不需要各自处理缩放。
     */
    default int scale() {
        return 1;
    }
}
