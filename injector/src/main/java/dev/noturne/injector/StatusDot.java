package dev.noturne.injector;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 状态指示点：一个带柔和光晕的圆点，颜色反映上一次操作的结果。
 *
 * <p>光晕与圆点分两层绘制：先画大 6px、alpha 更低的一层，再画实心圆点本体。
 */
public final class StatusDot extends JComponent {

    /** 当前状态色；默认取次要文字色，表示「空闲/尚未操作」。 */
    private Color color = AppTheme.TEXT_MUTED;

    /** 构造指示点并固定其首选与最小尺寸为 16×16。 */
    public StatusDot() {
        Dimension size = new Dimension(16, 16);
        setPreferredSize(size);
        setMinimumSize(size);
    }

    /**
     * 设置状态色并立即重绘。
     *
     * <p>必须在 EDT 上调用。
     *
     * @param state 圆点与光晕共用的颜色；传 {@code null} 会导致绘制时 NPE
     */
    public void setState(Color state) {
        this.color = state;
        repaint();
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
