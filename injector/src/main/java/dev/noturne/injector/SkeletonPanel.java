package dev.noturne.injector;

import net.miginfocom.swing.MigLayout;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 扫描进行中显示的「呼吸」骨架条，让列表区域不会看起来卡死或空着。
 *
 * <p>各条相位错开；面板被隐藏时定时器会停止，因此切到表格后不会有无谓的重绘。
 */
public final class SkeletonPanel extends JPanel {

    /** 骨架条数量。 */
    private static final int BAR_COUNT = 3;
    /** 重绘间隔（毫秒）；40ms 约合 25fps，对呼吸动画足够且开销低。 */
    private static final int FRAME_MS = 40;

    /** 驱动呼吸动画的定时器；生命周期与组件的可见性绑定。 */
    private final Timer timer;
    /** 当前动画相位，单位为弧度，取值环绕 [0, 2π)。 */
    private float phase;

    /**
     * 构造骨架面板。
     *
     * <p>必须在 EDT 上调用。只创建子组件与定时器，不启动动画——动画在 {@link #addNotify()} 里启动。
     */
    public SkeletonPanel() {
        super(new MigLayout("insets 18 14 18 14, fillx, wrap 1", "[grow,fill]", "[]16[]16[]"));
        setOpaque(false);

        // 逐条加入，行间距交给 MigLayout 的行规格控制。
        for (int i = 0; i < BAR_COUNT; i++) {
            add(new Bar(i), "h 20!");
        }

        // 相位用 0.055 递增：约 114 帧走完一个周期，正好是缓慢的呼吸感。
        timer = new Timer(FRAME_MS, e -> {
            phase += 0.055f;
            // 相位取模，避免长时间运行后 float 精度损失导致动画卡顿。
            if (phase > (float) (Math.PI * 2)) {
                phase -= (float) (Math.PI * 2);
            }
            repaint();
        });
    }

    /** 组件被挂到屏幕上时启动动画。 */
    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    /** 组件离开屏幕时停止动画，避免隐藏后仍在消耗 CPU。 */
    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    /**
     * 单条骨架条；透明度跟随共享相位并按条序号做偏移。
     *
     * <p>内部类，读取外层的 {@code phase} 字段。
     */
    private final class Bar extends JComponent {
        /** 本条在序列中的序号，用于相位偏移。 */
        private final int index;

        /**
         * @param index 条序号（0 起）
         */
        Bar(int index) {
            this.index = index;
            setOpaque(false);
        }

        /** 画一条圆角矩形；alpha 随波形起伏，颜色本身沿用面板色。 */
        @Override
        protected void paintComponent(Graphics g) {
            // 复制一份 Graphics2D 再设置抗锯齿，避免污染组件的共享绘图状态。
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float wave = (float) Math.sin(phase + index * 0.7f) * 0.5f + 0.5f;
            // 用同一个相位加不同偏移，做出波峰依次推过去的效果。
            int alpha = 26 + (int) (wave * 46);
            // alpha 区间 26–72：既能看出呼吸，又不会抢过后续表格内容的注意力。
            g2.setColor(new Color(AppTheme.PANEL_HOVER.getRed(),
                    AppTheme.PANEL_HOVER.getGreen(),
                    AppTheme.PANEL_HOVER.getBlue(),
                    alpha));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.dispose();
        }
    }
}
