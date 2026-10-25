package dev.nocturne.injector;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 注入器设计令牌与 FlatLaf 全局样式。
 *
 * <p>视觉采用原来的暖石墨工作台与钠灯琥珀信号色：侧栏负责定位和设置，主区域只承载当前任务。
 * 所有颜色、字体层级和间距在此集中定义，业务布局不直接写十六进制颜色。
 */
public final class AppTheme {

    public static final Color BACKGROUND = new Color(0x13, 0x13, 0x15);
    public static final Color SIDEBAR = new Color(0x10, 0x10, 0x12);
    public static final Color PANEL = new Color(0x1A, 0x1A, 0x1D);
    public static final Color PANEL_RAISED = new Color(0x20, 0x20, 0x24);
    public static final Color PANEL_HOVER = new Color(0x26, 0x26, 0x2B);
    public static final Color TEXT = new Color(0xEC, 0xE9, 0xE1);
    public static final Color TEXT_MUTED = new Color(0x9C, 0x97, 0x8B);
    public static final Color TEXT_FAINT = new Color(0x7C, 0x77, 0x6C);
    public static final Color ACCENT = new Color(0xE8, 0xA3, 0x3D);
    public static final Color ACCENT_HOVER = new Color(0xF2, 0xB6, 0x5A);
    public static final Color ACCENT_PRESSED = new Color(0xD5, 0x96, 0x38);
    public static final Color ON_ACCENT = new Color(0x17, 0x14, 0x10);
    public static final Color ACCENT_SOFT = new Color(0x31, 0x29, 0x1B);
    public static final Color DANGER = new Color(0xD9, 0x6A, 0x57);
    public static final Color SUCCESS = new Color(0x7F, 0xB8, 0x7A);
    public static final Color INFO = new Color(0xEF, 0xB0, 0x4B);
    public static final Color BORDER = new Color(0x2B, 0x2B, 0x31);
    public static final Color BORDER_SOFT = new Color(0x22, 0x22, 0x27);

    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;
    public static final int SPACE_XXL = 32;

    public static final int RADIUS_CARD = 14;
    public static final int RADIUS_CONTROL = 9;

    public static final float TYPE_HERO = 1.75f;
    public static final float TYPE_TITLE = 1.22f;
    public static final float TYPE_BODY = 1.0f;
    public static final float TYPE_CAPTION = 0.86f;
    public static final float TYPE_MONO = 0.88f;
    public static final float TYPE_OVERLINE = 0.80f;

    private static Font systemBaseFont;
    private static final List<FontBinding> FONT_BINDINGS = new ArrayList<FontBinding>();

    private AppTheme() {
    }

