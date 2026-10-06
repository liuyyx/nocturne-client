package dev.nocturne.injector;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 状态指示点：一个带柔和光晕的圆点，颜色反映上一次操作的结果。
 *
 * <p>光晕与圆点分两层绘制：先画大 6px、alpha 更低的一层，再画实心圆点本体。状态切换时颜色会在
 * 约 150ms 内平滑过渡，避免从「空闲灰」突变成「成功绿」时的闪烁。
 */
public final class StatusDot extends JComponent {

    /** 颜色过渡的定时器间隔（毫秒）；16ms 约合 60fps。 */
    private static final int FRAME_MS = 16;

    /** 每帧向目标色靠近的比例；0.25 约 4 帧走完大半过渡。 */
    private static final float STEP = 0.25f;

    /** 当前显示色；默认取次要文字色，表示「空闲/尚未操作」。 */
    private Color color = AppTheme.TEXT_MUTED;

    /** 目标状态色；{@link #color} 会逐帧向其收敛。 */
    private Color target = AppTheme.TEXT_MUTED;

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
     * @param state 圆点与光晕共用的目标色；传 {@code null} 视为回到空闲色
     */
    public void setState(Color state) {
        target = state != null ? state : AppTheme.TEXT_MUTED;
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    /** 推进一帧颜色过渡；足够接近时吸附到目标色并停止定时器。 */
    private void step() {
        color = blend(color, target, STEP);
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

    /** 绘制光晕与圆点本体。 */
    @Override
    protected void paintComponent(Graphics g) {
        // 复制一份 Graphics2D，避免抗锯齿提示泄漏到同一绘制周期里的其他组件。
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 圆点直径取宽高中较小者再留 3px 边距，使光晕层有落点；下限 4px 防止被压扁成点。
        int size = Math.max(4, Math.min(getWidth(), getHeight()) - 6);
        int x = (getWidth() - size) / 2;
        int y = (getHeight() - size) / 2;
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
        g2.fillOval(x - 3, y - 3, size + 6, size + 6);
        g2.setColor(color);
        g2.fillOval(x, y, size, size);
        g2.dispose();
    }
}
