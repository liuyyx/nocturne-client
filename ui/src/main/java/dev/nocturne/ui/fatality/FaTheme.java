package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;

/**
 * Fatality 界面的配色、尺寸与字形表。颜色为 ARGB。
 *
 * <p>色板与注入器（{@code injector/AppTheme}）保持同一套 token：暖石墨近黑底 + 钠灯琥珀强调色 +
 * 暖纸白文字梯度。两处刻意共用同一组数值，界面与注入器看起来才是同一个产品；改色时两边一起改。
 */
final class FaTheme {
    // ── 表面（对应 AppTheme.BACKGROUND / PANEL / PANEL_RAISED / PANEL_HOVER） ──
    static final int WINDOW = 0xFF131315;
    static final int HEADER_TOP = 0xFF1A1A1D;
    static final int HEADER = 0xFF1A1A1D;
    static final int FOOTER = 0xFF1A1A1D;
    static final int DIVIDER = 0xFF222227;
    static final int GROUP = 0xFF1A1A1D;
    static final int GROUP_BORDER = 0xFF2B2B31;
    static final int FIELD = 0xFF202024;
    static final int FIELD_HOVER = 0xFF26262B;
    static final int TAB_ACTIVE = 0xFF26262B;
    static final int POPUP = 0xFF1A1A1D;
    static final int POPUP_BORDER = 0xFF2B2B31;
    static final int SHADOW = 0x8C000000;

    // ── 文字（对应 AppTheme.TEXT / TEXT_MUTED / TEXT_FAINT） ────────────────
    static final int LOGO = 0xFFECE9E1;
    static final int TITLE = 0xFFECE9E1;
    static final int LABEL = 0xFFECE9E1;
    static final int LABEL_HOVER = 0xFFF6F4EE;
    static final int LABEL_DISABLED = 0xFF625E55;
    static final int MUTED = 0xFF9C978B;
    static final int FIELD_TEXT = 0xFF9C978B;
    static final int FIELD_TEXT_HOVER = 0xFFECE9E1;
    static final int TAB_TEXT = 0xFF9C978B;
    static final int TAB_TEXT_HOVER = 0xFFECE9E1;
    static final int TAB_TEXT_ACTIVE = 0xFFECE9E1;
    static final int SIDE_TEXT = 0xFF9C978B;
    static final int SIDE_TEXT_ENABLED = 0xFFECE9E1;
    static final int SIDE_TEXT_ACTIVE = 0xFFECE9E1;
    static final int ICON = 0xFF9C978B;
    static final int ICON_FAINT = 0xFF625E55;
    static final int ICON_HOVER = 0xFFECE9E1;
    static final int CHEVRON = 0xFF9C978B;
    /** 勾选标记画在强调色底上，用暖墨（AppTheme.ON_ACCENT）保证对比度。 */
    static final int TICK = 0xFF171410;

    // ── 强调色（对应 AppTheme.ACCENT / ACCENT_PRESSED / INFO） ──────────────
    /** 钠灯琥珀：品牌、开关、滑条、选中态一色三用。 */
    static final int ACCENT = 0xFFE8A33D;
    /** 强调色的暗态（AppTheme.ACCENT_PRESSED）。 */
    static final int ACCENT_DIM = 0xFFD59638;
    static final int ORANGE = 0xFFEFB04B;

    // ── 尺寸（设计单位） ────────────────────────────────────────────────────
    // 参考实现的目标画布是 960x540 的逻辑像素，这里取其 0.8 倍，使默认窗口在 960x540 下仍有余量；
    // FaClickGui 再把整套设计单位缩放到实际绘制区。
    static final float DEFAULT_W = 744.0f;
    static final float DEFAULT_H = 540.0f;
    static final float MIN_W = 500.0f;
    static final float MIN_H = 320.0f;
    static final float RADIUS = 4.0f;
    static final float HEADER_H = 40.0f;
    static final float FOOTER_H = 22.0f;
    static final float SIDEBAR_W = 98.0f;
    static final float CONTENT_PAD = 10.0f;
    static final float COLUMN_GAP = 13.5f;
    static final float GROUP_GAP = 16.0f;
    static final float GROUP_RADIUS = 3.5f;
    /** 卡片顶边到第一行；标题压在顶边上。 */
    static final float GROUP_TITLE_H = 15.0f;
    static final float GROUP_BOTTOM = 10.0f;
    static final float ROW_H = 25.5f;
    static final float ROW_PAD = 15.0f;
    static final float FIELD_W = 72.0f;
    static final float FIELD_H = 19.0f;
    static final float SLIDER_H = 13.0f;
    static final float FIELD_RADIUS = 2.5f;
    static final float CHECK = 10.5f;
    static final float CHIP_W = 17.5f;
    static final float CHIP_H = 9.0f;
    static final float GEAR = 10.5f;
    static final float MIN_COLUMN_W = 188.0f;

    /** 品牌名；标题栏与信息浮层都用它（不读游戏用户名）。 */
    static final String BRAND = "NOCTURNE";