    static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    static void stylePrimaryButton(AbstractButton button) {
        button.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + RADIUS_CONTROL
                        + "; background: " + hex(ACCENT) + "; foreground: " + hex(ON_ACCENT)
                        + "; hoverBackground: " + hex(ACCENT_HOVER)
                        + "; pressedBackground: " + hex(ACCENT_PRESSED)
                        + "; borderWidth: 0; focusWidth: 1; focusedBorderColor: " + hex(ACCENT_HOVER)
                        + "; disabledBackground: " + hex(PANEL_RAISED)
                        + "; disabledText: " + hex(TEXT_FAINT));
    }

    static void styleSecondaryButton(AbstractButton button) {
        button.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + RADIUS_CONTROL
                        + "; background: " + hex(PANEL_RAISED) + "; foreground: " + hex(TEXT)
                        + "; hoverBackground: " + hex(PANEL_HOVER)
                        + "; pressedBackground: " + hex(PANEL_HOVER)
                        + "; borderColor: " + hex(BORDER) + "; hoverBorderColor: " + hex(ACCENT)
                        + "; disabledText: " + hex(TEXT_FAINT));
    }

    /** 低强调操作，用于侧栏设置等不抢主任务视觉权重的按钮。 */
    static void styleQuietButton(AbstractButton button) {
        button.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + RADIUS_CONTROL + "; background: " + hex(SIDEBAR)
                        + "; foreground: " + hex(TEXT_MUTED)
                        + "; hoverBackground: " + hex(PANEL_RAISED) + "; hoverForeground: " + hex(TEXT)
                        + "; pressedBackground: " + hex(PANEL_HOVER)
                        + "; borderColor: " + hex(BORDER_SOFT));
    }

    static void styleCard(JComponent component) {
        component.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + RADIUS_CARD + "; border: 1,1,1,1," + hex(BORDER_SOFT));
    }

    static void styleField(JComponent component) {
        component.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + RADIUS_CONTROL + "; background: " + hex(PANEL_RAISED)
                        + "; borderColor: " + hex(BORDER)
                        + "; focusedBorderColor: " + hex(ACCENT));
    }

    static void styleChip(JComponent component) {
        component.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; background: " + hex(ACCENT_SOFT));
    }

    /** 必须在任何 Swing 组件创建前调用。 */
    public static void install() {
        try {
            FlatDarkLaf.setup();
        } catch (RuntimeException | LinkageError broken) {
            System.err.println("[nocturne] FlatLaf 安装失败，退回系统外观：" + broken);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception fallbackFailed) {
                System.err.println("[nocturne] 系统外观也不可用：" + fallbackFailed);
            }
        }

        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("control", PANEL);
        UIManager.put("Component.background", PANEL);
        UIManager.put("Component.foreground", TEXT);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.focusColor", ACCENT);
        UIManager.put("Component.innerFocusWidth", 1);
        UIManager.put("Component.accentColor", ACCENT);
        UIManager.put("Component.arc", RADIUS_CONTROL);
        UIManager.put("Component.disabledForeground", TEXT_FAINT);
        UIManager.put("Button.arc", RADIUS_CONTROL);
        UIManager.put("Button.disabledText", TEXT_FAINT);
        UIManager.put("Button.disabledBackground", PANEL_RAISED);
        UIManager.put("Button.background", PANEL_RAISED);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.hoverBackground", PANEL_HOVER);
        UIManager.put("Button.pressedBackground", PANEL_HOVER);
        UIManager.put("TextComponent.arc", RADIUS_CONTROL);
        UIManager.put("TextComponent.selectionBackground", ACCENT_SOFT);
        UIManager.put("TextComponent.selectionForeground", TEXT);
        UIManager.put("TextField.placeholderForeground", TEXT_FAINT);
        UIManager.put("ScrollPane.smoothScrolling", Boolean.TRUE);
        UIManager.put("ProgressBar.background", BORDER_SOFT);
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("ProgressBar.arc", 0);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.disabledForeground", TEXT_MUTED);
        UIManager.put("Table.background", PANEL);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", ACCENT_SOFT);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.gridColor", BORDER_SOFT);
        UIManager.put("TableHeader.background", PANEL_RAISED);
        UIManager.put("TableHeader.foreground", TEXT_MUTED);
        UIManager.put("TableHeader.separatorColor", BORDER);
        UIManager.put("TableHeader.bottomSeparatorColor", BORDER);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.thumbHover", TEXT_FAINT);
        UIManager.put("ScrollBar.track", PANEL);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("TextArea.background", PANEL);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextField.background", PANEL_RAISED);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", ACCENT);
        UIManager.put("Slider.thumbColor", ACCENT);
        UIManager.put("Slider.trackColor", BORDER);
        UIManager.put("Slider.trackValueColor", ACCENT);
        UIManager.put("Slider.hoverThumbColor", ACCENT_HOVER);
        UIManager.put("Slider.pressedThumbColor", ACCENT_PRESSED);
        UIManager.put("Slider.focusedColor", ACCENT);
        UIManager.put("Dialog.background", BACKGROUND);
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("PopupMenu.background", PANEL_RAISED);
        UIManager.put("PopupMenu.foreground", TEXT);
        UIManager.put("MenuItem.background", PANEL_RAISED);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", ACCENT_SOFT);
        UIManager.put("MenuItem.selectionForeground", TEXT);
    }

    /** 幂等应用用户缩放；基础字体只捕获一次，避免重复缩放累乘。 */
    public static void setZoom(float factor) {
        if (systemBaseFont == null) {
            systemBaseFont = baseFont();
        }
        float zoom = factor <= 0f ? 1f : factor;
        Font zoomed = systemBaseFont.deriveFont(systemBaseFont.getSize2D() * zoom);
        UIManager.put("defaultFont", new FontUIResource(zoomed));
        try {
            FlatLaf.updateUI();
        } catch (RuntimeException | LinkageError broken) {
            System.err.println("[nocturne] 重建界面外观失败：" + broken);
        }
        applyFontBindings();
    }

    public static Font baseFont() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font : new JLabel().getFont();
    }

    public static Font scaled(int style, float times) {
        Font base = baseFont();
        return base.deriveFont(style, base.getSize2D() * times);
    }

    public static void bindFont(JComponent component, int style, float times) {
        component.setFont(scaled(style, times));
        register(new FontBinding(component, style, times, false));
    }

    public static void bindMonospacedFont(JComponent component, float times) {
        component.setFont(monospaced(component.getFont(), times));
        register(new FontBinding(component, Font.PLAIN, times, true));
    }

    private static Font monospaced(Font current, float times) {
        float size = Math.max(1f, baseFont().getSize2D() * times);
        int style = current != null ? current.getStyle() : Font.PLAIN;
        return new Font(Font.MONOSPACED, style, 1).deriveFont(size);
    }

    private static final class FontBinding {
        private final WeakReference<JComponent> component;
        private final int style;
        private final float times;
        private final boolean monospaced;

        FontBinding(JComponent component, int style, float times, boolean monospaced) {
            this.component = new WeakReference<JComponent>(component);
            this.style = style;
            this.times = times;
            this.monospaced = monospaced;
        }
    }

    private static void register(FontBinding binding) {
        synchronized (FONT_BINDINGS) {
            FONT_BINDINGS.add(binding);
        }
    }

    private static void applyFontBindings() {
        synchronized (FONT_BINDINGS) {
            Iterator<FontBinding> iterator = FONT_BINDINGS.iterator();
            while (iterator.hasNext()) {
                FontBinding binding = iterator.next();
                JComponent component = binding.component.get();
                if (component == null) {
                    iterator.remove();
                    continue;
                }
                component.setFont(binding.monospaced
                        ? monospaced(component.getFont(), binding.times)
                        : scaled(binding.style, binding.times));
            }
        }
    }
}
