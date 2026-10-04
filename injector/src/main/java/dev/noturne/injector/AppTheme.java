package dev.noturne.injector;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
        // setup() 同时设置 UIManager 默认值与系统 Look & Feel。极端 DPI 或残缺的 FlatLaf 依赖
        // 下可能失败（例如 JDK 版本过低触发 UnsupportedClassVersionError），此时退回系统外观，
        // 至少保证注入器能起来。
        try {
            FlatDarkLaf.setup();
        } catch (RuntimeException | LinkageError broken) {
            System.err.println("[noturne] FlatLaf 安装失败，退回系统外观：" + broken);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception fallbackFailed) {
                System.err.println("[noturne] 系统外观也不可用：" + fallbackFailed);
            }
        }

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
        try {
            FlatLaf.updateUI();
        } catch (RuntimeException | LinkageError broken) {
            // 极端 DPI 下重建 L&F 可能失败；字体绑定仍会照常生效，界面不至于卡死。
            System.err.println("[noturne] 重建界面外观失败：" + broken);
        }
        applyFontBindings();
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
     * <p>注意：{@code setFont(scaled(...))} 设下的字体只会保留到下一次 {@code updateUI()}，字号
     * 不会随 {@link #setZoom(float)} 变化。需要随缩放更新的常驻组件请用 {@link #bindFont}。
     *
     * @param style {@link Font} 的字形常量（如 {@link Font#BOLD}）
     * @param times 相对于基础字号的倍数
     * @return 派生出的字体，保留基础字体的族名
     */
    public static Font scaled(int style, float times) {
        Font base = baseFont();
        return base.deriveFont(style, base.getSize() * times);
    }

    /**
     * 注册一个「基础字体派生的常驻字体」：立即设置，并在每次 {@link #setZoom(float)} 后重算。
     *
     * <p>运行时缩放的作用范围因此被明确定义为：L&amp;F 默认值 + 通过本方法（或
     * {@link #bindMonospacedFont}）登记的组件。渲染器里每次绘制都调用 {@link #scaled} 重取字体，
     * 会自动跟随，无需登记。用弱引用持有组件，登记项不会阻止对话框/窗口被回收。
     *
     * <p>必须在 EDT 上调用。
     *
     * @param component 目标组件
     * @param style     {@link Font} 的字形常量
     * @param times     相对于基础字号的倍数
     */
    public static void bindFont(JComponent component, int style, float times) {
        component.setFont(scaled(style, times));
        register(new FontBinding(component, style, times, false));
    }

    /**
     * 注册一个「等宽字体」的常驻绑定：立即设置，并在每次 {@link #setZoom(float)} 后按当前基础字号
     * 重算字号，但保留等宽族名。
     *
     * <p>必须在 EDT 上调用。
     *
     * @param component 目标组件
     * @param times     相对于基础字号的倍数
     */
    public static void bindMonospacedFont(JComponent component, float times) {
        component.setFont(monospaced(component.getFont(), times));
        register(new FontBinding(component, Font.PLAIN, times, true));
    }

    /** 生成等宽字体：字号随倍数变化，族名固定为 {@link Font#MONOSPACED}。 */
    private static Font monospaced(Font current, float times) {
        int size = Math.max(1, Math.round(baseFont().getSize() * times));
        int style = current != null ? current.getStyle() : Font.PLAIN;
        return new Font(Font.MONOSPACED, style, size);
    }

    /** 已登记的字体绑定；弱引用组件，避免阻碍 GC。 */
    private static final List<FontBinding> FONT_BINDINGS = new ArrayList<FontBinding>();

    /** 一条「组件 + 字形 + 倍数」的字体绑定。 */
    private static final class FontBinding {
        /** 用弱引用持有组件：对话框被回收后对应条目会被惰性清理。 */
        private final WeakReference<JComponent> component;
        /** 字形常量。 */
        private final int style;
        /** 相对于基础字号的倍数。 */
        private final float times;
        /** 是否为等宽字体绑定。 */
        private final boolean monospaced;

        FontBinding(JComponent component, int style, float times, boolean monospaced) {
            this.component = new WeakReference<JComponent>(component);
            this.style = style;
            this.times = times;
            this.monospaced = monospaced;
        }
    }

    /** 登记一条绑定（EDT 上调用）。 */
    private static void register(FontBinding binding) {
        synchronized (FONT_BINDINGS) {
            FONT_BINDINGS.add(binding);
        }
    }

    /** 按当前基础字号重算所有已登记组件的字体，并清理已被回收的条目。 */
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
