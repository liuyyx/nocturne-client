package dev.noturne.injector;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * 主题安装入口。
 *
 * <p>配色由 FlatDarkLaf 负责，本类只覆写设计稿指定的少数几个颜色。没有自绘、没有手写的
 * Look &amp; Feel——控件外观全部交给 FlatLaf，这样它才能继续把系统 DPI 缩放应用到字体与尺寸上。
 */
public final class AppTheme {

    /** 窗口底色，最深的一层背景。 */
    public static final Color BACKGROUND = new Color(0x0B, 0x12, 0x20);

    /** 卡片/面板底色，比 {@link #BACKGROUND} 略亮。 */
    public static final Color PANEL = new Color(0x11, 0x1A, 0x2E);

    /** 悬停与选中态底色。 */
    public static final Color PANEL_HOVER = new Color(0x16, 0x22, 0x3A);

    /** 主文字色。 */
    public static final Color TEXT = new Color(0xD6, 0xE2, 0xF5);

    /** 次要文字色（副标题、版本号等）。 */
    public static final Color TEXT_MUTED = new Color(0x7A, 0x8B, 0xA8);

    /** 主强调色，同时用作焦点色。 */
    public static final Color ACCENT = new Color(0x3D, 0x7B, 0xFF);

    /** 强调色的悬停态。 */
    public static final Color ACCENT_HOVER = new Color(0x5A, 0x90, 0xFF);

    /** 错误/危险语义色。 */
    public static final Color DANGER = new Color(0xE8, 0x4C, 0x4C);

    /** 成功语义色。 */
    public static final Color SUCCESS = new Color(0x3F, 0xD0, 0x8A);

    /** 工具类，不允许实例化。 */
    private AppTheme() {
    }

    /**
     * 安装 FlatDarkLaf 并覆写调色板。
     *
     * <p>只应调用一次，且必须在创建任何窗口之前——L&amp;F 的默认值只在组件构造时被读取。
     */
    public static void install() {
        // setup() 同时设置 UIManager 默认值与系统 Look & Feel。
        FlatDarkLaf.setup();

        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("control", PANEL);
        UIManager.put("Component.background", PANEL);
        UIManager.put("Component.foreground", TEXT);
        // 边框色是全局 1px 描边的基础色；innerFocusWidth=1 让焦点框更克制。
        UIManager.put("Component.borderColor", new Color(0x1E, 0x2B, 0x45));
        UIManager.put("Component.focusColor", ACCENT);
        UIManager.put("Component.innerFocusWidth", 1);
        UIManager.put("Component.accentColor", ACCENT);

        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.disabledForeground", TEXT_MUTED);

        UIManager.put("Button.background", PANEL);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.hoverBackground", PANEL_HOVER);
        UIManager.put("Button.pressedBackground", PANEL_HOVER);
        UIManager.put("Button.default.background", ACCENT);
        UIManager.put("Button.default.foreground", Color.WHITE);
        UIManager.put("Button.default.hoverBackground", ACCENT_HOVER);
        UIManager.put("Button.default.pressedBackground", ACCENT);

        UIManager.put("Table.background", BACKGROUND);
        // 表格区：网格线用比边框更暗的颜色，避免与卡片描边抢视觉。
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", PANEL_HOVER);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.gridColor", new Color(0x18, 0x24, 0x3C));
        UIManager.put("TableHeader.background", PANEL);
        UIManager.put("TableHeader.foreground", TEXT_MUTED);
        UIManager.put("TableHeader.separatorColor", new Color(0x18, 0x24, 0x3C));

        UIManager.put("ScrollBar.thumb", new Color(0x2A, 0x3A, 0x58));
        UIManager.put("ScrollBar.thumbHover", new Color(0x36, 0x4A, 0x70));
        UIManager.put("ScrollBar.track", BACKGROUND);
        UIManager.put("ScrollBar.width", 10);

        UIManager.put("TextArea.background", PANEL);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextField.background", PANEL);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);

        UIManager.put("Slider.thumbColor", ACCENT);
        UIManager.put("Slider.trackColor", new Color(0x1E, 0x2B, 0x45));
        UIManager.put("Slider.focusColor", ACCENT);

        UIManager.put("Dialog.background", BACKGROUND);
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("PopupMenu.background", PANEL);
        UIManager.put("PopupMenu.foreground", TEXT);
        UIManager.put("MenuItem.background", PANEL);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", ACCENT);
        UIManager.put("MenuItem.selectionForeground", Color.WHITE);
    }

    /** 应用任何缩放之前捕获的 L&amp;F 基础字体；为 {@code null} 表示尚未初始化。 */
    private static Font systemBaseFont;

    /**
     * 应用用户缩放系数：1.0 表示 100%。
     *
     * <p>FlatLaf 没有运行时缩放设置接口，因此按它支持的方式实现：把 {@code defaultFont} 换成
     * 派生尺寸的字体，再调用 {@link FlatLaf#updateUI()} 重建 UI 默认值。控件的字体与高度会立即
     * 跟随——这正是缩放滑块无需重启就生效的原因。
     *
     * @param factor 缩放系数；非正数一律按 1.0 处理
     */
    public static void setZoom(float factor) {
        if (systemBaseFont == null) {
            // 只捕获一次：若每次都读当前的 defaultFont，缩放会累乘而不是幂等地重设。
            systemBaseFont = baseFont();
        }
        // 兜底：配置里出现 0 或负数时退回 100%。
        float zoom = factor <= 0f ? 1f : factor;
        Font zoomed = systemBaseFont.deriveFont(systemBaseFont.getSize2D() * zoom);
        UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(zoomed));
        FlatLaf.updateUI();
    }

    /**
     * 返回 L&amp;F 基础字体，已经按系统 DPI 缩放。
     *
     * @return UIManager 中的 {@code Label.font}；若缺失则回落到一个新 JLabel 的字体
     */
    public static Font baseFont() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font : new JLabel().getFont();
    }

    /**
     * 从 L&amp;F 基础字体派生出指定倍数的字体。
     *
     * <p>绝不用绝对磅值构造字体：那正是破坏 DPI 无关性的做法。
     *
     * @param style {@link Font} 的字形常量（如 {@link Font#BOLD}）
     * @param times 相对于基础字号的倍数
     * @return 派生出的字体，保留基础字体的族名
     */
    public static Font scaled(int style, float times) {
        Font base = baseFont();
        return base.deriveFont(style, base.getSize() * times);
    }
}
