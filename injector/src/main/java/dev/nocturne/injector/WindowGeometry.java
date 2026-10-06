package dev.nocturne.injector;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * 窗口尺寸与定位，全部基于屏幕的<em>逻辑</em>分辨率推导。
 *
 * <p>{@link GraphicsConfiguration#getBounds()} 返回的是窗口管理器使用的坐标系，也就是物理像素
 * 除以系统缩放系数（100% 时为 1.0，200% 时为 2.0）。按这些边界定尺寸，正是窗口在任何缩放设置下
 * 都占据屏幕相同比例的原因。
 */
public final class WindowGeometry {

    /** 窗口宽度占屏幕宽度的比例。 */
    private static final double WIDTH_RATIO = 0.42;
    /** 窗口高度占屏幕高度的比例。 */
    private static final double HEIGHT_RATIO = 0.52;
    /** 最小宽度（逻辑像素）。 */
    private static final int MIN_WIDTH = 640;
    /** 最小高度（逻辑像素）。 */
    private static final int MIN_HEIGHT = 440;
    /** 最大宽度（逻辑像素）；宽屏上防止窗口过分横跨。 */
    private static final int MAX_WIDTH = 900;
    /** 最大高度（逻辑像素）。 */
    private static final int MAX_HEIGHT = 640;

    /** 工具类，不允许实例化。 */
    private WindowGeometry() {
    }

    /**
     * 计算初始窗口矩形：在鼠标所在的那块屏幕上居中。
     *
     * <p>宽高按比例计算后再夹到 {@code [MIN, MAX]} 区间，并进一步不超过屏幕的实际逻辑尺寸，
     * 因此小屏与 4K 屏上都不会越界。
     *
     * @return 期望的窗口边界（逻辑像素坐标）
     */
    public static Rectangle initialBounds() {
        Rectangle screen = activeScreenBounds();
        // 先按比例算再夹取：直接夹取屏幕尺寸会让小屏上的窗口小到无法使用。
        int width = clamp((int) Math.round(screen.width * WIDTH_RATIO), MIN_WIDTH, MAX_WIDTH);
        int height = clamp((int) Math.round(screen.height * HEIGHT_RATIO), MIN_HEIGHT, MAX_HEIGHT);
        // 再与屏幕实际尺寸比较：MIN 常量在极小分辨率下可能已超过屏幕，否则窗口会越出屏幕。
        width = Math.max(1, Math.min(width, screen.width));
        height = Math.max(1, Math.min(height, screen.height));
        // 位置用屏幕原点 + 居中偏移；在副屏上 x/y 不为 0，不能只算居中量。
        return new Rectangle(
                screen.x + (screen.width - width) / 2,
                screen.y + (screen.height - height) / 2,
                width,
                height);
    }

    /**
     * 返回鼠标所在屏幕的边界，取不到时回落到默认屏幕。
     *
     * <p>刻意不用 {@code setLocationRelativeTo(null)}：那总是居中到主显示器，在多显示器下是错的。
     *
     * @return 目标屏幕的边界（逻辑像素坐标）
     */
    public static Rectangle activeScreenBounds() {
        try {
            Point pointer = MouseInfo.getPointerInfo().getLocation();
            // 遍历所有屏幕找包含指针的那个；多屏下指针可能落在任意一块。
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                if (bounds.contains(pointer)) {
                    // 命中即返回：命中后不再检查后续屏幕，避免跨屏边界时的歧义。
                    return bounds;
                }
            }
        } catch (Throwable ignored) {
            // 无头模式或拿不到指针信息，此时只能退回默认屏幕。
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
    }

    /**
     * 返回鼠标所在屏幕的逻辑尺寸。
     *
     * @return 该屏幕的宽高（逻辑像素）
     */
    public static java.awt.Dimension activeScreenSize() {
        Rectangle bounds = activeScreenBounds();
        return new java.awt.Dimension(bounds.width, bounds.height);
    }

    /**
     * 把数值夹到 {@code [min, max]} 闭区间内。
     *
     * @param value 待夹取的数
     * @param min   下界
     * @param max   上界
     * @return 夹取后的值
     */
    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
