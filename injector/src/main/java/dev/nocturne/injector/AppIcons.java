package dev.nocturne.injector;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** 注入器使用的轻量矢量图标；不依赖字体字形，跨平台外观一致。 */
final class AppIcons implements Icon {

    enum Kind { SCAN, INJECT, SETTINGS, PROCESS }

    private final Kind kind;
    private final Color color;
    private final int size;

    AppIcons(Kind kind, Color color, int size) {
        this.kind = kind;
        this.color = color;
        this.size = size;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        double scale = size / 16.0;
        g.scale(scale, scale);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case SCAN:
                g.drawArc(2, 2, 12, 12, 35, 245);
                g.drawLine(12, 2, 12, 6);
                g.drawLine(12, 2, 8, 2);
                g.drawLine(8, 8, 8, 8);
                break;
            case INJECT:
                g.drawLine(2, 8, 11, 8);
                g.drawLine(8, 5, 11, 8);
                g.drawLine(8, 11, 11, 8);
                g.drawLine(14, 3, 14, 13);
                break;
            case SETTINGS:
                g.drawLine(2, 4, 14, 4);
                g.drawLine(2, 8, 14, 8);
                g.drawLine(2, 12, 14, 12);
                g.fillOval(4, 2, 4, 4);
                g.fillOval(9, 6, 4, 4);
                g.fillOval(3, 10, 4, 4);
                break;
            case PROCESS:
                g.drawRoundRect(2, 2, 12, 12, 3, 3);
                g.drawLine(2, 6, 14, 6);
                g.drawLine(5, 4, 5, 4);
                g.drawLine(8, 10, 11, 10);
                break;
            default:
                break;
        }
        g.dispose();
    }
}
