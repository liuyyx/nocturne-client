package dev.nocturne.ui.theme;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.skija.SkijaTheme;

/**
 * 视觉语言：Setsuna 风格——近黑分层表面、青绿强调、（相对）小圆角。
 *
 * <p>色值一律取自 {@link SkijaTheme}（自 Setsuna 上游 {@code ui/UiTheme} 移植，见
 * THIRD-PARTY-NOTICES.md），本类只做**语义映射**：Material Design 3 的层级命名
 * （surface container / primary / on-primary…）映射到 Setsuna 的色板。这样组件树沿用原有
 * 取名，换主题只需改这一处。
 *
 * <p>命名与 Setsuna 色板的对应关系写在每个字段的注释里，便于日后对照上游。
 *
 * <p>注意：强调色在 Setsuna 里是**可配置**的（{@link SkijaTheme#accent()}），本类是编译期常量，
 * 取的是默认强调色。需要跟随用户设置的地方请直接用 {@code SkijaTheme.accent()}。
 */
public final class Theme {

    // 表面（AARRGGBB）：近黑系，自带透明度，层级越高越浅
    /** 投影 ← Setsuna {@code SHADOW}。 */
    public static final Color SHADOW = Color.of(SkijaTheme.SHADOW);
    /** 面板底色 ← Setsuna {@code SURFACE}。 */
    public static final Color SURFACE = Color.of(SkijaTheme.SURFACE);
    /** 更暗的一层（全屏背板） ← Setsuna {@code BACKDROP}。 */
    public static final Color SURFACE_DIM = Color.of(SkijaTheme.BACKDROP);
    /** 面板内的分区 ← Setsuna {@code SURFACE_ALT}。 */
    public static final Color SURFACE_CONTAINER_LOW = Color.of(SkijaTheme.SURFACE_ALT);
    /** 抬升表面 ← Setsuna {@code SURFACE_RAISED}。 */
    public static final Color SURFACE_CONTAINER = Color.of(SkijaTheme.SURFACE_RAISED);
    /** 悬停表面 ← Setsuna {@code SURFACE_HOVER}。 */
    public static final Color SURFACE_CONTAINER_HIGH = Color.of(SkijaTheme.SURFACE_HOVER);
    /** 最高的悬停表面（控件悬停） ← Setsuna {@code CONTROL_HOVER}。 */
    public static final Color SURFACE_CONTAINER_HIGHEST = Color.of(SkijaTheme.CONTROL_HOVER);

    // 描边
    /** 常规描边 ← Setsuna {@code BORDER}。 */
    public static final Color OUTLINE = Color.of(SkijaTheme.BORDER);
    /** 弱描边（分隔线，自带透明度） ← Setsuna {@code BORDER_SOFT}。 */
    public static final Color OUTLINE_SOFT = Color.of(SkijaTheme.BORDER_SOFT);

    // 强调色（Setsuna 的青绿 #3ED6B4）与其容器、内容色
    /** 强调色 ← Setsuna {@code ACCENT}（默认值；动态值用 {@code SkijaTheme.accent()}）。 */
    public static final Color PRIMARY = Color.of(SkijaTheme.ACCENT);
    /** 强调色之上的文字 ← Setsuna {@code ACCENT_DARK}（亮青绿上要压深色才读得清）。 */
    public static final Color ON_PRIMARY = Color.of(SkijaTheme.ACCENT_DARK);
    /** 强调色的半透明铺底 ← Setsuna {@code ACCENT_SOFT}。 */
    public static final Color PRIMARY_CONTAINER = Color.of(SkijaTheme.ACCENT_SOFT);
    /** 强调容器之上的文字 ← Setsuna {@code TEXT}。 */
    public static final Color ON_PRIMARY_CONTAINER = Color.of(SkijaTheme.TEXT);

    // 次要色：弱强调表面（按钮等）
    /** 次要前景 ← Setsuna {@code TEXT_MUTED}。 */
    public static final Color SECONDARY = Color.of(SkijaTheme.TEXT_MUTED);
    /** 次要容器 ← Setsuna {@code CONTROL}。 */
    public static final Color SECONDARY_CONTAINER = Color.of(SkijaTheme.CONTROL);
    /** 次要容器之上的文字 ← Setsuna {@code TEXT}。 */
    public static final Color ON_SECONDARY_CONTAINER = Color.of(SkijaTheme.TEXT);

