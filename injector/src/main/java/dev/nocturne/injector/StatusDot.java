package dev.nocturne.injector;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 状态指示点：紧凑的实心圆点，颜色反映当前操作状态。
 *
 * <p>状态切换时颜色会在约 150ms 内平滑过渡，动画速度遵循用户偏好。
 */
public final class StatusDot extends JComponent {

    /** 颜色过渡的定时器间隔（毫秒）；16ms 约合 60fps。 */
    private static final int FRAME_MS = 16;

    /** 当前显示色；默认取次要文字色，表示「空闲/尚未操作」。 */
    private Color color = AppTheme.TEXT_MUTED;

    /** 目标状态色；{@link #color} 会逐帧向其收敛。 */
    private Color target = AppTheme.TEXT_MUTED;

    /** 颜色过渡时长（毫秒）；由动画速度百分比推导，0 表示立即切换。 */
    private int durationMs = 150;

    /** 驱动颜色过渡的定时器；到达目标后自动停止。 */
    private final Timer timer;

    /** 构造指示点并固定其首选与最小尺寸为 16×16。 */
    public StatusDot() {
        Dimension size = new Dimension(16, 16);
        setPreferredSize(size);
        setMinimumSize(size);
        timer = new Timer(FRAME_MS, e -> step());
    }

    /**
     * 设置状态色并平滑过渡到它。
     *
     * <p>必须在 EDT 上调用。
     *
     * @param state 圆点目标色；传 {@code null} 视为回到空闲色
     */
    public void setState(Color state) {
        target = state != null ? state : AppTheme.TEXT_MUTED;
        if (durationMs <= 0) {
            // 动画速度为 0：立即切换，不启动定时器。
            color = target;
            repaint();
            return;
        }
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    /**
     * 应用动画速度百分比（0–200）：100 为标准速度（过渡约 150ms），200 翻倍，0 关闭动画。
     *
     * <p>由设置对话框实时调用。
     *
     * @param percent 动画速度百分比；非正数立即切换
     */
    public void setSpeedPercent(int percent) {
        durationMs = percent <= 0 ? 0 : Math.max(1, Math.round(150f * 100f / percent));
    }

    /** 当前过渡时长（毫秒）；包级可见仅供测试断言。 */
    int transitionDurationMs() {
        return durationMs;
    }

    /** 推进一帧颜色过渡；足够接近时吸附到目标色并停止定时器。 */
    private void step() {
        if (durationMs <= 0) {
            color = target;
            timer.stop();
            repaint();
            return;
        }
        color = blend(color, target, Math.min(1f, (float) FRAME_MS / durationMs));
        if (Math.abs(color.getRed() - target.getRed()) <= 2
                && Math.abs(color.getGreen() - target.getGreen()) <= 2
                && Math.abs(color.getBlue() - target.getBlue()) <= 2) {
            color = target;
            timer.stop();
        }
        repaint();
    }

    /** 在 {@code from} 与 {@code to} 之间按 {@code amount} 线性插值。 */
    private static Color blend(Color from, Color to, float amount) {
        int red = Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount);
        int green = Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount);
        int blue = Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount);
        return new Color(red, green, blue);
    }

    /** 绘制紧凑的实心状态点，不使用外发光。 */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int size = Math.max(5, Math.min(getWidth(), getHeight()) - 6);
        int x = (getWidth() - size) / 2;
        int y = (getHeight() - size) / 2;
        g2.setColor(color);
        g2.fillOval(x, y, size, size);
        g2.dispose();
    }
}
