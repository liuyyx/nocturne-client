package dev.noturne.ui.theme;

import dev.noturne.ui.render.Color;

/**
 * 视觉语言：Material Design 3 深色主题——带透明度的暖黑表面、淡紫强调色、
 * 大圆角面板与小圆角控件。所有数值取自规格表（MD3 深色基准值）。
 */
public final class Theme {

    // 表面（AARRGGBB）：暖黑系，自带透明度，层级越高越浅
    public static final Color SHADOW = Color.hex("60000000");
    public static final Color SURFACE = Color.hex("EE141218");
    public static final Color SURFACE_DIM = Color.hex("E80F0D13");
    public static final Color SURFACE_CONTAINER_LOW = Color.hex("F01D1B20");
    public static final Color SURFACE_CONTAINER = Color.hex("F4211F26");
    public static final Color SURFACE_CONTAINER_HIGH = Color.hex("F82B2930");
    public static final Color SURFACE_CONTAINER_HIGHEST = Color.hex("FC36343B");

    // 描边
    public static final Color OUTLINE = Color.hex("B4938F99");
    public static final Color OUTLINE_SOFT = Color.hex("60938F99");

    // 强调色（淡紫 #D0BCFF）与其容器、内容色
    public static final Color PRIMARY = Color.hex("FFD0BCFF");
    public static final Color ON_PRIMARY = Color.hex("FF381E72");
    public static final Color PRIMARY_CONTAINER = Color.hex("EC4F378B");
    public static final Color ON_PRIMARY_CONTAINER = Color.hex("FFEADDFF");

    // 次要色：弱强调表面（按钮等）
    public static final Color SECONDARY = Color.hex("FFCCC2DC");
    public static final Color SECONDARY_CONTAINER = Color.hex("EC4A4458");
    public static final Color ON_SECONDARY_CONTAINER = Color.hex("FFE8DEF8");

    // 文本
    public static final Color TEXT_PRIMARY = Color.hex("FFE6E0E9");
    public static final Color TEXT_SECONDARY = Color.hex("FFCAC4D0");
    public static final Color TEXT_MUTED = Color.hex("FF938F99");
    public static final Color ERROR = Color.hex("FFF2B8B5");

    /** 悬停状态层透明度（约 8%）：在底色上叠一层该 alpha 的强调色以表达悬停。 */
    public static final int STATE_LAYER_ALPHA = 20;

    // 圆角
    public static final float PANEL_RADIUS = 17f;
    public static final float SECTION_RADIUS = 13f;
    public static final float CARD_RADIUS = 9f;
    public static final float CONTROL_RADIUS = 7f;

    // 间距与缩进
    public static final float OUTER_PADDING = 5f;
    public static final float SECTION_GAP = 3f;
    public static final float INNER_PADDING = 5f;
    public static final float ROW_GAP = 3f;
    public static final float PANEL_TITLE_INSET = 6f;
    public static final float ROW_CONTENT_INSET = 5f;

    // 分类栏与控件尺寸
    public static final float RAIL_COLLAPSED_WIDTH = 42f;
    public static final float RAIL_EXPANDED_WIDTH = 120f;
    public static final float CONTROL_HEIGHT = 18f;
    /** 标题栏高度；规格表未作规定，沿用既有值。 */
    public static final float HEADER_HEIGHT = 22f;

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

    // 动效：悬停 120ms、展开 180ms，与参照实现一致
    public static final long HOVER_MS = 120L;
    public static final long EXPAND_MS = 180L;

    private Theme() {
    }
}