    // ── Lucide 字形 ─────────────────────────────────────────────────────────
    static final String I_COMBAT = "\uE0B0";
    static final String I_MOVEMENT = "\uE3BC";
    static final String I_PLAYER = "\uE19E";
    static final String I_WORLD = "\uE0EB";
    static final String I_RENDER = "\uE0BE";
    static final String I_MISC = "\uE157";
    static final String I_GEAR = "\uE157";
    static final String I_SEARCH = "\uE154";
    static final String I_SAVE = "\uE150";
    static final String I_FOLDER = "\uE0DC";
    static final String I_INFO = "\uE0FE";
    static final String I_CHEVRON_DOWN = "\uE071";
    static final String I_CHEVRON_UP = "\uE074";
    static final String I_CHECK = "\uE070";
    static final String I_COPY = "\uE0A2";
    static final String I_PASTE = "\uE3EB";
    static final String I_CLOSE = "\uE1B1";
    static final String I_PLUS = "\uE140";
    static final String I_KEYBOARD = "\uE283";
    static final String I_EYE = "\uE0BE";
    static final String I_EYE_OFF = "\uE0BF";

    private FaTheme() {
    }

    /**
     * 当前字体句柄集；由 {@code FaClickGui} 每帧按后端与缩放同步（见
     * {@code dev.nocturne.ui.text.FontRasterizer#provider}）。
     */
    static FaFont.Provider fonts = dev.nocturne.ui.text.FontRasterizer.provider();

    static FaFont logo() {
        return fonts.logo();
    }

    static FaFont tab() {
        return fonts.tab();
    }

    static FaFont title() {
        return fonts.title();
    }

    static FaFont label() {
        return fonts.label();
    }

    static FaFont field() {
        return fonts.field();
    }

    static FaFont small() {
        return fonts.small();
    }

    static FaFont icon(float size) {
        return fonts.icon(size);
    }

    /** 分类图标；本项目只有四个分类（契约见 {@link Category}）。 */
    static String icon(Category category) {
        switch (category) {
            case MOVEMENT:
                return glyph(I_MOVEMENT);
            case RENDER:
                return glyph(I_RENDER);
            case PLAYER:
                return glyph(I_PLAYER);
            case MISC:
            default:
                return glyph(I_MISC);
        }
    }

    /**
     * 图标字形的可用形式。
     *
     * <p>走字形图集时原样返回 Lucide 码点；退化为游戏字体时 Lucide 的私用区码点渲染不出来，
     * 必须换成等价的 ASCII 别名，否则界面上出现的是一排方框。
     */
    static String glyph(String lucide) {
        if (dev.nocturne.ui.text.FontRasterizer.atlasActive()) {
            return lucide;
        }
        if (I_CHECK.equals(lucide)) {
            return "v";
        }
        if (I_SEARCH.equals(lucide)) {
            return "?";
        }
        if (I_CHEVRON_DOWN.equals(lucide)) {
            return "v";
        }
        if (I_CHEVRON_UP.equals(lucide)) {
            return "^";
        }
        if (I_CLOSE.equals(lucide)) {
            return "x";
        }
        if (I_PLUS.equals(lucide)) {
            return "+";
        }
        if (I_COPY.equals(lucide)) {
            return "C";
        }
        if (I_PASTE.equals(lucide)) {
            return "V";
        }
        if (I_FOLDER.equals(lucide)) {
            return "[";
        }
        if (I_INFO.equals(lucide)) {
            return "i";
        }
        if (I_KEYBOARD.equals(lucide)) {
            return "k";
        }
        if (I_EYE.equals(lucide)) {
            return "o";
        }
        if (I_EYE_OFF.equals(lucide)) {
            return "-";
        }
        if (I_SAVE.equals(lucide) || I_GEAR.equals(lucide) || I_MISC.equals(lucide)
                || I_COMBAT.equals(lucide)) {
            return "*";
        }
        if (I_MOVEMENT.equals(lucide)) {
            return "M";
        }
        if (I_RENDER.equals(lucide)) {
            return "R";
        }
        if (I_PLAYER.equals(lucide)) {
            return "P";
        }
        if (I_WORLD.equals(lucide)) {
            return "W";
        }
        return lucide;
    }

    /** 把 alpha 通道乘以 {@code alpha}（0..1）。 */
    static int alpha(int argb, float alpha) {
        int a = Math.round((argb >>> 24) * Math.max(0.0f, Math.min(1.0f, alpha)));
        return (argb & 0x00FFFFFF) | a << 24;
    }

    /** 在 {@code from} 与 {@code to} 之间线性插值，{@code amount} 夹取到 0..1。 */
    static int mix(int from, int to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        int a = Math.round(((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * t);
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** 按可用宽度截断并加省略号；宽度不够时退化为单独的省略号。 */
    static String fit(String text, FaFont font, float width) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return end <= 0 ? ellipsis : trimTrailing(text.substring(0, end)) + ellipsis;
    }

    /** {@code String.stripTrailing} 的 Java 8 等价实现（去掉尾部空白）。 */
    private static String trimTrailing(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