    // 文本
    /** 主文字 ← Setsuna {@code TEXT}。 */
    public static final Color TEXT_PRIMARY = Color.of(SkijaTheme.TEXT);
    /** 次级文字 ← Setsuna {@code TEXT_MUTED}。 */
    public static final Color TEXT_SECONDARY = Color.of(SkijaTheme.TEXT_MUTED);
    /** 弱文字 ← Setsuna {@code TEXT_FAINT}。 */
    public static final Color TEXT_MUTED = Color.of(SkijaTheme.TEXT_FAINT);
    /** 危险/错误 ← Setsuna {@code DANGER}。 */
    public static final Color ERROR = Color.of(SkijaTheme.DANGER);

    /** 悬停状态层透明度（约 8%）：在底色上叠一层该 alpha 的强调色以表达悬停。 */
    public static final int STATE_LAYER_ALPHA = 20;

    // 圆角
    /** 面板圆角 ← Setsuna {@code RADIUS}（6，比 MD3 的 10 更方）。 */
    public static final float PANEL_RADIUS = SkijaTheme.RADIUS;
    /** 控件圆角（按钮、滑块、开关等） ← Setsuna {@code RADIUS_SMALL}（4）。 */
    public static final float CONTROL_RADIUS = SkijaTheme.RADIUS_SMALL;

    // 间距与缩进
    /** 标题栏文本左内缩。 */
    public static final float PANEL_TITLE_INSET = 10f;
    /** 控件内文本与控件边缘的间距。 */
    public static final float ROW_CONTENT_INSET = 5f;

    // ── 面板规格（下拉式分类/设置面板的几何；三栏布局用 ClickGuiLayout，见阶段 B）──
    /** 面板宽度；分类面板与设置面板同宽。 */
    public static final float PANEL_WIDTH = 130f;
    /** 面板标题栏高度。 */
    public static final float PANEL_HEADER_HEIGHT = 28f;
    /** 相邻面板之间的间距。 */
    public static final float PANEL_GAP = 14f;
    /** 面板与绘制区边缘的边距。 */
    public static final float PANEL_MARGIN = 20f;
    /** 面板底部留白。 */
    public static final float PANEL_BOTTOM_PADDING = 8f;

    // 模块行与文字
    /** 模块行高度。 */
    public static final float MODULE_HEIGHT = 19f;
    /** 模块行文本左内缩。 */
    public static final float MODULE_PADDING_X = 7f;
    /** 模块行文字号。 */
    public static final float MODULE_TEXT_SIZE = 9.8f;
    /** 标题栏文字号。 */
    public static final float HEADER_TEXT_SIZE = 11.5f;
    /** 模块行底部分隔线的 alpha（叠在 OUTLINE 上）。 */
    public static final int MODULE_DIVIDER_ALPHA = 24;

    // 设置行
    /** 设置行高度。 */
    public static final float SETTING_HEIGHT = 16f;
    /** 设置行文字号。 */
    public static final float SETTING_TEXT_SIZE = 9.1f;
    /** 设置行之间的间距。 */
    public static final float SETTING_GAP = 3f;
    /** 设置行左右内缩。 */
    public static final float SETTING_PADDING_X = 6f;

    // 开关（26×16，滑块 off 8 / on 12，内缩 off 4 / on 2）
    public static final float SWITCH_WIDTH = 26f;
    public static final float SWITCH_HEIGHT = 16f;
    public static final float SWITCH_HANDLE_OFF = 8f;
    public static final float SWITCH_HANDLE_ON = 12f;
    public static final float SWITCH_INSET_OFF = 4f;
    public static final float SWITCH_INSET_ON = 2f;

    // 字号
    public static final float FONT_SIZE = 14f;
    public static final float FONT_SIZE_SMALL = 12f;

    // 动效：悬停 120ms、展开 180ms
    public static final long HOVER_MS = 120L;
    public static final long EXPAND_MS = 180L;

    private Theme() {
    }
}
