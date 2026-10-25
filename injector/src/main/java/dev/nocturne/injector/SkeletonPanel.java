package dev.nocturne.injector;

import net.miginfocom.swing.MigLayout;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/** 扫描时显示的三行进程骨架；卡片隐藏后动画立即停止。 */
public final class SkeletonPanel extends JPanel {

    private static final int BAR_COUNT = 3;
    private static final int FRAME_MS = 40;
    private final Timer timer;
    private float phase;

    public SkeletonPanel() {
        super(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]14[]14[]"));
        setOpaque(false);
        for (int i = 0; i < BAR_COUNT; i++) {
            add(new Bar(i), "h 46!");
        }
        timer = new Timer(FRAME_MS, e -> {
            phase += 0.055f;
            if (phase > (float) (Math.PI * 2)) {
                phase -= (float) (Math.PI * 2);
            }
            repaint();
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                timer.start();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                timer.stop();
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (isVisible()) {
            timer.start();
        }
    }

    /** 非正数冻结动画；其余百分比按标准速度等比调整。 */
    public void setSpeedPercent(int percent) {
        timer.setDelay(percent <= 0 ? 100000
                : Math.max(16, Math.round(FRAME_MS * 100f / percent)));
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    private final class Bar extends JComponent {
        private final int index;

        Bar(int index) {
            this.index = index;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float wave = (float) Math.sin(phase + index * 0.7f) * 0.5f + 0.5f;
            int alpha = 105 + (int) (wave * 85);
            g.setColor(withAlpha(AppTheme.PANEL_RAISED, alpha));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.setColor(withAlpha(AppTheme.BORDER, alpha));
            int height = getHeight();
            int width = getWidth();
            g.fillRoundRect(18, height / 2 - 4, Math.max(40, width * 38 / 100), 8, 6, 6);
            g.fillRoundRect(width * 64 / 100, height / 2 - 3,
                    Math.max(28, width * 10 / 100), 6, 5, 5);
            g.dispose();
        }

        private Color withAlpha(Color color, int alpha) {
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
        }
    }
}
